import re
import asyncio
import time
import logging
import hashlib
from dataclasses import dataclass, field
from collections import deque
from typing import AsyncIterator, Optional

from fastapi import HTTPException, Request
from starlette.responses import StreamingResponse

from Api.deps.db import get_audio_tracks_collection
from Api.utils.auth import verify_auth_token
from stream.core.config_manager import Config
from stream import bot, get_primary_client_user_id
from stream.helpers.logger import LOGGER
from stream.database.MongoDb import db_handler

_CHUNK_SIZE = 1024 * 1024
_MAX_STREAM_BUFFER_BYTES = 25_000_000
_STREAM_HUBS: dict[str, "_StreamHub"] = {}
_STREAM_HUBS_LOCK = asyncio.Lock()
_FILE_ID_LOCKS: dict[str, asyncio.Lock] = {}
_FILE_ID_LOCKS_LOCK = asyncio.Lock()
_TRACK_AFFINITY: dict[str, tuple[int, float]] = {}
_TRACK_AFFINITY_LOCK = asyncio.Lock()
_TRACK_AFFINITY_TTL_SEC = 300.0

_PLAY_COUNT_THRESHOLD_SEC = 30.0
_PLAY_PROGRESS_TTL_SEC = 1800.0
_PLAY_PROGRESS_MAX = 5000
_PLAY_PROGRESS_UPDATE_EVERY_BYTES = 512 * 1024
_PLAY_PROGRESS_LOCK = asyncio.Lock()

LOG = LOGGER(__name__)

@dataclass(slots=True)
class _PlayProgress:
    file_size: int | None = None
    duration_sec: float | None = None
    bitrate_kbps: int | None = None
    ranges: list[tuple[int, int]] = field(default_factory=list)
    counted: bool = False
    last_seen: float = 0.0


_PLAY_PROGRESS: dict[str, _PlayProgress] = {}


class _StreamBehindError(RuntimeError):
    pass


class _StreamHub:
    def __init__(
        self,
        *,
        file_id: str,
        source_chat_id: int | None,
        source_message_id: int | None,
    ):
        self.file_id = file_id
        self.source_chat_id = source_chat_id
        self.source_message_id = source_message_id
        self._cond = asyncio.Condition()
        self._chunks: deque[tuple[int, bytes]] = deque()
        self._total_written = 0
        self._done = False
        self._error: BaseException | None = None
        self._producer: asyncio.Task | None = None
        self._consumers: dict[str, int] = {}
        self._lagged: set[str] = set()
        self._last_access = time.monotonic()

    def start(self) -> None:
        if self._producer is None or self._producer.done():
            self._producer = asyncio.create_task(self._run())

    async def close(self) -> None:
        producer = self._producer
        self._producer = None
        if producer and not producer.done():
            producer.cancel()
            try:
                await producer
            except Exception:
                pass

    def _buffer_start(self) -> int:
        if not self._chunks:
            return self._total_written
        return self._chunks[0][0]

    async def _run(self) -> None:
        from stream import acquire_stream_client, release_stream_client

        client_id = 0
        try:
            client_id, client = await acquire_stream_client()
            LOG.info(f"Streaming started using client {client_id}")
            target = self.file_id
            if self.source_chat_id is not None and self.source_message_id is not None:
                msg = await client.get_messages(self.source_chat_id, self.source_message_id)
                if msg:
                    target = msg

            async for chunk in client.stream_media(target):
                if not chunk:
                    continue
                async with self._cond:
                    start = self._total_written
                    self._total_written += len(chunk)
                    self._chunks.append((start, chunk))
                    self._last_access = time.monotonic()
                    self._gc_locked()
                    self._cond.notify_all()
        except asyncio.CancelledError:
            raise
        except BaseException as e:
            async with self._cond:
                self._error = e
                self._cond.notify_all()
        finally:
            await release_stream_client(client_id)
            async with self._cond:
                self._done = True
                self._cond.notify_all()

    def _gc_locked(self) -> None:
        min_offset = min(self._consumers.values(), default=self._total_written)
        while self._chunks:
            start, data = self._chunks[0]
            end = start + len(data)
            if end <= min_offset:
                self._chunks.popleft()
                continue
            break

        buffer_start = self._buffer_start()
        buffer_bytes = self._total_written - buffer_start
        if buffer_bytes <= _MAX_STREAM_BUFFER_BYTES:
            return

        keep_from = self._total_written - _MAX_STREAM_BUFFER_BYTES
        while self._chunks:
            start, data = self._chunks[0]
            end = start + len(data)
            if end <= keep_from:
                self._chunks.popleft()
                continue
            break

        new_start = self._buffer_start()
        if new_start <= buffer_start:
            return

        for cid, off in list(self._consumers.items()):
            if off < new_start:
                self._lagged.add(cid)

    async def iter_bytes(self, start_byte: int) -> AsyncIterator[bytes]:
        cid = str(time.time_ns())
        start_byte = max(0, int(start_byte))

        self.start()
        async with self._cond:
            self._consumers[cid] = start_byte
            self._last_access = time.monotonic()
            self._cond.notify_all()

        try:
            while True:
                async with self._cond:
                    if cid in self._lagged:
                        raise _StreamBehindError()
                    if self._error:
                        raise self._error

                    offset = self._consumers.get(cid, start_byte)
                    buffer_start = self._buffer_start()
                    if offset < buffer_start and self._total_written > 0:
                        raise _StreamBehindError()

                    if self._done and offset >= self._total_written:
                        return

                    chunk = None
                    for start, data in self._chunks:
                        end = start + len(data)
                        if end <= offset:
                            continue
                        if start > offset:
                            break
                        chunk = (start, data)
                        break

                    if chunk is None:
                        await self._cond.wait()
                        continue

                    start, data = chunk
                    rel = offset - start
                    out = data[rel:] if rel > 0 else data
                    self._consumers[cid] = offset + len(out)
                    self._last_access = time.monotonic()
                    self._gc_locked()

                if out:
                    yield out
        finally:
            async with self._cond:
                self._consumers.pop(cid, None)
                self._lagged.discard(cid)
                self._last_access = time.monotonic()
                self._gc_locked()


