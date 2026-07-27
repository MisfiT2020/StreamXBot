import asyncio
import json
import re
import time
from urllib.parse import quote

from aiohttp import ClientSession

from stream.core.config_manager import Config
from stream.helpers.hoaders import hoaders_big_cover_url, hoaders_cover_info

_SPOTIFY_TOKEN: str | None = None
_SPOTIFY_TOKEN_EXPIRES_AT: float = 0.0
_SPOTIFY_SEM = asyncio.Semaphore(3)


def _dbg(msg: str) -> None:
    if bool(getattr(Config, "DEBUG", False)):
        print(msg)


async def _json_or_none(resp, *, label: str) -> dict | None:
    text = await resp.text()
    if not (text or "").strip():
        _dbg(f"[cover] {label} empty_response status={resp.status}")
        return None
    try:
        payload = json.loads(text)
    except Exception as e:
        _dbg(
            f"[cover] {label} bad_json status={resp.status} err={e!r} body={text[:300]!r}"
        )
        return None
    return payload if isinstance(payload, dict) else None


def _strip_query_noise(text: str) -> str:
    s = (text or "").strip()
    if not s:
        return ""
    s = re.sub(r"[\[\(].*?[\]\)]", " ", s)
    s = re.sub(
        r"\b(?:official|audio|video|lyrics?|lyric|mv|visualizer|remaster(?:ed)?|hd|explicit|clean|version|edit)\b",
        " ",
        s,
        flags=re.I,
    )
    s = re.sub(r"\s+", " ", s).strip()
    return s


def _norm_cmp(text: str) -> str:
    s = (text or "").casefold()
    s = re.sub(r"[\[\(].*?[\]\)]", " ", s)
    s = re.sub(r"[^\w\s]+", " ", s, flags=re.UNICODE)
    s = re.sub(r"\s+", " ", s).strip()
    return s


async def _spotify_get_access_token() -> str:
    global _SPOTIFY_TOKEN, _SPOTIFY_TOKEN_EXPIRES_AT

    now = time.time()
    if _SPOTIFY_TOKEN and now < (_SPOTIFY_TOKEN_EXPIRES_AT - 60):
        return _SPOTIFY_TOKEN

    client_id = (getattr(Config, "SPOTIFY_CLIENT_ID", "") or "").strip()
    client_secret = (getattr(Config, "SPOTIFY_CLIENT_SECRET", "") or "").strip()
    if not client_id or not client_secret:
        raise RuntimeError("Spotify credentials missing")

    data = {
        "grant_type": "client_credentials",
        "client_id": client_id,
        "client_secret": client_secret,
    }

    async with _SPOTIFY_SEM:
        async with ClientSession() as session:
            async with session.post("https://accounts.spotify.com/api/token", data=data) as resp:
                payload = await _json_or_none(resp, label="spotify_token")
                if resp.status != 200:
                    raise RuntimeError(f"Spotify token error: {payload}")
                if payload is None:
                    raise RuntimeError("Spotify token response was not JSON")

    token = (payload.get("access_token") or "").strip()
    expires_in = int(payload.get("expires_in") or 3600)
    if not token:
        raise RuntimeError("Spotify token missing")

    _SPOTIFY_TOKEN = token
    _SPOTIFY_TOKEN_EXPIRES_AT = time.time() + expires_in
    return token


def _spotify_cover_from_track(track: dict) -> str | None:
    album = track.get("album") if isinstance(track.get("album"), dict) else {}
    images = album.get("images") if isinstance(album.get("images"), list) else []
    for img in images:
        if isinstance(img, dict) and img.get("url"):
            return str(img.get("url")).strip() or None
    return None


def _spotify_cover_from_album(album: dict) -> str | None:
    images = album.get("images") if isinstance(album.get("images"), list) else []
    for img in images:
        if isinstance(img, dict) and img.get("url"):
            return str(img.get("url")).strip() or None
    return None


def _spotify_album_score(album: dict, *, artist: str, album_name: str, year: int | None = None) -> int:
    want_artist = _norm_cmp(artist)
    want_album = _norm_cmp(album_name)

    name = _norm_cmp(album.get("name") or "")
    artists = album.get("artists") if isinstance(album.get("artists"), list) else []
    first_artist = ""
    for a in artists:
        if isinstance(a, dict) and a.get("name"):
            first_artist = str(a.get("name"))
            break
    got_artist = _norm_cmp(first_artist)

    score = 0
    if want_album:
        score += 8 if name == want_album else (4 if want_album in name or name in want_album else 0)
    if want_artist:
        score += 4 if got_artist == want_artist else (2 if want_artist in got_artist or got_artist in want_artist else 0)
    if year is not None:
        release_date = str(album.get("release_date") or "").strip()
        if len(release_date) >= 4 and release_date[:4].isdigit():
            try:
                score += 2 if int(release_date[:4]) == int(year) else 0
            except Exception:
                pass
    return score


