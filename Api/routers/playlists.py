import time
import uuid
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from Api.schemas.browse import BrowseResponse
from Api.schemas.playlists import (
    PlaylistCreate,
    PlaylistItem,
    PlaylistRename,
    PlaylistTrackAdd,
    PlaylistTracksResponse,
    PlaylistsResponse,
    UserAlbumAdd,
    UserAlbumItem,
    UserAlbumsResponse,
)
from Api.services.playlist_cover_service import ensure_user_playlist_cover, ensure_user_playlist_normal_cover
from Api.services.track_service import get_browse_items_by_ids, get_track_by_id, get_tracks_by_ids
from Api.utils.auth import get_optional_user_id, require_user_id
from stream.database.MongoDb import db_handler


router = APIRouter(prefix="/me", tags=["me"])


@router.get("/history", response_model=BrowseResponse)
async def get_user_history(
    limit: int = Query(default=100, ge=1, le=200),
    user_id: int = Depends(require_user_id),
):
    hist_col = db_handler.get_collection("userHistory").collection
    query = {"$or": [{"user_id": int(user_id)}, {"user_id": str(user_id)}]}
    cursor = hist_col.find(query).sort([("played_at", -1)]).limit(limit * 2)

    seen_track_ids: set[str] = set()
    ordered_track_ids: list[str] = []
    async for doc in cursor:
        tid = str(doc.get("track_id") or "").strip()
        if tid and tid not in seen_track_ids:
            seen_track_ids.add(tid)
            ordered_track_ids.append(tid)
            if len(ordered_track_ids) >= limit:
                break

    if not ordered_track_ids:
        return BrowseResponse(page=1, per_page=limit, total=0, items=[])

    items = await get_browse_items_by_ids(ordered_track_ids, user_id=user_id)
    item_map = {item.id: item for item in items}
    ordered_items = [item_map[tid] for tid in ordered_track_ids if tid in item_map]

    return BrowseResponse(page=1, per_page=limit, total=len(ordered_items), items=ordered_items)

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

    for u in track_thumbnails:
        if len(out) >= int(limit):
            break
        u2 = (u or "").strip()
        if not u2 or u2 in seen:
            continue
        out.append(u2)
        seen.add(u2)

    cover = (cover_url or "").strip()
    if cover and cover not in seen and len(out) < int(limit):
        out.append(cover)
        seen.add(cover)

    return out


