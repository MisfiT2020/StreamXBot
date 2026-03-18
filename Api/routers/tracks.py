import datetime
import time
import re
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel

from Api.deps.db import get_audio_tracks_collection
from Api.schemas.browse import BrowseResponse
from Api.schemas.playlists import AvailablePlaylistItem, AvailablePlaylistsResponse
from Api.schemas.track import TrackResponse
from Api.services.lyrics_service import get_track_lyrics
from Api.services.stream_service import download_track, stream_track, warm_track_cached
from Api.services.track_service import (
    get_daily_playlist,
    get_daily_playlist_thumbnail_info,
    get_track_by_id,
    get_user_top_played_thumbnail_info,
    random_tracks,
    search_tracks,
)
from Api.utils.auth import require_user_id, verify_auth_token
from stream.core.config_manager import Config
from stream.database.MongoDb import db_handler

router = APIRouter()

_ALBUM_SLUG_RE = re.compile(r"[^a-z0-9]+", flags=re.I)


def _slugify(value: str) -> str:
    s = (value or "").strip().lower()
    if not s:
        return ""
    s = _ALBUM_SLUG_RE.sub("_", s)
    s = re.sub(r"_+", "_", s).strip("_")
    return s


def _album_id(*, artist: str, title: str) -> str:
    a = _slugify(artist)
    t = _slugify(title)
    if not a or not t:
        return ""
    return f"album_{a}_{t}"


