import time
from fastapi import APIRouter, Depends, HTTPException
from bson import ObjectId
from Api.schemas.friends import FriendRequestPayload, AcceptRequestPayload, InviteJamPayload, SettingsPayload, FcmTokenPayload
from Api.utils.auth import require_user_id
from stream.database.MongoDb import db_handler

router = APIRouter(prefix="/friends", tags=["friends"])

@router.post("/request")
async def send_friend_request(payload: FriendRequestPayload, user_id: int = Depends(require_user_id)):
    if user_id == payload.to:
        raise HTTPException(status_code=400, detail="Cannot send friend request to yourself")
    
    users_col = db_handler.get_collection("users").collection
    target_user = await users_col.find_one({"_id": payload.to})
    if not target_user:
        raise HTTPException(status_code=404, detail="Target user not found")

    freq_col = db_handler.get_collection("friendRequests").collection
    
    # If the other person already sent a request to us, auto-accept it
    reverse_req = await freq_col.find_one({"from": payload.to, "to": user_id, "status": "pending"})
    if reverse_req:
        await freq_col.update_many(
            {"$or": [{"from": payload.to, "to": user_id}, {"from": user_id, "to": payload.to}]},
            {"$set": {"status": "accepted"}}
        )
        friends_col = db_handler.get_collection("friends").collection
        await friends_col.update_one({"_id": user_id}, {"$addToSet": {"friend_ids": payload.to}, "$set": {"updated_at": time.time()}}, upsert=True)
        await friends_col.update_one({"_id": payload.to}, {"$addToSet": {"friend_ids": user_id}, "$set": {"updated_at": time.time()}}, upsert=True)
        return {"ok": True, "message": "Mutual friend request detected and auto-accepted"}

    existing = await freq_col.find_one({"from": user_id, "to": payload.to, "status": "pending"})
    if existing:
        return {"ok": True, "message": "Request already sent"}

    friends_col = db_handler.get_collection("friends").collection
    friend_doc = await friends_col.find_one({"_id": user_id})
    if friend_doc and payload.to in friend_doc.get("friend_ids", []):
        raise HTTPException(status_code=400, detail="Already friends")
        
    await freq_col.insert_one({
        "from": user_id,
        "to": payload.to,
        "status": "pending",
        "created_at": time.time()
    })
    return {"ok": True}

@router.get("/requests")
async def get_friend_requests(user_id: int = Depends(require_user_id)):
    freq_col = db_handler.get_collection("friendRequests").collection
    cursor = freq_col.find({"to": user_id, "status": "pending"})
    
    from_user_ids = []
    requests_map = {}
    async for req in cursor:
        uid = req["from"]
        from_user_ids.append(uid)
        requests_map[uid] = {
            "request_id": str(req["_id"]),
            "created_at": req.get("created_at")
        }
        
    if not from_user_ids:
        return {"ok": True, "requests": []}
        
    users_col = db_handler.get_collection("users").collection
    u_cursor = users_col.find({"_id": {"$in": from_user_ids}}, {"first_name": 1, "profile_url": 1, "photo_url": 1, "username": 1})
    
    requests_list = []
    async for u in u_cursor:
        uid = int(u["_id"])
        req_data = requests_map.get(uid, {})
        requests_list.append({
            "user_id": uid,
            "first_name": u.get("first_name"),
            "username": u.get("username"),
            "profile_url": u.get("profile_url") or u.get("photo_url"),
            "request_id": req_data.get("request_id"),
            "created_at": req_data.get("created_at")
        })
        
    return {"ok": True, "requests": requests_list}

@router.post("/accept")
async def accept_friend_request(payload: AcceptRequestPayload, user_id: int = Depends(require_user_id)):
    freq_col = db_handler.get_collection("friendRequests").collection
        
    req = await freq_col.find_one({"from": payload.userId, "to": user_id, "status": "pending"})
    if not req:
        raise HTTPException(status_code=404, detail="Friend request not found")
        
    # Mark both possible directions as accepted to clean up mutual requests
    await freq_col.update_many(
        {"$or": [{"from": payload.userId, "to": user_id}, {"from": user_id, "to": payload.userId}]},
        {"$set": {"status": "accepted"}}
    )
    
    friends_col = db_handler.get_collection("friends").collection
    await friends_col.update_one({"_id": user_id}, {"$addToSet": {"friend_ids": payload.userId}, "$set": {"updated_at": time.time()}}, upsert=True)
    await friends_col.update_one({"_id": payload.userId}, {"$addToSet": {"friend_ids": user_id}, "$set": {"updated_at": time.time()}}, upsert=True)
    
    return {"ok": True}

@router.delete("/{friendId}")
async def remove_friend(friendId: int, user_id: int = Depends(require_user_id)):
    friends_col = db_handler.get_collection("friends").collection
    await friends_col.update_one({"_id": user_id}, {"$pull": {"friend_ids": friendId}})
    await friends_col.update_one({"_id": friendId}, {"$pull": {"friend_ids": user_id}})
    return {"ok": True}

