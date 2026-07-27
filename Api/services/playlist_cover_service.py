import time
import uuid

async def ensure_user_playlist_cover(playlist_id: str, name: str, force: bool = False) -> dict:
    return {
        "cover_id": f"cover_{playlist_id}",
        "url": f"/covers/user-playlist/{playlist_id}.png"
    }

async def ensure_user_playlist_normal_cover(playlist_id: str, name: str, force: bool = False) -> dict:
    return {
        "cover_id": f"cover_norm_{playlist_id}",
        "url": f"/covers/user-playlist/{playlist_id}.png"
    }
