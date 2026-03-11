import hashlib
import time
import asyncio
import os
from io import BytesIO
from typing import Any
import requests
from PIL import Image, ImageOps

from stream.database.MongoDb import db_handler
from Api.services.genColor import upload_to_cloudinary, _cloudinary_config, _cloudinary_signature, _gen_covers_dir

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

def _get_tracks_thumbnails(tracks: list[dict]) -> list[str]:
    thumbs = []
    seen = set()
    for t in tracks:
        url = _track_thumbnail_url(t)
        if url and url not in seen:
            thumbs.append(url)
            seen.add(url)
            if len(thumbs) >= 4:
                break
    
    if not thumbs:
        return []
        
    while len(thumbs) < 4:
        thumbs.append(thumbs[-1])
    return thumbs

def _thumbnail_hash(urls: list[str]) -> str:
    # Deterministic hash based on covers
    raw = ("".join(urls)).encode("utf-8", errors="ignore")
    return hashlib.md5(raw).hexdigest()[:12]

def _fetch_image(url: str, *, timeout: int = 10) -> Image.Image | None:
    try:
        resp = requests.get(url, timeout=timeout)
        if resp.status_code != 200 or not resp.content:
            return None
        img = Image.open(BytesIO(resp.content))
        if img.mode not in ("RGB", "RGBA"):
            img = img.convert("RGB")
        return img
    except Exception:
        return None

def _generate_collage(urls: list[str], size: int = 512) -> Image.Image | None:
    srcs = []
    for u in urls[:4]:
        img = _fetch_image(u)
        if img:
            srcs.append(img)
    
    if not srcs:
        return None
        
    while len(srcs) < 4 and srcs:
        srcs.append(srcs[-1])
        
    if len(srcs) < 4:
        return None

    canvas = Image.new("RGB", (size, size))
    cell = size // 2
    for i in range(4):
        x = (i % 2) * cell
        y = (i // 2) * cell
        # Resize and crop to fill the cell
        tile = ImageOps.fit(srcs[i], (cell, cell), method=Image.Resampling.LANCZOS)
        canvas.paste(tile, (x, y))
    return canvas

def delete_from_cloudinary(public_id: str) -> bool:
    cfg = _cloudinary_config()
    cloud_name = cfg["cloud_name"]
    api_key = cfg["api_key"]
    api_secret = cfg["api_secret"]
    if not cloud_name or not api_key or not api_secret:
        return False
        
    ts = str(int(time.time()))
    sign_params = {"public_id": public_id, "timestamp": ts}
    signature = _cloudinary_signature(sign_params, api_secret)
    url = f"https://api.cloudinary.com/v1_1/{cloud_name}/image/destroy"
    
    try:
        resp = requests.post(
            url,
            data={**sign_params, "api_key": api_key, "signature": signature},
            timeout=30,
        )
        return resp.status_code == 200
    except Exception:
        return False

async def ensure_playlist_thumbnail(playlist_id: str, tracks: list[dict], is_system: bool = False, system_key: str | None = None) -> dict | None:
    cover_urls = _get_tracks_thumbnails(tracks)
    if not cover_urls:
        return None
        
    new_hash = _thumbnail_hash(cover_urls)
    
    col = db_handler.get_collection("user_playlists").collection
    playlist = await col.find_one({"_id": playlist_id})
    if not playlist and not is_system:
        return None
        
    old_hash = playlist.get("thumbnail_hash") if playlist else None
    
    # Step 10: Fixed public_id for system playlists
    if is_system and system_key:
        folder = "playlists/system"
        public_id = system_key
        # For system playlists, we might still want to check if the hash changed before re-uploading,
        # but the plan says "Overwrite image instead of creating new ones".
        # We can still avoid upload if hash is same.
    else:
        folder = f"playlists/{playlist_id}"
        public_id = new_hash

    if playlist and old_hash == new_hash and playlist.get("thumbnail_url") and not is_system:
        return {
            "thumbnail_url": playlist.get("thumbnail_url"),
            "thumbnail_hash": new_hash,
            "thumbnail_covers": cover_urls
        }
        
    # Generate collage
    loop = asyncio.get_event_loop()
    collage = await loop.run_in_executor(None, _generate_collage, cover_urls)
    if not collage:
        return None
        
    # Save to temp
    tmp_dir = _gen_covers_dir()
    tmp_path = os.path.join(tmp_dir, f"playlist_{playlist_id}_{new_hash}.jpg")
    try:
        await loop.run_in_executor(None, lambda: collage.save(tmp_path, "JPEG", quality=85))
        
        # Upload to Cloudinary
        try:
            cloud_url = await loop.run_in_executor(None, lambda: upload_to_cloudinary(
                file_path=tmp_path,
                folder=folder,
                public_id=public_id
            ))
        except Exception as e:
            print(f"Cloudinary upload failed: {e}")
            return None
    finally:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass
            
    # Update DB if it's a user playlist
    if playlist:
        await col.update_one(
            {"_id": playlist_id},
            {
                "$set": {
                    "thumbnail_url": cloud_url,
                    "thumbnail_hash": new_hash,
                    "thumbnail_covers": cover_urls,
                    "updated_at": time.time()
                }
            }
        )
        
        # Step 8: Delete old thumbnail
        if not is_system and old_hash and old_hash != new_hash:
            old_public_id = f"playlists/{playlist_id}/{old_hash}"
            await loop.run_in_executor(None, delete_from_cloudinary, old_public_id)
            
    return {
        "thumbnail_url": cloud_url,
        "thumbnail_hash": new_hash,
        "thumbnail_covers": cover_urls
    }
