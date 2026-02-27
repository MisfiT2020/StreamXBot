import asyncio
import time
from typing import Any

import httpx
import json

from stream.core.config_manager import Config

_BASE_URL = "https://apic.musixmatch.com/ws/1.1"
_APP_ID = "mac-ios-v2.0"

_token_lock = asyncio.Lock()
_cached_user_token: str | None = None
_cached_user_token_ts: float = 0.0

_DEFAULT_HEADERS = {
    "Accept": "application/json, text/plain;q=0.9, */*;q=0.8",
    "User-Agent": "Mozilla/5.0",
}

def _safe_json(resp: httpx.Response) -> Any:
    try:
        return resp.json()
    except Exception:
        pass
    try:
        text = resp.content.decode("utf-8-sig", errors="replace")
        return json.loads(text)
    except Exception:
        return {}


def _sync_get_json(*, url: str, params: dict[str, Any]) -> tuple[int, Any]:
    with httpx.Client(timeout=10, follow_redirects=True, headers=_DEFAULT_HEADERS) as client:
        r = client.get(url, params=params)
    return r.status_code, _safe_json(r)


def _extract_header_status(payload: Any) -> int | None:
    try:
        v = (((payload or {}).get("message") or {}).get("header") or {}).get("status_code")
        return int(v)
    except Exception:
        return None


def _pick_spotify_track_id(track: dict) -> str | None:
    spotify = track.get("spotify") if isinstance(track.get("spotify"), dict) else {}
    for k in ("track_spotify_id", "spotify_track_id", "spotify_id", "track_id", "id"):
        v = spotify.get(k)
        if isinstance(v, str) and v.strip():
            return v.strip()

    url = spotify.get("url") or spotify.get("spotify_url")
    if isinstance(url, str) and url.strip():
        s = url.strip()
        marker = "/track/"
        if marker in s:
            tail = s.split(marker, 1)[1]
            tid = tail.split("?", 1)[0].split("/", 1)[0].strip()
            if tid:
                return tid
    return None


