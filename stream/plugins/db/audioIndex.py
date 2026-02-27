import time
import asyncio
import json
from os import path as ospath
from aiofiles.os import remove as aioremove

from pyrogram import filters
from pyrogram.types import Message

from stream import bot, get_primary_client_user_id
from stream.core.config_manager import Config
from stream.database.MongoDb import db_handler
from stream.helpers.dedup import metadata_fingerprint, sha256_prefix_file
from stream.helpers.logger import LOGGER
from stream.plugins.Analyzer.mediaHelper import (
    download_message_media,
    ensure_media_dir,
    extract_audio_metadata_normalized,
    infer_artist_title,
    run_mediainfo,
    sanitize_filename,
)
from stream.helpers.cover_search import find_best_cover_url, spotify_best_track

LOG = LOGGER(__name__)

_INDEX_TASKS: dict[str, asyncio.Task] = {}
_INDEX_TASKS_LOCK = asyncio.Lock()

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


async def _ensure_dump_message_id(*, source_chat_id: int, source_message_id: int) -> int | None:
    dump_channel_id = getattr(Config, "DUMP_CHANNEL_ID", None)
    try:
        dump_channel_id = int(dump_channel_id)
    except Exception:
        dump_channel_id = 0
    if not dump_channel_id:
        return None

    doc = await db_handler.audio_collection.find_one(
        {"source_chat_id": int(source_chat_id), "source_message_id": int(source_message_id)},
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
        {"source_chat_id": int(source_chat_id), "source_message_id": int(source_message_id)},
        {"$set": {"telegram.dump_message_id": dump_message_id, "updated_at": time.time()}},
        upsert=False,
    )
    return dump_message_id


async def _sync_file_ids_for_all_clients(*, source_chat_id: int, source_message_id: int) -> None:
    try:
        from stream import _multi_lock, multi_clients
    except Exception:
        return

    try:
        source_chat_id = int(source_chat_id)
        source_message_id = int(source_message_id)
    except Exception:
        return

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
                    msg = await client.get_messages(int(dump_channel_id), int(dump_message_id))
                    fid = _extract_message_file_id(msg)
                except Exception:
                    fid = None

        if not fid:
            continue

        key = str(int(uid))
        await db_handler.audio_collection.update_one(
            {"source_chat_id": int(source_chat_id), "source_message_id": int(source_message_id)},
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
    if not media and message.document and (message.document.mime_type or "").startswith("audio/"):
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


def _is_junk_title(title: str) -> bool:
    t = (title or "").strip().casefold()
    if not t:
        return True
    if t == "core media audio":
        return True
    if t == "mpeg audio":
        return True
    return False


def _best_title_artist_album(*, audio_doc: dict, media, inferred_title: str, inferred_artist: str) -> tuple[str, str, str]:
    title = ""
    for cand in (audio_doc.get("title"), getattr(media, "title", ""), inferred_title):
        c = (cand or "").strip()
        if c and not _is_junk_title(c):
            title = c
            break
    artist = (audio_doc.get("artist") or getattr(media, "performer", "") or inferred_artist or "").strip()
    album = (audio_doc.get("album") or "").strip()

    file_name = getattr(media, "file_name", "") or ""
    if not title:
        base = ospath.splitext(ospath.basename(file_name))[0].strip()
        title = base or str(getattr(media, "file_unique_id", "") or "").strip()

    if not title:
        title = str(getattr(media, "file_id", "") or "").strip()

    return title, artist, album


async def _upsert_minimal(message: Message, media) -> str:
    file_unique_id = getattr(media, "file_unique_id", None) or f"{message.chat.id}:{message.id}"
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
            "duration_sec": duration_sec,
        },
        "source_chat_id": message.chat.id,
        "source_message_id": message.id,
        "updated_at": time.time(),
    }
    if file_ids:
        payload["telegram"]["file_ids"] = file_ids

    await db_handler.audio_collection.update_one(
        {"_id": file_unique_id},
        {"$set": {k: v for k, v in payload.items() if v is not None}},
        upsert=True,
    )
    return file_unique_id


