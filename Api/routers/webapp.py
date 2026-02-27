import hmac
import hashlib
import json
import time
from urllib.parse import parse_qsl

from fastapi import APIRouter, Form, HTTPException
from Api.utils.auth import create_auth_token
from stream.core.config_manager import Config
from stream.database.MongoDb import db_handler


router = APIRouter(prefix="/webapp", tags=["webapp"])


def extract_telegram_user(init_data: str) -> dict:
    try:
        data = dict(parse_qsl(init_data))
        received_hash = data.pop("hash")
        data_check_string = "\n".join(f"{k}={v}" for k, v in sorted(data.items()))

        bot_token = (getattr(Config, "BOT_TOKEN", "") or "").strip()
        if not bot_token:
            raise ValueError("BOT_TOKEN missing")

        secret_key = hmac.new(b"WebAppData", bot_token.encode(), hashlib.sha256).digest()
        calculated_hash = hmac.new(secret_key, data_check_string.encode(), hashlib.sha256).hexdigest()
        if calculated_hash != received_hash:
            raise ValueError("Invalid Telegram signature")

        user = json.loads(data["user"])
        profile_url = user.get("photo_url")
        return {
            "user_id": user["id"],
            "username": user.get("username"),
            "first_name": user.get("first_name"),
            "photo_url": profile_url,
            "profile_url": profile_url,
        }
    except Exception as e:
        raise HTTPException(status_code=401, detail=str(e))


@router.post("/verify")
async def webapp_verify(init_data: str = Form(...)):
    user = extract_telegram_user(init_data)
    uid = int(user["user_id"])
    now = time.time()
    col = db_handler.get_collection("users").collection
    await col.update_one(
        {"_id": uid},
        {
            "$set": {
                "first_name": user.get("first_name"),
                "photo_url": user.get("photo_url"),
                "profile_url": user.get("profile_url") or user.get("photo_url"),
                "telegram": {
                    "id": uid,
                    "username": user.get("username"),
                },
                "updated_at": now,
            },
            "$setOnInsert": {"created_at": now},
        },
        upsert=True,
    )
    token = create_auth_token(
        user_id=uid,
        first_name=user.get("first_name"),
        photo_url=user.get("photo_url"),
        profile_url=user.get("profile_url"),
    )
    return {**user, "token": token}

