import time
import json
import asyncio
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from typing import Dict, List
from Api.utils.auth import verify_auth_token
from stream.database.MongoDb import db_handler

from stream.helpers.logger import LOGGER

router = APIRouter(prefix="/ws", tags=["presence"])

async def broadcast_presence_to_friends(user_id: int, online: bool):
    friends_col = db_handler.get_collection("friends").collection
    friend_doc = await friends_col.find_one({"_id": user_id})
    if not friend_doc:
        return
        
    friend_ids = friend_doc.get("friend_ids", [])
    if not friend_ids:
        return
        
    broadcast_msg = {
        "type": "friend_presence_update",
        "user_id": user_id,
        "online": online,
        "last_seen": time.time()
    }

    LOGGER(__name__).debug(f"[WS] Broadcasting presence update ({online}) from {user_id} to {len(friend_ids)} friends.")
    for f_id in friend_ids:
        await manager.broadcast_to_user(f_id, broadcast_msg)

class ConnectionManager:
    def __init__(self):
        # user_id -> list of active websockets
        self.active_connections: Dict[int, List[WebSocket]] = {}

    async def connect(self, websocket: WebSocket, user_id: int):
        await websocket.accept()
        is_first_session = user_id not in self.active_connections
        if is_first_session:
            self.active_connections[user_id] = []
        self.active_connections[user_id].append(websocket)
        
        LOGGER(__name__).info(f"[WS] User {user_id} connected. Total active sessions: {len(self.active_connections[user_id])}")
        
        # Mark user as online
        presence_col = db_handler.get_collection("presence").collection
        await presence_col.update_one(
            {"user_id": user_id},
            {"$set": {"last_seen": time.time(), "online": True}},
            upsert=True
        )

        # Notify friends if this is their first active session
        if is_first_session:
            await broadcast_presence_to_friends(user_id, True)

    def disconnect(self, websocket: WebSocket, user_id: int):
        if user_id in self.active_connections:
            if websocket in self.active_connections[user_id]:
                self.active_connections[user_id].remove(websocket)
                LOGGER(__name__).info(f"[WS] User {user_id} session closed. Remaining sessions: {len(self.active_connections[user_id])}")
            if not self.active_connections[user_id]:
                del self.active_connections[user_id]
                LOGGER(__name__).info(f"[WS] User {user_id} fully disconnected.")

    async def broadcast_to_user(self, user_id: int, message: dict):
        if user_id in self.active_connections:
            disconnected = []
            for connection in self.active_connections[user_id]:
                try:
                    await connection.send_json(message)
                except Exception as e:
                    LOGGER(__name__).error(f"[WS] Broadcast failed to user {user_id}: {e}")
                    disconnected.append(connection)
            for c in disconnected:
                self.disconnect(c, user_id)

manager = ConnectionManager()

async def broadcast_listening_to_friends(user_id: int, update_data: dict):
    users_col = db_handler.get_collection("users").collection
    user = await users_col.find_one({"_id": user_id}, {"settings": 1})
    if not user:
        return
    settings = user.get("settings", {})
    share = settings.get("share_listening", "friends")
    if share == "nobody":
        return

    friends_col = db_handler.get_collection("friends").collection
    friend_doc = await friends_col.find_one({"_id": user_id})
    if not friend_doc:
        return
        
    friend_ids = friend_doc.get("friend_ids", [])
    if not friend_ids:
        return
        
    broadcast_msg = {
        "type": "friend_listening_update",
        "user_id": user_id,
        "track_id": update_data.get("track_id"),
        "is_playing": update_data.get("is_playing", True),
        "position_sec": update_data.get("position_sec", 0),
        "jam_id": update_data.get("jam_id"),
        "updated_at": update_data.get("updated_at")
    }

    LOGGER(__name__).debug(f"[WS] Broadcasting listening update from {user_id} to {len(friend_ids)} friends.")
    for f_id in friend_ids:
        await manager.broadcast_to_user(f_id, broadcast_msg)


@router.websocket("/{token}")
async def websocket_endpoint(websocket: WebSocket, token: str):
    try:
        payload = verify_auth_token(token)
        user_id = int(payload["uid"])
    except Exception as e:
        LOGGER(__name__).warning(f"[WS] Auth failed for token: {e}")
        await websocket.close(code=1008)
        return

    await manager.connect(websocket, user_id)
    try:
        while True:
            data = await websocket.receive_text()
            LOGGER(__name__).debug(f"[WS] Raw message from {user_id}: {data}")
            try:
                msg = json.loads(data)
                msg_type = msg.get("type")
                
                if msg_type == "heartbeat":
                    LOGGER(__name__).debug(f"[WS] Heartbeat from {user_id} ({msg.get('device', 'unknown')})")
                    presence_col = db_handler.get_collection("presence").collection
                    await presence_col.update_one(
                        {"user_id": user_id},
                        {"$set": {"last_seen": time.time(), "online": True, "device": msg.get("device", "unknown")}},
                        upsert=True
                    )
                    
                elif msg_type == "listening_update":
                    LOGGER(__name__).info(f"[WS] Listening update from {user_id}: track={msg.get('track_id')} playing={msg.get('is_playing')}")
                    list_col = db_handler.get_collection("listeningStatus").collection
                    update_data = {
                        "track_id": msg.get("track_id"),
                        "started_at": time.time(),
                        "is_playing": msg.get("is_playing", True),
                        "position_sec": msg.get("position_sec", 0),
                        "jam_id": msg.get("jam_id"),
                        "updated_at": time.time()
                    }
                    await list_col.update_one(
                        {"user_id": user_id},
                        {"$set": update_data},
                        upsert=True
                    )
                    
                    # Broadcast the new status to connected friends!
                    await broadcast_listening_to_friends(user_id, update_data)
                
                else:
                    LOGGER(__name__).warning(f"[WS] Unknown message type from {user_id}: {msg_type}")
                    
            except json.JSONDecodeError:
                LOGGER(__name__).warning(f"[WS] Invalid JSON from {user_id}: {data}")
    except WebSocketDisconnect:
        LOGGER(__name__).info(f"[WS] WebSocketDisconnect for user {user_id}")
        manager.disconnect(websocket, user_id)
        asyncio.create_task(mark_offline_if_not_reconnected(user_id))
    except Exception as e:
        LOGGER(__name__).error(f"[WS] Unexpected error for user {user_id}: {e}")
        manager.disconnect(websocket, user_id)

async def mark_offline_if_not_reconnected(user_id: int):
    await asyncio.sleep(5)
    if user_id not in manager.active_connections:
        LOGGER(__name__).info(f"[WS] User {user_id} still disconnected after grace period. Marking offline.")
        presence_col = db_handler.get_collection("presence").collection
        await presence_col.update_one(
            {"user_id": user_id},
            {"$set": {"online": False, "last_seen": time.time()}}
        )
        # Notify friends that user is now offline
        await broadcast_presence_to_friends(user_id, False)
    else:
        LOGGER(__name__).info(f"[WS] User {user_id} reconnected within grace period. Staying online.")
