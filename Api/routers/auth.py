import base64
import hashlib
import hmac
import os
import re
import time
import random

from fastapi import APIRouter, Depends, HTTPException, Query, Response

from Api.routers.webapp import extract_telegram_user
from Api.schemas.auth import (
    PasswordLoginRequest,
    SetCookieRequest,
    SetCredentialsRequest,
    TgLoginRequest,
    FCMTokenRequest,
    RegisterRequest,
    ValidateOTPRequest,
)
from Api.utils.auth import create_auth_token, require_user_id, verify_auth_token
from stream.core.config_manager import Config
from stream.database.MongoDb import db_handler


router = APIRouter(prefix="/auth", tags=["auth"])


_USERNAME_RE = re.compile(r"^[a-z0-9_\.]{3,32}$", flags=re.I)
_TG_USERNAME_RE = re.compile(r"^[a-z0-9_]{5,32}$", flags=re.I)


def _canon_username(value: str) -> str:
    s = (value or "").strip()
    if not s:
        return ""
    s = s.lower()
    if not _USERNAME_RE.match(s):
        return ""
    return s


def _tg_userpic_url(username: str | None) -> str | None:
    u = (username or "").strip().lstrip("@").strip()
    if not u:
        return None
    if not _TG_USERNAME_RE.match(u):
        return None
    return f"https://t.me/i/userpic/320/{u}.jpg"


async def _get_telegram_profile(user_id: int) -> dict[str, str | None]:
    try:
        from stream import bot
    except Exception:
        bot = None

    if bot is None:
        return {"first_name": None, "telegram_username": None, "profile_url": None, "photo_url": None}

    try:
        u = await bot.get_users(int(user_id))
    except Exception:
        return {"first_name": None, "telegram_username": None, "profile_url": None, "photo_url": None}

    first_name = (getattr(u, "first_name", None) or "").strip() or None
    telegram_username = (getattr(u, "username", None) or "").strip() or None
    photo_url = _tg_userpic_url(telegram_username)
    profile_url = photo_url
    return {
        "first_name": first_name,
        "telegram_username": telegram_username,
        "profile_url": profile_url,
        "photo_url": photo_url,
    }


def _b64e(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _b64d(data: str) -> bytes:
    s = (data or "").strip()
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + pad)


def _hash_password(password: str, *, salt: bytes | None = None, iterations: int = 200_000) -> dict:
    pwd = (password or "").encode("utf-8")
    if not pwd:
        raise HTTPException(status_code=400, detail="password is required")
    salt = os.urandom(16) if salt is None else salt
    dk = hashlib.pbkdf2_hmac("sha256", pwd, salt, int(iterations))
    return {"algo": "pbkdf2_sha256", "salt": _b64e(salt), "iterations": int(iterations), "hash": _b64e(dk)}


def _verify_password(password: str, stored: dict) -> bool:
    if not isinstance(stored, dict):
        return False
    if (stored.get("algo") or "") != "pbkdf2_sha256":
        return False
    try:
        salt = _b64d(str(stored.get("salt") or ""))
        iters = int(stored.get("iterations") or 0)
        expected = _b64d(str(stored.get("hash") or ""))
    except Exception:
        return False
    if not salt or iters <= 0 or not expected:
        return False
    dk = hashlib.pbkdf2_hmac("sha256", (password or "").encode("utf-8"), salt, iters)
    return hmac.compare_digest(dk, expected)


def _is_truthy(value: object) -> bool:
    if isinstance(value, bool):
        return bool(value)
    s = str(value or "").strip().lower()
    return s in {"1", "true", "yes", "on"}

def _normalize_samesite(value: object) -> str | None:
    s = str(value or "").strip().lower()
    if not s:
        return None
    if s in {"lax", "strict", "none"}:
        return s
    return None


def _set_auth_cookie(
    *,
    response: Response,
    token: str,
) -> None:
    debug = _is_truthy(getattr(Config, "DEBUG", False))
    configured_secure = getattr(Config, "COOKIE_SECURE", None)
    configured_samesite = _normalize_samesite(getattr(Config, "COOKIE_SAMESITE", None))
    secure = _is_truthy(configured_secure) if str(configured_secure or "").strip() else (not debug)
    samesite = configured_samesite or ("lax" if debug else "none")
    response.set_cookie(
        key="auth_token",
        value=str(token),
        httponly=True,
        secure=secure,
        samesite=samesite,
        path="/",
        max_age=365 * 24 * 60 * 60,
    )


