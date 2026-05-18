from __future__ import annotations

import time
from urllib.parse import parse_qs

from fastapi import HTTPException
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from Api.utils.auth import verify_auth_token

_SETUP_CACHE: dict[str, object] = {"value": None, "expires": 0.0}
_SETUP_CACHE_TTL_SEC = 10.0

# Path prefixes that REQUIRE a valid auth token.
# Anything not matching this list is allowed through (SPA routes, static
# files, public endpoints, the /auth/* login flow, /health, etc.).
_PROTECTED_PREFIXES: tuple[str, ...] = (
    "/tracks",
    "/albums",
    "/artists",
    "/search",
    "/browse",
    "/me",
    "/jam",
    "/friends",
    "/notifications",
    "/admin",
    "/logs",
    "/youtube",
    "/soundcloud",
    "/test",
    "/daily-playlist",
    "/channelids",
    "/playlists/available",
    "/covers/user-playlist",
)


def _path_is_protected(path: str) -> bool:
    p = (path or "/").rstrip("/") or "/"
    for prefix in _PROTECTED_PREFIXES:
        if p == prefix or p.startswith(prefix + "/"):
            return True
    return False


def _extract_token(request: Request) -> str:
    auth_header = (request.headers.get("authorization") or "").strip()
    if auth_header:
        if auth_header.lower().startswith("bearer "):
            return auth_header[7:].strip()
        return auth_header
    x_auth = (request.headers.get("x-auth-token") or "").strip()
    if x_auth:
        return x_auth
    cookie_token = (request.cookies.get("auth_token") or "").strip()
    if cookie_token:
        return cookie_token
    cookie_token = (request.cookies.get("token") or "").strip()
    if cookie_token:
        return cookie_token
    query_token = (request.query_params.get("token") or "").strip()
    if query_token:
        return query_token
    raw_query = str(request.url.query or "")
    if raw_query:
        parsed = parse_qs(raw_query)
        vals = parsed.get("token")
        if vals:
            token_from_query = str(vals[0]).strip()
            if token_from_query:
                return token_from_query
    return ""  


async def _owner_password_exists() -> bool:
    now = time.time()
    if _SETUP_CACHE["expires"] > now and _SETUP_CACHE["value"] is not None:
        return bool(_SETUP_CACHE["value"])

    from stream.database.MongoDb import db_handler

    col = db_handler.get_collection("auth_config").collection
    doc = await col.find_one({"_id": "owner_password"}, {"password": 1})
    stored = doc.get("password") if isinstance(doc, dict) else None
    exists = isinstance(stored, dict) and bool(stored)
    _SETUP_CACHE["value"] = exists
    _SETUP_CACHE["expires"] = now + _SETUP_CACHE_TTL_SEC
    return exists


class AuthMiddleware(BaseHTTPMiddleware):
    """Enforces bearer/cookie auth on protected API routes.

    During first-run (owner password not yet configured), protected API
    routes return 503 so the frontend can redirect to the setup screen.
    Public/static/auth routes are excluded. CORS preflight (OPTIONS) is
    always allowed through so the CORSMiddleware can handle it.
    """

    async def dispatch(self, request: Request, call_next) -> Response:
        if request.method == "OPTIONS":
            return await call_next(request)

        path = request.url.path or "/"
        if not _path_is_protected(path):
            return await call_next(request)

        if not await _owner_password_exists():
            return JSONResponse(
                status_code=503,
                content={"ok": False, "detail": "setup required"},
            )

        token = _extract_token(request)
        if not token:
            return JSONResponse(
                status_code=401,
                content={"ok": False, "detail": "missing auth token"},
            )

        try:
            verify_auth_token(token)
        except HTTPException as e:
            return JSONResponse(
                status_code=int(e.status_code),
                content={"ok": False, "detail": str(e.detail)},
            )
        except Exception:
            return JSONResponse(
                status_code=401,
                content={"ok": False, "detail": "invalid auth token"},
            )

        return await call_next(request)