def _clean_url(value: object) -> str:
    if not isinstance(value, str):
        return ""
    s = value.strip()
    if len(s) >= 2 and s[0] == "`" and s[-1] == "`":
        s = s[1:-1].strip()
    return s


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
async def list_playlists(user_id: Optional[int] = Depends(get_optional_user_id)):
    if user_id is None:
        return PlaylistsResponse(items=[])
    col = db_handler.get_collection("user_playlists").collection
    cursor = col.find(
        {"user_id": user_id},
        {"_id": 1, "name": 1, "cover_id": 1, "cover_url": 1, "normal_thumbnail": 1, "collage_hash": 1, "created_at": 1, "updated_at": 1},
    ).sort([("created_at", -1)])
    raw_items: list[dict] = []
    async for doc in cursor:
        raw_items.append(
            {
                "playlist_id": str(doc.get("_id")),
                "name": (doc.get("name") or ""),
                "cover_id": doc.get("cover_id"),
                "cover_url": doc.get("cover_url"),
                "normal_thumbnail": doc.get("normal_thumbnail"),
                "collage_hash": doc.get("collage_hash"),
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
        
        # Resolve track thumbnails
        track_thumbs: list[str] = []
        for tid in ids[:4]:
            tdoc = tracks_by_id.get(tid)
            if not isinstance(tdoc, dict):
                continue
            url = _track_thumbnail_url(tdoc)
            if url:
                track_thumbs.append(url)

        cover_url = it.get("cover_url")
        cover_id = it.get("cover_id")
        normal_thumbnail = it.get("normal_thumbnail")

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
    doc = await col.find_one({"_id": playlist_id, "user_id": int(user_id)})
    if not doc:
        raise HTTPException(status_code=404, detail="playlist not found")
        
    res = await col.delete_one({"_id": playlist_id, "user_id": int(user_id)})
    if not getattr(res, "deleted_count", 0):
        raise HTTPException(status_code=404, detail="playlist not found")

    await db_handler.get_collection("playlist_tracks").collection.delete_many({"playlist_id": playlist_id})
    
    try:
        import hashlib
        root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
        covers_dir = os.path.join(root, "GenCovers")
        if doc.get("cover_id"):
            fk = hashlib.sha256(str(doc["cover_id"]).encode("utf-8")).hexdigest()[:16]
            p = os.path.join(covers_dir, f"{fk}.png")
            if os.path.exists(p):
                os.remove(p)
        fk_normal = hashlib.sha256(f"user-playlist-normal:{playlist_id}".encode("utf-8")).hexdigest()[:16]
        pn = os.path.join(covers_dir, f"{fk_normal}.png")
        if os.path.exists(pn):
            os.remove(pn)
    except Exception:
        pass
        
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

    tracks = await get_tracks_by_ids(track_ids, user_id=user_id)
    return PlaylistTracksResponse(page=page, per_page=per_page, total=total, items=tracks)


@router.post("/albums")
async def save_album(payload: UserAlbumAdd, user_id: int = Depends(require_user_id)):
    a_ids: list[str] = []
    if isinstance(payload.album_id, list):
        a_ids.extend(payload.album_id)
    elif payload.album_id:
        a_ids.append(payload.album_id)
    if payload.album_ids:
        a_ids.extend(payload.album_ids)

    album_ids: list[str] = []
    seen: set[str] = set()
    for aid in a_ids:
        s = str(aid or "").strip()
        if not s or s in seen:
            continue
        seen.add(s)
        album_ids.append(s)

    if not album_ids:
        raise HTTPException(status_code=400, detail="album_id or album_ids is required")

    albums_col = db_handler.get_collection("albums").collection
    existing = await albums_col.find_one({"_id": {"$in": album_ids}}, {"_id": 1})
    if not existing:
        raise HTTPException(status_code=404, detail="albums not found")

    col = db_handler.get_collection("user_albums").collection
    now = time.time()
    added = 0
    for aid in album_ids:
        res = await col.update_one(
            {"user_id": int(user_id), "album_id": aid},
            {"$setOnInsert": {"user_id": int(user_id), "album_id": aid, "saved_at": now}, "$set": {"updated_at": now}},
            upsert=True,
        )
        if getattr(res, "upserted_id", None) is not None:
            added += 1

    if len(album_ids) == 1:
        return {"ok": True, "already_exists": added == 0}
    return {"ok": True, "added": int(added)}


@router.get("/albums", response_model=UserAlbumsResponse)
async def list_saved_albums(
    user_id: int = Depends(require_user_id),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=50, ge=1, le=200),
):
    page = int(page)
    per_page = int(limit)
    skip = (page - 1) * per_page

    col = db_handler.get_collection("user_albums").collection
    query = {"user_id": int(user_id)}
    total = await col.count_documents(query)
    if total <= 0:
        return UserAlbumsResponse(page=page, per_page=per_page, total=0, items=[])

    cursor = col.find(query, {"_id": 0, "album_id": 1, "saved_at": 1}).sort([("saved_at", -1)]).skip(skip).limit(per_page)
    rows: list[dict] = []
    album_ids: list[str] = []
    async for r in cursor:
        aid = (r.get("album_id") or "").strip()
        if not aid:
            continue
        rows.append({"album_id": aid, "saved_at": r.get("saved_at")})
        album_ids.append(aid)

    if not album_ids:
        return UserAlbumsResponse(page=page, per_page=per_page, total=total, items=[])

    albums_col = db_handler.get_collection("albums").collection
    projection = {"match_album": 0, "match_artist": 0}
    acur = albums_col.find({"_id": {"$in": album_ids}}, projection)
    by_id: dict[str, dict] = {}
    async for a in acur:
        aid = str(a.get("_id") or "").strip()
        if not aid:
            continue
        a["_id"] = aid
        if isinstance(a.get("cover_url"), str):
            a["cover_url"] = _clean_url(a.get("cover_url"))
        by_id[aid] = a

    items: list[UserAlbumItem] = []
    for r in rows:
        aid = r["album_id"]
        items.append(UserAlbumItem(album_id=aid, album=by_id.get(aid), saved_at=r.get("saved_at")))

    return UserAlbumsResponse(page=page, per_page=per_page, total=int(total), items=items)
