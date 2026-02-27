import asyncio
import time
import uuid
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Header, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field

from Api.services.stream_service import warm_track_cached
from Api.utils.auth import require_user_id, verify_auth_token
from stream.database.MongoDb import db_handler

_WS_LOCK = asyncio.Lock()
_WS_ROOMS: dict[str, set[WebSocket]] = {}

router = APIRouter(prefix="/jam", tags=["jam"])

def _now() -> float:
    return time.time()


def _as_int(v: Any) -> int | None:
    try:
        return int(v)
    except Exception:
        return None


def _sanitize_track_id(v: Any) -> str:
    s = (v or "").strip()
    if not s:
        return ""
    return s


def _member_meta_from_payload(payload: dict) -> tuple[str | None, str | None]:
    fn = payload.get("first_name")
    if not isinstance(fn, str) or not fn.strip():
        fn = None
    else:
        fn = fn.strip()
    pu = payload.get("profile_url")
    if not isinstance(pu, str) or not pu.strip():
        pu = payload.get("photo_url")
        if not isinstance(pu, str) or not pu.strip():
            pu = None
        else:
            pu = pu.strip()
    else:
        pu = pu.strip()
    return fn, pu


async def _resolve_member_meta(*, user_id: int, auth_payload: dict) -> tuple[str | None, str | None]:
    first_name, profile_url = _member_meta_from_payload(auth_payload)
    if first_name and profile_url:
        return first_name, profile_url

    col = db_handler.get_collection("users").collection
    doc = await col.find_one(
        {"_id": int(user_id)},
        {"first_name": 1, "profile_url": 1, "photo_url": 1},
    )
    if isinstance(doc, dict):
        if not first_name:
            fn = doc.get("first_name")
            if isinstance(fn, str) and fn.strip():
                first_name = fn.strip()
        if not profile_url:
            pu = doc.get("profile_url")
            if not isinstance(pu, str) or not pu.strip():
                pu = doc.get("photo_url")
            if isinstance(pu, str) and pu.strip():
                profile_url = pu.strip()

    return first_name, profile_url


def _auth_payload_from_headers(authorization: str | None, x_auth_token: str | None) -> dict:
    token = (authorization or "").strip()
    if not token:
        token = (x_auth_token or "").strip()
    return verify_auth_token(token)


def _ws_auth_payload(ws: WebSocket) -> dict:
    token = (ws.query_params.get("token") or ws.query_params.get("auth") or "").strip()
    if not token:
        token = (ws.headers.get("x-auth-token") or "").strip()
    if not token:
        token = (ws.headers.get("authorization") or "").strip()
    return verify_auth_token(token)


def _compute_position(playback: dict) -> float:
    try:
        pos = float(playback.get("position_sec") or 0.0)
    except Exception:
        pos = 0.0
    is_playing = bool(playback.get("is_playing"))
    started_at = playback.get("started_at")
    if is_playing:
        try:
            started_at = float(started_at)
        except Exception:
            started_at = 0.0
        if started_at > 0:
            pos = pos + max(0.0, _now() - started_at)
    return max(0.0, float(pos))


async def _warm_tracks(track_ids: list[str]) -> None:
    uniq: list[str] = []
    seen: set[str] = set()
    for t in (track_ids or []):
        tid = _sanitize_track_id(t)
        if not tid or tid in seen:
            continue
        seen.add(tid)
        uniq.append(tid)

    for tid in uniq[:3]:
        try:
            asyncio.create_task(warm_track_cached(tid))
        except Exception:
            pass


async def _get_session(jam_id: str) -> dict | None:
    jam_id = (jam_id or "").strip()
    if not jam_id:
        return None
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id})
    return doc


def _serialize_session(doc: dict) -> dict:
    out = dict(doc or {})
    if "_id" in out:
        out["_id"] = str(out["_id"])
    host = _as_int(out.get("host_user_id"))
    if host is not None:
        out["host_user_id"] = int(host)
    members = out.get("members")
    if isinstance(members, list):
        normalized: list[dict] = []
        for m in members:
            if not isinstance(m, dict):
                continue
            mm = dict(m)
            uid = _as_int(mm.get("user_id"))
            if uid is not None:
                mm["user_id"] = int(uid)
            pu = mm.get("profile_url")
            if not isinstance(pu, str) or not pu.strip():
                pu2 = mm.get("photo_url")
                if isinstance(pu2, str) and pu2.strip():
                    mm["profile_url"] = pu2.strip()
            else:
                mm["profile_url"] = pu.strip()
            mm.pop("photo_url", None)
            normalized.append(mm)
        out["members"] = normalized
    playback = out.get("playback")
    if isinstance(playback, dict):
        if "started_at" in playback and playback["started_at"] is not None:
            try:
                playback["started_at"] = float(playback["started_at"])
            except Exception:
                playback["started_at"] = None
        if "position_sec" in playback and playback["position_sec"] is not None:
            try:
                playback["position_sec"] = float(playback["position_sec"])
            except Exception:
                playback["position_sec"] = 0.0
        out["playback"] = playback
    return out