@router.post("/tg/login")
async def tg_login(
    payload: TgLoginRequest,
    response: Response,
    set_cookie: bool = Query(default=False),
):
    tg = extract_telegram_user(payload.init_data)
    tg_user_id = int(tg["user_id"])
    if tg_user_id <= 0:
        raise HTTPException(status_code=401, detail="invalid telegram user")

    now = time.time()
    updates: dict = {
        "first_name": tg.get("first_name"),
        "photo_url": tg.get("photo_url"),
        "profile_url": tg.get("photo_url"),
        "telegram": {
            "id": tg_user_id,
            "username": tg.get("username"),
        },
        "updated_at": now,
    }
    set_on_insert = {"created_at": now}

    canon = _canon_username(payload.username or "")
    if payload.username is not None:
        if not canon:
            raise HTTPException(status_code=400, detail="invalid username")
        col = db_handler.get_collection("users").collection
        existing = await col.find_one({"username": canon, "_id": {"$ne": tg_user_id}}, {"_id": 1})
        if existing:
            raise HTTPException(status_code=409, detail="username already taken")
        updates["username"] = canon
        updates["username_updated_at"] = now

    if payload.password is not None:
        updates["password"] = _hash_password(payload.password)
        updates["password_updated_at"] = now

    col = db_handler.get_collection("users").collection
    await col.update_one({"_id": tg_user_id}, {"$set": updates, "$setOnInsert": set_on_insert}, upsert=True)

    token = create_auth_token(user_id=tg_user_id, first_name=tg.get("first_name"), profile_url=tg.get("photo_url"))
    if set_cookie:
        _set_auth_cookie(response=response, token=token)
    return {"ok": True, "user_id": tg_user_id, "token": token}


@router.post("/login")
async def password_login(
    payload: PasswordLoginRequest,
    response: Response,
    set_cookie: bool = Query(default=False),
):
    canon = _canon_username(payload.username)
    if not canon:
        raise HTTPException(status_code=400, detail="invalid username")

    col = db_handler.get_collection("users").collection
    doc = await col.find_one(
        {"username": canon},
        {"_id": 1, "password": 1, "first_name": 1, "profile_url": 1, "photo_url": 1, "telegram": 1},
    )
    if not doc:
        raise HTTPException(status_code=401, detail="invalid credentials")
    if not _verify_password(payload.password, doc.get("password") if isinstance(doc.get("password"), dict) else {}):
        raise HTTPException(status_code=401, detail="invalid credentials")

    uid = int(doc["_id"])
    first_name = doc.get("first_name") if isinstance(doc.get("first_name"), str) else None
    profile_url = doc.get("profile_url") if isinstance(doc.get("profile_url"), str) else None
    if not profile_url:
        profile_url = doc.get("photo_url") if isinstance(doc.get("photo_url"), str) else None
    token = create_auth_token(user_id=uid, first_name=first_name, profile_url=profile_url)
    if set_cookie:
        _set_auth_cookie(response=response, token=token)
    return {"ok": True, "user_id": uid, "token": token, "first_name": first_name, "profile_url": profile_url, "photo_url": profile_url}


@router.post("/cookie")
async def set_auth_cookie(
    payload: SetCookieRequest,
    response: Response,
):
    token = (payload.token or "").strip()
    if not token:
        raise HTTPException(status_code=400, detail="token is required")
    verified = verify_auth_token(token)
    uid = int(verified.get("uid") or 0)
    if uid <= 0:
        raise HTTPException(status_code=401, detail="invalid auth token")
    _set_auth_cookie(response=response, token=token)
    return {"ok": True, "user_id": uid}


@router.post("/logout")
async def logout(response: Response):
    debug = _is_truthy(getattr(Config, "DEBUG", False))
    configured_secure = getattr(Config, "COOKIE_SECURE", None)
    configured_samesite = _normalize_samesite(getattr(Config, "COOKIE_SAMESITE", None))
    secure = _is_truthy(configured_secure) if str(configured_secure or "").strip() else (not debug)
    samesite = configured_samesite or ("lax" if debug else "none")
    response.delete_cookie(
        key="auth_token",
        path="/",
        secure=secure,
        samesite=samesite,
    )
    return {"ok": True}


@router.post("/credentials")
async def set_credentials(payload: SetCredentialsRequest, user_id: int = Depends(require_user_id)):
    canon = _canon_username(payload.username)
    if not canon:
        raise HTTPException(status_code=400, detail="invalid username")

    now = time.time()
    col = db_handler.get_collection("users").collection
    existing = await col.find_one({"username": canon, "_id": {"$ne": int(user_id)}}, {"_id": 1})
    if existing:
        raise HTTPException(status_code=409, detail="username already taken")

    await col.update_one(
        {"_id": int(user_id)},
        {
            "$set": {
                "username": canon,
                "password": _hash_password(payload.password),
                "username_updated_at": now,
                "password_updated_at": now,
                "updated_at": now,
            }
        },
        upsert=True,
    )
    return {"ok": True}


