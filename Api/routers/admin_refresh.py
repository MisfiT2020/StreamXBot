import re
import time
import unicodedata

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
import datetime

from pymongo import UpdateOne

from Api.services.track_service import (
    refresh_daily_playlist_cache,
    refresh_daily_playlists_bulk,
    refresh_user_top_played_cache,
    refresh_user_top_played_cache_bulk,
    rebuild_global_playback_from_userplayback,
)
from Api.utils.auth import require_user_id
from Api.deps.db import get_audio_tracks_collection
from stream.core.config_manager import Config
from stream.database.MongoDb import db_handler


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


_ALBUM_ARTIST_SPLIT_RE = re.compile(r"\s*(?:,|/|&| and | x | feat\. | feat | ft\. | ft )\s*", flags=re.I)


def _split_artists(value: str) -> list[str]:
    raw = (value or "").strip()
    if not raw:
        return []
    raw = raw.replace("(", " ").replace(")", " ").replace("[", " ").replace("]", " ").strip()
    parts = [p.strip() for p in _ALBUM_ARTIST_SPLIT_RE.split(raw) if p and p.strip()]
    out: list[str] = []
    seen: set[str] = set()
    for p in parts:
        k = p.casefold()
        if k in seen:
            continue
        seen.add(k)
        out.append(p)
    return out


def _normalize_album_id_part(text: str) -> str:
    s = (text or "").strip().lower()
    if not s:
        return ""
    s = s.replace("÷", " divide ")
    s = s.replace("&", " and ")
    s = s.replace("+", " plus ")
    s = unicodedata.normalize("NFKD", s)
    s = s.encode("ascii", "ignore").decode("ascii")
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    s = re.sub(r"\s+", "_", s.strip())
    s = re.sub(r"_+", "_", s).strip("_")
    return s


def _coerce_year(value: object) -> int | None:
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
    b = _normalize_album_id_part(album)
    if not b:
        return ""
    y = _coerce_year(year)
    if y is not None:
        return f"album_{b}_{y}"
    return f"album_{b}"