def _clean_url(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    s = value.strip()
    if len(s) >= 2 and s[0] == "`" and s[-1] == "`":
        s = s[1:-1].strip()
    return s


async def _refresh_albums_cache(*, limit_albums: int = 2000) -> dict[str, int]:
    limit_albums = int(limit_albums)
    if limit_albums <= 0:
        limit_albums = 2000
    if limit_albums > 5000:
        limit_albums = 5000

    tracks_col = get_audio_tracks_collection()
    pipeline = [
        {
            "$match": {
                "deleted": {"$ne": True},
                "audio.album": {"$exists": True, "$ne": ""},
                "$or": [{"audio.artist": {"$exists": True, "$ne": ""}}, {"audio.performer": {"$exists": True, "$ne": ""}}],
            }
        },
        {
            "$addFields": {
                "_album_title": "$audio.album",
                "_album_artist": {"$ifNull": ["$audio.artist", "$audio.performer"]},
            }
        },
        {
            "$addFields": {
                "_album_norm": {"$toLower": {"$trim": {"input": "$_album_title"}}},
                "_artist_norm": {"$toLower": {"$trim": {"input": "$_album_artist"}}},
            }
        },
        {"$sort": {"updated_at": -1}},
        {
            "$group": {
                "_id": {"album": "$_album_norm", "artist": "$_artist_norm"},
                "title": {"$first": "$_album_title"},
                "artist": {"$first": "$_album_artist"},
                "cover_url": {"$first": "$spotify.cover_url"},
                "tracks_count": {"$sum": 1},
                "duration_total": {"$sum": {"$ifNull": ["$audio.duration_sec", 0]}},
                "updated_at": {"$max": "$updated_at"},
            }
        },
        {"$sort": {"updated_at": -1}},
        {"$limit": int(limit_albums)},
    ]
    cur = await tracks_col.aggregate(pipeline)

    albums_col = db_handler.get_collection("albums").collection
    now = time.time()
    upserted = 0
    processed = 0
    async for row in cur:
        processed += 1
        title = (row.get("title") or "").strip() if isinstance(row, dict) else ""
        artist = (row.get("artist") or "").strip() if isinstance(row, dict) else ""
        cover_url = _clean_url(row.get("cover_url")) if isinstance(row, dict) else ""
        tracks_count = int(row.get("tracks_count") or 0) if isinstance(row, dict) else 0
        duration_total = int(row.get("duration_total") or 0) if isinstance(row, dict) else 0
        updated_at = float(row.get("updated_at") or 0.0) if isinstance(row, dict) else 0.0
        norm = row.get("_id") if isinstance(row, dict) else {}
        match_album = norm.get("album") if isinstance(norm, dict) else ""
        match_artist = norm.get("artist") if isinstance(norm, dict) else ""

        aid = _album_id(artist=artist, title=title)
        if not aid or not match_album or not match_artist:
            continue

        res = await albums_col.update_one(
            {"_id": aid},
            {
                "$setOnInsert": {"created_at": now},
                "$set": {
                    "title": title,
                    "artist": artist,
                    "cover_url": cover_url or None,
                    "tracks_count": tracks_count,
                    "duration_total": duration_total,
                    "match_album": match_album,
                    "match_artist": match_artist,
                    "updated_at": updated_at or now,
                },
            },
            upsert=True,
        )
        if getattr(res, "upserted_id", None) is not None:
            upserted += 1

    return {"processed": processed, "upserted": upserted}


def require_admin_user_id(user_id: int = Depends(require_user_id)) -> int:
    uid = int(user_id)
    owners = getattr(Config, "OWNER_ID", None) or []
    sudos = getattr(Config, "SUDO_USERS", None) or []
    allow: set[int] = set()
    for v in (owners or []):
        try:
            allow.add(int(v))
        except Exception:
            pass
    for v in (sudos or []):
        try:
            allow.add(int(v))
        except Exception:
            pass
    if not allow:
        raise HTTPException(status_code=403, detail="admin access not configured")
    if uid not in allow:
        raise HTTPException(status_code=403, detail="admin only")
    return uid

def _optional_user_id(request: Request) -> int | None:
    token = ""
    auth = (request.headers.get("authorization") or request.headers.get("Authorization") or "").strip()
    if auth.lower().startswith("bearer "):
        token = auth[7:].strip()
    if not token:
        token = (request.headers.get("X-Auth-Token") or request.headers.get("x-auth-token") or "").strip()
    if not token:
        token = (request.cookies.get("auth_token") or "").strip()
    if not token:
        token = (request.cookies.get("token") or "").strip()
    if not token:
        token = (request.query_params.get("auth_token") or "").strip()
    if not token:
        token = (request.query_params.get("token") or "").strip()
    if not token:
        return None
    try:
        payload = verify_auth_token(token)
        uid = int(payload.get("uid") or 0)
        return uid if uid > 0 else None
    except Exception:
        return None


async def _has_daily_playlist_tracks(*, key: str) -> bool:
    k = (key or "").strip().lower()
    now = float(time.time())

    if k in {"random", "mix", "daily", "daily-playlist"}:
        col = get_audio_tracks_collection()
        return bool(await col.find_one({}, {"_id": 1}))

    if k in {"top", "top-played", "top-playlist"}:
        col = db_handler.globalplayback_collection.collection
        return bool(await col.find_one({}, {"_id": 1}))

    if k in {"surprise", "surprise-me"}:
        col = db_handler.globalplayback_collection.collection
        return bool(await col.find_one({}, {"_id": 1}))

    if k in {"rediscover"}:
        cutoff = now - 30 * 24 * 3600
        col = db_handler.globalplayback_collection.collection
        return bool(await col.find_one({"last_played_at": {"$lt": cutoff}}, {"_id": 1}))

    if k in {"trending", "trending-today"}:
        since = now - 24 * 3600
        col = db_handler.userplayback_collection.collection
        return bool(await col.find_one({"played_at": {"$gte": since}}, {"_id": 1}))

    if k in {"rising", "rising-tracks"}:
        since = now - 3 * 24 * 3600
        col = db_handler.userplayback_collection.collection
        return bool(await col.find_one({"played_at": {"$gte": since}}, {"_id": 1}))

    if k in {"late-night", "late-night-mix", "night"}:
        since = now - 30 * 24 * 3600
        col = db_handler.userplayback_collection.collection
        cur = await col.aggregate(
            [
                {"$match": {"played_at": {"$gte": since}}},
                {"$addFields": {"_dt": {"$toDate": {"$multiply": ["$played_at", 1000]}}}},
                {"$addFields": {"_hour": {"$hour": "$_dt"}}},
                {"$match": {"$or": [{"_hour": {"$gte": 22}}, {"_hour": {"$lte": 3}}]}},
                {"$limit": 1},
                {"$project": {"_id": 1}},
            ]
        )
        rows = await cur.to_list(length=1)
        return bool(rows)

    return False


@router.get("/search", response_model=BrowseResponse)
async def search(
    q: str | None = Query(default=None, min_length=0, max_length=80),
    query: str | None = Query(default=None, min_length=0, max_length=80),
    channel_id: int | None = Query(default=None),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=20, ge=1, le=50),
):
    raw = (q or "").strip()
    if not raw:
        raw = (query or "").strip()
    if not raw:
        return BrowseResponse(page=int(page), per_page=int(limit), total=0, items=[])
    return await search_tracks(raw, channel_id=channel_id, page=int(page), per_page=int(limit))

@router.get("/tracks/search", response_model=BrowseResponse)
async def track_search(
    q: str = Query(default="", min_length=0, max_length=80),
    channel_id: int | None = Query(default=None),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=20, ge=1, le=50),
):
    q = (q or "").strip()
    if not q:
        return BrowseResponse(page=int(page), per_page=int(limit), total=0, items=[])
    return await search_tracks(q, channel_id=channel_id, page=int(page), per_page=int(limit))

