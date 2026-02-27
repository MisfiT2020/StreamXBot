import time

from fastapi import APIRouter, Depends, HTTPException, Query

from Api.schemas.favourites import FavouriteCreate, FavouriteIdsResponse, FavouritesResponse, FavouriteItem
from Api.schemas.browse import BrowseResponse
from Api.services.track_service import get_track_by_id, get_tracks_by_ids, user_top_played_tracks
from Api.utils.auth import require_user_id
from stream.database.MongoDb import db_handler


router = APIRouter(prefix="/me", tags=["me"])


@router.post("/favourites")
async def add_favourite(payload: FavouriteCreate, user_id: int = Depends(require_user_id)):
    track_id = (payload.track_id or "").strip()
    if not track_id:
        raise HTTPException(status_code=400, detail="track_id is required")

    track = await get_track_by_id(track_id)
    if not track:
        raise HTTPException(status_code=404, detail="track not found")

    col = db_handler.get_collection("user_favourites").collection
    res = await col.update_one(
        {"user_id": int(user_id), "track_id": track_id},
        {
            "$setOnInsert": {"created_at": time.time()},
            "$set": {"user_id": int(user_id), "track_id": track_id, "updated_at": time.time()},
        },
        upsert=True,
    )
    return {"ok": True, "already_exists": res.upserted_id is None}


@router.delete("/favourites/{track_id}")
async def remove_favourite(track_id: str, user_id: int = Depends(require_user_id)):
    track_id = (track_id or "").strip()
    if not track_id:
        raise HTTPException(status_code=400, detail="track_id is required")

    col = db_handler.get_collection("user_favourites").collection
    res = await col.delete_one({"user_id": int(user_id), "track_id": track_id})
    return {"ok": True, "deleted": bool(getattr(res, "deleted_count", 0))}


@router.get("/favourites", response_model=FavouritesResponse)
async def list_favourites(
    user_id: int = Depends(require_user_id),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=20, ge=1, le=100),
):
    page = int(page)
    per_page = int(limit)
    skip = (page - 1) * per_page

    col = db_handler.get_collection("user_favourites").collection
    query = {"user_id": int(user_id)}
    total = await col.count_documents(query)

    cursor = (
        col.find(query, {"_id": 0, "track_id": 1, "created_at": 1})
        .sort([("created_at", -1)])
        .skip(skip)
        .limit(per_page)
    )

    fav_rows: list[dict] = []
    async for doc in cursor:
        tid = (doc.get("track_id") or "").strip()
        if not tid:
            continue
        fav_rows.append({"track_id": tid, "created_at": doc.get("created_at")})

    tracks = await get_tracks_by_ids([r["track_id"] for r in fav_rows])
    by_id = {str(t.get("_id")): t for t in tracks if t.get("_id")}
    items: list[FavouriteItem] = []
    for r in fav_rows:
        t = by_id.get(r["track_id"])
        if not t:
            continue
        items.append(FavouriteItem(track=t, created_at=r.get("created_at")))
    return FavouritesResponse(page=page, per_page=per_page, total=total, items=items)


@router.get("/favourites/ids", response_model=FavouriteIdsResponse)
async def list_favourite_ids(
    user_id: int = Depends(require_user_id),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=200, ge=1, le=1000),
):
    page = int(page)
    per_page = int(limit)
    skip = (page - 1) * per_page

    col = db_handler.get_collection("user_favourites").collection
    query = {"user_id": int(user_id)}
    total = await col.count_documents(query)

    cursor = (
        col.find(query, {"_id": 0, "track_id": 1, "updated_at": 1, "created_at": 1})
        .sort([("created_at", -1)])
        .skip(skip)
        .limit(per_page)
    )

    ids: list[str] = []
    last_ts: float | None = None
    async for doc in cursor:
        tid = (doc.get("track_id") or "").strip()
        if tid:
            ids.append(tid)
            ts = doc.get("updated_at")
            if ts is None:
                ts = doc.get("created_at")
            if isinstance(ts, (int, float)):
                if last_ts is None or float(ts) > float(last_ts):
                    last_ts = float(ts)

    return FavouriteIdsResponse(
        page=page,
        per_page=per_page,
        total=total,
        ids=ids,
        exists=bool(total > 0),
        last_updated_at=last_ts,
    )


@router.get("/top-played", response_model=BrowseResponse)
async def my_top_played(
    user_id: int = Depends(require_user_id),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=50, ge=1, le=100),
):
    return await user_top_played_tracks(user_id=int(user_id), page=int(page), per_page=int(limit))
