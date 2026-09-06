import asyncio
import json
import re
import time
import unicodedata
from os import path as ospath

from aiofiles.os import remove as aioremove
from pymongo.errors import DuplicateKeyError
from pyrogram import filters
from pyrogram.types import Message

from stream import bot, get_primary_client_user_id
from stream.core.config_manager import Config
from stream.database.MongoDb import db_handler
from stream.helpers.cover_search import fetch_artist_avatar_info, find_best_cover_url, spotify_best_track
from stream.helpers.dedup import metadata_fingerprint, sha256_prefix_file
from stream.helpers.logger import LOGGER
from stream.plugins.Analyzer.mediaHelper import (
    _is_junk_title,
    download_message_media,
    ensure_media_dir,
    extract_audio_metadata_normalized,
    infer_artist_title,
    run_mediainfo,
    sanitize_filename,
)

LOG = LOGGER(__name__)

_ENRICH_WORKERS: list[asyncio.Task] = []
_INDEX_TASKS: dict[str, asyncio.Task] = {}
_INDEX_TASKS_LOCK = asyncio.Lock()
_ENRICH_RETRY_DELAY_SEC = 60.0


async def _mark_enrichment_retry(
    doc_id: str,
    reason: str,
    *,
    retry_delay: float | None = None,
) -> None:
    now = time.time()
    delay = _ENRICH_RETRY_DELAY_SEC if retry_delay is None else float(retry_delay)
    await db_handler.audio_collection.collection.update_one(
        {"_id": doc_id},
        {
            "$set": {
                "enriched": False,
                "enrichment_error": str(reason or "enrichment failed")[:500],
                "enrichment_error_at": now,
                "enrich_retry_after": now + max(0.0, delay),
                "updated_at": now,
            },
            "$unset": {"enriching": "", "enrichment_started_at": ""},
        },
    )


async def _fetch_message(chat_id: int, message_id: int) -> Message | None:
    try:
        msg = await bot.get_messages(chat_id, message_id)
        if msg and not getattr(msg, "empty", False):
            return msg
    except Exception:
        pass

    from stream.plugins.userBot.service import _USERBOT_INSTANCE

    if _USERBOT_INSTANCE:
        try:
            msg = await _USERBOT_INSTANCE.get_messages(chat_id, message_id)
            if msg and not getattr(msg, "empty", False):
                return msg
        except Exception:
            pass

    return None


async def _enrichment_loop(worker_id: int):
    while True:
        try:
            col = db_handler.audio_collection.collection
            now = time.time()
            doc = await col.find_one_and_update(
                {
                    "enriched": False,
                    "enriching": {"$ne": True},
                    "deleted": {"$ne": True},
                    "$or": [
                        {"enrich_retry_after": {"$exists": False}},
                        {"enrich_retry_after": {"$lte": now}},
                    ],
                },
                {"$set": {"enriching": True, "enrichment_started_at": now}},
                sort=[("source_message_id", -1)],
            )
            if not doc:
                await asyncio.sleep(5)
                continue

            source_chat_id = doc.get("source_chat_id")
            source_msg_id = doc.get("source_message_id")
            chat_id = doc.get("cache_chat_id") or source_chat_id
            msg_id = doc.get("cache_message_id") or source_msg_id

            if chat_id is None or msg_id is None:
                await _mark_enrichment_retry(
                    doc["_id"],
                    "missing cache/source chat or message id",
                    retry_delay=300,
                )
                continue

            msg = await _fetch_message(chat_id, msg_id)
            media = _pick_audio_media(msg) if msg else None

            if not msg or not media:
                await _mark_enrichment_retry(
                    doc["_id"],
                    "source message unavailable or has no audio media",
                    retry_delay=300,
                )
                continue

            # We use the existing _enrich_audio_doc logic which now supports partial download
            try:
                await _enrich_audio_doc(msg, media, existing_doc_id=str(doc["_id"]))
                # _enrich_audio_doc marks enriched only after all enrichment steps finish.
            except Exception as e:
                LOG.warning(
                    f"Enrichment worker {worker_id} failed on {chat_id}:{msg_id}: {e}"
                )
                await _mark_enrichment_retry(doc["_id"], str(e))

        except asyncio.CancelledError:
            break
        except Exception as e:
            LOG.error(f"Enrichment worker {worker_id} error: {e}")
            await asyncio.sleep(5)