def _lrc_timestamp(total_seconds: float) -> str:
    try:
        t = float(total_seconds)
    except Exception:
        t = 0.0
    if t < 0:
        t = 0.0
    mm = int(t // 60)
    sec_f = t - (mm * 60)
    ss = int(sec_f // 1)
    hh = int(round((sec_f - ss) * 100))
    if hh >= 100:
        hh = 0
        ss += 1
    if ss >= 60:
        ss = 0
        mm += 1
    return f"[{mm:02d}:{ss:02d}.{hh:02d}]"


def _subtitles_json_to_lrc(subtitle_body: str) -> str | None:
    s = (subtitle_body or "").strip()
    if not s:
        return None
    try:
        items = json.loads(s)
    except Exception:
        return None
    if not isinstance(items, list) or not items:
        return None
    out: list[str] = []
    for it in items:
        if not isinstance(it, dict):
            continue
        text = it.get("text")
        if not isinstance(text, str):
            text = ""
        time_obj = it.get("time") if isinstance(it.get("time"), dict) else {}
        total = time_obj.get("total")
        ts = _lrc_timestamp(float(total) if total is not None else 0.0)
        text = text.rstrip()
        if text:
            out.append(f"{ts} {text}")
        else:
            out.append(ts)
    joined = "\n".join(out).strip()
    return joined or None


def _extract_plain_lyrics(payload: Any) -> str | None:
    try:
        macro = (((payload or {}).get("message") or {}).get("body") or {}).get("macro_calls") or {}
        lyr = macro.get("track.lyrics.get") or {}
        body = ((lyr.get("message") or {}).get("body") or {}).get("lyrics") or {}
        text = (body.get("lyrics_body") or "").strip()
        if not text:
            return None
        lines = text.splitlines()
        cleaned: list[str] = []
        for line in lines:
            if line.strip().startswith("***") and "This Lyrics" in line:
                break
            cleaned.append(line.rstrip())
        out = "\n".join(cleaned).strip()
        return out or None
    except Exception:
        return None


def _extract_synced_subtitles(payload: Any) -> str | None:
    try:
        macro = (((payload or {}).get("message") or {}).get("body") or {}).get("macro_calls") or {}
        sub = macro.get("track.subtitles.get") or {}
        body = ((sub.get("message") or {}).get("body") or {})
        subtitle_list = body.get("subtitle_list") or []
        if not isinstance(subtitle_list, list) or not subtitle_list:
            return None
        first = subtitle_list[0] if isinstance(subtitle_list[0], dict) else {}
        subtitle = first.get("subtitle") if isinstance(first.get("subtitle"), dict) else {}
        text = (subtitle.get("subtitle_body") or "").strip()
        return _subtitles_json_to_lrc(text)
    except Exception:
        return None


async def _fetch_user_token(*, force_refresh: bool = False) -> str | None:
    global _cached_user_token, _cached_user_token_ts

    now = time.time()
    if not force_refresh and _cached_user_token and (now - _cached_user_token_ts) < 6 * 3600:
        return _cached_user_token

    async with _token_lock:
        now = time.time()
        if not force_refresh and _cached_user_token and (now - _cached_user_token_ts) < 6 * 3600:
            return _cached_user_token

        http_status, payload = await asyncio.to_thread(
            _sync_get_json,
            url=f"{_BASE_URL}/token.get",
            params={"app_id": _APP_ID},
        )
        if http_status != 200:
            return None

        mxm_status = _extract_header_status(payload) or http_status
        if mxm_status != 200:
            return None
        token = (
            (((payload or {}).get("message") or {}).get("body") or {}).get("user_token") or ""
        ).strip()
        if not token:
            return None

        _cached_user_token = token
        _cached_user_token_ts = time.time()
        return token


async def fetch_track_lyrics_from_musixmatch(*, track: dict) -> dict:
    spotify_track_id = _pick_spotify_track_id(track)
    if not spotify_track_id:
        audio = track.get("audio") if isinstance(track.get("audio"), dict) else {}
        telegram = track.get("telegram") if isinstance(track.get("telegram"), dict) else {}
        title = (audio.get("title") or "").strip() or (telegram.get("title") or "").strip()
        artist = (audio.get("artist") or "").strip() or (audio.get("performer") or "").strip() or (telegram.get("artist") or "").strip()
        album = (audio.get("album") or "").strip() or (telegram.get("album") or "").strip()
        year = audio.get("year")
        try:
            from stream.helpers.cover_search import spotify_best_track

            sp = await spotify_best_track(title=title, artist=artist, album=album, year=year)
        except Exception:
            sp = None

        if isinstance(sp, dict):
            spotify_track_id = (sp.get("id") or "").strip() if isinstance(sp.get("id"), str) else ""
            ext = sp.get("external_urls") if isinstance(sp.get("external_urls"), dict) else {}
            spotify_url = (ext.get("spotify") or "").strip() if isinstance(ext.get("spotify"), str) else ""
            if spotify_track_id:
                spotify = track.get("spotify") if isinstance(track.get("spotify"), dict) else {}
                spotify["track_spotify_id"] = spotify_track_id
                if spotify_url:
                    spotify["url"] = spotify_url
                track["spotify"] = spotify

        if not spotify_track_id:
            return {"ok": False, "error": "missing_spotify_track_id"}

    token = await _fetch_user_token(force_refresh=False)
    if not token:
        return {"ok": False, "error": "token_failed"}

    params = {
        "usertoken": token,
        "app_id": _APP_ID,
        "subtitle_format": "mxm",
        "track_spotify_id": spotify_track_id,
    }

    async def _call() -> tuple[int, Any]:
        return await asyncio.to_thread(
            _sync_get_json,
            url=f"{_BASE_URL}/macro.subtitles.get",
            params=params,
        )

    http_status, payload = await _call()
    mxm_status = _extract_header_status(payload) or http_status

    if mxm_status == 400 or http_status in (400, 401, 403):
        token2 = await _fetch_user_token(force_refresh=True)
        if token2:
            params["usertoken"] = token2
            http_status, payload = await _call()
            mxm_status = _extract_header_status(payload) or http_status

    debug_payload = None
    if bool(getattr(Config, "DEBUG", False)):
        debug_payload = payload

    if mxm_status != 200:
        return {
            "ok": False,
            "error": "mxm_failed",
            "status_code": int(mxm_status),
            "spotify_track_id": spotify_track_id,
            **({"musixmatch": debug_payload} if debug_payload is not None else {}),
        }

    synced = _extract_synced_subtitles(payload)
    if synced:
        return {
            "ok": True,
            "lyrics": synced,
            "kind": "synced",
            "source": "musixmatch",
            "spotify_track_id": spotify_track_id,
            **({"musixmatch": debug_payload} if debug_payload is not None else {}),
        }

    plain = _extract_plain_lyrics(payload)
    if plain:
        return {
            "ok": True,
            "lyrics": plain,
            "kind": "plain",
            "source": "musixmatch",
            "spotify_track_id": spotify_track_id,
            **({"musixmatch": debug_payload} if debug_payload is not None else {}),
        }

    return {
        "ok": False,
        "error": "no_lyrics",
        "spotify_track_id": spotify_track_id,
        **({"musixmatch": debug_payload} if debug_payload is not None else {}),
    }
