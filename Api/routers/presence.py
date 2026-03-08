import time
import json
import asyncio
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from typing import Dict, List
from Api.utils.auth import verify_auth_token
from stream.database.MongoDb import db_handler

router = APIRouter(prefix="/ws", tags=["presence"])

class ConnectionManager:
    def __init__(self):
        # user_id -> list of active websockets
        self.active_connections: Dict[int, List[WebSocket]] = {}

    async def connect(self, websocket: WebSocket, user_id: int):
        await websocket.accept()
        if user_id not in self.active_connections:
            self.active_connections[user_id] = []
        self.active_connections[user_id].append(websocket)
        
        # Mark user as online
        presence_col = db_handler.get_collection("presence").collection
        await presence_col.update_one(
            {"user_id": user_id},
            {"$set": {"last_seen": time.time(), "online": True}},
            upsert=True
        )

    def disconnect(self, websocket: WebSocket, user_id: int):
        if user_id in self.active_connections:
            if websocket in self.active_connections[user_id]:
                self.active_connections[user_id].remove(websocket)
            if not self.active_connections[user_id]:
                del self.active_connections[user_id]

    async def broadcast_to_user(self, user_id: int, message: dict):
        if user_id in self.active_connections:
            disconnected = []
            for connection in self.active_connections[user_id]:
                try:
                    await connection.send_json(message)
                except Exception:
                    disconnected.append(connection)
            for c in disconnected:
                self.disconnect(c, user_id)

manager = ConnectionManager()

@router.websocket("/{token}")
async def websocket_endpoint(websocket: WebSocket, token: str):
    try:
        payload = verify_auth_token(token)
        user_id = int(payload["uid"])
    except Exception:
        await websocket.close(code=1008)
        return

    await manager.connect(websocket, user_id)
    try:
        while True:
            data = await websocket.receive_text()
            try:
                msg = json.loads(data)
                msg_type = msg.get("type")
                
                if msg_type == "heartbeat":
                    presence_col = db_handler.get_collection("presence").collection
                    await presence_col.update_one(
                        {"user_id": user_id},
                        {"$set": {"last_seen": time.time(), "online": True, "device": msg.get("device", "unknown")}},
                        upsert=True
                    )
                    
                elif msg_type == "listening_update":
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
                    
            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        manager.disconnect(websocket, user_id)
        asyncio.create_task(mark_offline_if_not_reconnected(user_id))

async def mark_offline_if_not_reconnected(user_id: int):
    await asyncio.sleep(5)
    if user_id not in manager.active_connections:
        presence_col = db_handler.get_collection("presence").collection
        await presence_col.update_one(
            {"user_id": user_id},
            {"$set": {"online": False, "last_seen": time.time()}}
        )