@router.get("/me")
async def auth_me(user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("users").collection
    doc = await col.find_one(
        {"_id": int(user_id)},
        {
            "_id": 1,
            "username": 1,
            "first_name": 1,
            "profile_url": 1,
            "photo_url": 1,
            "telegram": 1,
            "created_at": 1,
            "updated_at": 1,
        },
    )
    if not doc:
        return {"ok": True, "user_id": int(user_id)}
    doc["_id"] = int(doc["_id"])
    if not isinstance(doc.get("profile_url"), str) or not doc.get("profile_url"):
        pu = doc.get("photo_url")
        if isinstance(pu, str) and pu:
            doc["profile_url"] = pu
    return {"ok": True, "user": doc}

@router.post("/fcm-token")
async def update_fcm_token(payload: FCMTokenRequest, user_id: int = Depends(require_user_id)):
    col = db_handler.get_collection("users").collection
    await col.update_one(
        {"_id": int(user_id)},
        {"$set": {"fcm_token": payload.fcm_token, "updated_at": time.time()}},
        upsert=True,
    )
    return {"ok": True}

@router.post("/register")
async def register_account(payload: RegisterRequest):
    from stream import bot
    if not bot:
        raise HTTPException(status_code=500, detail="Bot is not running or ONLY_API is true")

    canon = _canon_username(payload.username)
    if not canon:
        raise HTTPException(status_code=400, detail="invalid username")

    col = db_handler.get_collection("users").collection
    existing_user = await col.find_one({"_id": payload.userid})
    if existing_user and existing_user.get("password"):
        raise HTTPException(status_code=409, detail="User already registered")

    existing_username = await col.find_one({"username": canon, "_id": {"$ne": payload.userid}}, {"_id": 1})
    if existing_username:
        raise HTTPException(status_code=409, detail="username already taken")

    otp = str(random.randint(100000, 999999))
    now = time.time()
    otp_col = db_handler.get_collection("registration_otps").collection
    tg_profile = await _get_telegram_profile(int(payload.userid))
    
    await otp_col.update_one(
        {"_id": payload.userid},
        {
            "$set": {
                "otp": otp,
                "username": canon,
                "password": _hash_password(payload.password),
                "first_name": tg_profile.get("first_name"),
                "telegram_username": tg_profile.get("telegram_username"),
                "profile_url": tg_profile.get("profile_url"),
                "photo_url": tg_profile.get("photo_url"),
                "created_at": now
            }
        },
        upsert=True
    )
    
    try:
        await bot.send_message(
            chat_id=payload.userid,
            text=f"Your StreamX registration OTP is: `{otp}`\n\nThis OTP is valid for registration."
        )
    except Exception as e:
        import logging
        logging.getLogger(__name__).error(f"Failed to send OTP to {payload.userid}: {e}")
        raise HTTPException(status_code=500, detail="Failed to send OTP via Telegram bot. Have you started the bot?")

    return {
        "ok": True,
        "message": "OTP sent",
        "user_id": int(payload.userid),
        "username": canon,
        "first_name": tg_profile.get("first_name"),
        "profile_url": tg_profile.get("profile_url"),
        "photo_url": tg_profile.get("photo_url"),
    }

@router.post("/validate")
async def validate_account(
    payload: ValidateOTPRequest,
    response: Response,
    set_cookie: bool = Query(default=False),
):
    otp_col = db_handler.get_collection("registration_otps").collection
    doc = await otp_col.find_one({"_id": payload.userid})
    if not doc:
        raise HTTPException(status_code=400, detail="No pending registration found")

    if doc.get("otp") != payload.otp:
        raise HTTPException(status_code=401, detail="Invalid OTP")

    now = time.time()
    col = db_handler.get_collection("users").collection
    tg_profile = await _get_telegram_profile(int(payload.userid))
    first_name = tg_profile.get("first_name") or (doc.get("first_name") if isinstance(doc.get("first_name"), str) else None)
    telegram_username = tg_profile.get("telegram_username") or (
        doc.get("telegram_username") if isinstance(doc.get("telegram_username"), str) else None
    )
    profile_url = tg_profile.get("profile_url") or (doc.get("profile_url") if isinstance(doc.get("profile_url"), str) else None)
    photo_url = tg_profile.get("photo_url") or (doc.get("photo_url") if isinstance(doc.get("photo_url"), str) else None)
    if not profile_url:
        profile_url = photo_url
    if not photo_url:
        photo_url = profile_url

    updates = {
        "username": doc["username"],
        "password": doc["password"],
        "first_name": first_name,
        "photo_url": photo_url,
        "profile_url": profile_url,
        "profile_refreshed_at": now,
        "username_updated_at": now,
        "password_updated_at": now,
        "updated_at": now,
    }
    set_on_insert = {
        "created_at": now,
        "telegram": {"id": payload.userid, "username": telegram_username}
    }
    if telegram_username:
        updates["telegram"] = {"id": payload.userid, "username": telegram_username}

    await col.update_one(
        {"_id": payload.userid},
        {"$set": updates, "$setOnInsert": set_on_insert},
        upsert=True
    )

    await otp_col.delete_one({"_id": payload.userid})

    token = create_auth_token(user_id=payload.userid, first_name=first_name, profile_url=profile_url, photo_url=photo_url)
    if set_cookie:
        _set_auth_cookie(response=response, token=token)
        
    return {
        "ok": True,
        "user_id": int(payload.userid),
        "username": doc.get("username"),
        "token": token,
        "first_name": first_name,
        "profile_url": profile_url,
        "photo_url": photo_url,
    }