async def _broadcast(jam_id: str, payload: dict) -> None:
    jam_id = (jam_id or "").strip()
    if not jam_id:
        return
    async with _WS_LOCK:
        conns = list(_WS_ROOMS.get(jam_id, set()))
    if not conns:
        return
    dead: list[WebSocket] = []
    for ws in conns:
        try:
            await ws.send_json(payload)
        except Exception:
            dead.append(ws)
    if dead:
        async with _WS_LOCK:
            room = _WS_ROOMS.get(jam_id)
            if room:
                for ws in dead:
                    room.discard(ws)
                if not room:
                    _WS_ROOMS.pop(jam_id, None)


async def _broadcast_fresh_state(jam_id: str) -> dict | None:
    doc = await _get_session(jam_id)
    if not doc:
        return None
    await _broadcast(str(doc.get("_id") or jam_id), {"type": "jam_state", "jam": _serialize_session(doc)})
    return doc


def _ws_user_id(ws: WebSocket) -> int:
    payload = _ws_auth_payload(ws)
    uid = _as_int(payload.get("uid"))
    if not uid or uid <= 0:
        raise HTTPException(status_code=401, detail="invalid auth token")
    return int(uid)


async def _ensure_member(*, jam_id: str, user_id: int, first_name: str | None = None, photo_url: str | None = None) -> None:
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id}, {"members": 1, "host_user_id": 1})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    members = doc.get("members") if isinstance(doc.get("members"), list) else []
    fn = (first_name or "").strip() or None
    pu = (photo_url or "").strip() or None
    role = "host" if _as_int(doc.get("host_user_id")) == int(user_id) else "listener"
    for m in members:
        if not isinstance(m, dict) or _as_int(m.get("user_id")) != int(user_id):
            continue
        updates: dict[str, object] = {}
        if (m.get("role") or "") != role:
            updates["members.$.role"] = role
        if fn and (m.get("first_name") or "") != fn:
            updates["members.$.first_name"] = fn
        existing_pu = m.get("profile_url")
        if not isinstance(existing_pu, str) or not existing_pu.strip():
            existing_pu = m.get("photo_url")
        if pu and (existing_pu or "") != pu:
            updates["members.$.profile_url"] = pu
        if updates:
            updates["updated_at"] = _now()
            update_doc: dict[str, object] = {"$set": updates}
            if pu and "photo_url" in m:
                update_doc["$unset"] = {"members.$.photo_url": ""}
            await col.update_one(
                {"_id": jam_id, "members.user_id": {"$in": [int(user_id), str(int(user_id))]}},
                update_doc,
            )
        return
    member: dict[str, object] = {"user_id": int(user_id), "role": role}
    if fn:
        member["first_name"] = fn
    if pu:
        member["profile_url"] = pu
    await col.update_one({"_id": jam_id}, {"$push": {"members": member}, "$set": {"updated_at": _now()}})


def _has_permission(doc: dict, user_id: int, *, action: str) -> bool:
    host_id = _as_int(doc.get("host_user_id")) or 0
    if int(user_id) == int(host_id):
        return True
    settings = doc.get("settings") if isinstance(doc.get("settings"), dict) else {}
    if action == "seek":
        return bool(settings.get("allow_seek"))
    if action == "queue":
        return bool(settings.get("allow_queue_edit"))
    return False


class JamCreateRequest(BaseModel):
    track_id: str
    position_sec: float = 0.0
    is_playing: bool = True
    queue: list[str] = Field(default_factory=list)
    settings: dict[str, Any] = Field(default_factory=dict)


class JamJoinResponse(BaseModel):
    ok: bool = True
    jam: dict[str, Any]


class JamSeekRequest(BaseModel):
    position_sec: float


class JamQueueAddRequest(BaseModel):
    track_id: str
    position: int | None = None


class JamQueueReorderRequest(BaseModel):
    queue: list[str] = Field(default_factory=list)


class JamSettingsUpdateRequest(BaseModel):
    allow_seek: bool | None = None
    allow_queue_edit: bool | None = None


