import datetime
import time

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import PlainTextResponse

from Api.deps.db import get_audio_tracks_collection
from Api.schemas.browse import BrowseResponse
from Api.schemas.playlists import AvailablePlaylistItem, AvailablePlaylistsResponse
from Api.schemas.track import TrackResponse
from Api.services.genColor import ensure_daily_playlist_cover, ensure_user_top_played_cover
from Api.services.lyrics_service import get_track_lyrics
from Api.services.stream_service import stream_track, warm_track_cached
from Api.services.track_service import get_daily_playlist, get_track_by_id, random_tracks, search_tracks
from Api.utils.auth import verify_auth_token
from stream.database.MongoDb import db_handler

router = APIRouter()

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
        cover = await ensure_daily_playlist_cover(key=key, date=today, channel_id=None, force=False)
        url = cover.get("url") if isinstance(cover, dict) else None
        items.append(
            AvailablePlaylistItem(
                id=f"daily:{key}",
                kind="daily",
                name=name,
                thumbnail_url=url,
                endpoint=f"/daily-playlist/{key}",
                requires_auth=False,
            )
        )

    user_id = _optional_user_id(request)
    if user_id is not None:
        ucol = db_handler.userplayback_collection.collection
        if not await ucol.find_one({"user_id": int(user_id)}, {"_id": 1}):
            return AvailablePlaylistsResponse(items=items)
        cover = await ensure_user_top_played_cover(user_id=int(user_id), force=False)
        url = cover.get("url") if isinstance(cover, dict) else None
        items.append(
            AvailablePlaylistItem(
                id="me:top-played",
                kind="me_top_played",
                name="Top Played",
                thumbnail_url=url,
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

@router.get("/tracks/{track_id}/stream")
async def track_stream(track_id: str, request: Request):
    return await stream_track(track_id, request)


@router.head("/tracks/{track_id}/stream")
async def track_stream_head(track_id: str, request: Request):
    return await stream_track(track_id, request)


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
