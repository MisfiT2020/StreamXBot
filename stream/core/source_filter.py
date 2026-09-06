import asyncio
import time
from typing import Optional

from stream.helpers.logger import LOGGER

LOG = LOGGER(__name__)


class FilterMode:
    GROUP_ONLY = 0
    ANYONE = 1
    HYBRID = 2

    @classmethod
    def parse(cls, val) -> int:
        if val is None:
            return cls.GROUP_ONLY
        s = str(val).strip().lower()
        if s in {"0", "group_only", "group", "channel_only"}:
            return cls.GROUP_ONLY
        if s in {"1", "anyone", "all", "any"}:
            return cls.ANYONE
        if s in {"2", "hybrid", "allowlist", "whitelist"}:
            return cls.HYBRID
        try:
            n = int(val)
            if n in (cls.GROUP_ONLY, cls.ANYONE, cls.HYBRID):
                return n
        except (ValueError, TypeError):
            pass
        return cls.GROUP_ONLY

    @classmethod
    def to_string(cls, mode: int) -> str:
        if mode == cls.ANYONE:
            return "anyone"
        if mode == cls.HYBRID:
            return "hybrid"
        return "group_only"


_allowed_cache: Optional[set[int]] = None
_banned_cache: Optional[set[int]] = None
_cache_time: float = 0.0
_cache_lock = asyncio.Lock()
_CACHE_TTL = 30.0


def _get_db():
    from stream.database.MongoDb import db_handler

    return db_handler


def _get_config():
    from stream.core.config_manager import Config

    return Config


def get_filter_mode() -> int:
    cfg = _get_config()
    raw = getattr(cfg, "FILTER_MODE", FilterMode.GROUP_ONLY)
    return FilterMode.parse(raw)


def invalidate_cache():
    global _allowed_cache, _banned_cache, _cache_time
    _allowed_cache = None
    _banned_cache = None
    _cache_time = 0.0


async def _ensure_cache():
    global _allowed_cache, _banned_cache, _cache_time
    now = time.time()
    if (
        _allowed_cache is not None
        and _banned_cache is not None
        and (now - _cache_time) < _CACHE_TTL
    ):
        return

    async with _cache_lock:
        if (
            _allowed_cache is not None
            and _banned_cache is not None
            and (now - _cache_time) < _CACHE_TTL
        ):
            return

        db = _get_db()
        allowed = set()
        banned = set()

        try:
            async for doc in db.allowed_sources.collection.find({}, {"source_id": 1}):
                sid = doc.get("source_id")
                if sid is not None:
                    try:
                        allowed.add(int(sid))
                    except (ValueError, TypeError):
                        pass
        except Exception as e:
            LOG.warning(f"[source_filter] Failed to load allowed sources: {e}")

        try:
            async for doc in db.banned_sources.collection.find({}, {"source_id": 1}):
                sid = doc.get("source_id")
                if sid is not None:
                    try:
                        banned.add(int(sid))
                    except (ValueError, TypeError):
                        pass
        except Exception as e:
            LOG.warning(f"[source_filter] Failed to load banned sources: {e}")

        # Ensure any banned sources are purged from allowed sources
        conflict = allowed & banned
        if conflict:
            try:
                await db.allowed_sources.collection.delete_many(
                    {"source_id": {"$in": list(conflict)}}
                )
            except Exception as e:
                LOG.warning(f"[source_filter] Failed to purge conflicting allowed sources: {e}")
            allowed = allowed - conflict

        _allowed_cache = allowed
        _banned_cache = banned
        _cache_time = time.time()


def _coerce_int(val) -> Optional[int]:
    if val is None:
        return None
    try:
        return int(val)
    except (ValueError, TypeError):
        return None


async def is_source_banned(source_id: int | str | None) -> bool:
    sid = _coerce_int(source_id)
    if sid is None or sid == 0:
        return False
    await _ensure_cache()
    return sid in (_banned_cache or set())