def start_enrichment_workers():
    _ENRICH_WORKERS[:] = [task for task in _ENRICH_WORKERS if not task.done()]
    if _ENRICH_WORKERS:
        LOG.info(f"Enrichment workers already running: {len(_ENRICH_WORKERS)}")
        return

    workers = int(getattr(Config, "PROCESSING_CONTENT", 4))
    if workers <= 0:
        workers = 4
    for i in range(workers):
        task = asyncio.create_task(_enrichment_loop(i))
        _ENRICH_WORKERS.append(task)
    LOG.info(f"Started {workers} enrichment workers.")


async def stop_enrichment_workers():
    for task in _ENRICH_WORKERS:
        task.cancel()
    if _ENRICH_WORKERS:
        await asyncio.gather(*_ENRICH_WORKERS, return_exceptions=True)
    _ENRICH_WORKERS.clear()


def _extract_message_file_id(message: Message) -> str | None:
    if not message:
        return None
    media = getattr(message, "audio", None) or getattr(message, "document", None)
    if not media:
        return None
    fid = getattr(media, "file_id", None)
    if not fid:
        return None
    return str(fid)


async def _ensure_dump_message_id(
    *, source_chat_id: int, source_message_id: int
) -> int | None:
    dump_channel_id = getattr(Config, "DUMP_CHANNEL_ID", None)
    try:
        dump_channel_id = int(dump_channel_id)
    except Exception:
        dump_channel_id = 0
    if not dump_channel_id:
        return None

    doc = await db_handler.audio_collection.find_one(
        {
            "source_chat_id": int(source_chat_id),
            "source_message_id": int(source_message_id),
        },
        projection={"telegram": 1},
    )
    telegram = (doc or {}).get("telegram") or {}
    dump_message_id = telegram.get("dump_message_id")
    try:
        dump_message_id = int(dump_message_id) if dump_message_id is not None else None
    except Exception:
        dump_message_id = None
    if dump_message_id:
        return dump_message_id

    try:
        sent = await bot.copy_message(
            chat_id=int(dump_channel_id),
            from_chat_id=int(source_chat_id),
            message_id=int(source_message_id),
        )
    except Exception:
        return None

    dump_message_id = int(getattr(sent, "id"))
    await db_handler.audio_collection.update_one(
        {
            "source_chat_id": int(source_chat_id),
            "source_message_id": int(source_message_id),
        },
        {
            "$set": {
                "telegram.dump_message_id": dump_message_id,
                "updated_at": time.time(),
            }
        },
        upsert=False,
    )
    return dump_message_id


async def _sync_file_ids_for_all_clients(
    *, source_chat_id: int, source_message_id: int
) -> None:
    try:
        from stream import _multi_lock, multi_clients
    except Exception:
        return

    try:
        source_chat_id = int(source_chat_id)
        source_message_id = int(source_message_id)
    except Exception:
        return

    track_filter = {
        "$or": [
            {
                "source_chat_id": int(source_chat_id),
                "source_message_id": int(source_message_id),
            },
            {
                "cache_chat_id": int(source_chat_id),
                "cache_message_id": int(source_message_id),
            },
        ]
    }

    async with _multi_lock:
        clients = list(multi_clients.items())

    if not clients:
        return

    dump_channel_id = getattr(Config, "DUMP_CHANNEL_ID", None)
    try:
        dump_channel_id = int(dump_channel_id)
    except Exception:
        dump_channel_id = 0

    dump_message_id: int | None = None
    for uid, client in clients:
        fid = None
        try:
            msg = await client.get_messages(int(source_chat_id), int(source_message_id))
            fid = _extract_message_file_id(msg)
        except Exception:
            fid = None

        if not fid and dump_channel_id:
            if dump_message_id is None:
                dump_message_id = await _ensure_dump_message_id(
                    source_chat_id=int(source_chat_id),
                    source_message_id=int(source_message_id),
                )
            if dump_message_id:
                try:
                    msg = await client.get_messages(
                        int(dump_channel_id), int(dump_message_id)
                    )
                    fid = _extract_message_file_id(msg)
                except Exception:
                    fid = None

        if not fid:
            continue

        key = str(int(uid))
        await db_handler.audio_collection.update_one(
            track_filter,
            {"$set": {f"telegram.file_ids.{key}": str(fid), "updated_at": time.time()}},
            upsert=False,
        )
        LOG.debug(
            f"index stored file_id source={source_chat_id}:{source_message_id} client={key} file_id={str(fid)}"
        )


