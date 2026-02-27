from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
import datetime

from Api.services.track_service import (
    refresh_daily_playlist_cache,
    refresh_daily_playlists_bulk,
    refresh_user_top_played_cache,
    refresh_user_top_played_cache_bulk,
    rebuild_global_playback_from_userplayback,
)
from Api.utils.auth import require_user_id
from stream.core.config_manager import Config


router = APIRouter(prefix="/admin/refresh", tags=["admin"])


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


@router.get("/keys")
async def list_refresh_keys(_: int = Depends(require_admin_user_id)):
    return {
        "daily_playlist_keys": [
            {
                "key": "random",
                "aliases": ["mix", "daily", "daily-playlist"],
                "what": "Seeded daily random selection from the track library.",
            },
            {
                "key": "top-played",
                "aliases": ["top", "top-playlist"],
                "what": "Seeded daily selection from globally most played tracks.",
            },
            {
                "key": "trending",
                "aliases": ["trending-today"],
                "what": "Global plays in last 24h.",
            },
            {
                "key": "rediscover",
                "aliases": [],
                "what": "Popular tracks not played recently (global).",
            },
            {
                "key": "late-night",
                "aliases": ["late-night-mix", "night"],
                "what": "Tracks mostly played 22:00–03:00 (global).",
            },
            {
                "key": "rising",
                "aliases": ["rising-tracks"],
                "what": "Fast-rising tracks (last 3 days vs all-time).",
            },
            {
                "key": "surprise",
                "aliases": ["surprise-me"],
                "what": "Weighted random from global plays.",
            },
        ],
        "keys": [
            {
                "key": "all",
                "what": "Refreshes everything (globalplayback rebuild, daily playlists + covers, user top played + cover).",
                "endpoint": {"method": "POST", "path": "/admin/refresh/all"},
                "params": {},
            },
            {
                "key": "rebuild-globalplayback",
                "what": "Rebuilds globalPlayback from userPlayback (restores top-played sources).",
                "endpoint": {"method": "POST", "path": "/admin/refresh/rebuild-globalplayback"},
                "params": {},
            },
            {
                "key": "daily-playlist",
                "what": "Regenerates one cached daily playlist (tracks + cover).",
                "endpoint": {"method": "POST", "path": "/admin/refresh/daily-playlist"},
                "params": {"key": "string", "date": "YYYY-MM-DD|\"\"", "channel_id": "int (0 means default)", "limit": "int (0 means default)"},
            },
            {
                "key": "daily-playlists",
                "what": "Regenerates daily playlists for multiple channels (tracks + covers).",
                "endpoint": {"method": "POST", "path": "/admin/refresh/daily-playlists"},
                "params": {"date": "YYYY-MM-DD|\"\"", "keys": ["random", "top-played", "trending", "rediscover", "late-night", "rising", "surprise"], "channel_ids": ["int (0 means default)"], "limit": "int (0 means default)"},
            },
            {
                "key": "user-top-played",
                "what": "Precomputes cached top played list for all users (tracks + cover).",
                "endpoint": {"method": "POST", "path": "/admin/refresh/user-top-played"},
                "params": {"limit_tracks": "int (0 means default)"},
            },
        ]
    }


@router.post("/all")
async def refresh_all(_: int = Depends(require_admin_user_id)):
    today = datetime.datetime.utcnow().date().isoformat()
    globalplayback = await rebuild_global_playback_from_userplayback()
    daily = await refresh_daily_playlists_bulk(date=today, keys=None, channel_ids=[None], limit=75)
    top_played = await refresh_user_top_played_cache_bulk(user_ids=None, limit_users=None, limit_tracks=500)
    return {"ok": True, "date": today, "globalplayback": globalplayback, "daily_playlists": daily, "user_top_played": top_played}


@router.post("/rebuild-globalplayback")
async def rebuild_globalplayback(_: int = Depends(require_admin_user_id)):
    return {"ok": True, "result": await rebuild_global_playback_from_userplayback()}


class RefreshDailyPlaylistRequest(BaseModel):
    key: str = "random"
    date: str = ""
    channel_id: int = 0
    limit: int = 0


@router.post("/daily-playlist")
async def refresh_one_daily_playlist(payload: RefreshDailyPlaylistRequest, _: int = Depends(require_admin_user_id)):
    d = (payload.date or "").strip() or datetime.datetime.utcnow().date().isoformat()
    channel_id = int(payload.channel_id) if int(payload.channel_id or 0) != 0 else None
    limit = int(payload.limit or 0)
    if limit <= 0:
        limit = 75
    if limit > 75:
        limit = 75
    return {"ok": True, "result": await refresh_daily_playlist_cache(key=payload.key, date=d, channel_id=channel_id, limit=limit)}


class RefreshDailyPlaylistsRequest(BaseModel):
    date: str = ""
    keys: list[str] | None = None
    channel_ids: list[int] | None = None
    limit: int = 0


@router.post("/daily-playlists")
async def refresh_many_daily_playlists(payload: RefreshDailyPlaylistsRequest, _: int = Depends(require_admin_user_id)):
    d = (payload.date or "").strip() or datetime.datetime.utcnow().date().isoformat()
    limit = int(payload.limit or 0)
    if limit <= 0:
        limit = 75
    if limit > 75:
        limit = 75

    channel_ids: list[int | None] | None = None
    if payload.channel_ids is not None:
        channel_ids = [(int(v) if int(v or 0) != 0 else None) for v in payload.channel_ids]

    return {"ok": True, **(await refresh_daily_playlists_bulk(date=d, keys=payload.keys, channel_ids=channel_ids, limit=limit))}


class RefreshUserTopPlayedRequest(BaseModel):
    limit_tracks: int = 0


@router.post("/user-top-played")
async def refresh_user_top_played(payload: RefreshUserTopPlayedRequest, _: int = Depends(require_admin_user_id)):
    limit_tracks = int(payload.limit_tracks or 0)
    if limit_tracks <= 0:
        limit_tracks = 500
    return {"ok": True, **(await refresh_user_top_played_cache_bulk(user_ids=None, limit_users=None, limit_tracks=limit_tracks))}