def _hub_key(file_id: str, source_chat_id: int | None, source_message_id: int | None) -> str:
    if source_chat_id is not None and source_message_id is not None:
        return f"m:{source_chat_id}:{source_message_id}"
    return f"f:{file_id}"


async def _get_or_create_hub(
    *,
    file_id: str,
    source_chat_id: int | None,
    source_message_id: int | None,
) -> _StreamHub:
    key = _hub_key(file_id, source_chat_id, source_message_id)
    async with _STREAM_HUBS_LOCK:
        hub = _STREAM_HUBS.get(key)
        if hub is None:
            hub = _StreamHub(
                file_id=file_id,
                source_chat_id=source_chat_id,
                source_message_id=source_message_id,
            )
            _STREAM_HUBS[key] = hub
        hub.start()
        return hub


async def close_stream_hubs() -> None:
    async with _STREAM_HUBS_LOCK:
        hubs = list(_STREAM_HUBS.values())
        _STREAM_HUBS.clear()
    for hub in hubs:
        try:
            await hub.close()
        except Exception:
            pass


def _parse_range_header(value: str) -> tuple[int | None, int | None]:
    if not value:
        return None, None
    m = re.match(r"^bytes=(\d+)-(\d+)?$", value.strip(), flags=re.I)
    if not m:
        return None, None
    try:
        start = int(m.group(1))
    except Exception:
        return None, None
    if start < 0:
        return None, None
    end = None
    if m.group(2) is not None and m.group(2) != "":
        try:
            end = int(m.group(2))
        except Exception:
            end = None
    if end is not None and end < start:
        return None, None
    return start, end


def _request_fingerprint(request: Request) -> str:
    ip = ""
    try:
        if request.client and request.client.host:
            ip = str(request.client.host)
    except Exception:
        ip = ""
    ua = (request.headers.get("user-agent") or request.headers.get("User-Agent") or "").strip()
    raw = f"{ip}|{ua}"
    return hashlib.sha1(raw.encode("utf-8", errors="ignore")).hexdigest()[:16]


