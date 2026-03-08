import time
from fastapi import APIRouter, Depends, HTTPException
from bson import ObjectId
from Api.utils.auth import require_user_id
from stream.database.MongoDb import db_handler

router = APIRouter(prefix="/notifications", tags=["notifications"])

@router.get("")
async def get_notifications(user_id: int = Depends(require_user_id)):
    notif_col = db_handler.get_collection("notifications").collection
    cursor = notif_col.find({"user_id": user_id}).sort("created_at", -1).limit(50)
    notifs = []
    async for n in cursor:
        n["_id"] = str(n["_id"])
        notifs.append(n)
    return {"ok": True, "notifications": notifs}

@router.post("/{notif_id}/read")
async def mark_notification_read(notif_id: str, user_id: int = Depends(require_user_id)):
    notif_col = db_handler.get_collection("notifications").collection
    try:
        nid = ObjectId(notif_id)
    except:
        raise HTTPException(status_code=400, detail="Invalid notification ID")
        
    await notif_col.update_one({"_id": nid, "user_id": user_id}, {"$set": {"read": True}})
    return {"ok": True}