async def is_source_allowed(source_id: int | str | None) -> bool:
    sid = _coerce_int(source_id)
    if sid is None or sid == 0:
        return False

    await _ensure_cache()
    # Ban strictly takes precedence over allowlist
    if sid in (_banned_cache or set()):
        return False

    cfg = _get_config()
    owner_id = _coerce_int(getattr(cfg, "OWNER_ID", 0))
    if owner_id and sid == owner_id:
        return True
    sudos = getattr(cfg, "SUDO_USERS", []) or []
    if isinstance(sudos, (int, str)):
        sudos = [sudos]
    for sudo in sudos:
        if _coerce_int(sudo) == sid:
            return True
    channel_id = _coerce_int(getattr(cfg, "CHANNEL_ID", 0))
    if channel_id and sid == channel_id:
        return True

    return sid in (_allowed_cache or set())


async def is_message_allowed(message) -> tuple[bool, str]:
    """Validate whether an incoming Telegram message is allowed for audio ingestion.

    Checks banned status first (strict ban), then evaluates according to FILTER_MODE:
    - Mode 0 (GROUP_ONLY): only Config.CHANNEL_ID is accepted.
    - Mode 1 (ANYONE): all sources accepted as long as not banned.
    - Mode 2 (HYBRID): only sources in allowed_sources (or CHANNEL_ID) are accepted.
    """
    chat_id = _coerce_int(getattr(getattr(message, "chat", None), "id", None))
    from_user_id = _coerce_int(getattr(getattr(message, "from_user", None), "id", None))
    sender_chat_id = _coerce_int(
        getattr(getattr(message, "sender_chat", None), "id", None)
    )
    fwd_chat_id = _coerce_int(
        getattr(getattr(message, "forward_from_chat", None), "id", None)
    )
    fwd_user_id = _coerce_int(
        getattr(getattr(message, "forward_from", None), "id", None)
    )

    candidate_ids = [
        cid
        for cid in [chat_id, from_user_id, sender_chat_id, fwd_chat_id, fwd_user_id]
        if cid is not None and cid != 0
    ]

    await _ensure_cache()
    banned_set = _banned_cache or set()

    # 1. Ban check: if any associated entity is banned, reject immediately
    for cid in candidate_ids:
        if cid in banned_set:
            return False, f"source {cid} is banned"

    mode = get_filter_mode()
    cfg = _get_config()
    main_channel_id = _coerce_int(getattr(cfg, "CHANNEL_ID", 0))
    owner_id = _coerce_int(getattr(cfg, "OWNER_ID", 0))

    # 2. Mode 0: Group/Channel Only
    if mode == FilterMode.GROUP_ONLY:
        if main_channel_id and (
            chat_id == main_channel_id or sender_chat_id == main_channel_id
        ):
            return True, "allowed (group_only)"
        if owner_id and (from_user_id == owner_id or chat_id == owner_id):
            return True, "allowed (owner)"
        return (
            False,
            f"chat {chat_id} is not configured CHANNEL_ID ({main_channel_id})",
        )

    # 3. Mode 1: Anyone
    if mode == FilterMode.ANYONE:
        return True, "allowed (anyone)"

    # 4. Mode 2: Hybrid (Allowlist)
    if mode == FilterMode.HYBRID:
        allowed_set = _allowed_cache or set()

        # Check if the main channel is the source
        if main_channel_id and (
            chat_id == main_channel_id or sender_chat_id == main_channel_id
        ):
            return True, "allowed (main channel)"

        # Check if owner or sudo
        if owner_id and (from_user_id == owner_id or chat_id == owner_id):
            return True, "allowed (owner)"

        # Check if chat, from_user, or sender_chat is explicitly in allowed contributors
        for cid in candidate_ids:
            if cid in allowed_set:
                return True, f"allowed contributor ({cid})"

        return False, "source/contributor is not in allowed contributors collection"

    return False, "unknown filter mode"