@router.post("/create", response_model=JamJoinResponse)
async def jam_create(
    payload: JamCreateRequest,
    user_id: int = Depends(require_user_id),
    authorization: str | None = Header(default=None),
    x_auth_token: str | None = Header(default=None, alias="X-Auth-Token"),
):
    auth_payload = _auth_payload_from_headers(authorization, x_auth_token)
    first_name, profile_url = await _resolve_member_meta(user_id=int(user_id), auth_payload=auth_payload)
    track_id = _sanitize_track_id(payload.track_id)
    if not track_id:
        raise HTTPException(status_code=400, detail="track_id is required")
    now = _now()
    jam_id = f"jam_{uuid.uuid4().hex}"
    host_member: dict[str, object] = {"user_id": int(user_id), "role": "host"}
    if first_name:
        host_member["first_name"] = first_name
    if profile_url:
        host_member["profile_url"] = profile_url
    doc = {
        "_id": jam_id,
        "host_user_id": int(user_id),
        "created_at": now,
        "updated_at": now,
        "playback": {
            "track_id": track_id,
            "position_sec": float(payload.position_sec or 0.0),
            "started_at": now if bool(payload.is_playing) else now,
            "is_playing": bool(payload.is_playing),
        },
        "queue": [_sanitize_track_id(x) for x in (payload.queue or []) if _sanitize_track_id(x)],
        "members": [host_member],
        "settings": payload.settings or {},
    }
    await db_handler.get_collection("jam_sessions").collection.insert_one(doc)

    try:
        next_ids = [track_id] + (doc.get("queue") or [])[:2]
        asyncio.create_task(_warm_tracks(next_ids))
    except Exception:
        pass

    return {"ok": True, "jam": _serialize_session(doc)}


@router.get("/{jam_id}", response_model=JamJoinResponse)
async def jam_get(
    jam_id: str,
    user_id: int = Depends(require_user_id),
    authorization: str | None = Header(default=None),
    x_auth_token: str | None = Header(default=None, alias="X-Auth-Token"),
):
    doc = await _get_session(jam_id)
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    auth_payload = _auth_payload_from_headers(authorization, x_auth_token)
    first_name, profile_url = await _resolve_member_meta(user_id=int(user_id), auth_payload=auth_payload)
    await _ensure_member(jam_id=str(doc["_id"]), user_id=int(user_id), first_name=first_name, photo_url=profile_url)
    doc2 = await _get_session(jam_id)
    if not doc2:
        raise HTTPException(status_code=404, detail="jam not found")
    return {"ok": True, "jam": _serialize_session(doc2)}


@router.post("/{jam_id}/join", response_model=JamJoinResponse)
async def jam_join(
    jam_id: str,
    user_id: int = Depends(require_user_id),
    authorization: str | None = Header(default=None),
    x_auth_token: str | None = Header(default=None, alias="X-Auth-Token"),
):
    doc = await _get_session(jam_id)
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    auth_payload = _auth_payload_from_headers(authorization, x_auth_token)
    first_name, profile_url = await _resolve_member_meta(user_id=int(user_id), auth_payload=auth_payload)
    await _ensure_member(jam_id=str(doc["_id"]), user_id=int(user_id), first_name=first_name, photo_url=profile_url)
    doc2 = await _get_session(jam_id)
    if not doc2:
        raise HTTPException(status_code=404, detail="jam not found")
    await _broadcast(str(doc2["_id"]), {"type": "jam_state", "jam": _serialize_session(doc2)})
    return {"ok": True, "jam": _serialize_session(doc2)}