def _request_user_id(request: Request) -> int | None:
    token = (request.headers.get("authorization") or request.headers.get("Authorization") or "").strip()
    if not token:
        token = (request.headers.get("x-auth-token") or request.headers.get("X-Auth-Token") or "").strip()
    if not token:
        token = (request.query_params.get("token") or "").strip()
    if not token:
        token = (request.query_params.get("auth_token") or "").strip()
    if not token:
        token = (request.query_params.get("auth") or "").strip()
    if not token:
        token = (request.cookies.get("auth_token") or "").strip()
    if not token:
        token = (request.cookies.get("token") or "").strip()
    if not token:
        return None
    try:
        payload = verify_auth_token(token)
        uid = payload.get("uid")
        uid = int(uid)
        if uid > 0:
            return int(uid)
    except Exception:
        return None
    return None


def _merge_ranges(ranges: list[tuple[int, int]], start: int, end: int) -> list[tuple[int, int]]:
    start = int(start)
    end = int(end)
    if end < start:
        return ranges
    if not ranges:
        return [(start, end)]
    out: list[tuple[int, int]] = []
    inserted = False
    for a, b in ranges:
        if b + 1 < start:
            out.append((a, b))
            continue
        if end + 1 < a:
            if not inserted:
                out.append((start, end))
                inserted = True
            out.append((a, b))
            continue
        start = min(start, int(a))
        end = max(end, int(b))
    if not inserted:
        out.append((start, end))
    out.sort(key=lambda x: x[0])
    merged: list[tuple[int, int]] = []
    for a, b in out:
        if not merged:
            merged.append((a, b))
            continue
        la, lb = merged[-1]
        if a <= lb + 1:
            merged[-1] = (la, max(lb, b))
        else:
            merged.append((a, b))
    return merged


def _covered_bytes(ranges: list[tuple[int, int]]) -> int:
    total = 0
    for a, b in ranges:
        total += max(0, int(b) - int(a) + 1)
    return int(total)


def _seconds_from_bytes(*, covered: int, file_size: int | None, duration_sec: float | None, bitrate_kbps: int | None) -> float:
    covered = int(max(0, covered))
    if file_size and duration_sec and file_size > 0 and duration_sec > 0:
        return float(covered) * float(duration_sec) / float(file_size)
    if bitrate_kbps and bitrate_kbps > 0:
        bytes_per_sec = (float(bitrate_kbps) * 1000.0) / 8.0
        if bytes_per_sec > 0:
            return float(covered) / float(bytes_per_sec)
    return 0.0


async def _prune_play_progress_locked(now: float) -> None:
    if not _PLAY_PROGRESS:
        return
    expired: list[str] = []
    for k, v in _PLAY_PROGRESS.items():
        if (now - float(v.last_seen)) > float(_PLAY_PROGRESS_TTL_SEC):
            expired.append(k)
    for k in expired:
        _PLAY_PROGRESS.pop(k, None)
    if len(_PLAY_PROGRESS) <= _PLAY_PROGRESS_MAX:
        return
    items = sorted(_PLAY_PROGRESS.items(), key=lambda kv: float(kv[1].last_seen))
    for k, _ in items[: max(0, len(items) - _PLAY_PROGRESS_MAX)]:
        _PLAY_PROGRESS.pop(k, None)


async def _update_play_progress(
    *,
    key: str,
    start: int,
    end: int,
    file_size: int | None,
    duration_sec: float | None,
    bitrate_kbps: int | None,
) -> bool:
    now = time.monotonic()
    async with _PLAY_PROGRESS_LOCK:
        await _prune_play_progress_locked(now)
        prog = _PLAY_PROGRESS.get(key)
        if prog is None:
            prog = _PlayProgress(
                file_size=int(file_size) if file_size is not None else None,
                duration_sec=float(duration_sec) if duration_sec is not None else None,
                bitrate_kbps=int(bitrate_kbps) if bitrate_kbps is not None else None,
                last_seen=float(now),
            )
            _PLAY_PROGRESS[key] = prog
        prog.last_seen = float(now)
        if file_size is not None:
            try:
                prog.file_size = int(file_size)
            except Exception:
                pass
        if duration_sec is not None:
            try:
                prog.duration_sec = float(duration_sec)
            except Exception:
                pass
        if bitrate_kbps is not None:
            try:
                prog.bitrate_kbps = int(bitrate_kbps)
            except Exception:
                pass
        if prog.counted:
            return False
        prog.ranges = _merge_ranges(prog.ranges, int(start), int(end))
        covered = _covered_bytes(prog.ranges)
        sec = _seconds_from_bytes(
            covered=covered,
            file_size=prog.file_size,
            duration_sec=prog.duration_sec,
            bitrate_kbps=prog.bitrate_kbps,
        )
        if sec >= float(_PLAY_COUNT_THRESHOLD_SEC):
            prog.counted = True
            return True
        return False


