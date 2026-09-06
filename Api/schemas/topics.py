from pydantic import BaseModel


class TopicItem(BaseModel):
    name: str
    topic_name: str
    topic_id: int | None = None
    count: int = 0
    cover_url: str | None = None
    thumbnail_url: str | None = None
    normal_thumbnail: str | None = None
    thumbnails: list[str] = []
    source_chat_id: int | None = None
    endpoint: str


class TopicsResponse(BaseModel):
    ok: bool = True
    total: int
    items: list[TopicItem]
    topics: list[str]