@router.post("/{jam_id}/leave")
async def jam_leave(jam_id: str, user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")

    members = doc.get("members") if isinstance(doc.get("members"), list) else []
    kept: list[dict] = []
    for m in members:
        if not isinstance(m, dict):
            continue
        if _as_int(m.get("user_id")) == int(user_id):
            continue
        kept.append(m)

    host_id = _as_int(doc.get("host_user_id")) or 0
    if int(user_id) != int(host_id):
        await col.update_one({"_id": jam_id}, {"$set": {"members": kept, "updated_at": _now()}})
        await _broadcast_fresh_state(jam_id)
        return {"ok": True}

    if not kept:
        await col.delete_one({"_id": jam_id})
        await _broadcast(jam_id, {"type": "jam_ended", "jam_id": jam_id})
        return {"ok": True, "ended": True}

    new_host = _as_int(kept[0].get("user_id")) or 0
    for m in kept:
        if not isinstance(m, dict):
            continue
        if _as_int(m.get("user_id")) == int(new_host):
            m["role"] = "host"
        elif m.get("role") == "host":
            m["role"] = "listener"

    await col.update_one(
        {"_id": jam_id},
        {"$set": {"host_user_id": int(new_host), "members": kept, "updated_at": _now()}},
    )
    doc3 = await col.find_one({"_id": jam_id})
    if doc3:
        await _broadcast_fresh_state(jam_id)
    return {"ok": True, "new_host_user_id": int(new_host)}


@router.post("/{jam_id}/settings")
async def jam_settings_update(
    jam_id: str,
    payload: JamSettingsUpdateRequest,
    user_id: int = Depends(require_user_id),
):
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id}, {"host_user_id": 1, "settings": 1})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    if int(user_id) != int(_as_int(doc.get("host_user_id")) or 0):
        raise HTTPException(status_code=403, detail="not allowed")

    updates: dict[str, object] = {}
    if payload.allow_seek is not None:
        updates["settings.allow_seek"] = bool(payload.allow_seek)
    if payload.allow_queue_edit is not None:
        updates["settings.allow_queue_edit"] = bool(payload.allow_queue_edit)
    if not updates:
        raise HTTPException(status_code=400, detail="no settings to update")
    updates["updated_at"] = _now()

    await col.update_one({"_id": jam_id}, {"$set": updates})
    await _broadcast_fresh_state(jam_id)
    return {"ok": True}


