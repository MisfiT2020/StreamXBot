import re
import time
from typing import Any

import httpx
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from stream.core.config_manager import Config


router = APIRouter(prefix="", tags=["soundcloud"])

_SC_BASE_URL = "https://soundcloud.com"
_SC_SEARCH_API = "https://api-v2.soundcloud.com/search/tracks"
_SC_TRACK_API = "https://api-v2.soundcloud.com/tracks/{track_id}"

_SC_HEADERS = {
    "User-Agent": "Mozilla/5.0",
    "Accept": "application/json",
    "Referer": "https://soundcloud.com/",
    "Origin": "https://soundcloud.com",
}

_SC_CLIENT_ID: str | None = None
_SC_CLIENT_ID_TS: float = 0.0


class SoundCloudTrackItem(BaseModel):
    id: int
    title: str
    username: str | None = None
    permalink_url: str | None = None
    duration_ms: int | None = None
    duration_sec: int | None = None
    artwork_url: str | None = None
    streamable: bool | None = None
    policy: str | None = None
    hls_api_url: str | None = None
    hls_is_preview: bool | None = None


class SoundCloudSearchResponse(BaseModel):
    ok: bool = True
    q: str
    page: int
    per_page: int
    items: list[SoundCloudTrackItem]


class SoundCloudStreamResponse(BaseModel):
    ok: bool = True
    id: int
    hls_url: str
    is_preview: bool | None = None


async def _get_sc_client_id(client: httpx.AsyncClient) -> str:
    global _SC_CLIENT_ID, _SC_CLIENT_ID_TS
    now = time.time()
    ttl = float(getattr(Config, "SOUNDCLOUD_CLIENT_ID_TTL", 3600) or 3600)
    if _SC_CLIENT_ID and now - _SC_CLIENT_ID_TS < ttl:
        return _SC_CLIENT_ID

    resp = await client.get(_SC_BASE_URL)
    html = resp.text
    js_urls = re.findall(r'src="(https://a-v2\.sndcdn\.com/assets/.*?\.js)"', html)
    client_id: str | None = None
    for js_url in js_urls:
        r2 = await client.get(js_url)
        js = r2.text
        m = re.search(r'client_id\s*:\s*"([a-zA-Z0-9]+)"', js)
        if m:
            client_id = m.group(1)
            break
    if not client_id:
        raise HTTPException(status_code=502, detail="Unable to resolve SoundCloud client_id")
    _SC_CLIENT_ID = client_id
    _SC_CLIENT_ID_TS = now
    return client_id


def _pick_hls_transcoding(track: dict[str, Any]) -> dict[str, Any] | None:
    media = track.get("media") if isinstance(track.get("media"), dict) else {}
    transcodings = media.get("transcodings") if isinstance(media.get("transcodings"), list) else []
    for tr in transcodings:
        if not isinstance(tr, dict):
            continue
        fmt = tr.get("format") if isinstance(tr.get("format"), dict) else {}
        if fmt.get("protocol") == "hls":
            return tr
    return None


def _parse_sc_track(track: dict[str, Any]) -> SoundCloudTrackItem | None:
    try:
        tid = int(track.get("id"))
    except Exception:
        return None
    title = str(track.get("title") or "").strip()
    if not title:
        return None
    user = track.get("user") if isinstance(track.get("user"), dict) else {}
    username = str(user.get("username")).strip() if isinstance(user.get("username"), str) else None
    permalink_url = str(track.get("permalink_url")).strip() if isinstance(track.get("permalink_url"), str) else None

    dur_ms = None
    for key in ("full_duration", "duration"):
        v = track.get(key)
        if isinstance(v, (int, float)) and v > 0:
            dur_ms = int(v)
            break
    if dur_ms is None:
        try:
            v = int(track.get("duration"))
            if v > 0:
                dur_ms = v
        except Exception:
            dur_ms = None
    dur_sec = int(dur_ms / 1000) if dur_ms is not None else None

    artwork_url = str(track.get("artwork_url")).strip() if isinstance(track.get("artwork_url"), str) else None
    streamable = bool(track.get("streamable"))
    policy = str(track.get("policy")).strip() if isinstance(track.get("policy"), str) else None

    hls_api_url: str | None = None
    hls_is_preview: bool | None = None
    tr = _pick_hls_transcoding(track)
    if tr:
        u = tr.get("url")
        if isinstance(u, str) and u.strip():
            hls_api_url = u.strip()
        hls_is_preview = bool(tr.get("snipped")) or ("preview" in hls_api_url if hls_api_url else False)

    return SoundCloudTrackItem(
        id=tid,
        title=title,
        username=username,
        permalink_url=permalink_url,
        duration_ms=dur_ms,
        duration_sec=dur_sec,
        artwork_url=artwork_url,
        streamable=streamable,
        policy=policy,
        hls_api_url=hls_api_url,
        hls_is_preview=hls_is_preview,
    )