async def spotify_album_cover_url(*, artist: str, album: str, year: int | None = None) -> str | None:
    a = _strip_query_noise(artist)
    al = _strip_query_noise(album)
    if not a or not al:
        return None

    parts = [
        f'album:"{al}" artist:"{a}"',
        f'album:"{al}"',
        f"{al} {a}",
    ]
    if year:
        parts.insert(1, f'album:"{al}" year:{int(year)}')

    try:
        token = await _spotify_get_access_token()
    except Exception as e:
        _dbg(f"[cover] spotify_album token_failed err={e!r}")
        return None
    headers = {"Authorization": f"Bearer {token}"}

    seen: set[str] = set()
    for q in parts:
        k = (q or "").strip().casefold()
        if not k or k in seen:
            continue
        seen.add(k)
        _dbg(f"[cover] spotify_album query={q!r}")
        url = f"https://api.spotify.com/v1/search?q={quote(q)}&type=album&limit=5"
        async with _SPOTIFY_SEM:
            async with ClientSession() as session:
                async with session.get(url, headers=headers) as resp:
                    payload = await _json_or_none(resp, label="spotify_album")
                    if payload is None:
                        continue
                    if resp.status != 200:
                        _dbg(f"[cover] spotify_album error status={resp.status} body={str(payload)[:300]!r}")
                        continue

        items = (((payload or {}).get("albums") or {}).get("items") or [])
        if not items:
            continue

        best = None
        best_score = -1
        for it in items:
            if not isinstance(it, dict):
                continue
            s = _spotify_album_score(it, artist=a, album_name=al, year=year)
            if s > best_score:
                best = it
                best_score = s

        picked = best or (items[0] if items else None)
        if not isinstance(picked, dict):
            continue

        cover = _spotify_cover_from_album(picked)
        if cover:
            _dbg(f"[cover] spotify_album match_found url={cover!r}")
            return cover

    return None


def _spotify_track_score(track: dict, *, title: str, artist: str, album: str) -> int:
    want_title = _norm_cmp(title)
    want_artist = _norm_cmp(artist)
    want_album = _norm_cmp(album)

    name = _norm_cmp(track.get("name") or "")
    artists = track.get("artists") if isinstance(track.get("artists"), list) else []
    first_artist = ""
    for a in artists:
        if isinstance(a, dict) and a.get("name"):
            first_artist = str(a.get("name"))
            break
    got_artist = _norm_cmp(first_artist)

    alb = track.get("album") if isinstance(track.get("album"), dict) else {}
    got_album = _norm_cmp(alb.get("name") or "")

    score = 0
    if want_title:
        score += 6 if name == want_title else (3 if want_title in name else 0)
    if want_artist:
        score += 6 if got_artist == want_artist else (3 if want_artist in got_artist or got_artist in want_artist else 0)
    if want_album:
        score += 3 if got_album == want_album else (1 if want_album and want_album in got_album else 0)
    return score


async def spotify_best_track(*, title: str, artist: str, album: str = "", year: int | None = None) -> dict | None:
    t = _strip_query_noise(title)
    a = _strip_query_noise(artist)
    al = _strip_query_noise(album)

    if not t:
        return None

    parts: list[str] = []
    if al:
        parts.append(f'track:"{t}" album:"{al}"')
        parts.append(f"{t} {al}")
    if year:
        parts.append(f'track:"{t}" year:{int(year)}')
    if a:
        parts.append(f'track:"{t}" artist:"{a}"')
    parts.append(f'track:"{t}"')

    try:
        token = await _spotify_get_access_token()
    except Exception as e:
        _dbg(f"[cover] spotify_track token_failed err={e!r}")
        return None
    headers = {"Authorization": f"Bearer {token}"}

    seen: set[str] = set()
    for q in parts:
        k = (q or "").strip().casefold()
        if not k or k in seen:
            continue
        seen.add(k)
        _dbg(f"[cover] spotify_track query={q!r}")
        url = f"https://api.spotify.com/v1/search?q={quote(q)}&type=track&limit=5"
        async with _SPOTIFY_SEM:
            async with ClientSession() as session:
                async with session.get(url, headers=headers) as resp:
                    payload = await _json_or_none(resp, label="spotify_track")
                    if payload is None:
                        continue
                    if resp.status != 200:
                        _dbg(f"[cover] spotify_track error status={resp.status} body={str(payload)[:300]!r}")
                        continue

        items = (((payload or {}).get("tracks") or {}).get("items") or [])
        if not items:
            continue

        best = None
        best_score = -1
        for tr in items:
            if not isinstance(tr, dict):
                continue
            s = _spotify_track_score(tr, title=t, artist=a, album=al)
            if s > best_score:
                best = tr
                best_score = s

        picked = best or (items[0] if items else None)
        if isinstance(picked, dict):
            return picked

    return None