def _clean_url(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    s = value.strip()
    if len(s) >= 2 and s[0] == "`" and s[-1] == "`":
        s = s[1:-1].strip()
    return s or None


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


class RebuildAlbumsFromTracksRequest(BaseModel):
    dry_run: bool = True
    force_album_id: bool = False
    limit_tracks: int = 0
    rebuild_albums: bool = True
    limit_albums: int = 0
    clear_albums: bool = False


@router.post("/albums/from-tracks")
async def rebuild_albums_from_tracks(payload: RebuildAlbumsFromTracksRequest, _: int = Depends(require_admin_user_id)):
    limit_tracks = int(payload.limit_tracks or 0)
    if limit_tracks <= 0:
        limit_tracks = 200_000
    if limit_tracks > 1_000_000:
        limit_tracks = 1_000_000

    limit_albums = int(payload.limit_albums or 0)
    if limit_albums <= 0:
        limit_albums = 50_000
    if limit_albums > 200_000:
        limit_albums = 200_000

    dry_run = bool(payload.dry_run)
    force_album_id = bool(payload.force_album_id)

    tracks_col = get_audio_tracks_collection()
    scanned = 0
    updated = 0
    bulk: list[UpdateOne] = []

    cursor = (
        tracks_col.find(
            {
                "deleted": {"$ne": True},
                "$or": [
                    {"audio.album": {"$exists": True, "$ne": ""}},
                    {"audio.title": {"$exists": True, "$ne": ""}},
                ],
            },
            {"audio": 1, "spotify.cover_url": 1, "spotify.big_cover_url": 1, "updated_at": 1},
        )
        .sort([("updated_at", -1)])
        .limit(limit_tracks)
    )

    async for doc in cursor:
        scanned += 1
        audio = doc.get("audio") if isinstance(doc.get("audio"), dict) else {}
        album = audio.get("album")
        if not isinstance(album, str) or not album.strip():
            album = audio.get("title")
        if not isinstance(album, str) or not album.strip():
            continue
        album = album.strip()

        current = audio.get("album_id")
        if isinstance(current, str) and current.strip() and not force_album_id:
            continue

        year = _coerce_year(audio.get("year"))
        aid = _album_id(album=album, year=year)
        if not aid:
            continue

        if not dry_run:
            bulk.append(UpdateOne({"_id": doc["_id"]}, {"$set": {"audio.album_id": aid}}))
            if len(bulk) >= 500:
                res = await tracks_col.bulk_write(bulk, ordered=False)
                updated += int(getattr(res, "modified_count", 0) or 0)
                bulk.clear()
        else:
            updated += 1

    if bulk and not dry_run:
        res = await tracks_col.bulk_write(bulk, ordered=False)
        updated += int(getattr(res, "modified_count", 0) or 0)
        bulk.clear()

    albums_upserted = 0
    album_groups = 0
    albums_cleared = 0

    if bool(payload.rebuild_albums):
        albums_col = db_handler.get_collection("albums").collection
        now = time.time()
        if bool(payload.clear_albums) and not dry_run:
            try:
                res = await albums_col.delete_many({})
                albums_cleared = int(getattr(res, "deleted_count", 0) or 0)
            except Exception:
                albums_cleared = 0
        pipeline = [
            {"$match": {"deleted": {"$ne": True}, "audio.album_id": {"$exists": True, "$ne": ""}}},
            {
                "$addFields": {
                    "_aid": "$audio.album_id",
                    "_album_title": {"$ifNull": ["$audio.album", "$audio.title"]},
                    "_artist_raw": {
                        "$cond": [
                            {"$and": [{"$isArray": "$audio.artists"}, {"$gt": [{"$size": "$audio.artists"}, 0]}]},
                            {"$arrayElemAt": ["$audio.artists", 0]},
                            {"$ifNull": ["$audio.artist", "$audio.performer"]},
                        ]
                    },
                }
            },
            {
                "$addFields": {
                    "_match_album": {"$toLower": {"$trim": {"input": "$_album_title"}}},
                    "_match_artist": {"$toLower": {"$trim": {"input": "$_artist_raw"}}},
                }
            },
            {"$sort": {"updated_at": -1}},
            {
                "$group": {
                    "_id": "$_aid",
                    "title": {"$first": "$_album_title"},
                    "artist": {"$first": "$_artist_raw"},
                    "cover_url": {"$first": {"$ifNull": ["$spotify.big_cover_url", "$spotify.cover_url"]}},
                    "tracks_count": {"$sum": 1},
                    "duration_total": {"$sum": {"$ifNull": ["$audio.duration_sec", 0]}},
                    "updated_at": {"$max": {"$ifNull": ["$updated_at", 0]}},
                    "match_album": {"$first": "$_match_album"},
                    "match_artist": {"$first": "$_match_artist"},
                }
            },
            {"$limit": int(limit_albums)},
        ]
        cur = await tracks_col.aggregate(pipeline)
        ops: list[UpdateOne] = []
        async for row in cur:
            album_groups += 1
            aid = row.get("_id")
            if not isinstance(aid, str) or not aid.strip():
                continue
            title = (row.get("title") or "").strip()
            artist = (row.get("artist") or "").strip()
            match_album = (row.get("match_album") or "").strip()
            match_artist = (row.get("match_artist") or "").strip()
            if not title or not match_album:
                continue

            cover_url = _clean_url(row.get("cover_url"))
            artist_out = artist or None
            artists = _split_artists(artist) if artist else []
            album_type = "single" if int(row.get("tracks_count") or 0) <= 1 else "album"
            ops.append(
                UpdateOne(
                    {"_id": aid},
                    {
                        "$setOnInsert": {"created_at": now},
                        "$set": {
                            "title": title,
                            "artist": artist_out,
                            "artists": artists if artists else None,
                            "cover_url": cover_url,
                            "tracks_count": int(row.get("tracks_count") or 0),
                            "duration_total": int(row.get("duration_total") or 0),
                            "type": album_type,
                            "match_album": match_album,
                            "match_artist": match_artist or None,
                            "updated_at": float(row.get("updated_at") or now),
                        },
                    },
                    upsert=True,
                )
            )
            if len(ops) >= 500:
                if not dry_run:
                    res = await albums_col.bulk_write(ops, ordered=False)
                    albums_upserted += int(getattr(res, "upserted_count", 0) or 0)
                ops.clear()

        if ops:
            if not dry_run:
                res = await albums_col.bulk_write(ops, ordered=False)
                albums_upserted += int(getattr(res, "upserted_count", 0) or 0)
            ops.clear()

    return {
        "ok": True,
        "dry_run": dry_run,
        "force_album_id": force_album_id,
        "scanned_tracks": int(scanned),
        "tracks_updated": int(updated),
        "albums_rebuilt": bool(payload.rebuild_albums),
        "albums_cleared": int(albums_cleared),
        "album_groups": int(album_groups),
        "albums_upserted": int(albums_upserted),
    }
