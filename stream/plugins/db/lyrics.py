import aiohttp
import re


LRCLIB_SEARCH = "https://lrclib.net/api/search"

LRCLIB_HEADERS = {
    "User-Agent": "LRCLIB Web Client (https://github.com/tranxuanthang/lrclib)",
    "Accept": "application/json",
}

def parse_synced_lyrics(synced: str) -> str:
    output = []
    for line in (synced or "").splitlines():
        line = line.rstrip()
        if not line:
            continue
        if line.startswith("[") and "]" in line:
            ts, lyric = line.split("]", 1)
            lyric = lyric.lstrip()
            if lyric:
                output.append(f"{ts}] {lyric}")
            else:
                output.append(f"{ts}]")
    return "\n".join(output)


def _extract_synced_timestamps(synced: str) -> list[str]:
    out: list[str] = []
    for line in (synced or "").splitlines():
        line = (line or "").strip()
        if not line.startswith("[") or "]" not in line:
            continue
        ts = line.split("]", 1)[0] + "]"
        if len(ts) < 4:
            continue
        out.append(ts)
    return out


def _merge_timestamps_into_plain(*, synced: str, plain: str) -> str | None:
    timestamps = _extract_synced_timestamps(synced)
    if not timestamps:
        return None

    plain_raw_lines = (plain or "").splitlines()
    if not any((l or "").strip() for l in plain_raw_lines):
        return None

    merged = []
    ts_i = 0
    for line in plain_raw_lines:
        raw = (line or "").rstrip()
        if not raw.strip():
            merged.append("")
            continue
        if ts_i < len(timestamps):
            merged.append(f"{timestamps[ts_i]} {raw.strip()}")
            ts_i += 1
        else:
            merged.append(raw.strip())
    return "\n".join(merged).rstrip() or None


def _norm_text(s: str) -> str:
    return " ".join((s or "").strip().lower().split())


def _split_artists(artist: str | None) -> list[str]:
    a = (artist or "").strip()
    if not a:
        return []

    lowered = a.casefold()
    lowered = re.sub(r"\s+", " ", lowered).strip()

    a2 = a
    a2 = re.sub(r"(?i)\s+(feat\.?|ft\.?|featuring)\s+", " / ", a2)
    for sep in ["/", "&", ";", ",", "•", "|"]:
        a2 = a2.replace(sep, " / ")
    parts = [p.strip() for p in a2.split(" / ") if p.strip()]

    out: list[str] = []
    seen: set[str] = set()
    for p in [a, *parts]:
        key = _norm_text(p)
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(p)
    return out


def score_match(item: dict, title: str, artist: str | None, album: str | None) -> int:
    score = 0
    want_title = _norm_text(title)
    want_album = _norm_text(album or "")

    got_title = _norm_text((item or {}).get("trackName") or "")
    got_artist = _norm_text((item or {}).get("artistName") or "")
    got_album = _norm_text((item or {}).get("albumName") or "")

    if want_title:
        if want_title == got_title:
            score += 5
        elif want_title in got_title:
            score += 3

    artist_variants = _split_artists(artist)
    if artist_variants:
        if any(_norm_text(v) == got_artist for v in artist_variants):
            score += 5
        elif any(_norm_text(v) and _norm_text(v) in got_artist for v in artist_variants):
            score += 3

    if want_album:
        if want_album == got_album:
            score += 3
        elif want_album and want_album in got_album:
            score += 1

    if (item or {}).get("syncedLyrics"):
        score += 2
    elif (item or {}).get("plainLyrics"):
        score += 1

    return score


async def search_lrclib(*, title: str, artist: str | None = None, album: str | None = None) -> dict | None:
    title = (title or "").strip()
    artist = (artist or "").strip() or None
    album = (album or "").strip() or None
    if not title:
        return None

    artist_variants = _split_artists(artist)
    primary_artist = artist_variants[0] if artist_variants else None
    q = " ".join([p for p in [title, primary_artist] if p]).strip() or title

    async with aiohttp.ClientSession(headers=LRCLIB_HEADERS) as session:
        async with session.get(LRCLIB_SEARCH, params={"q": q}) as resp:
            resp.raise_for_status()
            data = await resp.json()

    if not isinstance(data, list) or not data:
        return None

    filtered = data
    if artist:
        tokens = [_norm_text(v) for v in artist_variants] if artist_variants else [_norm_text(artist)]
        tokens = [t for t in tokens if t]
        filtered2 = []
        for d in data:
            if not isinstance(d, dict):
                continue
            got = _norm_text(str(d.get("artistName") or ""))
            if not got:
                continue
            if any(t in got for t in tokens):
                filtered2.append(d)
        if filtered2:
            filtered = filtered2[:100]

    best = max(filtered, key=lambda x: score_match(x if isinstance(x, dict) else {}, title, artist, album))
    if not isinstance(best, dict):
        return None

    s = score_match(best, title, artist, album)
    got_title = _norm_text(best.get("trackName") or "")
    want_title = _norm_text(title)
    has_lyrics = bool(best.get("syncedLyrics") or best.get("plainLyrics"))
    threshold = 7 if (want_title and want_title == got_title and has_lyrics) else 8
    if s < threshold:
        return None
    return best


def extract_lyrics(best: dict) -> tuple[str | None, str | None]:
    if not isinstance(best, dict):
        return None, None
    if best.get("syncedLyrics"):
        return parse_synced_lyrics(str(best.get("syncedLyrics") or "")).strip() or None, "synced"
    if best.get("plainLyrics"):
        plain = str(best.get("plainLyrics") or "").strip()
        return plain or None, "plain"
    return None, None


async def fetch_best_lyrics(*, title: str, artist: str | None = None, album: str | None = None) -> dict:
    best = await search_lrclib(title=title, artist=artist, album=album)
    if not best:
        return {"ok": False, "error": "no_match"}

    lyrics, kind = extract_lyrics(best)
    if not lyrics:
        return {"ok": False, "error": "no_lyrics", "match": best}

    return {"ok": True, "lyrics": lyrics, "kind": kind, "match": best}
