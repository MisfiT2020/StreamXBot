import os
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

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