def _dbg(msg: str) -> None:
    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(msg)


def _pick_audio_media(message: Message):
    media = message.audio
    if (
        not media
        and message.document
        and (message.document.mime_type or "").startswith("audio/")
    ):
        media = message.document
    if not media:
        return None
    return media


def _coerce_int(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except Exception:
        return None


_META_KEYS = {"source_chat_id", "source_message_id", "topic_id", "topic_name"}


def _parse_meta_caption(caption) -> dict:
    text = str(caption or "")
    if not text.strip():
        return {}
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    if not lines or lines[0].strip() != "#META":
        return {}

    meta: dict[str, str] = {}
    for raw in lines[1:]:
        line = raw.strip()
        if not line:
            break
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key in _META_KEYS:
            meta[key] = value.strip()
    return meta


def _source_metadata_from_message(message: Message) -> dict:
    meta = _parse_meta_caption(getattr(message, "caption", None))
    cache_chat_id = _coerce_int(getattr(getattr(message, "chat", None), "id", None))
    cache_message_id = _coerce_int(getattr(message, "id", None))

    source_chat_id = _coerce_int(meta.get("source_chat_id")) or cache_chat_id
    source_message_id = _coerce_int(meta.get("source_message_id")) or cache_message_id
    topic_id = _coerce_int(meta.get("topic_id"))
    if topic_id is None:
        topic_id = _coerce_int(getattr(message, "message_thread_id", None))
    if topic_id is None:
        topic_id = 0

    topic_name = str(meta.get("topic_name") or "").strip()
    if not topic_name:
        topic_name = "main" if int(topic_id) == 0 else f"topic_{int(topic_id)}"

    return {
        "source_chat_id": source_chat_id,
        "source_message_id": source_message_id,
        "topic_id": int(topic_id),
        "topic_name": topic_name,
        "cache_chat_id": cache_chat_id,
        "cache_message_id": cache_message_id,
    }


_ARTIST_SPLIT_RE = re.compile(
    r"\s*(?:,|/|&| and | x | feat\. | feat | ft\. | ft )\s*", flags=re.I
)
_ALBUM_SLUG_RE = re.compile(r"[^a-z0-9 ]+", flags=re.I)


def _split_artists(value: str) -> list[str]:
    raw = (value or "").strip()
    if not raw:
        return []
    raw = (
        raw.replace("(", " ")
        .replace(")", " ")
        .replace("[", " ")
        .replace("]", " ")
        .strip()
    )
    parts = [p.strip() for p in _ARTIST_SPLIT_RE.split(raw) if p and p.strip()]
    out: list[str] = []
    seen: set[str] = set()
    for p in parts:
        key = p.casefold()
        if key in seen:
            continue
        seen.add(key)
        out.append(p)
    return out


def _slugify(value: str) -> str:
    s = (value or "").strip().lower()
    if not s:
        return ""
    s = s.replace("÷", " divide ").replace("&", " and ").replace("+", " plus ")
    s = unicodedata.normalize("NFKD", s)
    s = s.encode("ascii", "ignore").decode("ascii")
    s = _ALBUM_SLUG_RE.sub(" ", s)
    s = re.sub(r"\s+", "_", s.strip())
    s = re.sub(r"_+", "_", s).strip("_")
    return s


def _coerce_year(value) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        y = int(value)
    except Exception:
        return None
    if y < 1000 or y > 2100:
        return None
    return y


def _album_id(*, album: str, year: int | None) -> str:
    b = _slugify(album)
    if not b:
        return ""
    y = _coerce_year(year)
    if y is not None:
        return f"album_{b}_{y}"
    return f"album_{b}"


def _best_title_artist_album(
    *, audio_doc: dict, media, inferred_title: str, inferred_artist: str
) -> tuple[str, str, str]:
    title = ""
    for cand in (audio_doc.get("title"), getattr(media, "title", ""), inferred_title):
        c = (cand or "").strip()
        if c and not _is_junk_title(c):
            title = c
            break
    artist = (
        audio_doc.get("artist")
        or getattr(media, "performer", "")
        or inferred_artist
        or ""
    ).strip()
    album = (audio_doc.get("album") or "").strip()

    file_name = getattr(media, "file_name", "") or ""
    if not title:
        base = ospath.splitext(ospath.basename(file_name))[0].strip()
        title = base or str(getattr(media, "file_unique_id", "") or "").strip()

    if not title:
        title = str(getattr(media, "file_id", "") or "").strip()

    return title, artist, album


async def _upsert_minimal(message: Message, media, enriching: bool = False) -> str:
    file_unique_id = (
        getattr(media, "file_unique_id", None) or f"{message.chat.id}:{message.id}"
    )
    source_meta = _source_metadata_from_message(message)
    file_id = getattr(media, "file_id", None)
    primary_uid = get_primary_client_user_id()
    file_ids = None
    if primary_uid is not None and file_id:
        file_ids = {str(int(primary_uid)): file_id}
    file_size = _coerce_int(getattr(media, "file_size", None))
    duration_sec = _coerce_int(getattr(media, "duration", None))

    file_name = getattr(media, "file_name", "") or ""
    inferred_artist, inferred_title = infer_artist_title(file_name)

    title = (getattr(media, "title", "") or inferred_title or "").strip()
    artist = (getattr(media, "performer", "") or inferred_artist or "").strip()
    artists = _split_artists(artist) if artist else []

    if not title:
        base = ospath.splitext(ospath.basename(file_name))[0].strip()
        title = base or str(message.id)

    payload = {
        "telegram": {
            "file_id": file_id,
            "mime_type": getattr(media, "mime_type", None),
            "file_size": file_size,
        },
        "audio": {
            "title": title,
            "artist": artist,
            "artists": artists if artists else None,
            "duration_sec": duration_sec,
        },
        "source_chat_id": source_meta.get("source_chat_id"),
        "source_message_id": source_meta.get("source_message_id"),
        "topic_id": source_meta.get("topic_id"),
        "topic_name": source_meta.get("topic_name"),
        "cache_chat_id": source_meta.get("cache_chat_id"),
        "cache_message_id": source_meta.get("cache_message_id"),
        "indexed": True,
        "enriched": False,
    }
    if enriching:
        payload["enriching"] = True

    payload["updated_at"] = time.time()

    if file_ids:
        payload["telegram"]["file_ids"] = file_ids

    update = {"$set": {k: v for k, v in payload.items() if v is not None}}
    if not enriching:
        update["$unset"] = {
            "enriching": "",
            "enrichment_started_at": "",
            "enrichment_error": "",
            "enrichment_error_at": "",
            "enrich_retry_after": "",
        }

    await db_handler.audio_collection.update_one(
        {"_id": file_unique_id},
        update,
        upsert=True,
    )
    return file_unique_id


async def _enrich_audio_doc(
    message: Message,
    media,
    existing_doc_id: str | None = None,
):
    source_meta = _source_metadata_from_message(message)
    media_file_unique_id = getattr(media, "file_unique_id", None)
    file_unique_id = str(
        existing_doc_id or media_file_unique_id or f"{message.chat.id}:{message.id}"
    )
    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(
            f"[index] start file_unique_id={file_unique_id!r} chat={int(message.chat.id)} msg={int(message.id)} "
            f"file_name={str(getattr(media, 'file_name', '') or '')!r}"
        )

    base_dir = await ensure_media_dir()
    base_name = sanitize_filename(getattr(media, "file_name", "") or f"{message.id}")
    unique_key = (
        getattr(media, "file_unique_id", None) or f"{message.chat.id}_{message.id}"
    )
    unique_prefix = sanitize_filename(str(unique_key))[:48]
    nonce = str(time.time_ns())[-8:]
    filename = f"{unique_prefix}_{nonce}_{base_name}"
    file_path = ospath.join(base_dir, filename)

    file_size = getattr(media, "file_size", None)
    content_hash = None
    output = ""
    partial_path = file_path + ".part"
    from stream.plugins.Analyzer.mediaHelper import download_partial_media

    try:
        max_chunk = int(getattr(Config, "PARTIAL_DOWNLOAD_BYTES", 2_000_000))
        await download_partial_media(message, partial_path, max_bytes=max_chunk)
        output = await run_mediainfo(partial_path)
        audio_doc_test = extract_audio_metadata_normalized(output, duration_sec=None)

        # If partial download failed to extract duration, do a full download.
        if not audio_doc_test.get("duration_sec"):
            LOG.debug(
                f"Partial mediainfo insufficient, falling back to full download for {file_unique_id}"
            )
            file_size_dl = await download_message_media(message, file_path)
            if file_size_dl:
                file_size = file_size_dl
            output = await run_mediainfo(file_path)
            try:
                content_hash = sha256_prefix_file(file_path)
            except Exception as e:
                LOG.warning(
                    f"Hashing failed chat={message.chat.id} msg={message.id}: {e}"
                )
        else:
            # We got enough info from partial download. Try hashing partial just as prefix
            try:
                content_hash = sha256_prefix_file(partial_path)
            except Exception:
                pass

    except Exception as e:
        LOG.warning(
            f"Indexing failed chat={message.chat.id} msg={message.id}: {e}",
            exc_info=True,
        )
    finally:
        for p in (file_path, partial_path):
            try:
                await aioremove(p)
            except Exception:
                pass

    if not output:
        if bool(getattr(Config, "DEBUG", False)):
            LOG.debug(f"[index] mediainfo empty file_unique_id={file_unique_id!r}")
        await _mark_enrichment_retry(file_unique_id, "mediainfo produced no output")
        return

    duration_sec = _coerce_int(getattr(media, "duration", None))

    audio_doc = extract_audio_metadata_normalized(output, duration_sec=duration_sec)

    file_name = getattr(media, "file_name", "") or ""
    inferred_performer, inferred_title = infer_artist_title(file_name)

    title, performer, album = _best_title_artist_album(
        audio_doc=audio_doc,
        media=media,
        inferred_title=inferred_title,
        inferred_artist=inferred_performer,
    )
    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(
            f"[index] metadata picked file_unique_id={file_unique_id!r} title={title!r} artist={performer!r} album={album!r} "
            f"duration_sec={audio_doc.get('duration_sec')!r}"
        )

    audio_doc["title"] = title
    if performer:
        audio_doc["artist"] = performer
        artists = _split_artists(performer)
        if artists:
            audio_doc["artists"] = artists
    if album:
        audio_doc["album"] = album
        aid = _album_id(album=album, year=_coerce_year(audio_doc.get("year")))
        if aid:
            audio_doc["album_id"] = aid

    origin_cover_url = None
    cover_url = None
    cover_source = None
    spotify: dict = {}

    spotify_enabled = bool(getattr(Config, "SPOTIFY_COVER_SEARCH", False))
    fallbacks_enabled = bool(getattr(Config, "MUSIC_HOADER_SEARCH", False))

    _dbg(
        "[cover] start "
        + json.dumps(
            {
                "title": title,
                "artist": performer,
                "album": album,
                "year": audio_doc.get("year"),
                "spotify_enabled": spotify_enabled,
                "fallbacks_enabled": fallbacks_enabled,
                "file_unique_id": file_unique_id,
            },
            ensure_ascii=False,
        )
    )

    small_cover_url = None
    try:
        origin_cover_url, cover_source, small_cover_url = await find_best_cover_url(
            title=title,
            artist=performer,
            album=album,
            year=audio_doc.get("year"),
        )
    except Exception as e:
        LOG.warning(f"Cover lookup failed chat={message.chat.id} msg={message.id}: {e}")
        await _mark_enrichment_retry(file_unique_id, f"cover lookup failed: {e}")
        raise

    if origin_cover_url:
        cover_url = str(origin_cover_url).strip()
        if (
            cover_url.startswith("`")
            and cover_url.endswith("`")
            and len(cover_url) >= 2
        ):
            cover_url = cover_url[1:-1].strip()
        _dbg(
            f"[cover] found track={title!r} artist={performer!r} src={cover_source!r} url={origin_cover_url!r}"
        )

    if performer:
        try:
            art_info = await fetch_artist_avatar_info(performer)
            if art_info and art_info.get("avatar_url"):
                spotify["artist_avatar"] = art_info.get("avatar_url")
        except Exception as e:
            _dbg(f"[artist] failed to fetch artist avatar: {e}")
    if cover_source == "hoaders" and small_cover_url:
        spotify["cover_url"] = small_cover_url
        spotify["big_cover_url"] = cover_url
    elif small_cover_url:
        spotify["small_cover_url"] = small_cover_url
    try:
        sp_track = await spotify_best_track(
            title=title,
            artist=performer,
            album=album,
            year=audio_doc.get("year"),
        )
        if isinstance(sp_track, dict):
            sp_id = sp_track.get("id")
            if isinstance(sp_id, str) and sp_id.strip():
                spotify["track_spotify_id"] = sp_id.strip()
            ext = (
                sp_track.get("external_urls")
                if isinstance(sp_track.get("external_urls"), dict)
                else {}
            )
            sp_url = ext.get("spotify")
            if isinstance(sp_url, str) and sp_url.strip():
                s = sp_url.strip()
                spotify["url"] = s
    except Exception as e:
        LOG.warning(
            f"Spotify enrichment failed chat={message.chat.id} msg={message.id}: {e}"
        )
        await _mark_enrichment_retry(file_unique_id, f"spotify enrichment failed: {e}")
        raise
    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(
            f"[spotify] resolved file_unique_id={file_unique_id!r} "
            f"track_spotify_id={spotify.get('track_spotify_id')!r} url={spotify.get('url')!r} cover_url={spotify.get('cover_url')!r}"
        )

    _dbg(
        "[cover] done "
        + json.dumps(
            {
                "cover_url": cover_url,
                "cover_source": cover_source,
                "origin_cover_url": origin_cover_url,
            },
            ensure_ascii=False,
        )
    )

    file_id = getattr(media, "file_id", None)
    primary_uid = get_primary_client_user_id()
    primary_uid_key = None
    if primary_uid is not None:
        try:
            primary_uid_key = str(int(primary_uid))
        except Exception:
            primary_uid_key = None
    if primary_uid_key and file_id:
        LOG.debug(
            f"index file_ids set primary={primary_uid_key} file_id={file_id} doc={file_unique_id}"
        )

    fingerprint = metadata_fingerprint(
        title=title,
        artist=performer,
        album=album,
        duration_sec=audio_doc.get("duration_sec"),
    )

    payload = {
        "telegram.file_id": file_id,
        "telegram.file_unique_id": media_file_unique_id,
        "telegram.mime_type": getattr(media, "mime_type", None),
        "telegram.file_size": file_size,
        "audio": audio_doc,
        "spotify": spotify,
        "content_hash": content_hash,
        "fingerprint": fingerprint,
        "topic_id": source_meta.get("topic_id"),
        "topic_name": source_meta.get("topic_name"),
        "cache_chat_id": source_meta.get("cache_chat_id"),
        "cache_message_id": source_meta.get("cache_message_id"),
        "updated_at": time.time(),
    }
    if primary_uid_key and file_id:
        payload[f"telegram.file_ids.{primary_uid_key}"] = str(file_id)

    col = db_handler.audio_collection

    duplicate = None
    if not duplicate and content_hash:
        duplicate = await col.find_document(
            {"content_hash": content_hash}, projection={"_id": 1}
        )
    if not duplicate and fingerprint:
        duplicate = await col.find_document(
            {"fingerprint": fingerprint}, projection={"_id": 1}
        )

    target_id = (
        duplicate["_id"] if (duplicate and duplicate.get("_id")) else file_unique_id
    )

    ensure_source = {}
    existing = await col.read_document(
        target_id,
        projection={
            "_id": 1,
            "source_chat_id": 1,
            "source_message_id": 1,
            "topic_id": 1,
            "topic_name": 1,
            "cache_chat_id": 1,
            "cache_message_id": 1,
        },
    )
    if (
        not existing
        or existing.get("source_chat_id") is None
        or existing.get("source_message_id") is None
    ):
        ensure_source = {
            "source_chat_id": source_meta.get("source_chat_id"),
            "source_message_id": source_meta.get("source_message_id"),
        }
    if not existing or existing.get("topic_id") is None:
        ensure_source["topic_id"] = source_meta.get("topic_id")
    if not existing or not existing.get("topic_name"):
        ensure_source["topic_name"] = source_meta.get("topic_name")
    if not existing or existing.get("cache_chat_id") is None:
        ensure_source["cache_chat_id"] = source_meta.get("cache_chat_id")
    if not existing or existing.get("cache_message_id") is None:
        ensure_source["cache_message_id"] = source_meta.get("cache_message_id")

    now_ts = time.time()
    set_fields = {
        **{k: v for k, v in payload.items() if v is not None},
        **ensure_source,
        "enriched": False,
        "enriching": True,
        "updated_at": now_ts,
    }
    try:
        await col.update_one({"_id": target_id}, {"$set": set_fields, "$setOnInsert": {"created_at": now_ts}}, upsert=True)
    except DuplicateKeyError:
        dup = None
        if content_hash:
            try:
                dup = await col.find_document(
                    {"content_hash": content_hash}, projection={"_id": 1}
                )
            except Exception:
                dup = None
        if not dup and fingerprint:
            try:
                dup = await col.find_document(
                    {"fingerprint": fingerprint}, projection={"_id": 1}
                )
            except Exception:
                dup = None
        if dup and dup.get("_id"):
            target_id = dup["_id"]
            ensure_source2 = {}
            try:
                existing3 = await col.read_document(
                    target_id,
                    projection={
                        "_id": 1,
                        "source_chat_id": 1,
                        "source_message_id": 1,
                        "topic_id": 1,
                        "topic_name": 1,
                        "cache_chat_id": 1,
                        "cache_message_id": 1,
                    },
                )
            except Exception:
                existing3 = None
            if (
                not existing3
                or existing3.get("source_chat_id") is None
                or existing3.get("source_message_id") is None
            ):
                ensure_source2 = {
                    "source_chat_id": source_meta.get("source_chat_id"),
                    "source_message_id": source_meta.get("source_message_id"),
                }
            if not existing3 or existing3.get("topic_id") is None:
                ensure_source2["topic_id"] = source_meta.get("topic_id")
            if not existing3 or not existing3.get("topic_name"):
                ensure_source2["topic_name"] = source_meta.get("topic_name")
            if not existing3 or existing3.get("cache_chat_id") is None:
                ensure_source2["cache_chat_id"] = source_meta.get("cache_chat_id")
            if not existing3 or existing3.get("cache_message_id") is None:
                ensure_source2["cache_message_id"] = source_meta.get("cache_message_id")
            await col.update_one(
                {"_id": target_id},
                {"$set": {**set_fields, **ensure_source2}},
                upsert=False,
            )
            if target_id != file_unique_id:
                try:
                    await col.delete_document(file_unique_id)
                except Exception:
                    pass
        else:
            raise

    if target_id != file_unique_id:
        try:
            await col.delete_document(file_unique_id)
        except Exception:
            pass

    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(f"[mongo] upserted audio doc _id={target_id!r}")

    try:
        lyrics_enabled = bool(getattr(Config, "MUSIXMATCH", False)) or bool(
            getattr(Config, "LRCLIB", True)
        )
        if lyrics_enabled:
            existing2 = None
            try:
                existing2 = await col.read_document(target_id, projection={"lyrics": 1})
            except Exception:
                existing2 = None
            existing_lyrics = (existing2 or {}).get("lyrics")
            has_lyrics = isinstance(existing_lyrics, str) and existing_lyrics.strip()
            if not has_lyrics:
                from Api.services.lyrics_service import get_track_lyrics

                lyrics_result = await get_track_lyrics(target_id)
                if (
                    isinstance(lyrics_result, dict)
                    and lyrics_result.get("ok") is False
                ):
                    lyrics_error = str(lyrics_result.get("error") or "unknown")
                    if lyrics_error not in {"no_match", "no_lyrics", "lyrics_disabled"}:
                        raise RuntimeError(
                            f"lyrics enrichment failed: {lyrics_error}"
                        )
    except Exception as e:
        LOG.error(f"[index] lyrics fetch failed for {target_id!r}: {e}", exc_info=True)
        await _mark_enrichment_retry(target_id, str(e))
        raise

    now = time.time()
    await col.update_one(
        {"_id": target_id},
        {
            "$set": {"enriched": True, "enriched_at": now, "updated_at": now},
            "$unset": {
                "enriching": "",
                "enrichment_started_at": "",
                "enrichment_error": "",
                "enrichment_error_at": "",
                "enrich_retry_after": "",
            },
        },
        upsert=False,
    )
    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(
            f"[index] done file_unique_id={file_unique_id!r} target_id={target_id!r}"
        )


def _audio_ingest_filter():
    """Build the source filter for audio and document messages.
    Dynamic source checks (mode 0 channel only, mode 1 anyone, mode 2 hybrid allowlist,
    and strict ban enforcement) are executed in channel_audio_filter via is_message_allowed().
    """
    return filters.audio | filters.document


@bot.on_message(_audio_ingest_filter())
async def channel_audio_filter(_, message: Message):
    try:
        from stream.core.source_filter import is_message_allowed

        allowed, reason = await is_message_allowed(message)
        if not allowed:
            LOG.debug(
                f"[ingest] Ignored audio message {getattr(message, 'id', None)} "
                f"from chat={getattr(getattr(message, 'chat', None), 'id', None)}: {reason}"
            )
            return

        key = f"{message.chat.id}:{message.id}"
        async with _INDEX_TASKS_LOCK:
            task = _INDEX_TASKS.get(key)
            if task and not task.done():
                return
            media = _pick_audio_media(message)
            if not media:
                return
            await _upsert_minimal(message, media, enriching=True)
            fid_key = f"fid:{message.chat.id}:{message.id}"
            fid_task = _INDEX_TASKS.get(fid_key)
            if not fid_task or fid_task.done():
                _INDEX_TASKS[fid_key] = asyncio.create_task(
                    _sync_file_ids_for_all_clients(
                        source_chat_id=int(message.chat.id),
                        source_message_id=int(message.id),
                    )
                )
            task = asyncio.create_task(_enrich_audio_doc(message, media))
            _INDEX_TASKS[key] = task

        def _done(_t: asyncio.Task):
            try:
                _t.result()
            except Exception as e:
                LOG.warning(
                    f"channel_audio_filter background indexing failed chat={message.chat.id} msg={message.id}: {e}",
                    exc_info=True,
                )

            async def _cleanup():
                async with _INDEX_TASKS_LOCK:
                    current = _INDEX_TASKS.get(key)
                    if current is _t:
                        _INDEX_TASKS.pop(key, None)

            asyncio.create_task(_cleanup())

        task.add_done_callback(_done)
    except Exception as e:
        LOG.warning(
            f"channel_audio_filter failed chat={message.chat.id} msg={message.id}: {e}",
            exc_info=True,
        )


@bot.on_deleted_messages(group=4)
async def deleted_messages_handler(_, messages):
    try:
        now = time.time()
        pairs: list[tuple[int, int]] = []
        for m in messages or []:
            chat = getattr(m, "chat", None)
            chat_id = (
                getattr(chat, "id", None)
                if chat is not None
                else getattr(m, "chat_id", None)
            )
            msg_id = getattr(m, "id", None)
            if msg_id is None:
                msg_id = getattr(m, "message_id", None)
            try:
                chat_id = int(chat_id) if chat_id is not None else None
                msg_id = int(msg_id) if msg_id is not None else None
            except Exception:
                chat_id = None
                msg_id = None
            if chat_id is None or msg_id is None:
                continue
            pairs.append((chat_id, msg_id))

        if not pairs:
            return

        res = await db_handler.audio_collection.collection.update_many(
            {
                "$or": [
                    {"source_chat_id": cid, "source_message_id": mid}
                    for (cid, mid) in pairs
                ]
                + [
                    {"cache_chat_id": cid, "cache_message_id": mid}
                    for (cid, mid) in pairs
                ]
            },
            {"$set": {"deleted": True, "deleted_at": now, "updated_at": now}},
        )
        if bool(getattr(Config, "DEBUG", False)):
            try:
                LOG.debug(
                    f"deleted_messages_handler marked deleted count={len(pairs)} matched={int(getattr(res, 'matched_count', 0) or 0)} "
                    f"modified={int(getattr(res, 'modified_count', 0) or 0)}"
                )
            except Exception:
                pass
    except Exception as e:
        LOG.warning(f"deleted_messages_handler failed: {e}", exc_info=True)


@bot.on_raw_update(group=4)
async def deleted_messages_raw_handler(_, update, __, ___):
    try:
        from pyrogram.raw.types import UpdateDeleteChannelMessages
    except Exception:
        return

    if not isinstance(update, UpdateDeleteChannelMessages):
        return

    try:
        channel_id = int(getattr(update, "channel_id", 0) or 0)
    except Exception:
        channel_id = 0
    if channel_id <= 0:
        return

    msg_ids = getattr(update, "messages", None) or []
    ids: list[int] = []
    for x in msg_ids:
        try:
            ids.append(int(x))
        except Exception:
            continue
    if not ids:
        return

    chat_id = -1000000000000 - int(channel_id)
    now = time.time()
    try:
        res = await db_handler.audio_collection.collection.update_many(
            {"source_chat_id": int(chat_id), "source_message_id": {"$in": ids}},
            {"$set": {"deleted": True, "deleted_at": now, "updated_at": now}},
        )
    except Exception as e:
        LOG.warning(
            f"deleted_messages_raw_handler mongo update failed: {e}", exc_info=True
        )
        return

    if bool(getattr(Config, "DEBUG", False)):
        try:
            LOG.debug(
                f"deleted_messages_raw_handler chat={chat_id} ids={len(ids)} matched={int(getattr(res, 'matched_count', 0) or 0)} "
                f"modified={int(getattr(res, 'modified_count', 0) or 0)}"
            )
        except Exception:
            pass
