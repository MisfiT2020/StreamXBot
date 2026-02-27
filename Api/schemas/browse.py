from pydantic import BaseModel, ConfigDict, Field

class BrowseItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True, ser_json_by_alias=False)

    id: str = Field(alias="_id")
    source_chat_id: int | None = None
    source_message_id: int | None = None
    title: str | None = None
    artist: str | None = None
    album: str | None = None
    duration_sec: int | None = None
    type: str | None = None
    sampling_rate_hz: int | None = None
    spotify_url: str | None = None
    cover_url: str | None = None
    updated_at: float | None = None

class BrowseResponse(BaseModel):
    page: int
    per_page: int
    total: int
    items: list[BrowseItem]
    cover_url: str | None = None
