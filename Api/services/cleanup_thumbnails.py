import time
import requests
import hashlib
from stream.database.MongoDb import db_handler
from Api.services.genColor import _cloudinary_config, _cloudinary_signature
from Api.services.playlist_thumbnail import delete_from_cloudinary

def list_cloudinary_images(folder: str) -> list[dict]:
    cfg = _cloudinary_config()
    cloud_name = cfg["cloud_name"]
    api_key = cfg["api_key"]
    api_secret = cfg["api_secret"]
    if not cloud_name or not api_key or not api_secret:
        return []
        
    ts = str(int(time.time()))
    # Note: Admin API uses different credentials sometimes, but usually same.
    # Cloudinary Search API or Admin API is needed to list.
    # But Search API requires 'Resource List' permission.
    
    # We'll use the Admin API 'resources' endpoint.
    # https://api.cloudinary.com/v1_1/<cloud_name>/resources/image
    url = f"https://api.cloudinary.com/v1_1/{cloud_name}/resources/image"
    params = {
        "type": "upload",
        "prefix": folder,
        "max_results": 500
    }
    
    # Admin API uses Basic Auth with api_key:api_secret
    try:
        resp = requests.get(url, params=params, auth=(api_key, api_secret))
        if resp.status_code != 200:
            print(f"Failed to list Cloudinary resources: {resp.status_code} {resp.text}")
            return []
        return resp.json().get("resources", [])
    except Exception as e:
        print(f"Error listing Cloudinary resources: {e}")
        return []

async def cleanup_orphaned_thumbnails():
    print("Starting thumbnail cleanup...")
    
    # 1. Get all used thumbnail hashes/URLs from DB
    used_public_ids = set()
    
    # User playlists
    col = db_handler.get_collection("user_playlists").collection
    cursor = col.find({"thumbnail_url": {"$ne": None}}, {"_id": 1, "thumbnail_hash": 1})
    async for doc in cursor:
        pid = doc["_id"]
        h = doc["thumbnail_hash"]
        if pid and h:
            used_public_ids.add(f"playlists/{pid}/{h}")
            
    # Daily playlists
    col = db_handler.get_collection("daily_playlists").collection
    cursor = col.find({"thumbnail_url": {"$ne": None}}, {"_id": 1, "thumbnail_hash": 1, "key": 1, "channel_id": 1})
    async for doc in cursor:
        # System playlists use fixed keys
        k = doc.get("key")
        cid = doc.get("channel_id")
        scope = str(int(cid)) if cid is not None else ""
        system_key = f"{k}_{scope}" if scope else k
        used_public_ids.add(f"playlists/system/{system_key}")
        
    # User top played
    col = db_handler.get_collection("user_top_played_cache").collection
    cursor = col.find({"thumbnail_url": {"$ne": None}}, {"_id": 1, "thumbnail_hash": 1})
    async for doc in cursor:
        uid = doc["_id"]
        used_public_ids.add(f"playlists/system/user_{uid}_top_played")

    # 2. List images from Cloudinary
    # We might need to list recursively or per folder.
    # Cloudinary folder 'playlists'
    resources = list_cloudinary_images("playlists/")
    
    deleted_count = 0
    for res in resources:
        public_id = res.get("public_id")
        if not public_id:
            continue
            
        if public_id not in used_public_ids:
            print(f"Deleting orphaned thumbnail: {public_id}")
            if delete_from_cloudinary(public_id):
                deleted_count += 1
                
    print(f"Cleanup finished. Deleted {deleted_count} orphaned thumbnails.")

if __name__ == "__main__":
    import asyncio
    asyncio.run(cleanup_orphaned_thumbnails())