@router.get("")
async def get_friends(user_id: int = Depends(require_user_id)):
    friends_col = db_handler.get_collection("friends").collection
    friend_doc = await friends_col.find_one({"_id": user_id})
    if not friend_doc:
        return {"ok": True, "friends": []}
        
    friend_ids = friend_doc.get("friend_ids", [])
    if not friend_ids:
        return {"ok": True, "friends": []}
        
    users_col = db_handler.get_collection("users").collection
    cursor = users_col.find({"_id": {"$in": friend_ids}}, {"first_name": 1, "profile_url": 1, "photo_url": 1, "settings": 1})
    
    # Fetch current presence statuses for all friends
    presence_col = db_handler.get_collection("presence").collection
    p_cursor = presence_col.find({"user_id": {"$in": friend_ids}})
    presence_map = {}
    async for p in p_cursor:
        presence_map[p["user_id"]] = {
            "online": p.get("online", False),
            "last_seen": p.get("last_seen"),
            "device": p.get("device")
        }

    friends = []
    async for f in cursor:
        uid = int(f["_id"])
        f["_id"] = uid
        
        # Default offline state if no presence doc exists
        p_data = presence_map.get(uid, {"online": False, "last_seen": None, "device": None})
        
        # Optional: Safety check - if last_seen is older than 2 minutes, mark as offline manually 
        # in case the TTL index hasn't cleaned it up yet.
        if p_data["online"] and p_data["last_seen"]:
            if time.time() - p_data["last_seen"] > 120:
                p_data["online"] = False

        f["presence"] = p_data
        friends.append(f)
        
    return {"ok": True, "friends": friends}

class ListeningUpdatePayload(BaseModel):
    track_id: str
    is_playing: bool = True
    position_sec: float = 0
    jam_id: Optional[str] = None

from Api.routers.presence import manager, broadcast_listening_to_friends

@router.post("/listening")
async def update_listening_status(payload: ListeningUpdatePayload, user_id: int = Depends(require_user_id)):
    list_col = db_handler.get_collection("listeningStatus").collection
    update_data = {
        "track_id": payload.track_id,
        "started_at": time.time(),
        "is_playing": payload.is_playing,
        "position_sec": payload.position_sec,
        "jam_id": payload.jam_id,
        "updated_at": time.time()
    }
    await list_col.update_one(
        {"user_id": user_id},
        {"$set": update_data},
        upsert=True
    )

    await broadcast_listening_to_friends(user_id, update_data)

    return {"ok": True}

@router.get("/listening")
async def get_friends_listening(user_id: int = Depends(require_user_id)):
    friends_col = db_handler.get_collection("friends").collection
    friend_doc = await friends_col.find_one({"_id": user_id})
    if not friend_doc:
        return {"ok": True, "listening": []}
        
    friend_ids = friend_doc.get("friend_ids", [])
    if not friend_ids:
        return {"ok": True, "listening": []}
        
    users_col = db_handler.get_collection("users").collection
    cursor = users_col.find({"_id": {"$in": friend_ids}}, {"settings": 1})
    allowed_friends = []
    async for u in cursor:
        settings = u.get("settings", {})
        share = settings.get("share_listening", "friends")
        if share in ["friends", "everyone"]:
            allowed_friends.append(u["_id"])
            
    if not allowed_friends:
        return {"ok": True, "listening": []}
        
    listening_col = db_handler.get_collection("listeningStatus").collection
    l_cursor = listening_col.find({"user_id": {"$in": allowed_friends}})
    status_list = []
    async for l in l_cursor:
        l["_id"] = str(l["_id"])
        status_list.append(l)
        
    return {"ok": True, "listening": status_list}

from Api.routers.presence import manager

@router.post("/invite-jam")
async def invite_to_jam(payload: InviteJamPayload, user_id: int = Depends(require_user_id)):
    users_col = db_handler.get_collection("users").collection
    target = await users_col.find_one({"_id": payload.toUserId}, {"settings": 1})
    if not target:
        raise HTTPException(status_code=404, detail="Target user not found")
        
    settings = target.get("settings", {})
    if not settings.get("allow_jam_invites", True):
        raise HTTPException(status_code=403, detail="User does not allow jam invites")
        
    notif_col = db_handler.get_collection("notifications").collection
    await notif_col.insert_one({
        "user_id": payload.toUserId,
        "type": "jam_invite",
        "payload": {"jam_id": payload.jamId, "from_user": user_id},
        "read": False,
        "created_at": time.time()
    })
    
    await manager.broadcast_to_user(payload.toUserId, {
        "type": "jam_invite",
        "jam_id": payload.jamId,
        "from": user_id
    })
    
    return {"ok": True}

@router.put("/settings")
async def update_settings(payload: SettingsPayload, user_id: int = Depends(require_user_id)):
    users_col = db_handler.get_collection("users").collection
    updates = {}
    if payload.share_listening is not None:
        updates["settings.share_listening"] = payload.share_listening
    if payload.allow_jam_invites is not None:
        updates["settings.allow_jam_invites"] = payload.allow_jam_invites
        
    if updates:
        await users_col.update_one({"_id": user_id}, {"$set": updates})
        
    return {"ok": True}

@router.post("/fcm_tokens")
async def add_fcm_token(payload: FcmTokenPayload, user_id: int = Depends(require_user_id)):
    users_col = db_handler.get_collection("users").collection
    await users_col.update_one({"_id": user_id}, {"$addToSet": {"fcm_tokens": payload.token}})
    return {"ok": True}