@router.get("/tracks/shuffle", response_model=BrowseResponse)
async def track_shuffle(
    limit: int = Query(default=100, ge=1, le=200),
    seed: int | None = Query(default=None),
    channel_id: int | None = Query(default=None),
):
    return await random_tracks(limit=int(limit), seed=seed, channel_id=channel_id)

@router.get("/playlists/available", response_model=AvailablePlaylistsResponse)
async def available_playlists(request: Request):
    today = datetime.datetime.utcnow().date().isoformat()
    items: list[AvailablePlaylistItem] = []

    daily_defs = [
        ("random", "Daily Mix"),
        ("top-played", "Top Played"),
        ("trending", "Trending Today"),
        ("rediscover", "Rediscover"),
        ("late-night", "Late Night Mix"),
        ("rising", "Rising Tracks"),
        ("surprise", "Surprise Me"),
    ]
    for key, name in daily_defs:
        if not await _has_daily_playlist_tracks(key=key):
            continue
        info = await get_daily_playlist_thumbnail_info(key=key, date=today, channel_id=None, limit=4)
        url = info.get("cover_url") if isinstance(info, dict) else None
        normal = info.get("normal_thumbnail") if isinstance(info, dict) else None
        items.append(
            AvailablePlaylistItem(
                id=f"daily:{key}",
                kind="daily",
                name=name,
                thumbnail_url=url,
                normal_thumbnail=normal,
                endpoint=f"/daily-playlist/{key}",
                requires_auth=False,
            )
        )

    user_id = _optional_user_id(request)
    if user_id is not None:
        ucol = db_handler.userplayback_collection.collection
        if not await ucol.find_one({"user_id": int(user_id)}, {"_id": 1}):
            return AvailablePlaylistsResponse(items=items)
        info = await get_user_top_played_thumbnail_info(user_id=int(user_id), limit=4)
        url = info.get("cover_url") if isinstance(info, dict) else None
        normal = info.get("normal_thumbnail") if isinstance(info, dict) else None
        items.append(
            AvailablePlaylistItem(
                id="me:top-played",
                kind="me_top_played",
                name="Top Played",
                thumbnail_url=url,
                normal_thumbnail=normal,
                endpoint="/me/top-played",
                requires_auth=True,
            )
        )

    return AvailablePlaylistsResponse(items=items)

@router.get("/daily-playlist/{key}", response_model=BrowseResponse)
async def daily_playlist(
    key: str,
    limit: int = Query(default=75, ge=1, le=75),
    channel_id: int | None = Query(default=None),
):
    key = (key or "").strip().lower()
    if not key:
        raise HTTPException(status_code=400, detail="key is required")
    if key not in {
        "random",
        "mix",
        "top",
        "top-played",
        "daily-playlist",
        "top-playlist",
        "trending",
        "trending-today",
        "rediscover",
        "late-night",
        "late-night-mix",
        "rising",
        "rising-tracks",
        "surprise",
        "surprise-me",
    }:
        raise HTTPException(status_code=404, detail="unknown daily playlist")
    return await get_daily_playlist(key=key, date=None, channel_id=channel_id, limit=int(limit))


@router.get("/tracks/{track_id}", response_model=TrackResponse)
async def track_details(track_id: str):
    doc = await get_track_by_id(track_id)
    if not doc:
        raise HTTPException(status_code=404, detail="track not found")
    return doc


@router.get("/albums")
async def list_albums(
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=50, ge=1, le=200),
    refresh: bool = Query(default=False),
):
    albums_col = db_handler.get_collection("albums").collection
    try:
        existing = int(await albums_col.estimated_document_count())
    except Exception:
        existing = 0

    if refresh or existing <= 0:
        await _refresh_albums_cache(limit_albums=5000 if refresh else 2000)
        try:
            existing = int(await albums_col.estimated_document_count())
        except Exception:
            existing = 0

    skip = (int(page) - 1) * int(limit)
    cursor = (
        albums_col.find({}, {"match_album": 0, "match_artist": 0})
        .sort([("updated_at", -1)])
        .skip(int(skip))
        .limit(int(limit))
    )
    items: list[dict] = []
    async for doc in cursor:
        if "_id" in doc:
            doc["_id"] = str(doc["_id"])
        if isinstance(doc.get("cover_url"), str):
            doc["cover_url"] = _clean_url(doc.get("cover_url"))
        items.append(doc)
    return {"ok": True, "page": int(page), "per_page": int(limit), "total": int(existing), "items": items}


