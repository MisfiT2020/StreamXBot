import re
import random
import datetime
import hashlib
import time
from typing import Any, Optional

from pymongo import UpdateOne

from Api.deps.db import get_audio_tracks_collection
from Api.schemas.browse import BrowseItem, BrowseResponse
from stream.database.MongoDb import db_handler

def _as_str_id(value: Any) -> str:
    if value is None:
        return ""
    return str(value)

def _clean_url(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    s = value.strip()
    if len(s) >= 2 and s[0] == "`" and s[-1] == "`":
        s = s[1:-1].strip()
    return s


def _normalize_spotify(doc: dict) -> None:
    spotify = doc.get("spotify")
    if not isinstance(spotify, dict):
        return

    spotify["url"] = _clean_url(spotify.get("url") or spotify.get("spotify_url"))
    spotify["cover_url"] = _clean_url(spotify.get("cover_url"))
    spotify.pop("spotify_url", None)
    spotify.pop("links", None)


def _browse_item_from_doc(doc: dict) -> BrowseItem:
    audio = doc.get("audio") or {}
    spotify = doc.get("spotify") or {}
    if isinstance(spotify, dict):
        spotify["url"] = _clean_url(spotify.get("url") or spotify.get("spotify_url"))
        spotify["cover_url"] = _clean_url(spotify.get("cover_url"))

    t = audio.get("type")
    if isinstance(t, str) and t:
        t = t.upper()

    return BrowseItem(
        _id=_as_str_id(doc.get("_id")),
        source_chat_id=doc.get("source_chat_id"),
        source_message_id=doc.get("source_message_id"),
        title=audio.get("title"),
        artist=audio.get("artist"),
        album=audio.get("album"),
        duration_sec=audio.get("duration_sec"),
        type=t,
        sampling_rate_hz=audio.get("sampling_rate_hz"),
        spotify_url=_clean_url(spotify.get("url") or spotify.get("spotify_url")),
        cover_url=_clean_url(spotify.get("cover_url")),
        updated_at=doc.get("updated_at"),
    )


def _search_pattern(q: str) -> str:
    s = (q or "").strip()
    if not s:
        return ""
    tokens = [t for t in s.split() if t.strip()]
    if not tokens:
        return ""
    return ".*".join(re.escape(t) for t in tokens[:8])


async def browse_tracks(channel_id: Optional[int], page: int, per_page: int) -> BrowseResponse:
    per_page = int(per_page)
    if per_page <= 0:
        per_page = 20
    page = int(page)
    if page < 1:
        page = 1
    skip = (page - 1) * per_page

    col = get_audio_tracks_collection()
    query: dict[str, Any] = {}
    if channel_id is not None:
        query["source_chat_id"] = int(channel_id)

    sort = [("source_message_id", -1)] if channel_id is not None else [("updated_at", -1)]
    projection = {
        "_id": 1,
        "source_chat_id": 1,
        "source_message_id": 1,
        "audio": 1,
        "spotify": 1,
        "updated_at": 1,
    }

    total = await col.count_documents(query)
    cursor = col.find(query, projection).sort(sort).skip(skip).limit(per_page)

    items: list[BrowseItem] = []
    async for doc in cursor:
        items.append(_browse_item_from_doc(doc))

    return BrowseResponse(page=page, per_page=per_page, total=total, items=items)


async def search_tracks(q: str, *, channel_id: Optional[int], page: int, per_page: int) -> BrowseResponse:
    per_page = int(per_page)
    if per_page <= 0:
        per_page = 20
    if per_page > 50:
        per_page = 50
    page = int(page)
    if page < 1:
        page = 1
    skip = (page - 1) * per_page

    pattern = _search_pattern(q)
    if not pattern:
        return BrowseResponse(page=page, per_page=per_page, total=0, items=[])

    col = get_audio_tracks_collection()
    query: dict[str, Any] = {
        "$or": [
            {"audio.title": {"$regex": pattern, "$options": "i"}},
            {"audio.artist": {"$regex": pattern, "$options": "i"}},
            {"audio.performer": {"$regex": pattern, "$options": "i"}},
            {"audio.album": {"$regex": pattern, "$options": "i"}},
        ]
    }
    if channel_id is not None:
        query["source_chat_id"] = int(channel_id)

    projection = {
        "_id": 1,
        "source_chat_id": 1,
        "source_message_id": 1,
        "audio": 1,
        "spotify": 1,
        "updated_at": 1,
    }

    total = await col.count_documents(query)
    cursor = col.find(query, projection).sort([("updated_at", -1)]).skip(skip).limit(per_page)

    items: list[BrowseItem] = []
    async for doc in cursor:
        items.append(_browse_item_from_doc(doc))

    return BrowseResponse(page=page, per_page=per_page, total=total, items=items)

async def random_tracks(*, limit: int, seed: int | None = None, channel_id: Optional[int] = None) -> BrowseResponse:
    limit = int(limit)
    if limit <= 0:
        limit = 50
    if limit > 200:
        limit = 200

    query: dict[str, Any] = {}
    if channel_id is not None:
        query["source_chat_id"] = int(channel_id)

    col = get_audio_tracks_collection()
    total = await col.count_documents(query)
    if total <= 0:
        return BrowseResponse(page=1, per_page=limit, total=0, items=[])

    rng: random.Random
    if seed is None:
        rng = random.SystemRandom()
    else:
        rng = random.Random(int(seed))

    projection = {
        "_id": 1,
        "source_chat_id": 1,
        "source_message_id": 1,
        "audio": 1,
        "spotify": 1,
        "updated_at": 1,
    }

    items: list[BrowseItem] = []
    seen: set[str] = set()
    attempts = 0

    while len(items) < limit and attempts < 25:
        attempts += 1
        batch_limit = min(200, max(1, limit - len(items)))
        max_skip = max(0, int(total) - int(batch_limit))
        skip = int(rng.randint(0, max_skip)) if max_skip > 0 else 0

        cursor = (
            col.find(query, projection)
            .sort([("_id", 1)])
            .skip(skip)
            .limit(batch_limit)
        )
        async for doc in cursor:
            tid = _as_str_id(doc.get("_id"))
            if not tid or tid in seen:
                continue
            seen.add(tid)
            items.append(_browse_item_from_doc(doc))
            if len(items) >= limit:
                break

    rng.shuffle(items)
    return BrowseResponse(page=1, per_page=limit, total=total, items=items[:limit])

async def get_browse_items_by_ids(track_ids: list[str]) -> list[BrowseItem]:
    ids = [str(x) for x in (track_ids or []) if str(x)]
    if not ids:
        return []

    col = get_audio_tracks_collection()
    projection = {
        "_id": 1,
        "source_chat_id": 1,
        "source_message_id": 1,
        "audio": 1,
        "spotify": 1,
        "updated_at": 1,
    }
    cursor = col.find({"_id": {"$in": ids}}, projection)
    docs: list[dict] = []
    async for doc in cursor:
        docs.append(doc)

    by_id = {_as_str_id(d.get("_id")): d for d in docs if d.get("_id")}
    items: list[BrowseItem] = []
    for tid in ids:
        d = by_id.get(tid)
        if not d:
            continue
        items.append(_browse_item_from_doc(d))
    return items

def _daily_playlist_seed(*, key: str, date: str, channel_id: int | None) -> int:
    scope = str(int(channel_id)) if channel_id is not None else ""
    seed_src = f"{key}|{date}|{scope}".encode("utf-8", errors="ignore")
    return int(hashlib.sha1(seed_src).hexdigest()[:8], 16)

def _canon_daily_playlist_key(key: str) -> str:
    k = (key or "").strip().lower()
    if k in {"random", "mix", "daily", "daily-playlist"}:
        return "random"
    if k in {"top", "top-played", "top-playlist"}:
        return "top-played"
    if k in {"trending", "trending-today"}:
        return "trending"
    if k in {"late-night", "late-night-mix", "night"}:
        return "late-night"
    if k in {"rising", "rising-tracks"}:
        return "rising"
    if k in {"surprise", "surprise-me"}:
        return "surprise"
    return k


async def generate_daily_playlist(*, key: str, date: str, channel_id: int | None, limit: int) -> list[str]:
    key = _canon_daily_playlist_key(key)
    if not key:
        return []

    limit = int(limit)
    if limit <= 0:
        limit = 75
    if limit > 75:
        limit = 75

    scope = str(int(channel_id)) if channel_id is not None else ""
    doc_id = f"{key}:{date}:{scope}"

    col = db_handler.get_collection("daily_playlists").collection
    doc = await col.find_one({"_id": doc_id}, {"_id": 0, "track_ids": 1})
    if isinstance(doc, dict) and isinstance(doc.get("track_ids"), list) and doc["track_ids"]:
        return [str(x) for x in doc["track_ids"] if str(x)][:limit]

    seed = _daily_playlist_seed(key=key, date=date, channel_id=channel_id)

    if key in {"random", "mix"}:
        res = await random_tracks(limit=limit, seed=seed, channel_id=channel_id)
        track_ids = [str(it.id) for it in (res.items or []) if getattr(it, "id", None)]
        await col.update_one(
            {"_id": doc_id},
            {
                "$setOnInsert": {
                    "key": key,
                    "date": date,
                    "channel_id": int(channel_id) if channel_id is not None else None,
                    "track_ids": track_ids,
                    "generated_at": float(time.time()),
                }
            },
            upsert=True,
        )
        return track_ids

    if key in {"top", "top-played"}:
        gcol = db_handler.globalplayback_collection.collection
        cursor = gcol.find({}, {"_id": 1}).sort([("plays", -1)]).limit(500)
        candidate_ids: list[str] = []
        async for row in cursor:
            tid = str(row.get("_id") or "").strip()
            if tid:
                candidate_ids.append(tid)
        if not candidate_ids:
            return []

        rng = random.Random(int(seed))
        rng.shuffle(candidate_ids)
        picked = candidate_ids[:limit]
        await col.update_one(
            {"_id": doc_id},
            {
                "$setOnInsert": {
                    "key": key,
                    "date": date,
                    "channel_id": int(channel_id) if channel_id is not None else None,
                    "track_ids": picked,
                    "generated_at": float(time.time()),
                }
            },
            upsert=True,
        )
        return picked

    if key in {"trending"}:
        now = float(time.time())
        since = now - 24 * 3600
        ucol = db_handler.userplayback_collection.collection
        cur = await ucol.aggregate(
            [
                {"$match": {"played_at": {"$gte": since}}},
                {"$group": {"_id": "$track_id", "plays": {"$sum": 1}}},
                {"$sort": {"plays": -1}},
                {"$limit": 500},
            ]
        )
        rows = await cur.to_list(length=500)
        candidate_ids = [str(r.get("_id") or "").strip() for r in (rows or []) if str(r.get("_id") or "").strip()]
        if not candidate_ids:
            return []
        rng = random.Random(int(seed))
        rng.shuffle(candidate_ids)
        picked = candidate_ids[:limit]
        await col.update_one(
            {"_id": doc_id},
            {
                "$setOnInsert": {
                    "key": key,
                    "date": date,
                    "channel_id": int(channel_id) if channel_id is not None else None,
                    "track_ids": picked,
                    "generated_at": float(time.time()),
                }
            },
            upsert=True,
        )
        return picked

    if key in {"rediscover"}:
        now = float(time.time())
        cutoff = now - 30 * 24 * 3600
        gcol = db_handler.globalplayback_collection.collection
        cursor = gcol.find({"last_played_at": {"$lt": cutoff}}, {"_id": 1}).sort([("plays", -1)]).limit(500)
        candidate_ids: list[str] = []
        async for row in cursor:
            tid = str(row.get("_id") or "").strip()
            if tid:
                candidate_ids.append(tid)
        if not candidate_ids:
            return []
        rng = random.Random(int(seed))
        rng.shuffle(candidate_ids)
        picked = candidate_ids[:limit]
        await col.update_one(
            {"_id": doc_id},
            {
                "$setOnInsert": {
                    "key": key,
                    "date": date,
                    "channel_id": int(channel_id) if channel_id is not None else None,
                    "track_ids": picked,
                    "generated_at": float(time.time()),
                }
            },
            upsert=True,
        )
        return picked

    if key in {"late-night"}:
        now = float(time.time())
        since = now - 30 * 24 * 3600
        ucol = db_handler.userplayback_collection.collection
        cur = await ucol.aggregate(
            [
                {"$match": {"played_at": {"$gte": since}}},
                {"$addFields": {"_dt": {"$toDate": {"$multiply": ["$played_at", 1000]}}}},
                {"$addFields": {"_hour": {"$hour": "$_dt"}}},
                {"$match": {"$or": [{"_hour": {"$gte": 22}}, {"_hour": {"$lte": 3}}]}},
                {"$group": {"_id": "$track_id", "plays": {"$sum": 1}}},
                {"$sort": {"plays": -1}},
                {"$limit": 500},
            ]
        )
        rows = await cur.to_list(length=500)
        candidate_ids = [str(r.get("_id") or "").strip() for r in (rows or []) if str(r.get("_id") or "").strip()]
        if not candidate_ids:
            return []
        rng = random.Random(int(seed))
        rng.shuffle(candidate_ids)
        picked = candidate_ids[:limit]
        await col.update_one(
            {"_id": doc_id},
            {
                "$setOnInsert": {
                    "key": key,
                    "date": date,
                    "channel_id": int(channel_id) if channel_id is not None else None,
                    "track_ids": picked,
                    "generated_at": float(time.time()),
                }
            },
            upsert=True,
        )
        return picked

    if key in {"rising"}:
        now = float(time.time())
        since = now - 3 * 24 * 3600
        ucol = db_handler.userplayback_collection.collection
        cur = await ucol.aggregate(
            [
                {"$match": {"played_at": {"$gte": since}}},
                {"$group": {"_id": "$track_id", "plays3": {"$sum": 1}}},
                {"$sort": {"plays3": -1}},
                {"$limit": 2000},
            ]
        )
        rows = await cur.to_list(length=2000)
        if not rows:
            return []
        plays3_by_id: dict[str, int] = {}
        ids: list[str] = []
        for r in rows:
            tid = str(r.get("_id") or "").strip()
            if not tid:
                continue
            p3 = int(r.get("plays3") or 0)
            if p3 <= 0:
                continue
            plays3_by_id[tid] = p3
            ids.append(tid)
        if not ids:
            return []
        gcol = db_handler.globalplayback_collection.collection
        cursor = gcol.find({"_id": {"$in": ids}}, {"_id": 1, "plays": 1})
        plays_all: dict[str, int] = {}
        async for row in cursor:
            tid = str(row.get("_id") or "").strip()
            if not tid:
                continue
            plays_all[tid] = int(row.get("plays") or 0)
        scored: list[tuple[float, int, str]] = []
        for tid in ids:
            p3 = int(plays3_by_id.get(tid) or 0)
            pall = int(plays_all.get(tid) or 0)
            denom = float(pall if pall > 0 else 1)
            score = float(p3) / denom
            scored.append((score, p3, tid))
        scored.sort(key=lambda x: (x[0], x[1]), reverse=True)
        candidate_ids = [tid for _, _, tid in scored[:500]]
        if not candidate_ids:
            return []
        rng = random.Random(int(seed))
        rng.shuffle(candidate_ids)
        picked = candidate_ids[:limit]
        await col.update_one(
            {"_id": doc_id},
            {
                "$setOnInsert": {
                    "key": key,
                    "date": date,
                    "channel_id": int(channel_id) if channel_id is not None else None,
                    "track_ids": picked,
                    "generated_at": float(time.time()),
                }
            },
            upsert=True,
        )
        return picked

    if key in {"surprise"}:
        gcol = db_handler.globalplayback_collection.collection
        cursor = gcol.find({}, {"_id": 1, "plays": 1}).sort([("plays", -1)]).limit(2000)
        candidates: list[tuple[str, int]] = []
        async for row in cursor:
            tid = str(row.get("_id") or "").strip()
            if not tid:
                continue
            candidates.append((tid, int(row.get("plays") or 0)))
        if not candidates:
            return []
        rng = random.Random(int(seed))
        pool = candidates[:]
        picked: list[str] = []
        while pool and len(picked) < limit:
            weights = [float((p + 1)) for _, p in pool]
            total = float(sum(weights))
            if total <= 0:
                idx = rng.randrange(0, len(pool))
            else:
                r = rng.random() * total
                acc = 0.0
                idx = 0
                for i, w in enumerate(weights):
                    acc += w
                    if acc >= r:
                        idx = i
                        break
            tid, _ = pool.pop(idx)
            picked.append(tid)
        await col.update_one(
            {"_id": doc_id},
            {
                "$setOnInsert": {
                    "key": key,
                    "date": date,
                    "channel_id": int(channel_id) if channel_id is not None else None,
                    "track_ids": picked,
                    "generated_at": float(time.time()),
                }
            },
            upsert=True,
        )
        return picked

    return []


async def get_daily_playlist(*, key: str, date: str | None = None, channel_id: int | None, limit: int) -> BrowseResponse:
    key = _canon_daily_playlist_key(key)
    if not date:
        date = datetime.datetime.utcnow().date().isoformat()

    track_ids = await generate_daily_playlist(key=key, date=str(date), channel_id=channel_id, limit=int(limit))
    if not track_ids:
        return BrowseResponse(page=1, per_page=int(limit), total=0, items=[])
    items = await get_browse_items_by_ids(track_ids[: int(limit)])
    from Api.services.genColor import ensure_daily_playlist_cover

    cover = await ensure_daily_playlist_cover(key=key, date=str(date), channel_id=channel_id)
    cover_url = cover.get("url") if isinstance(cover, dict) else None
    return BrowseResponse(page=1, per_page=int(limit), total=len(items), items=items, cover_url=cover_url)

async def refresh_daily_playlist_cache(
    *,
    key: str,
    date: str,
    channel_id: int | None,
    limit: int = 75,
    refresh_cover: bool = True,
) -> dict[str, object]:
    k = _canon_daily_playlist_key(key)
    if k not in {"random", "top-played", "trending", "rediscover", "late-night", "rising", "surprise"}:
        raise ValueError("unknown daily playlist key")

    d = (date or "").strip()
    if not d:
        raise ValueError("date is required")

    scope = str(int(channel_id)) if channel_id is not None else ""
    doc_id = f"{k}:{d}:{scope}"

    col = db_handler.get_collection("daily_playlists").collection
    await col.delete_one({"_id": doc_id})

    track_ids = await generate_daily_playlist(key=k, date=d, channel_id=channel_id, limit=int(limit))

    cover_url = None
    if refresh_cover:
        from Api.services.genColor import ensure_daily_playlist_cover

        cover = await ensure_daily_playlist_cover(key=k, date=d, channel_id=channel_id, force=True)
        cover_url = cover.get("url") if isinstance(cover, dict) else None

    return {
        "key": k,
        "date": d,
        "channel_id": int(channel_id) if channel_id is not None else None,
        "track_count": len(track_ids),
        "cover_url": cover_url,
    }


async def refresh_daily_playlists_bulk(
    *,
    date: str,
    keys: list[str] | None = None,
    channel_ids: list[int | None] | None = None,
    limit: int = 75,
) -> dict[str, object]:
    if not keys:
        keys = ["random", "top-played", "trending", "rediscover", "late-night", "rising", "surprise"]
    canon_keys = []
    for k in keys:
        ck = _canon_daily_playlist_key(k)
        if ck in {"random", "top-played", "trending", "rediscover", "late-night", "rising", "surprise"} and ck not in canon_keys:
            canon_keys.append(ck)
    if not canon_keys:
        raise ValueError("no valid keys")

    if channel_ids is None:
        channel_ids = [None]

    results: list[dict[str, object]] = []
    ok = 0
    failed = 0
    for cid in channel_ids:
        for k in canon_keys:
            try:
                res = await refresh_daily_playlist_cache(key=k, date=date, channel_id=cid, limit=int(limit), refresh_cover=True)
                results.append(res)
                ok += 1
            except Exception as e:
                results.append(
                    {
                        "key": k,
                        "date": date,
                        "channel_id": int(cid) if cid is not None else None,
                        "error": str(e) or "failed",
                    }
                )
                failed += 1
    return {"ok": ok, "failed": failed, "results": results}


async def refresh_user_top_played_cache(*, user_id: int, limit: int = 500, refresh_cover: bool = True, force_cover: bool = True) -> dict[str, object]:
    uid = int(user_id)
    if uid <= 0:
        raise ValueError("user_id must be positive")
    limit = int(limit)
    if limit <= 0:
        limit = 100
    if limit > 1000:
        limit = 1000

    col = db_handler.userplayback_collection.collection
    match = {"user_id": int(uid)}
    rows_cur = await col.aggregate(
        [
            {"$match": match},
            {"$group": {"_id": "$track_id", "plays": {"$sum": 1}, "last_played_at": {"$max": "$played_at"}}},
            {"$sort": {"plays": -1, "last_played_at": -1}},
            {"$limit": int(limit)},
        ]
    )
    rows = await rows_cur.to_list(length=limit)

    track_ids: list[str] = []
    for r in rows or []:
        tid = (r.get("_id") or "").strip() if isinstance(r.get("_id"), str) else str(r.get("_id") or "").strip()
        if tid:
            track_ids.append(tid)

    cache_col = db_handler.get_collection("user_top_played_cache").collection
    now = float(time.time())
    cover_id: str | None = None
    cover_url: str | None = None
    if refresh_cover:
        from Api.services.genColor import ensure_user_top_played_cover

        cover = await ensure_user_top_played_cover(user_id=int(uid), force=bool(force_cover))
        cover_id = cover.get("cover_id") if isinstance(cover, dict) else None
        cover_url = cover.get("url") if isinstance(cover, dict) else None
    await cache_col.update_one(
        {"_id": str(uid)},
        {"$set": {"user_id": int(uid), "track_ids": track_ids, "generated_at": now, "cover_id": cover_id, "cover_url": cover_url}},
        upsert=True,
    )

    return {"user_id": int(uid), "track_count": len(track_ids), "generated_at": now, "cover_id": cover_id, "cover_url": cover_url}


async def refresh_user_top_played_cache_bulk(
    *,
    user_ids: list[int] | None = None,
    limit_users: int | None = 200,
    limit_tracks: int = 500,
    refresh_cover: bool = True,
    force_cover: bool = True,
) -> dict[str, object]:
    ids: list[int] = []
    if user_ids:
        for v in user_ids:
            try:
                n = int(v)
            except Exception:
                continue
            if n > 0 and n not in ids:
                ids.append(n)
    else:
        col = db_handler.userplayback_collection.collection
        if limit_users is None:
            cur = await col.aggregate([{"$group": {"_id": "$user_id"}}])
            async for r in cur:
                try:
                    n = int(r.get("_id"))
                except Exception:
                    continue
                if n > 0 and n not in ids:
                    ids.append(n)
        else:
            limit_users = int(limit_users)
            if limit_users <= 0:
                limit_users = 50
            if limit_users > 2000:
                limit_users = 2000
            cur = await col.aggregate([{"$group": {"_id": "$user_id"}}, {"$limit": int(limit_users)}])
            rows = await cur.to_list(length=limit_users)
            for r in rows or []:
                try:
                    n = int(r.get("_id"))
                except Exception:
                    continue
                if n > 0 and n not in ids:
                    ids.append(n)

    ok = 0
    failed = 0
    results: list[dict[str, object]] = []
    for uid in ids:
        try:
            res = await refresh_user_top_played_cache(
                user_id=int(uid),
                limit=int(limit_tracks),
                refresh_cover=bool(refresh_cover),
                force_cover=bool(force_cover),
            )
            results.append(res)
            ok += 1
        except Exception as e:
            results.append({"user_id": int(uid), "error": str(e) or "failed"})
            failed += 1

    return {"ok": ok, "failed": failed, "results": results}


async def rebuild_global_playback_from_userplayback(*, batch_size: int = 1000) -> dict[str, object]:
    batch_size = int(batch_size)
    if batch_size <= 0:
        batch_size = 1000
    if batch_size > 5000:
        batch_size = 5000

    user_col = db_handler.userplayback_collection.collection
    global_col = db_handler.globalplayback_collection.collection

    await global_col.delete_many({})

    pipeline = [
        {"$group": {"_id": "$track_id", "plays": {"$sum": 1}, "last_played_at": {"$max": "$played_at"}}},
        {"$sort": {"plays": -1}},
    ]
    cur = await user_col.aggregate(pipeline)

    now = float(time.time())
    pending: list[tuple[str, int, float]] = []
    processed = 0

    async def _flush() -> None:
        nonlocal pending
        if not pending:
            return
        ops = [
            UpdateOne(
                {"_id": tid},
                {"$set": {"plays": plays, "last_played_at": last_played_at, "updated_at": now}},
                upsert=True,
            )
            for (tid, plays, last_played_at) in pending
        ]
        try:
            await global_col.bulk_write(ops, ordered=False)
        except Exception:
            for tid, plays, last_played_at in pending:
                try:
                    await global_col.update_one(
                        {"_id": tid},
                        {"$set": {"plays": plays, "last_played_at": last_played_at, "updated_at": now}},
                        upsert=True,
                    )
                except Exception:
                    pass
        pending = []

    async for row in cur:
        tid = str(row.get("_id") or "").strip()
        if not tid:
            continue
        plays = int(row.get("plays") or 0)
        last_played_at = float(row.get("last_played_at") or 0.0)
        pending.append((tid, plays, last_played_at))
        processed += 1
        if len(pending) >= batch_size:
            await _flush()

    if processed == 0:
        audio_col = get_audio_tracks_collection()
        cursor = audio_col.find({}, {"_id": 1}).limit(500)
        rank = 500
        async for doc in cursor:
            tid = str(doc.get("_id") or "").strip()
            if not tid:
                continue
            pending.append((tid, rank, now))
            processed += 1
            rank -= 1
            if rank <= 0:
                rank = 1
            if len(pending) >= batch_size:
                await _flush()

    await _flush()

    try:
        await global_col.create_index([("plays", -1)])
        await global_col.create_index([("last_played_at", -1)])
    except Exception:
        pass

    return {"ok": True, "tracks": processed, "updated_at": now}

async def user_top_played_tracks(*, user_id: int, page: int, per_page: int) -> BrowseResponse:
    page = int(page)
    if page < 1:
        page = 1
    per_page = int(per_page)
    if per_page <= 0:
        per_page = 20
    if per_page > 100:
        per_page = 100
    skip = (page - 1) * per_page

    cache_col = db_handler.get_collection("user_top_played_cache").collection
    cached = await cache_col.find_one({"_id": str(int(user_id))}, {"_id": 0, "track_ids": 1, "cover_url": 1, "cover_id": 1})
    cover_url: str | None = None
    if isinstance(cached, dict) and isinstance(cached.get("cover_url"), str) and cached["cover_url"].strip():
        cover_url = cached["cover_url"].strip()
    else:
        from Api.services.genColor import ensure_user_top_played_cover

        cover = await ensure_user_top_played_cover(user_id=int(user_id), force=False)
        if isinstance(cover, dict) and isinstance(cover.get("url"), str) and cover["url"].strip():
            cover_url = cover["url"].strip()
            await cache_col.update_one(
                {"_id": str(int(user_id))},
                {"$set": {"cover_id": cover.get("cover_id"), "cover_url": cover_url, "user_id": int(user_id)}},
                upsert=True,
            )
    if isinstance(cached, dict) and isinstance(cached.get("track_ids"), list) and cached["track_ids"]:
        all_ids = [str(x) for x in cached["track_ids"] if str(x)]
        total = len(all_ids)
        start = int(skip)
        end = int(skip + per_page)
        items = await get_browse_items_by_ids(all_ids[start:end])
        return BrowseResponse(page=page, per_page=per_page, total=total, items=items, cover_url=cover_url)

    col = db_handler.userplayback_collection.collection
    match = {"user_id": int(user_id)}

    total_cur = await col.aggregate(
        [
            {"$match": match},
            {"$group": {"_id": "$track_id"}},
            {"$count": "total"},
        ]
    )
    total_rows = await total_cur.to_list(length=1)
    total = int(total_rows[0]["total"]) if total_rows else 0
    if total <= 0:
        return BrowseResponse(page=page, per_page=per_page, total=0, items=[], cover_url=cover_url)

    rows_cur = await col.aggregate(
        [
            {"$match": match},
            {"$group": {"_id": "$track_id", "plays": {"$sum": 1}, "last_played_at": {"$max": "$played_at"}}},
            {"$sort": {"plays": -1, "last_played_at": -1}},
            {"$skip": int(skip)},
            {"$limit": int(per_page)},
        ]
    )
    rows = await rows_cur.to_list(length=per_page)

    track_ids: list[str] = []
    for r in rows or []:
        tid = (r.get("_id") or "").strip() if isinstance(r.get("_id"), str) else str(r.get("_id") or "").strip()
        if tid:
            track_ids.append(tid)

    items = await get_browse_items_by_ids(track_ids)
    return BrowseResponse(page=page, per_page=per_page, total=total, items=items, cover_url=cover_url)


async def get_track_by_id(track_id: str) -> dict | None:
    col = get_audio_tracks_collection()
    doc = await col.find_one({"_id": track_id})
    if not doc:
        return None
    if "_id" in doc:
        doc["_id"] = _as_str_id(doc["_id"])
    spotify = doc.get("spotify")
    if not isinstance(spotify, dict):
        spotify = {}
    audio = doc.get("audio") if isinstance(doc.get("audio"), dict) else {}
    title = (audio.get("title") or "").strip()
    artist = (audio.get("artist") or "").strip() or (audio.get("performer") or "").strip()
    album = (audio.get("album") or "").strip()
    year = audio.get("year")

    url = spotify.get("url") or spotify.get("spotify_url")
    url = _clean_url(url)
    track_spotify_id = spotify.get("track_spotify_id")
    track_spotify_id = track_spotify_id.strip() if isinstance(track_spotify_id, str) else ""
    cover_url = _clean_url(spotify.get("cover_url"))
    if cover_url:
        spotify["cover_url"] = cover_url
    if url:
        spotify["url"] = url

    if "links" in spotify or "spotify_url" in spotify:
        try:
            await col.update_one({"_id": track_id}, {"$unset": {"spotify.links": "", "spotify.spotify_url": ""}})
        except Exception:
            pass
        spotify.pop("links", None)
        spotify.pop("spotify_url", None)

    if (not url or not track_spotify_id) and title and artist:
        try:
            from stream.helpers.cover_search import spotify_best_track

            sp = await spotify_best_track(title=title, artist=artist, album=album, year=year)
        except Exception:
            sp = None

        if isinstance(sp, dict):
            updates: dict[str, Any] = {}
            sp_id = sp.get("id")
            if isinstance(sp_id, str) and sp_id.strip():
                sp_id = sp_id.strip()
                spotify["track_spotify_id"] = sp_id
                updates["spotify.track_spotify_id"] = sp_id
            ext = sp.get("external_urls") if isinstance(sp.get("external_urls"), dict) else {}
            sp_url = ext.get("spotify")
            if isinstance(sp_url, str) and sp_url.strip():
                sp_url = _clean_url(sp_url)
                spotify["url"] = sp_url
                updates["spotify.url"] = sp_url
            if updates:
                try:
                    await col.update_one({"_id": track_id}, {"$set": updates})
                except Exception:
                    pass
            doc["spotify"] = spotify
    _normalize_spotify(doc)
    return doc

async def get_tracks_by_ids(track_ids: list[str]) -> list[dict]:
    ids = [str(x) for x in (track_ids or []) if str(x)]
    if not ids:
        return []

    col = get_audio_tracks_collection()
    projection = {
        "_id": 1,
        "source_chat_id": 1,
        "source_message_id": 1,
        "telegram": 1,
        "audio": 1,
        "spotify": 1,
        "content_hash": 1,
        "fingerprint": 1,
        "updated_at": 1,
    }
    cursor = col.find({"_id": {"$in": ids}}, projection)
    docs: list[dict] = []
    async for doc in cursor:
        if "_id" in doc:
            doc["_id"] = _as_str_id(doc["_id"])
        _normalize_spotify(doc)
        docs.append(doc)

    by_id = {str(d.get("_id")): d for d in docs if d.get("_id")}
    return [by_id[t] for t in ids if t in by_id]

