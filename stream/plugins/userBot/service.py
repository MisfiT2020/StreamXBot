import asyncio

from pyrogram import Client
from pyrogram.errors import FloodWait, RPCError

from stream.core.config_manager import Config
from stream.database.MongoDb import db_handler


def _has_audio_media(message) -> bool:
    if getattr(message, "audio", None):
        return True
    doc = getattr(message, "document", None)
    if doc and (getattr(doc, "mime_type", "") or "").startswith("audio/"):
        return True
    return False


async def _get_source_channels() -> list[int | str]:
    ids: list[int | str] = []

    try:
        async for doc in db_handler.channels_collection.find_all({"enabled": True}, projection={"_id": 1}):
            v = doc.get("_id")
            if v is None:
                continue
            try:
                ids.append(int(v))
            except Exception:
                s = str(v).strip()
                if s:
                    ids.append(s)
    except Exception:
        pass

    cfg_ids = getattr(Config, "SOURCE_CHANNEL_IDS", None) or []
    for v in cfg_ids:
        if v is None:
            continue
        try:
            ids.append(int(v))
        except Exception:
            s = str(v).strip()
            if s:
                ids.append(s)

    out: list[int | str] = []
    seen: set[str] = set()
    for cid in ids:
        key = str(cid)
        if key not in seen:
            seen.add(key)
            out.append(cid)
    return out


async def _copy_with_backoff(userbot: Client, from_chat_id: int | str, message_id: int, log) -> None:
    db_channel_id = int(Config.CHANNEL_ID)
    while True:
        try:
            await userbot.copy_message(
                chat_id=db_channel_id,
                from_chat_id=from_chat_id,
                message_id=message_id,
            )
            return
        except FloodWait as e:
            delay = int(getattr(e, "value", None) or getattr(e, "x", None) or 0)
            if delay <= 0:
                delay = 5
            log.warning(f"userbot floodwait {delay}s (from={from_chat_id} msg={message_id})")
            await asyncio.sleep(delay)
        except RPCError as e:
            log.warning(f"userbot copy failed (from={from_chat_id} msg={message_id}): {e}")
            raise


async def _warm_up_dialogs(userbot: Client, log) -> None:
    try:
        async for _ in userbot.get_dialogs():
            pass
    except Exception as e:
        log.warning(f"userbot dialogs warmup failed: {e}")


async def _ensure_peer(userbot: Client, chat_id: int | str, log) -> bool:
    try:
        if isinstance(chat_id, str) and chat_id.startswith(("http://", "https://")):
            v = chat_id.split("://", 1)[1]
            v = v.split("/", 1)[1] if "/" in v else v
            chat_id = v
        await userbot.resolve_peer(chat_id)
        return True
    except Exception as e:
        log.warning(f"userbot cannot resolve peer {chat_id}: {e}")
        return False


async def ingest_channel_history(userbot: Client, source_chat_id: int | str, log) -> int:
    if not await _ensure_peer(userbot, source_chat_id, log):
        return 0

    state = db_handler.get_collection("userbot_state")
    state_id = f"history:{source_chat_id}"
    doc = await state.read_document(state_id) or {}
    last_id = int(doc.get("last_message_id") or 0)

    cooldown = int(getattr(Config, "USERBOT_COOLDOWN_SEC", 2) or 2)
    batch_size = int(getattr(Config, "USERBOT_BATCH_SIZE", 50) or 50)
    if batch_size <= 0:
        batch_size = 50
    if cooldown < 0:
        cooldown = 0

    offset_id = 0
    copied = 0

    while True:
        batch = []
        async for m in userbot.get_chat_history(source_chat_id, offset_id=offset_id, limit=batch_size):
            batch.append(m)
        if not batch:
            break

        stop_after = False
        if batch[-1].id <= last_id:
            stop_after = True

        for m in reversed(batch):
            if m.id <= last_id:
                continue
            if _has_audio_media(m):
                await _copy_with_backoff(userbot, from_chat_id=source_chat_id, message_id=m.id, log=log)
                copied += 1
                if cooldown:
                    await asyncio.sleep(cooldown)

            last_id = m.id
            await state.update_document(
                state_id,
                {"last_message_id": last_id, "updated_at": asyncio.get_event_loop().time()},
            )

        if stop_after:
            break

        offset_id = batch[-1].id

    return copied


async def userbot_ingest_forever(userbot: Client, log):
    poll = int(getattr(Config, "USERBOT_POLL_INTERVAL_SEC", 300) or 300)
    if poll < 10:
        poll = 10

    while True:
        source_ids = await _get_source_channels()
        if not source_ids:
            await asyncio.sleep(poll)
            continue

        for cid in source_ids:
            try:
                copied = await ingest_channel_history(userbot, cid, log)
                if copied:
                    log.info(f"userbot ingested {copied} items from {cid}")
            except Exception as e:
                log.warning(f"userbot ingest failed for {cid}: {e}")

        await asyncio.sleep(poll)


async def start_userbot(log):
    session_string = (getattr(Config, "SESSION_STRING", "") or "").strip()
    if not session_string:
        return None

    userbot = Client(
        name="StreamUser",
        api_id=int(Config.API_ID),
        api_hash=str(Config.API_HASH),
        session_string=session_string,
        in_memory=False,
        workers=4,
    )
    await userbot.start()
    me = await userbot.get_me()
    log.info(f"Userbot started: {me.first_name} (@{me.username}) [ID: {me.id}]")
    return userbot


async def start_userbot_service(log):
    userbot = await start_userbot(log)
    if not userbot:
        return None, None
    await _warm_up_dialogs(userbot, log)
    task = asyncio.create_task(userbot_ingest_forever(userbot, log))
    return userbot, task


async def stop_userbot_service(userbot: Client | None, task: asyncio.Task | None):
    if task:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        except Exception:
            pass

    if userbot:
        await userbot.stop()
