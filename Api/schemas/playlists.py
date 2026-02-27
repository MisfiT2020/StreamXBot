from pydantic import BaseModel

from Api.schemas.track import TrackResponse


class PlaylistCreate(BaseModel):
    name: str


class PlaylistRename(BaseModel):
    name: str


class PlaylistItem(BaseModel):
    playlist_id: str
    name: str
    thumbnails: list[str] = []
    cover_url: str | None = None
    cover_id: str | None = None
    created_at: float | None = None
    updated_at: float | None = None


class PlaylistsResponse(BaseModel):
    items: list[PlaylistItem]


class PlaylistTrackAdd(BaseModel):
    track_id: str


class PlaylistTracksResponse(BaseModel):
    page: int
    per_page: int
    total: int
    items: list[TrackResponse]


class AvailablePlaylistItem(BaseModel):
    id: str
    kind: str
    name: str
    thumbnail_url: str | None = None
    endpoint: str
    requires_auth: bool = False


class AvailablePlaylistsResponse(BaseModel):
    items: list[AvailablePlaylistItem]
