import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query

from Api.schemas.playlists import (
    PlaylistCreate,
    PlaylistItem,
    PlaylistRename,
    PlaylistTrackAdd,
    PlaylistTracksResponse,
    PlaylistsResponse,
)
from Api.services.genColor import ensure_user_playlist_cover, ensure_user_playlist_normal_cover
from Api.services.track_service import get_track_by_id, get_tracks_by_ids
from Api.utils.auth import require_user_id
from stream.database.MongoDb import db_handler


router = APIRouter(prefix="/me", tags=["me"])

def _track_thumbnail_url(track: dict) -> str:
    spotify = track.get("spotify") if isinstance(track.get("spotify"), dict) else {}
    telegram = track.get("telegram") if isinstance(track.get("telegram"), dict) else {}
    audio = track.get("audio") if isinstance(track.get("audio"), dict) else {}

    candidates = [
        spotify.get("cover_url"),
        spotify.get("cover"),
        spotify.get("thumbnail"),
        telegram.get("thumb_url"),
        telegram.get("thumbnail_url"),
        telegram.get("thumb"),
        telegram.get("thumbnail"),
        audio.get("cover_url"),
        audio.get("thumbnail"),
    ]
    for c in candidates:
        if isinstance(c, str) and c.strip():
            return c.strip()
    return ""

def _playlist_thumbnails(*, cover_url: str | None, track_thumbnails: list[str], limit: int = 4) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()

    cover = (cover_url or "").strip()
    if cover:
        out.append(cover)
        seen.add(cover)

    for u in track_thumbnails:
        if len(out) >= int(limit):
            break
        u2 = (u or "").strip()
        if not u2 or u2 in seen:
            continue
        out.append(u2)
        seen.add(u2)

    return out


async def _get_playlist_or_404(playlist_id: str, user_id: int) -> dict:
    col = db_handler.get_collection("user_playlists").collection
    doc = await col.find_one({"_id": playlist_id, "user_id": int(user_id)})
    if not doc:
        raise HTTPException(status_code=404, detail="playlist not found")
    return doc


@router.post("/playlists", response_model=PlaylistItem)
async def create_playlist(payload: PlaylistCreate, user_id: int = Depends(require_user_id)):
    name = (payload.name or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="name is required")

    now = time.time()
    playlist_id = uuid.uuid4().hex
    cover = await ensure_user_playlist_cover(playlist_id=playlist_id, name=name, force=True)
    normal_cover = await ensure_user_playlist_normal_cover(playlist_id=playlist_id, name=name, force=True)
    doc = {
        "_id": playlist_id,
        "user_id": int(user_id),
        "name": name,
        "cover_id": cover.get("cover_id"),
        "cover_url": cover.get("url"),
        "created_at": now,
        "updated_at": now,
    }
    await db_handler.get_collection("user_playlists").collection.insert_one(doc)
    return PlaylistItem(
        playlist_id=playlist_id,
        name=name,
        thumbnails=_playlist_thumbnails(cover_url=cover.get("url"), track_thumbnails=[]),
        cover_id=cover.get("cover_id"),
        cover_url=cover.get("url"),
        normal_thumbnail=normal_cover.get("url"),
        created_at=now,
        updated_at=now,
    )


