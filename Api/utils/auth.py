import base64
import hashlib
import hmac
import json
import time

from fastapi import Depends, Header, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from stream.core.config_manager import Config

_bearer_scheme = HTTPBearer(auto_error=False)


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(data: str) -> bytes:
    s = (data or "").strip()
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + pad)


def _get_secret_key_bytes() -> bytes:
    secret = (getattr(Config, "SECRET_KEY", "") or "").strip()
    if not secret:
        raise HTTPException(status_code=500, detail="SECRET_KEY is missing")
    return secret.encode("utf-8")


def create_auth_token(
    *,
    user_id: int,
    ttl_sec: int = 365 * 24 * 60 * 60,
    first_name: str | None = None,
    photo_url: str | None = None,
    profile_url: str | None = None,
) -> str:
    uid = int(user_id)
    if uid <= 0:
        raise HTTPException(status_code=400, detail="user_id must be a positive int")

    now = int(time.time())
    payload: dict[str, object] = {"uid": uid, "iat": now, "exp": now + int(ttl_sec)}
    fn = (first_name or "").strip()
    if fn:
        payload["first_name"] = fn
    pu = (profile_url or photo_url or "").strip()
    if pu:
        payload["profile_url"] = pu
        payload["photo_url"] = pu
    payload_json = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    payload_b64 = _b64url_encode(payload_json)

    secret = _get_secret_key_bytes()
    sig = hmac.new(secret, payload_b64.encode("utf-8"), hashlib.sha256).digest()
    sig_b64 = _b64url_encode(sig)
    return f"v1.{payload_b64}.{sig_b64}"


def verify_auth_token(token: str) -> dict:
    raw = (token or "").strip()
    if not raw:
        raise HTTPException(status_code=401, detail="missing auth token")

    if raw.lower().startswith("bearer "):
        raw = raw[7:].strip()

    parts = raw.split(".")
    if len(parts) != 3 or parts[0] != "v1":
        raise HTTPException(status_code=401, detail="invalid auth token")

    payload_b64 = parts[1].strip()
    sig_b64 = parts[2].strip()

    secret = _get_secret_key_bytes()
    expected_sig = hmac.new(secret, payload_b64.encode("utf-8"), hashlib.sha256).digest()
    expected_sig_b64 = _b64url_encode(expected_sig)
    if not hmac.compare_digest(expected_sig_b64, sig_b64):
        raise HTTPException(status_code=401, detail="invalid auth token")

    try:
        payload = json.loads(_b64url_decode(payload_b64).decode("utf-8"))
    except Exception:
        raise HTTPException(status_code=401, detail="invalid auth token")

    uid = payload.get("uid")
    exp = payload.get("exp")
    try:
        uid = int(uid)
        exp = int(exp) if exp is not None else 0
    except Exception:
        raise HTTPException(status_code=401, detail="invalid auth token")

    if uid <= 0:
        raise HTTPException(status_code=401, detail="invalid auth token")

    if exp and int(time.time()) > exp:
        raise HTTPException(status_code=401, detail="auth token expired")

    return payload


def require_user_id(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
    x_auth_token: str | None = Header(default=None, alias="X-Auth-Token"),
) -> int:
    token = ""
    if credentials is not None and (credentials.credentials or "").strip():
        token = (credentials.credentials or "").strip()
    elif (x_auth_token or "").strip():
        token = (x_auth_token or "").strip()
    else:
        token = (request.cookies.get("auth_token") or "").strip()
        if not token:
            token = (request.cookies.get("token") or "").strip()
    payload = verify_auth_token(token)
    return int(payload["uid"])
