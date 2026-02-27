from typing import Optional
from fastapi import APIRouter, HTTPException

from Api.schemas.browse import BrowseResponse
from Api.services.track_service import browse_tracks

router = APIRouter()

@router.get("/browse", response_model=BrowseResponse)
async def browse(channel_id: Optional[int] = None, page: int = 1):
    if page < 1:
        raise HTTPException(status_code=400, detail="page must be >= 1")
    return await browse_tracks(channel_id=channel_id, page=page, per_page=20)