@router.get("/soundcloud/search", response_model=SoundCloudSearchResponse)
async def soundcloud_search(
    q: str = Query(min_length=1, max_length=200),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=10, ge=1, le=50),
):
    term = q.strip()
    if not term:
        raise HTTPException(status_code=400, detail="q is required")
    page = int(page)
    limit = int(limit)
    offset = (page - 1) * limit

    async with httpx.AsyncClient(headers=_SC_HEADERS, timeout=15.0, follow_redirects=True) as client:
        client_id = await _get_sc_client_id(client)
        params = {
            "q": term,
            "client_id": client_id,
            "limit": limit,
            "offset": offset,
        }
        resp = await client.get(_SC_SEARCH_API, params=params)
        if resp.status_code != 200:
            try:
                text = resp.text
            except Exception:
                text = ""
            raise HTTPException(status_code=resp.status_code, detail=f"SoundCloud search failed: {text[:200]}")
        data = resp.json()

    coll = data.get("collection") if isinstance(data, dict) else None
    items: list[SoundCloudTrackItem] = []
    if isinstance(coll, list):
        for raw in coll:
            if not isinstance(raw, dict):
                continue
            parsed = _parse_sc_track(raw)
            if parsed:
                items.append(parsed)

    return SoundCloudSearchResponse(ok=True, q=term, page=page, per_page=limit, items=items)


@router.get("/soundcloud/tracks/{track_id}", response_model=SoundCloudTrackItem)
async def soundcloud_track(track_id: int):
    async with httpx.AsyncClient(headers=_SC_HEADERS, timeout=15.0, follow_redirects=True) as client:
        client_id = await _get_sc_client_id(client)
        url = _SC_TRACK_API.format(track_id=int(track_id))
        resp = await client.get(url, params={"client_id": client_id})
        if resp.status_code == 404:
            raise HTTPException(status_code=404, detail="Track not found")
        if resp.status_code != 200:
            try:
                text = resp.text
            except Exception:
                text = ""
            raise HTTPException(status_code=resp.status_code, detail=f"SoundCloud track lookup failed: {text[:200]}")
        data = resp.json()

    if not isinstance(data, dict):
        raise HTTPException(status_code=502, detail="Unexpected SoundCloud track payload")
    parsed = _parse_sc_track(data)
    if not parsed:
        raise HTTPException(status_code=502, detail="Unable to parse SoundCloud track")
    return parsed


@router.get("/soundcloud/tracks/{track_id}/hls", response_model=SoundCloudStreamResponse)
async def soundcloud_track_hls(track_id: int):
    async with httpx.AsyncClient(headers=_SC_HEADERS, timeout=15.0, follow_redirects=True) as client:
        client_id = await _get_sc_client_id(client)
        url = _SC_TRACK_API.format(track_id=int(track_id))
        resp = await client.get(url, params={"client_id": client_id})
        if resp.status_code == 404:
            raise HTTPException(status_code=404, detail="Track not found")
        if resp.status_code != 200:
            try:
                text = resp.text
            except Exception:
                text = ""
            raise HTTPException(status_code=resp.status_code, detail=f"SoundCloud track lookup failed: {text[:200]}")
        data = resp.json()

        if not isinstance(data, dict):
            raise HTTPException(status_code=502, detail="Unexpected SoundCloud track payload")

        hls_tr = _pick_hls_transcoding(data)
        if not hls_tr:
            raise HTTPException(status_code=404, detail="No HLS transcoding available for this track")

        hls_api_url = hls_tr.get("url")
        if not isinstance(hls_api_url, str) or not hls_api_url.strip():
            raise HTTPException(status_code=502, detail="Invalid HLS transcoding URL")

        is_preview = bool(hls_tr.get("snipped")) or ("preview" in hls_api_url)

        r2 = await client.get(hls_api_url.strip(), params={"client_id": client_id})
        if r2.status_code != 200:
            try:
                text2 = r2.text
            except Exception:
                text2 = ""
            raise HTTPException(status_code=r2.status_code, detail=f"SoundCloud HLS manifest failed: {text2[:200]}")

        data2 = r2.json()
        if not isinstance(data2, dict) or not isinstance(data2.get("url"), str) or not data2.get("url").strip():
            raise HTTPException(status_code=502, detail="Unexpected HLS manifest payload")

        hls_url = data2.get("url").strip()

    return SoundCloudStreamResponse(ok=True, id=int(track_id), hls_url=hls_url, is_preview=is_preview)