async def add_allowed_source(
    source_id: int | str,
    source_type: str = "channel",
    name: str = "",
    added_by: int = 0,
) -> dict:
    sid = _coerce_int(source_id)
    if sid is None:
        raise ValueError(f"Invalid source_id: {source_id}")

    db = _get_db()
    doc = {
        "source_id": sid,
        "source_type": str(source_type or "channel").lower(),
        "name": str(name or "").strip(),
        "added_at": time.time(),
        "added_by": int(added_by or 0),
    }

    await db.allowed_sources.collection.update_one(
        {"source_id": sid},
        {"$set": doc},
        upsert=True,
    )
    # Remove from banned sources if present
    await db.banned_sources.collection.delete_one({"source_id": sid})
    invalidate_cache()
    return doc


async def remove_allowed_source(source_id: int | str) -> bool:
    sid = _coerce_int(source_id)
    if sid is None:
        return False
    db = _get_db()
    res = await db.allowed_sources.collection.delete_one({"source_id": sid})
    invalidate_cache()
    return res.deleted_count > 0


async def get_allowed_sources() -> list[dict]:
    db = _get_db()
    await _ensure_cache()
    banned_ids = list(_banned_cache or set())
    query = {"source_id": {"$nin": banned_ids}} if banned_ids else {}
    results = []
    async for doc in db.allowed_sources.collection.find(query).sort("added_at", -1):
        doc["_id"] = str(doc.get("_id", ""))
        results.append(doc)
    return results


async def add_banned_source(
    source_id: int | str,
    source_type: str = "user",
    reason: str = "",
    banned_by: int = 0,
    name: str = "",
) -> dict:
    sid = _coerce_int(source_id)
    if sid is None:
        raise ValueError(f"Invalid source_id: {source_id}")

    db = _get_db()
    doc = {
        "source_id": sid,
        "source_type": str(source_type or "user").lower(),
        "name": str(name or "").strip(),
        "reason": str(reason or "").strip(),
        "banned_at": time.time(),
        "banned_by": int(banned_by or 0),
    }

    await db.banned_sources.collection.update_one(
        {"source_id": sid},
        {"$set": doc},
        upsert=True,
    )
    # Remove from allowed sources if present
    await db.allowed_sources.collection.delete_one({"source_id": sid})
    invalidate_cache()
    return doc


async def remove_banned_source(source_id: int | str) -> bool:
    sid = _coerce_int(source_id)
    if sid is None:
        return False
    db = _get_db()
    res = await db.banned_sources.collection.delete_one({"source_id": sid})
    invalidate_cache()
    return res.deleted_count > 0


async def get_banned_sources() -> list[dict]:
    db = _get_db()
    results = []
    async for doc in db.banned_sources.collection.find().sort("banned_at", -1):
        doc["_id"] = str(doc.get("_id", ""))
        results.append(doc)
    return results


async def seed_from_config(collaborator_ids: list | int | str | None):
    """Seed collaborator IDs from config.py into allowed_sources collection."""
    if not collaborator_ids:
        return

    raw_list = (
        collaborator_ids
        if isinstance(collaborator_ids, (list, tuple, set))
        else [collaborator_ids]
    )

    db = _get_db()
    for item in raw_list:
        sid = _coerce_int(item)
        if sid is None or sid == 0:
            continue
        exists = await db.allowed_sources.collection.find_one({"source_id": sid})
        if not exists:
            stype = "user" if sid > 0 else "channel"
            await add_allowed_source(
                source_id=sid,
                source_type=stype,
                name=f"Config Collaborator {sid}",
                added_by=0,
            )
            LOG.info(f"[source_filter] Seeded allowed source {sid} ({stype}) from config")


# Convenient aliases
ban_source = add_banned_source
unban_source = remove_banned_source
get_all_allowed_sources = get_allowed_sources
get_all_banned_sources = get_banned_sources