@router.post("/{jam_id}/play")
async def jam_play(jam_id: str, user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    if not _has_permission(doc, int(user_id), action="play"):
        raise HTTPException(status_code=403, detail="not allowed")
    playback = doc.get("playback") if isinstance(doc.get("playback"), dict) else {}
    pos = _compute_position(playback)
    now = _now()
    updates = {
        "playback.position_sec": float(pos),
        "playback.started_at": now,
        "playback.is_playing": True,
        "updated_at": now,
    }
    await col.update_one({"_id": jam_id}, {"$set": updates})
    await _broadcast_fresh_state(jam_id)
    return {"ok": True}


@router.post("/{jam_id}/pause")
async def jam_pause(jam_id: str, user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    if not _has_permission(doc, int(user_id), action="pause"):
        raise HTTPException(status_code=403, detail="not allowed")
    playback = doc.get("playback") if isinstance(doc.get("playback"), dict) else {}
    pos = _compute_position(playback)
    now = _now()
    updates = {
        "playback.position_sec": float(pos),
        "playback.started_at": now,
        "playback.is_playing": False,
        "updated_at": now,
    }
    await col.update_one({"_id": jam_id}, {"$set": updates})
    await _broadcast_fresh_state(jam_id)
    return {"ok": True}


@router.post("/{jam_id}/seek")
async def jam_seek(jam_id: str, payload: JamSeekRequest, user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    if not _has_permission(doc, int(user_id), action="seek"):
        raise HTTPException(status_code=403, detail="not allowed")

    playback = doc.get("playback") if isinstance(doc.get("playback"), dict) else {}
    is_playing = bool(playback.get("is_playing"))
    now = _now()
    updates = {
        "playback.position_sec": max(0.0, float(payload.position_sec or 0.0)),
        "playback.started_at": now,
        "playback.is_playing": bool(is_playing),
        "updated_at": now,
    }
    await col.update_one({"_id": jam_id}, {"$set": updates})
    await _broadcast_fresh_state(jam_id)
    return {"ok": True}


@router.post("/{jam_id}/queue/add")
async def jam_queue_add(jam_id: str, payload: JamQueueAddRequest, user_id: int = Depends(require_user_id)):
    track_id = _sanitize_track_id(payload.track_id)
    if not track_id:
        raise HTTPException(status_code=400, detail="track_id is required")
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    if not _has_permission(doc, int(user_id), action="queue"):
        raise HTTPException(status_code=403, detail="not allowed")
    queue = doc.get("queue") if isinstance(doc.get("queue"), list) else []
    q2 = [str(x) for x in queue if isinstance(x, str) and x.strip()]
    pos = payload.position
    try:
        pos = int(pos) if pos is not None else None
    except Exception:
        pos = None
    if pos is None or pos < 0 or pos > len(q2):
        q2.append(track_id)
    else:
        q2.insert(int(pos), track_id)
    now = _now()
    await col.update_one({"_id": jam_id}, {"$set": {"queue": q2, "updated_at": now}})
    doc2 = await col.find_one({"_id": jam_id})
    try:
        pb = (doc2 or doc).get("playback") if isinstance((doc2 or doc).get("playback"), dict) else {}
        current = _sanitize_track_id(pb.get("track_id"))
        asyncio.create_task(_warm_tracks([current] + q2[:2]))
    except Exception:
        pass
    await _broadcast_fresh_state(jam_id)
    return {"ok": True}


@router.post("/{jam_id}/queue/reorder")
async def jam_queue_reorder(jam_id: str, payload: JamQueueReorderRequest, user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    if not _has_permission(doc, int(user_id), action="queue"):
        raise HTTPException(status_code=403, detail="not allowed")

    queue = [_sanitize_track_id(x) for x in (payload.queue or []) if _sanitize_track_id(x)]
    now = _now()
    await col.update_one({"_id": jam_id}, {"$set": {"queue": queue, "updated_at": now}})
    doc2 = await col.find_one({"_id": jam_id})
    try:
        pb = (doc2 or doc).get("playback") if isinstance((doc2 or doc).get("playback"), dict) else {}
        current = _sanitize_track_id(pb.get("track_id"))
        asyncio.create_task(_warm_tracks([current] + queue[:2]))
    except Exception:
        pass
    await _broadcast_fresh_state(jam_id)
    return {"ok": True}


@router.post("/{jam_id}/next")
async def jam_next(jam_id: str, user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("jam_sessions").collection
    doc = await col.find_one({"_id": jam_id})
    if not doc:
        raise HTTPException(status_code=404, detail="jam not found")
    if not _has_permission(doc, int(user_id), action="next"):
        raise HTTPException(status_code=403, detail="not allowed")

    queue = doc.get("queue") if isinstance(doc.get("queue"), list) else []
    q2 = [str(x) for x in queue if isinstance(x, str) and x.strip()]
    if not q2:
        raise HTTPException(status_code=400, detail="queue is empty")
    next_track = _sanitize_track_id(q2.pop(0))
    if not next_track:
        raise HTTPException(status_code=400, detail="invalid next track")
    now = _now()
    await col.update_one(
        {"_id": jam_id},
        {
            "$set": {
                "queue": q2,
                "playback.track_id": next_track,
                "playback.position_sec": 0.0,
                "playback.started_at": now,
                "playback.is_playing": True,
                "updated_at": now,
            }
        },
    )
    doc2 = await col.find_one({"_id": jam_id})
    try:
        asyncio.create_task(_warm_tracks([next_track] + q2[:2]))
    except Exception:
        pass
    await _broadcast_fresh_state(jam_id)
    return {"ok": True, "track_id": next_track}


@router.websocket("/{jam_id}/ws")
async def jam_ws(ws: WebSocket, jam_id: str):
    await ws.accept()
    room_id = (jam_id or "").strip() or jam_id
    try:
        auth_payload = _ws_auth_payload(ws)
        uid = _as_int(auth_payload.get("uid"))
        if not uid or uid <= 0:
            raise HTTPException(status_code=401, detail="invalid auth token")
        user_id = int(uid)
        first_name, profile_url = await _resolve_member_meta(user_id=int(user_id), auth_payload=auth_payload)
        doc = await _get_session(jam_id)
        if not doc:
            await ws.send_json({"type": "error", "error": "jam_not_found"})
            await ws.close(code=1008)
            return
        jam_id2 = str(doc["_id"])
        room_id = jam_id2
        await _ensure_member(jam_id=jam_id2, user_id=int(user_id), first_name=first_name, photo_url=profile_url)

        async with _WS_LOCK:
            room = _WS_ROOMS.get(jam_id2)
            if room is None:
                room = set()
                _WS_ROOMS[jam_id2] = room
            room.add(ws)

        await _broadcast_fresh_state(jam_id2)

        while True:
            msg = await ws.receive_json()
            mtype = (msg.get("type") or "").strip()
            if mtype == "ping":
                await ws.send_json({"type": "pong", "t": _now()})
            elif mtype == "get_state":
                doc3 = await _get_session(jam_id2)
                if doc3:
                    await ws.send_json({"type": "jam_state", "jam": _serialize_session(doc3)})
            else:
                await ws.send_json({"type": "error", "error": "unsupported_message"})
    except WebSocketDisconnect:
        pass
    except Exception:
        try:
            await ws.close(code=1011)
        except Exception:
            pass
    finally:
        async with _WS_LOCK:
            room = _WS_ROOMS.get(room_id)
            if room:
                room.discard(ws)
                if not room:
                    _WS_ROOMS.pop(room_id, None)
