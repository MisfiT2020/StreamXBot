from typing import Optional
from fastapi import APIRouter, Depends, HTTPException

from Api.schemas.browse import BrowseResponse
from Api.services.track_service import browse_tracks
from Api.utils.auth import get_optional_user_id

router = APIRouter()

@router.get("/browse", response_model=BrowseResponse)
async def browse(
    channel_id: Optional[int] = None,
    page: int = 1,
    user_id: Optional[int] = Depends(get_optional_user_id),
):
    if page < 1:
        raise HTTPException(status_code=400, detail="page must be >= 1")
    return await browse_tracks(channel_id=channel_id, page=page, per_page=20, user_id=user_id)
