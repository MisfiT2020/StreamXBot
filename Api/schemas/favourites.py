from pydantic import BaseModel

from Api.schemas.track import TrackResponse


class FavouriteCreate(BaseModel):
    track_id: str


class FavouriteItem(BaseModel):
    track: TrackResponse
    created_at: float | None = None


class FavouritesResponse(BaseModel):
    page: int
    per_page: int
    total: int
    items: list[FavouriteItem]


class FavouriteIdsResponse(BaseModel):
    page: int
    per_page: int
    total: int
    ids: list[str]
    exists: bool
    last_updated_at: float | None = None