async def spotify_cover_url(*, title: str, artist: str, album: str = "", year: int | None = None) -> str | None:
    t = _strip_query_noise(title)
    a = _strip_query_noise(artist)
    al = _strip_query_noise(album)

    if not t:
        return None

    parts: list[str] = []
    if al:
        parts.append(f'track:"{t}" album:"{al}"')
        parts.append(f"{t} {al}")
    if year:
        parts.append(f'track:"{t}" year:{int(year)}')
    parts.append(f'track:"{t}"')

    tr = await spotify_best_track(title=title, artist=artist, album=album, year=year)
    if not isinstance(tr, dict):
        return None
    cover = _spotify_cover_from_track(tr)
    if cover:
        _dbg(f"[cover] spotify match_found url={cover!r}")
        return cover
    return None


def _apple_artwork_upgrade(url: str) -> str:
    u = (url or "").strip()
    if not u:
        return ""
    u = re.sub(r"/\d+x\d+bb\.", "/3000x3000bb.", u)
    u = re.sub(r"/\d+x\d+\.", "/3000x3000.", u)
    return u


def _apple_score(item: dict, *, title: str, artist: str) -> int:
    want_t = _norm_cmp(title)
    want_a = _norm_cmp(artist)
    got_t = _norm_cmp(item.get("trackName") or "")
    got_a = _norm_cmp(item.get("artistName") or "")

    score = 0
    if want_t:
        score += 6 if got_t == want_t else (3 if want_t in got_t else 0)
    if want_a:
        score += 6 if got_a == want_a else (3 if want_a in got_a or got_a in want_a else 0)
    return score


async def apple_cover_url(*, title: str, artist: str, album: str = "", year: int | None = None) -> str | None:
    t = _strip_query_noise(title)
    a = _strip_query_noise(artist)
    al = _strip_query_noise(album)
    if not a:
        return None

    terms: list[str] = []
    if al:
        terms.append(f"{al} {a}".strip())
        terms.append(al)
        if t:
            terms.append(f"{t} {al}".strip())
    if t:
        terms.append(t)
    if year:
        if t:
            terms.append(f"{t} {int(year)}".strip())

    best_art = None
    best_score = -1

    seen: set[str] = set()
    for term in terms:
        k = (term or "").strip().casefold()
        if not k or k in seen:
            continue
        seen.add(k)

        url = f"https://itunes.apple.com/search?term={quote(term)}&entity=song&limit=5"
        _dbg(f"[cover] apple query={term!r}")
        async with ClientSession() as session:
            async with session.get(url) as resp:
                payload = await _json_or_none(resp, label="apple")
                if payload is None:
                    continue
                if resp.status != 200:
                    _dbg(f"[cover] apple error status={resp.status} body={str(payload)[:300]!r}")
                    continue

        results = (payload or {}).get("results") or []
        if not isinstance(results, list) or not results:
            continue

        for it in results:
            if not isinstance(it, dict):
                continue
            s = _apple_score(it, title=t, artist=a)
            if s <= best_score:
                continue
            art = it.get("artworkUrl100") or it.get("artworkUrl60") or ""
            art = _apple_artwork_upgrade(str(art))
            if not art:
                continue
            best_art = art
            best_score = s

        if best_art and best_score >= 9:
            break

    if best_art:
        _dbg(f"[cover] apple match_found url={best_art!r}")
        return best_art
    return None


async def deezer_cover_url(*, title: str, artist: str, album: str = "", year: int | None = None) -> str | None:
    t = _strip_query_noise(title)
    a = _strip_query_noise(artist)
    al = _strip_query_noise(album)
    if not a:
        return None

    queries: list[str] = []
    if al:
        queries.append(f"{al} {a}".strip())
        queries.append(al)
        if t:
            queries.append(f"{t} {al}".strip())
    if t:
        queries.append(t)
    if year:
        if t:
            queries.append(f"{t} {int(year)}".strip())

    seen: set[str] = set()
    for q in queries:
        k = (q or "").strip().casefold()
        if not k or k in seen:
            continue
        seen.add(k)

        url = f"https://api.deezer.com/search?q={quote(q)}&limit=3"
        _dbg(f"[cover] deezer query={q!r}")
        async with ClientSession() as session:
            async with session.get(url) as resp:
                payload = await _json_or_none(resp, label="deezer")
                if payload is None:
                    continue
                if resp.status != 200:
                    _dbg(f"[cover] deezer error status={resp.status} body={str(payload)[:300]!r}")
                    continue

        data = (payload or {}).get("data") or []
        if not isinstance(data, list) or not data:
            continue

        first = data[0] if isinstance(data[0], dict) else None
        if not isinstance(first, dict):
            continue
        alb = first.get("album") if isinstance(first.get("album"), dict) else {}
        cover = alb.get("cover_xl") or alb.get("cover_big") or alb.get("cover_medium") or ""
        cover = str(cover).strip()
        if cover:
            _dbg(f"[cover] deezer match_found url={cover!r}")
            return cover

    return None