async def _enrich_audio_doc(message: Message, media):
    file_unique_id = getattr(media, "file_unique_id", None) or f"{message.chat.id}:{message.id}"
    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(
            f"[index] start file_unique_id={file_unique_id!r} chat={int(message.chat.id)} msg={int(message.id)} "
            f"file_name={str(getattr(media, 'file_name', '') or '')!r}"
        )

    base_dir = await ensure_media_dir()
    base_name = sanitize_filename(getattr(media, "file_name", "") or f"{message.id}")
    unique_key = getattr(media, "file_unique_id", None) or f"{message.chat.id}_{message.id}"
    unique_prefix = sanitize_filename(str(unique_key))[:48]
    nonce = str(time.time_ns())[-8:]
    filename = f"{unique_prefix}_{nonce}_{base_name}"
    file_path = ospath.join(base_dir, filename)

    file_size = None
    content_hash = None
    output = ""
    try:
        file_size = await download_message_media(message, file_path)
        try:
            content_hash = sha256_prefix_file(file_path)
        except Exception as e:
            LOG.warning(f"Hashing failed chat={message.chat.id} msg={message.id}: {e}")
        output = await run_mediainfo(file_path)
    except Exception as e:
        LOG.warning(
            f"Indexing failed chat={message.chat.id} msg={message.id}: {e}",
            exc_info=True,
        )
    finally:
        try:
            await aioremove(file_path)
        except Exception:
            pass

    if not output:
        if bool(getattr(Config, "DEBUG", False)):
            LOG.debug(f"[index] mediainfo empty file_unique_id={file_unique_id!r}")
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
    if album:
        audio_doc["album"] = album

    origin_cover_url = None
    cover_url = None
    cover_source = None

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

    try:
        origin_cover_url, cover_source = await find_best_cover_url(
            title=title,
            artist=performer,
            album=album,
            year=audio_doc.get("year"),
        )
    except Exception as e:
        LOG.warning(f"Cover lookup failed chat={message.chat.id} msg={message.id}: {e}")

    if origin_cover_url:
        cover_url = str(origin_cover_url).strip()
        if cover_url.startswith("`") and cover_url.endswith("`") and len(cover_url) >= 2:
            cover_url = cover_url[1:-1].strip()
        _dbg(f"[cover] found track={title!r} artist={performer!r} src={cover_source!r} url={origin_cover_url!r}")

    spotify = {"cover_url": cover_url, "cover_source": cover_source}
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
            ext = sp_track.get("external_urls") if isinstance(sp_track.get("external_urls"), dict) else {}
            sp_url = ext.get("spotify")
            if isinstance(sp_url, str) and sp_url.strip():
                s = sp_url.strip()
                spotify["url"] = s
    except Exception:
        pass
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
        LOG.debug(f"index file_ids set primary={primary_uid_key} file_id={file_id} doc={file_unique_id}")

    fingerprint = metadata_fingerprint(
        title=title,
        artist=performer,
        album=album,
        duration_sec=audio_doc.get("duration_sec"),
    )

    payload = {
        "telegram.file_id": file_id,
        "telegram.mime_type": getattr(media, "mime_type", None),
        "telegram.file_size": file_size,
        "audio": audio_doc,
        "spotify": spotify,
        "content_hash": content_hash,
        "fingerprint": fingerprint,
        "updated_at": time.time(),
    }
    if primary_uid_key and file_id:
        payload[f"telegram.file_ids.{primary_uid_key}"] = str(file_id)

    col = db_handler.audio_collection

    duplicate = None
    if not duplicate and content_hash:
        duplicate = await col.find_document({"content_hash": content_hash}, projection={"_id": 1})
    if not duplicate and fingerprint:
        duplicate = await col.find_document({"fingerprint": fingerprint}, projection={"_id": 1})

    if duplicate and duplicate.get("_id") != file_unique_id:
        ensure_source = {}
        existing = await col.read_document(
            duplicate["_id"],
            projection={"_id": 1, "source_chat_id": 1, "source_message_id": 1},
        )
        if not existing or existing.get("source_chat_id") is None or existing.get("source_message_id") is None:
            ensure_source = {"source_chat_id": message.chat.id, "source_message_id": message.id}

        await col.update_one(
            {"_id": duplicate["_id"]},
            {"$set": {**{k: v for k, v in payload.items() if v is not None}, **ensure_source}},
            upsert=True,
        )
        try:
            await col.delete_document(file_unique_id)
        except Exception:
            pass
        return

    ensure_source = {}
    existing = await col.read_document(
        file_unique_id,
        projection={"_id": 1, "source_chat_id": 1, "source_message_id": 1},
    )
    if not existing or existing.get("source_chat_id") is None or existing.get("source_message_id") is None:
        ensure_source = {"source_chat_id": message.chat.id, "source_message_id": message.id}

    await col.update_one(
        {"_id": file_unique_id},
        {
            "$set": {**{k: v for k, v in payload.items() if v is not None}, **ensure_source},
        },
        upsert=True,
    )
    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(f"[mongo] upserted audio doc _id={file_unique_id!r}")
    try:
        lyrics_enabled = bool(getattr(Config, "MUSIXMATCH", False)) or bool(getattr(Config, "LRCLIB", True))
        if lyrics_enabled:
            existing2 = None
            try:
                existing2 = await col.read_document(file_unique_id, projection={"lyrics": 1})
            except Exception:
                existing2 = None
            existing_lyrics = (existing2 or {}).get("lyrics")
            has_lyrics = isinstance(existing_lyrics, str) and existing_lyrics.strip()
            if not has_lyrics:
                from Api.services.lyrics_service import get_track_lyrics

                await get_track_lyrics(file_unique_id)
    except Exception:
        pass
    if bool(getattr(Config, "DEBUG", False)):
        LOG.debug(f"[index] done file_unique_id={file_unique_id!r}")


@bot.on_message(filters.chat(Config.CHANNEL_ID) & (filters.audio | filters.document))
async def channel_audio_filter(_, message: Message):
    try:
        key = f"{message.chat.id}:{message.id}"
        async with _INDEX_TASKS_LOCK:
            task = _INDEX_TASKS.get(key)
            if task and not task.done():
                return
            media = _pick_audio_media(message)
            if not media:
                return
            await _upsert_minimal(message, media)
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