@router.get("/playlists", response_model=PlaylistsResponse)
async def list_playlists(user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("user_playlists").collection
    cursor = col.find(
        {"user_id": int(user_id)},
        {"_id": 1, "name": 1, "cover_id": 1, "cover_url": 1, "created_at": 1, "updated_at": 1},
    ).sort([("created_at", -1)])
    raw_items: list[dict] = []
    async for doc in cursor:
        raw_items.append(
            {
                "playlist_id": str(doc.get("_id")),
                "name": (doc.get("name") or ""),
                "cover_id": doc.get("cover_id"),
                "cover_url": doc.get("cover_url"),
                "created_at": doc.get("created_at"),
                "updated_at": doc.get("updated_at"),
            }
        )

    if not raw_items:
        return PlaylistsResponse(items=[])

    tracks_col = db_handler.get_collection("playlist_tracks").collection
    by_playlist: dict[str, list[str]] = {}
    for it in raw_items:
        pid = str(it.get("playlist_id") or "").strip()
        if not pid:
            continue
        cursor2 = (
            tracks_col.find({"playlist_id": pid}, {"_id": 0, "track_id": 1})
            .sort([("position", 1)])
            .limit(4)
        )
        ids: list[str] = []
        async for row in cursor2:
            tid = row.get("track_id")
            tid = tid.strip() if isinstance(tid, str) else ""
            if tid:
                ids.append(tid)
        by_playlist[pid] = ids

    needed_ids: list[str] = []
    for pid, ids in by_playlist.items():
        needed_ids.extend(ids[:4])

    unique_ids: list[str] = []
    seen: set[str] = set()
    for tid in needed_ids:
        if tid and tid not in seen:
            unique_ids.append(tid)
            seen.add(tid)

    tracks_by_id: dict[str, dict] = {}
    if unique_ids:
        try:
            tracks = await get_tracks_by_ids(unique_ids)
            tracks_by_id = {str(t.get("_id") or t.get("id") or ""): t for t in tracks if isinstance(t, dict)}
        except Exception:
            tracks_by_id = {}

    items: list[PlaylistItem] = []
    for it in raw_items:
        pid = str(it.get("playlist_id") or "")
        ids = by_playlist.get(pid) or []
        track_thumbs: list[str] = []
        for tid in ids[:4]:
            tdoc = tracks_by_id.get(tid)
            if not isinstance(tdoc, dict):
                continue
            url = _track_thumbnail_url(tdoc)
            if url:
                track_thumbs.append(url)

        cover = await ensure_user_playlist_cover(playlist_id=pid, name=str(it.get("name") or ""), force=False, collage_urls=track_thumbs[:4])
        normal_cover = await ensure_user_playlist_normal_cover(playlist_id=pid, name=str(it.get("name") or ""), force=False, collage_urls=track_thumbs[:4])
        cover_url = cover.get("url") if isinstance(cover, dict) else it.get("cover_url")
        cover_id = cover.get("cover_id") if isinstance(cover, dict) else it.get("cover_id")
        normal_thumbnail = normal_cover.get("url") if isinstance(normal_cover, dict) else None
        if cover_url and (cover_url != it.get("cover_url") or cover_id != it.get("cover_id")):
            try:
                await db_handler.get_collection("user_playlists").collection.update_one(
                    {"_id": pid, "user_id": int(user_id)},
                    {"$set": {"cover_id": cover_id, "cover_url": cover_url, "updated_at": time.time()}},
                )
            except Exception:
                pass

        items.append(
            PlaylistItem(
                playlist_id=pid,
                name=str(it.get("name") or ""),
                thumbnails=_playlist_thumbnails(cover_url=cover_url, track_thumbnails=track_thumbs),
                cover_id=cover_id,
                cover_url=cover_url,
                normal_thumbnail=normal_thumbnail,
                created_at=it.get("created_at"),
                updated_at=it.get("updated_at"),
            )
        )
    return PlaylistsResponse(items=items)


@router.patch("/playlists/{playlist_id}", response_model=PlaylistItem)
async def rename_playlist(
    playlist_id: str,
    payload: PlaylistRename,
    user_id: int = Depends(require_user_id),
):
    name = (payload.name or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="name is required")

    await _get_playlist_or_404(playlist_id, user_id)
    now = time.time()
    cover = await ensure_user_playlist_cover(playlist_id=playlist_id, name=name, force=True)
    normal_cover = await ensure_user_playlist_normal_cover(playlist_id=playlist_id, name=name, force=True)
    await db_handler.get_collection("user_playlists").collection.update_one(
        {"_id": playlist_id, "user_id": int(user_id)},
        {"$set": {"name": name, "cover_id": cover.get("cover_id"), "cover_url": cover.get("url"), "updated_at": now}},
    )
    return PlaylistItem(
        playlist_id=playlist_id,
        name=name,
        thumbnails=_playlist_thumbnails(cover_url=cover.get("url"), track_thumbnails=[]),
        cover_id=cover.get("cover_id"),
        cover_url=cover.get("url"),
        normal_thumbnail=normal_cover.get("url"),
        updated_at=now,
    )


@router.delete("/playlists/{playlist_id}")
async def delete_playlist(playlist_id: str, user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("user_playlists").collection
    res = await col.delete_one({"_id": playlist_id, "user_id": int(user_id)})
    if not getattr(res, "deleted_count", 0):
        raise HTTPException(status_code=404, detail="playlist not found")

    await db_handler.get_collection("playlist_tracks").collection.delete_many({"playlist_id": playlist_id})
    return {"ok": True}


@router.post("/playlists/{playlist_id}/tracks")
async def add_track_to_playlist(
    playlist_id: str,
    payload: PlaylistTrackAdd,
    user_id: int = Depends(require_user_id),
):
    t_ids = []
    if isinstance(payload.track_id, list):
        t_ids.extend(payload.track_id)
    elif payload.track_id:
        t_ids.append(payload.track_id)
        
    if payload.track_ids:
        t_ids.extend(payload.track_ids)
        
    track_ids = []
    for tid in t_ids:
        tid_str = str(tid).strip()
        if tid_str and tid_str not in track_ids:
            track_ids.append(tid_str)

    if not track_ids:
        raise HTTPException(status_code=400, detail="track_id or track_ids is required")

    await _get_playlist_or_404(playlist_id, user_id)

    tracks = await get_tracks_by_ids(track_ids)
    if not tracks:
        raise HTTPException(status_code=404, detail="tracks not found")
        
    valid_track_ids = {str(t.get("_id")) for t in tracks}

    tracks_col = db_handler.get_collection("playlist_tracks").collection
    last = await tracks_col.find_one({"playlist_id": playlist_id}, {"position": 1}, sort=[("position", -1)])
    next_pos = int(last.get("position") or 0) + 1 if last else 1

    now = time.time()
    
    upserted_count = 0
    for tid in track_ids:
        if tid not in valid_track_ids:
            continue
            
        res = await tracks_col.update_one(
            {"playlist_id": playlist_id, "track_id": tid},
            {
                "$setOnInsert": {"position": next_pos, "added_at": now},
                "$set": {"playlist_id": playlist_id, "track_id": tid}
            },
            upsert=True
        )
        if res.upserted_id is not None:
            upserted_count += 1
            next_pos += 1

    if len(track_ids) == 1:
        return {"ok": True, "already_exists": upserted_count == 0}
        
    return {"ok": True, "added": upserted_count}


@router.delete("/playlists/{playlist_id}/tracks/{track_id}")
async def remove_track_from_playlist(
    playlist_id: str,
    track_id: str,
    user_id: int = Depends(require_user_id),
):
    track_id = (track_id or "").strip()
    if not track_id:
        raise HTTPException(status_code=400, detail="track_id is required")

    await _get_playlist_or_404(playlist_id, user_id)

    tracks_col = db_handler.get_collection("playlist_tracks").collection
    res = await tracks_col.delete_one({"playlist_id": playlist_id, "track_id": track_id})
    return {"ok": True, "deleted": bool(getattr(res, "deleted_count", 0))}


@router.get("/playlists/{playlist_id}/tracks", response_model=PlaylistTracksResponse)
async def list_playlist_tracks(
    playlist_id: str,
    user_id: int = Depends(require_user_id),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=50, ge=1, le=200),
):
    await _get_playlist_or_404(playlist_id, user_id)

    page = int(page)
    per_page = int(limit)
    skip = (page - 1) * per_page

    tracks_col = db_handler.get_collection("playlist_tracks").collection
    query = {"playlist_id": playlist_id}
    total = await tracks_col.count_documents(query)
    cursor = (
        tracks_col.find(query, {"_id": 0, "track_id": 1})
        .sort([("position", 1)])
        .skip(skip)
        .limit(per_page)
    )

    track_ids: list[str] = []
    async for doc in cursor:
        tid = (doc.get("track_id") or "").strip()
        if tid:
            track_ids.append(tid)

    tracks = await get_tracks_by_ids(track_ids)
    return PlaylistTracksResponse(page=page, per_page=per_page, total=total, items=tracks)
