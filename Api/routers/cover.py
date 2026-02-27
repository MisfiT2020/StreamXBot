import os

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import FileResponse

from Api.services.genColor import ensure_cover, ensure_daily_playlist_cover, ensure_user_playlist_cover
from Api.utils.auth import require_user_id
from stream.database.MongoDb import db_handler


router = APIRouter(prefix="/covers", tags=["covers"])


def _gen_covers_dir() -> str:
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    out_dir = os.path.join(root, "GenCovers")
    os.makedirs(out_dir, exist_ok=True)
    return out_dir


@router.get("/file/{file_key}.png")
async def get_cover_file(file_key: str):
    fk = (file_key or "").strip()
    if not fk:
        raise HTTPException(status_code=400, detail="file_key is required")
    path = os.path.join(_gen_covers_dir(), f"{fk}.png")
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="cover not found")
    return FileResponse(path, media_type="image/png")


@router.get("/daily-playlist/{key}")
async def get_daily_playlist_cover(
    key: str,
    date: str | None = Query(default=None),
    channel_id: int | None = Query(default=None),
):
    import datetime

    k = (key or "").strip().lower()
    if not k:
        raise HTTPException(status_code=400, detail="key is required")
    d = (date or "").strip() or datetime.datetime.utcnow().date().isoformat()
    try:
        info = await ensure_daily_playlist_cover(key=k, date=d, channel_id=channel_id)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e) or "cover generation failed")
    return {"ok": True, **info}


@router.get("/user-playlist/{playlist_id}")
async def get_user_playlist_cover(
    playlist_id: str,
    user_id: int = Depends(require_user_id),
    force: bool = Query(default=False),
):
    pid = (playlist_id or "").strip()
    if not pid:
        raise HTTPException(status_code=400, detail="playlist_id is required")
    col = db_handler.get_collection("user_playlists").collection
    doc = await col.find_one({"_id": pid, "user_id": int(user_id)}, {"name": 1})
    if not doc:
        raise HTTPException(status_code=404, detail="playlist not found")
    name = (doc.get("name") or "").strip()
    try:
        info = await ensure_user_playlist_cover(playlist_id=pid, name=name, force=bool(force))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e) or "cover generation failed")
    await col.update_one(
        {"_id": pid, "user_id": int(user_id)},
        {"$set": {"cover_id": info.get("cover_id"), "cover_url": info.get("url")}},
    )
    return {"ok": True, **info}


@router.get("/text")
async def generate_text_cover(
    top: str = Query(default=""),
    bottom: str = Query(default=""),
    kind: str = Query(default="custom"),
    cover_id: str | None = Query(default=None),
    force: bool = Query(default=False),
):
    t = (top or "").strip()
    b = (bottom or "").strip()
    if not t and not b:
        raise HTTPException(status_code=400, detail="top or bottom is required")
    cid = (cover_id or "").strip()
    if not cid:
        import uuid

        cid = f"custom:{uuid.uuid4().hex}"
    try:
        info = await ensure_cover(cover_id=cid, top_text=t, bottom_text=b, kind=kind, folder="covers", force=bool(force))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e) or "cover generation failed")
    return {"ok": True, **info}