@router.get("/albums/{album_id}")
async def album_details(album_id: str):
    aid = (album_id or "").strip()
    if not aid:
        raise HTTPException(status_code=400, detail="album_id is required")

    albums_col = db_handler.get_collection("albums").collection
    album = await albums_col.find_one({"_id": aid})
    if not album:
        await _refresh_albums_cache(limit_albums=5000)
        album = await albums_col.find_one({"_id": aid})
    if not album:
        raise HTTPException(status_code=404, detail="album not found")

    match_album = (album.get("match_album") or "").strip()
    match_artist = (album.get("match_artist") or "").strip()
    if not match_album or not match_artist:
        raise HTTPException(status_code=404, detail="album not ready")

    tracks_col = get_audio_tracks_collection()
    pipeline = [
        {"$match": {"deleted": {"$ne": True}, "audio.album": {"$exists": True, "$ne": ""}}},
        {
            "$addFields": {
                "_album_title": "$audio.album",
                "_album_artist": {"$ifNull": ["$audio.artist", "$audio.performer"]},
            }
        },
        {
            "$addFields": {
                "_album_norm": {"$toLower": {"$trim": {"input": "$_album_title"}}},
                "_artist_norm": {"$toLower": {"$trim": {"input": "$_album_artist"}}},
            }
        },
        {"$match": {"_album_norm": str(match_album), "_artist_norm": str(match_artist)}},
        {"$sort": {"audio.track_number": 1, "source_message_id": 1, "updated_at": -1}},
        {
            "$project": {
                "_id": 1,
                "source_chat_id": 1,
                "source_message_id": 1,
                "audio": 1,
                "spotify": 1,
                "updated_at": 1,
            }
        },
    ]
    cur = await tracks_col.aggregate(pipeline)
    tracks: list[dict] = []
    async for doc in cur:
        if "_id" in doc:
            doc["_id"] = str(doc["_id"])
        spotify = doc.get("spotify") if isinstance(doc.get("spotify"), dict) else {}
        if isinstance(spotify, dict):
            if isinstance(spotify.get("cover_url"), str):
                spotify["cover_url"] = _clean_url(spotify.get("cover_url"))
            if isinstance(spotify.get("url"), str):
                spotify["url"] = _clean_url(spotify.get("url"))
            doc["spotify"] = spotify
        tracks.append(doc)

    album["_id"] = str(album.get("_id"))
    if isinstance(album.get("cover_url"), str):
        album["cover_url"] = _clean_url(album.get("cover_url"))
    album.pop("match_album", None)
    album.pop("match_artist", None)
    return {"ok": True, "album": album, "tracks": tracks}


class AdminDeleteTracksRequest(BaseModel):
    track_id: str | list[str] | None = None
    track_ids: list[str] | None = None


@router.post("/admin/tracks/delete")
async def admin_delete_tracks(payload: AdminDeleteTracksRequest, admin_user_id: int = Depends(require_admin_user_id)):
    t_ids: list[str] = []
    if isinstance(payload.track_id, list):
        t_ids.extend(payload.track_id)
    elif payload.track_id:
        t_ids.append(payload.track_id)
    if payload.track_ids:
        t_ids.extend(payload.track_ids)

    track_ids: list[str] = []
    seen: set[str] = set()
    for tid in t_ids:
        tid_str = str(tid or "").strip()
        if not tid_str or tid_str in seen:
            continue
        seen.add(tid_str)
        track_ids.append(tid_str)

    if not track_ids:
        raise HTTPException(status_code=400, detail="track_id or track_ids is required")

    now = time.time()
    col = get_audio_tracks_collection()
    res = await col.update_many(
        {"_id": {"$in": track_ids}},
        {"$set": {"deleted": True, "deleted_at": now, "deleted_by": int(admin_user_id), "updated_at": now}},
    )
    return {
        "ok": True,
        "track_ids": track_ids,
        "matched": int(getattr(res, "matched_count", 0) or 0),
        "modified": int(getattr(res, "modified_count", 0) or 0),
    }

@router.get("/tracks/{track_id}/stream")
async def track_stream(track_id: str, request: Request):
    return await stream_track(track_id, request)


@router.head("/tracks/{track_id}/stream")
async def track_stream_head(track_id: str, request: Request):
    return await stream_track(track_id, request)


@router.get("/tracks/{track_id}/download")
async def track_download(track_id: str, request: Request):
    return await download_track(track_id, request)


@router.get("/tracks/{track_id}/warm")
async def track_warm(track_id: str):
    return await warm_track_cached(track_id)


@router.get("/tracks/{track_id}/lyrics")
async def track_lyrics(track_id: str, request: Request):
    fmt = (request.query_params.get("format") or "").strip().lower()
    res = await get_track_lyrics(track_id)
    want_json = fmt == "json"
    if not want_json:
        if isinstance(res, dict) and res.get("ok") and isinstance(res.get("lyrics"), str):
            return PlainTextResponse(content=res["lyrics"], media_type="text/plain; charset=utf-8")
    return res
