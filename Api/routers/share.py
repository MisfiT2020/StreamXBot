from fastapi import APIRouter, HTTPException
from Api.schemas.playlists import PlaylistShareResponse
from Api.services.track_service import get_tracks_by_ids
from Api.services.genColor import ensure_user_playlist_normal_cover
from stream.database.MongoDb import db_handler

router = APIRouter(prefix="/share", tags=["share"])

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

@router.get("/playlists/{playlist_id}", response_model=PlaylistShareResponse)
async def get_shared_playlist(playlist_id: str):
    col = db_handler.get_collection("user_playlists").collection
    playlist = await col.find_one({"_id": playlist_id})
    if not playlist:
        raise HTTPException(status_code=404, detail="playlist not found")

    tracks_col = db_handler.get_collection("playlist_tracks").collection
    cursor = (
        tracks_col.find({"playlist_id": playlist_id}, {"_id": 0, "track_id": 1})
        .sort([("position", 1)])
    )
    
    track_ids = []
    async for doc in cursor:
        tid = (doc.get("track_id") or "").strip()
        if tid:
            track_ids.append(tid)

    tracks = await get_tracks_by_ids(track_ids)
    
    track_thumbs = []
    for t in tracks:
        url = _track_thumbnail_url(t)
        if url:
            track_thumbs.append(url)
            if len(track_thumbs) >= 4:
                break

    name = str(playlist.get("name") or "Playlist")
    
    # Resolve normal thumbnail cover
    normal_thumbnail = playlist.get("normal_thumbnail")
    if not normal_thumbnail:
        try:
            res = await ensure_user_playlist_normal_cover(
                playlist_id=playlist_id,
                name=name,
                force=False,
                collage_urls=track_thumbs
            )
            normal_thumbnail = res.get("url")
        except Exception:
            pass
    
    return PlaylistShareResponse(
        playlist_id=str(playlist.get("_id")),
        name=name,
        cover_url=playlist.get("cover_url"),
        normal_thumbnail=normal_thumbnail,
        tracks=tracks,
        created_at=playlist.get("created_at"),
    )
