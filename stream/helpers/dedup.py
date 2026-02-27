import hashlib
import re

def sha256_prefix_file(file_path: str, max_bytes: int = 10_000_000) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        remaining = max_bytes
        while remaining > 0:
            chunk = f.read(min(1024 * 1024, remaining))
            if not chunk:
                break
            h.update(chunk)
            remaining -= len(chunk)
    return f"sha256:{h.hexdigest()}"


def normalize_text(value: str) -> str:
    s = (value or "").lower()
    s = re.sub(r"[^a-z0-9]+", " ", s).strip()
    s = re.sub(r"\s+", " ", s)
    return s


def metadata_fingerprint(title: str, artist: str, album: str, duration_sec: int | None, tolerance_sec: int = 2) -> str:
    t = normalize_text(title)
    a = normalize_text(artist)
    al = normalize_text(album)

    d = None
    if duration_sec is not None:
        try:
            d = int(duration_sec)
        except Exception:
            d = None

    if d is None:
        dur_key = ""
    else:
        bucket = max(1, int(tolerance_sec))
        dur_key = str(int(round(d / bucket) * bucket))

    return "|".join([t, a, al, dur_key]).strip("|")