async def fetch_artist_avatar_info(artist_name: str) -> dict | None:
    if not artist_name or not artist_name.strip():
        return None

    name = artist_name.strip()
    slug = re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')
    if not slug:
        return None

    try:
        from stream.database.MongoDb import db_handler
        col = db_handler.get_collection("artist_profiles").collection
        existing = await col.find_one({"_id": slug})
        if existing and existing.get("avatar_url"):
            return existing
    except Exception:
        col = None

    avatar_url = None
    artist_id = None
    link = None

    try:
        url = f"https://api.deezer.com/search/artist?q={quote(name)}"
        async with ClientSession() as session:
            async with session.get(url, timeout=10) as resp:
                if resp.status == 200:
                    payload = await resp.json()
                    data = (payload or {}).get("data") or []
                    if data and isinstance(data[0], dict):
                        first = data[0]
                        avatar_url = first.get("picture_xl") or first.get("picture_big") or first.get("picture_medium")
                        # Reject the default/empty Deezer avatar
                        if avatar_url and "cdn-images.dzcdn.net/images/artist//" in avatar_url:
                            avatar_url = None
                        artist_id = first.get("id")
                        link = first.get("link")
    except Exception as e:
        _dbg(f"[artist] deezer search failed for {name!r}: {e}")

    if not avatar_url:
        try:
            itunes_url = f"https://itunes.apple.com/search?term={quote(name)}&entity=musicArtist&limit=1"
            async with ClientSession() as session:
                async with session.get(itunes_url, timeout=10) as resp:
                    if resp.status == 200:
                        payload = await resp.json()
                        results = (payload or {}).get("results") or []
                        if results and isinstance(results[0], dict):
                            first = results[0]
                            artist_id = first.get("artistId")
                            artist_link = first.get("artistLinkUrl")
                            if artist_link:
                                link = artist_link
                                headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
                                async with session.get(artist_link, headers=headers, timeout=10) as page_resp:
                                    if page_resp.status == 200:
                                        html = await page_resp.text()
                                        match = re.search(r'property="og:image"\s+content="([^"]+)"', html)
                                        if not match:
                                            match = re.search(r'content="([^"]+)"\s+property="og:image"', html)
                                        if not match:
                                            match = re.search(r'name="twitter:image"\s+content="([^"]+)"', html)
                                        if match:
                                            avatar_url = match.group(1)
        except Exception as e:
            _dbg(f"[artist] itunes search failed for {name!r}: {e}")

    doc = {
        "_id": slug,
        "name": name,
        "artist_id": artist_id,
        "avatar_url": avatar_url,
        "link": link,
        "updated_at": time.time()
    }
    if avatar_url and col is not None:
        try:
            await col.update_one({"_id": slug}, {"$set": doc}, upsert=True)
        except Exception:
            pass

    return doc


async def find_best_cover_url(*, title: str, artist: str, album: str = "", year: int | None = None) -> tuple[str | None, str | None, str | None]:
    use_spotify = bool(getattr(Config, "SPOTIFY_COVER_SEARCH", False))
    use_fallbacks = bool(getattr(Config, "MUSIC_HOADER_SEARCH", False))

    if not use_spotify and not use_fallbacks:
        use_spotify = True
        use_fallbacks = True

    if use_spotify:
        try:
            if (album or "").strip():
                u = await spotify_album_cover_url(artist=artist, album=album, year=year)
                if u:
                    return u, "spotify_album", None
            u = await spotify_cover_url(title=title, artist=artist, album=album, year=year)
            if u:
                return u, "spotify", None
        except Exception as e:
            _dbg(f"[cover] spotify failed err={e!r}")

    if use_fallbacks:
        try:
            name = (album or "").strip() or (title or "").strip()
            big, small = await hoaders_cover_info(artist=artist, album=name, year=year)
            if big:
                return big, "hoaders", small
        except Exception as e:
            _dbg(f"[cover] hoaders failed err={e!r}")

        try:
            u = await apple_cover_url(title=title, artist=artist, album=album, year=year)
            if u:
                return u, "apple", None
        except Exception as e:
            _dbg(f"[cover] apple failed err={e!r}")

        try:
            u = await deezer_cover_url(title=title, artist=artist, album=album, year=year)
            if u:
                return u, "deezer", None
        except Exception as e:
            _dbg(f"[cover] deezer failed err={e!r}")

    return None, None, None