async def _register_play(
    *,
    track_id: str,
    user_id: int | None,
    source: str,
    jam_id: str | None,
) -> None:
    track_id = (track_id or "").strip()
    if not track_id:
        return
    source = (source or "").strip() or "direct"
    if source not in {"direct", "jam"}:
        source = "direct"
    now = time.time()

    should_count_global = True
    if user_id is not None and int(user_id) > 0:
        played_bucket = int(now // _PLAY_PROGRESS_TTL_SEC)
        user_play_id = f"{int(user_id)}:{track_id}:{played_bucket}"
        doc: dict[str, object] = {
            "_id": user_play_id,
            "user_id": int(user_id),
            "track_id": track_id,
            "played_at": float(now),
            "bucket": int(played_bucket),
            "source": source,
        }
        if jam_id:
            doc["jam_id"] = str(jam_id)
        try:
            res = await db_handler.userplayback_collection.collection.update_one(
                {"_id": user_play_id},
                {"$setOnInsert": doc},
                upsert=True,
            )
            should_count_global = bool(getattr(res, "upserted_id", None))
        except Exception:
            should_count_global = False

    if not should_count_global:
        return

    inc: dict[str, int] = {"plays": 1, f"sources.{source}": 1}
    update: dict[str, object] = {"$inc": inc, "$set": {"last_played_at": float(now), "updated_at": float(now)}}
    if jam_id:
        update["$set"]["last_jam_id"] = str(jam_id)
    try:
        await db_handler.globalplayback_collection.collection.update_one({"_id": track_id}, update, upsert=True)
    except Exception:
        return


async def _wrap_with_play_count(
    *,
    iterator: AsyncIterator[bytes],
    request: Request,
    track_id: str,
    from_bytes: int,
    file_size: int | None,
    duration_sec: float | None,
    bitrate_kbps: int | None,
) -> AsyncIterator[bytes]:
    if (request.method or "").upper() == "HEAD":
        async for chunk in iterator:
            yield chunk
        return

    user_id = _request_user_id(request)
    source = "jam" if (request.query_params.get("jam_id") or "").strip() else "direct"
    jam_id = (request.query_params.get("jam_id") or "").strip() or None
    fp = _request_fingerprint(request)
    key = f"{int(user_id) if user_id is not None else 0}:{track_id}:{fp}"

    cursor = max(0, int(from_bytes))
    last_flush = int(cursor)

    triggered = False
    try:
        async for chunk in iterator:
            if not chunk:
                continue
            yield chunk
            clen = len(chunk)
            if clen <= 0:
                continue
            cursor += int(clen)
            if triggered:
                continue
            if (cursor - last_flush) < int(_PLAY_PROGRESS_UPDATE_EVERY_BYTES):
                continue
            hit = await _update_play_progress(
                key=key,
                start=last_flush,
                end=cursor - 1,
                file_size=file_size,
                duration_sec=duration_sec,
                bitrate_kbps=bitrate_kbps,
            )
            last_flush = int(cursor)
            if hit:
                triggered = True
                asyncio.create_task(
                    _register_play(track_id=track_id, user_id=user_id, source=source, jam_id=jam_id)
                )
    finally:
        if not triggered and cursor > last_flush:
            try:
                hit2 = await _update_play_progress(
                    key=key,
                    start=last_flush,
                    end=cursor - 1,
                    file_size=file_size,
                    duration_sec=duration_sec,
                    bitrate_kbps=bitrate_kbps,
                )
                if hit2:
                    asyncio.create_task(
                        _register_play(track_id=track_id, user_id=user_id, source=source, jam_id=jam_id)
                    )
            except Exception:
                pass


def _extract_media_file_id(message) -> str | None:
    if not message:
        return None
    media = getattr(message, "audio", None) or getattr(message, "document", None)
    if not media:
        return None
    fid = getattr(media, "file_id", None)
    if not fid:
        return None
    return str(fid)


async def _get_lock(key: str) -> asyncio.Lock:
    async with _FILE_ID_LOCKS_LOCK:
        lock = _FILE_ID_LOCKS.get(key)
        if lock is None:
            lock = asyncio.Lock()
            _FILE_ID_LOCKS[key] = lock
        return lock


async def _ensure_client_file_id(
    *,
    track_id: str,
    client_user_id: int,
    client,
    source_chat_id: int | None,
    source_message_id: int | None,
) -> str:
    lock = await _get_lock(f"{track_id}:{client_user_id}")
    async with lock:
        col = get_audio_tracks_collection()
        key = str(int(client_user_id))
        doc: dict | None = None
        telegram: dict = {}

        for attempt in range(6):
            doc = await col.find_one(
                {"_id": track_id},
                projection={"telegram": 1, "source_chat_id": 1, "source_message_id": 1},
            )
            telegram = (doc or {}).get("telegram") or {}
            file_ids = telegram.get("file_ids") if isinstance(telegram.get("file_ids"), dict) else {}
            existing = (file_ids or {}).get(key)
            if isinstance(existing, str) and existing.strip():
                LOG.debug(
                    f"stream file_id cache hit track={track_id} client={client_user_id} file_id={existing.strip()}"
                )
                return existing.strip()

            resolved_chat_id = source_chat_id
            if resolved_chat_id is None:
                resolved_chat_id = doc.get("source_chat_id") if isinstance(doc, dict) else None
                try:
                    resolved_chat_id = int(resolved_chat_id) if resolved_chat_id is not None else None
                except Exception:
                    resolved_chat_id = None
            if resolved_chat_id is None:
                resolved_chat_id = getattr(Config, "CHANNEL_ID", None)
                try:
                    resolved_chat_id = int(resolved_chat_id) if resolved_chat_id is not None else None
                except Exception:
                    resolved_chat_id = None

            resolved_message_id = source_message_id
            if resolved_message_id is None:
                resolved_message_id = doc.get("source_message_id") if isinstance(doc, dict) else None
                try:
                    resolved_message_id = int(resolved_message_id) if resolved_message_id is not None else None
                except Exception:
                    resolved_message_id = None

            if resolved_chat_id is not None and resolved_message_id is not None:
                try:
                    msg = await client.get_messages(int(resolved_chat_id), int(resolved_message_id))
                    fid = _extract_media_file_id(msg)
                except Exception:
                    fid = None

                if fid:
                    LOG.debug(
                        f"stream file_id synced via channel track={track_id} client={client_user_id} file_id={fid}"
                    )
                    await col.update_one(
                        {"_id": track_id},
                        {"$set": {f"telegram.file_ids.{key}": fid, "updated_at": time.time()}},
                    )
                    return fid

            if attempt < 5:
                await asyncio.sleep(0.35)

        dump_channel_id = getattr(Config, "DUMP_CHANNEL_ID", None)
        try:
            dump_channel_id = int(dump_channel_id)
        except Exception:
            dump_channel_id = 0
        if not dump_channel_id:
            raise HTTPException(status_code=404, detail="No source message to sync file_id")

        resolved_chat_id = source_chat_id
        if resolved_chat_id is None:
            resolved_chat_id = doc.get("source_chat_id") if isinstance(doc, dict) else None
            try:
                resolved_chat_id = int(resolved_chat_id) if resolved_chat_id is not None else None
            except Exception:
                resolved_chat_id = None

        resolved_message_id = source_message_id
        if resolved_message_id is None:
            resolved_message_id = doc.get("source_message_id") if isinstance(doc, dict) else None
            try:
                resolved_message_id = int(resolved_message_id) if resolved_message_id is not None else None
            except Exception:
                resolved_message_id = None

        dump_message_id = telegram.get("dump_message_id")
        try:
            dump_message_id = int(dump_message_id) if dump_message_id is not None else None
        except Exception:
            dump_message_id = None

        if not dump_message_id:
            if resolved_chat_id is None or resolved_message_id is None:
                fallback_file_id = (telegram.get("file_id") or "").strip()
                if not fallback_file_id:
                    raise HTTPException(status_code=404, detail="No source message to sync file_id")
                LOG.debug(f"stream syncing via dump send_document track={track_id} client={client_user_id}")
                sent = await bot.send_document(int(dump_channel_id), fallback_file_id)
            else:
                LOG.debug(
                    f"stream syncing via dump copy_message track={track_id} client={client_user_id} from={resolved_chat_id}:{resolved_message_id}"
                )
                sent = await bot.copy_message(
                    chat_id=int(dump_channel_id),
                    from_chat_id=int(resolved_chat_id),
                    message_id=int(resolved_message_id),
                )
            dump_message_id = int(getattr(sent, "id"))
            await col.update_one(
                {"_id": track_id},
                {"$set": {"telegram.dump_message_id": dump_message_id}},
            )

        msg = await client.get_messages(int(dump_channel_id), int(dump_message_id))
        fid = _extract_media_file_id(msg)
        if not fid:
            raise HTTPException(status_code=404, detail="Failed to read file_id from dump message")

        LOG.debug(f"stream file_id synced via dump track={track_id} client={client_user_id} file_id={fid}")
        await col.update_one(
            {"_id": track_id},
            {"$set": {f"telegram.file_ids.{key}": fid, "updated_at": time.time()}},
        )
        return fid


async def _stream_range(
    *,
    client_user_id: int,
    client,
    file_id: str,
    from_bytes: int,
    until_bytes: int | None,
) -> AsyncIterator[bytes]:
    try:
        from_bytes = max(0, int(from_bytes))
        if until_bytes is not None:
            until_bytes = max(from_bytes, int(until_bytes))

        start_chunk = from_bytes // _CHUNK_SIZE
        stream_kwargs: dict[str, int] = {}
        if start_chunk:
            stream_kwargs["offset"] = int(start_chunk)

        cursor = int(start_chunk) * int(_CHUNK_SIZE)
        async for chunk in client.stream_media(file_id, **stream_kwargs):
            if not chunk:
                continue

            chunk_start = cursor
            chunk_end = cursor + len(chunk) - 1
            cursor += len(chunk)

            if chunk_end < from_bytes:
                continue
            if until_bytes is not None and chunk_start > until_bytes:
                return

            out_start = max(from_bytes, chunk_start)
            out_end = chunk_end if until_bytes is None else min(until_bytes, chunk_end)
            rel_start = out_start - chunk_start
            rel_end = out_end - chunk_start
            chunk = chunk[int(rel_start) : int(rel_end) + 1]

            if chunk:
                yield chunk
            if until_bytes is not None and out_end >= until_bytes:
                return
    finally:
        from stream import release_stream_client

        await release_stream_client(client_user_id)


async def _direct_stream(
    *,
    client_user_id: int,
    client,
    file_id: str,
    start_byte: int = 0,
) -> AsyncIterator[bytes]:
    remaining_skip = max(0, int(start_byte))
    try:
        async for chunk in client.stream_media(file_id):
            if not chunk:
                continue
            if remaining_skip > 0:
                if len(chunk) <= remaining_skip:
                    remaining_skip -= len(chunk)
                    continue
                chunk = chunk[remaining_skip:]
                remaining_skip = 0
            yield chunk
    finally:
        from stream import release_stream_client

        await release_stream_client(client_user_id)


async def _stream_file_id(
    *,
    file_id: str,
    source_chat_id: int | None,
    source_message_id: int | None,
    start_byte: int = 0,
) -> AsyncIterator[bytes]:
    hub = await _get_or_create_hub(
        file_id=file_id,
        source_chat_id=source_chat_id,
        source_message_id=source_message_id,
    )
    sent = max(0, int(start_byte))
    try:
        async for chunk in hub.iter_bytes(start_byte=int(start_byte)):
            sent += len(chunk)
            yield chunk
    except _StreamBehindError:
        from stream import acquire_stream_client

        client_user_id, client = await acquire_stream_client()
        async for chunk in _direct_stream(
            client_user_id=int(client_user_id),
            client=client,
            file_id=file_id,
            start_byte=int(sent),
        ):
            yield chunk


async def stream_track(track_id: str, request: Request):
    if bool(getattr(Config, "ONLY_API", False)) or bot is None:
        raise HTTPException(status_code=503, detail="streaming disabled")
    col = get_audio_tracks_collection()
    doc = await col.find_one(
        {"_id": track_id},
        projection={"telegram": 1, "audio": 1, "source_chat_id": 1, "source_message_id": 1},
    )
    if not doc:
        raise HTTPException(status_code=404, detail="Track not found")

    telegram = doc.get("telegram") or {}
    audio = doc.get("audio") if isinstance(doc.get("audio"), dict) else {}
    primary_file_id = (telegram.get("file_id") or "").strip()

    source_chat_id = doc.get("source_chat_id")
    source_message_id = doc.get("source_message_id")
    try:
        if source_chat_id is not None:
            source_chat_id = int(source_chat_id)
    except Exception:
        source_chat_id = None
    try:
        if source_message_id is not None:
            source_message_id = int(source_message_id)
    except Exception:
        source_message_id = None

    mime_type = (telegram.get("mime_type") or "audio/mpeg").strip() or "audio/mpeg"

    file_size: Optional[int] = None
    try:
        if telegram.get("file_size") is not None:
            file_size = int(telegram.get("file_size"))
    except Exception:
        file_size = None
    if file_size is None:
        try:
            if doc.get("file_size") is not None:
                file_size = int(doc.get("file_size"))
        except Exception:
            file_size = None

    duration_sec: float | None = None
    try:
        if audio.get("duration_sec") is not None:
            duration_sec = float(audio.get("duration_sec"))
    except Exception:
        duration_sec = None

    bitrate_kbps: int | None = None
    try:
        if audio.get("bitrate_kbps") is not None:
            bitrate_kbps = int(audio.get("bitrate_kbps"))
    except Exception:
        bitrate_kbps = None

    range_header = (request.headers.get("range") or request.headers.get("Range") or "").strip()
    start_byte, end_byte = _parse_range_header(range_header)

    has_range = bool(range_header) and start_byte is not None
    from_bytes = int(start_byte or 0)

    until_bytes: int | None = None
    if has_range:
        if end_byte is not None:
            until_bytes = int(end_byte)
        elif file_size is not None:
            until_bytes = int(file_size) - 1
    if file_size is not None and until_bytes is not None:
        until_bytes = min(int(until_bytes), int(file_size) - 1)

    if file_size is not None and has_range and from_bytes >= file_size:
        raise HTTPException(status_code=416, detail="range not satisfiable")

    status_code = 206 if (has_range and file_size is not None and until_bytes is not None) else 200

    headers = {"Accept-Ranges": "bytes"}
    if status_code == 206 and file_size is not None and until_bytes is not None:
        headers["Content-Range"] = f"bytes {from_bytes}-{until_bytes}/{file_size}"
        headers["Content-Length"] = str((until_bytes - from_bytes) + 1)

    file_ids_for_pick = telegram.get("file_ids") if isinstance(telegram.get("file_ids"), dict) else {}
    preferred_user_ids: list[int] = []
    for k, v in (file_ids_for_pick or {}).items():
        if not isinstance(v, str) or not v.strip():
            continue
        try:
            preferred_user_ids.append(int(k))
        except Exception:
            pass

    from stream import acquire_stream_client, acquire_stream_client_prefer, release_stream_client

    affinity_client_id: int | None = None
    now = time.monotonic()
    async with _TRACK_AFFINITY_LOCK:
        entry = _TRACK_AFFINITY.get(track_id)
        if entry:
            cid, ts = entry
            if (now - float(ts)) <= float(_TRACK_AFFINITY_TTL_SEC):
                affinity_client_id = int(cid)
            else:
                _TRACK_AFFINITY.pop(track_id, None)

    if preferred_user_ids:
        if affinity_client_id is not None and affinity_client_id in set(preferred_user_ids):
            try:
                client_user_id, client = await acquire_stream_client_prefer([int(affinity_client_id)])
            except Exception:
                client_user_id, client = await acquire_stream_client_prefer(preferred_user_ids)
        else:
            client_user_id, client = await acquire_stream_client_prefer(preferred_user_ids)
    else:
        client_user_id, client = await acquire_stream_client()

    async with _TRACK_AFFINITY_LOCK:
        _TRACK_AFFINITY[track_id] = (int(client_user_id), time.monotonic())
    try:
        if preferred_user_ids:
            if affinity_client_id is not None and int(client_user_id) == int(affinity_client_id):
                LOG.debug(f"stream using affinity client track={track_id} client={client_user_id}")
            else:
                LOG.debug(f"stream picked preferred client track={track_id} client={client_user_id}")
        else:
            LOG.debug(f"stream picked client track={track_id} client={client_user_id}")
        client_key = str(int(client_user_id))
        file_ids = telegram.get("file_ids") if isinstance(telegram.get("file_ids"), dict) else {}
        file_id = (file_ids or {}).get(client_key)
        if isinstance(file_id, str):
            file_id = file_id.strip()
        else:
            file_id = ""

        primary_uid = get_primary_client_user_id()
        if not file_id and primary_uid is not None and int(primary_uid) == int(client_user_id) and primary_file_id:
            file_id = primary_file_id
            LOG.debug(f"stream using primary file_id track={track_id} client={client_user_id} file_id={file_id}")
            await get_audio_tracks_collection().update_one(
                {"_id": track_id},
                {"$set": {f"telegram.file_ids.{client_key}": file_id, "updated_at": time.time()}},
            )
        elif file_id:
            LOG.debug(f"stream using cached file_id track={track_id} client={client_user_id} file_id={file_id}")

        if not file_id:
            file_id = await _ensure_client_file_id(
                track_id=track_id,
                client_user_id=int(client_user_id),
                client=client,
                source_chat_id=source_chat_id,
                source_message_id=source_message_id,
            )
    except Exception:
        await release_stream_client(int(client_user_id))
        raise

    if status_code == 206:
        iterator = _stream_range(
            client_user_id=int(client_user_id),
            client=client,
            file_id=file_id,
            from_bytes=from_bytes,
            until_bytes=until_bytes,
        )
    else:
        iterator = _direct_stream(
            client_user_id=int(client_user_id),
            client=client,
            file_id=file_id,
            start_byte=0,
        )
    wrapped = _wrap_with_play_count(
        iterator=iterator,
        request=request,
        track_id=track_id,
        from_bytes=from_bytes if status_code == 206 else 0,
        file_size=file_size,
        duration_sec=duration_sec,
        bitrate_kbps=bitrate_kbps,
    )
    return StreamingResponse(wrapped, status_code=status_code, headers=headers, media_type=mime_type)


async def warm_track_cached(track_id: str) -> dict:
    if bool(getattr(Config, "ONLY_API", False)) or bot is None:
        raise HTTPException(status_code=503, detail="streaming disabled")
    track_id = (track_id or "").strip()
    if not track_id:
        raise HTTPException(status_code=400, detail="track_id is required")

    col = get_audio_tracks_collection()
    doc = await col.find_one(
        {"_id": track_id},
        projection={"telegram": 1, "source_chat_id": 1, "source_message_id": 1},
    )
    if not doc:
        raise HTTPException(status_code=404, detail="Track not found")

    telegram = doc.get("telegram") or {}
    file_id = (telegram.get("file_id") or "").strip()

    source_chat_id = doc.get("source_chat_id")
    source_message_id = doc.get("source_message_id")
    try:
        if source_chat_id is not None:
            source_chat_id = int(source_chat_id)
    except Exception:
        source_chat_id = None
    try:
        if source_message_id is not None:
            source_message_id = int(source_message_id)
    except Exception:
        source_message_id = None

    if not file_id and (source_chat_id is None or source_message_id is None):
        return {"ok": False, "error": "missing_source"}

    await _get_or_create_hub(
        file_id=file_id or track_id,
        source_chat_id=source_chat_id,
        source_message_id=source_message_id,
    )
    return {"ok": True}
