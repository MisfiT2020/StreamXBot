import hashlib
import os
import time
from typing import Any
import colorsys
import random

import requests
from PIL import Image, ImageDraw, ImageFont, ImageFilter

from stream.database.MongoDb import db_handler
from stream.core.config_manager import Config


CARD_SIZE = 650
CORNER_RADIUS = 60


def _gen_covers_dir() -> str:
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    out_dir = os.path.join(root, "GenCovers")
    os.makedirs(out_dir, exist_ok=True)
    return out_dir


def _font_path() -> str:
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    return os.path.join(root, "Assets", "SFPRODISPLAYBOLD.OTF")


def generate_nice_color() -> tuple[int, int, int]:
    hue = random.random()
    saturation = random.uniform(0.6, 0.9)
    value = random.uniform(0.75, 0.95)
    r, g, b = colorsys.hsv_to_rgb(hue, saturation, value)
    return (int(r * 255), int(g * 255), int(b * 255))


def _wrap_text(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, max_width: int, max_lines: int) -> list[str]:
    t = " ".join((text or "").strip().split())
    if not t:
        return []
    words = t.split(" ")
    lines: list[str] = []
    buf = ""
    for w in words:
        cand = f"{buf} {w}".strip() if buf else w
        wbox = draw.textbbox((0, 0), cand, font=font)
        if (wbox[2] - wbox[0]) <= max_width:
            buf = cand
            continue
        if buf:
            lines.append(buf)
        buf = w
        if len(lines) >= max_lines:
            break
    if buf and len(lines) < max_lines:
        lines.append(buf)

    if len(lines) > max_lines:
        lines = lines[:max_lines]

    if len(lines) == max_lines and words:
        last = lines[-1]
        while True:
            bbox = draw.textbbox((0, 0), last, font=font)
            if (bbox[2] - bbox[0]) <= max_width:
                break
            if len(last) <= 3:
                last = "..."
                break
            last = last[:-1].rstrip()
        if last != lines[-1]:
            if not last.endswith("..."):
                if len(last) >= 3:
                    last = last[:-3].rstrip() + "..."
                else:
                    last = "..."
            lines[-1] = last
    return lines


def _split_words_into_lines(
    draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, max_width: int, max_lines: int
) -> list[str]:
    t = " ".join((text or "").strip().split())
    if not t:
        return []
    words = t.split(" ")
    if not words:
        return []
    if len(words) <= max_lines:
        lines = words
    else:
        head = words[: max_lines - 1]
        tail = " ".join(words[max_lines - 1 :]).strip()
        lines = [*head, tail] if tail else head
        if not lines:
            lines = _wrap_text(draw, t, font, max_width, max_lines)

    for ln in lines:
        bbox = draw.textbbox((0, 0), ln, font=font)
        if (bbox[2] - bbox[0]) > max_width:
            return _wrap_text(draw, t, font, max_width, max_lines)
    return lines


def _load_font(size: int) -> ImageFont.FreeTypeFont:
    try:
        return ImageFont.truetype(_font_path(), int(size))
    except Exception:
        return ImageFont.load_default()


def render_cover(*, top_text: str, bottom_text: str, out_path: str) -> dict[str, Any]:
    base_color = generate_nice_color()

    scale = 4
    size = int(CARD_SIZE * scale)
    radius = int(CORNER_RADIUS * scale)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    card = Image.new("RGBA", (size, size), base_color + (255,))

    depth = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    depth_draw = ImageDraw.Draw(depth)
    blob_radius = int(320 * scale)
    blob_x = size - int(170 * scale)
    blob_y = size - int(160 * scale)
    depth_draw.ellipse(
        [blob_x - blob_radius, blob_y - blob_radius, blob_x + blob_radius, blob_y + blob_radius],
        fill=(0, 0, 0, 160),
    )
    depth = depth.filter(ImageFilter.GaussianBlur(int(140 * scale)))
    card = Image.alpha_composite(card, depth)

    mask = Image.new("L", (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)
    mask_draw.rounded_rectangle([(0, 0), (size, size)], radius=radius, fill=255)
    canvas.paste(card, (0, 0), mask)

    draw = ImageDraw.Draw(canvas)

    margin_x = int(50 * scale)
    max_width = size - (margin_x * 2)
    top_y = int(60 * scale)
    bottom_margin = int(70 * scale)
    line_spacing = int(10 * scale)

    font_top = _load_font(int(75 * scale))
    font_bottom = _load_font(int(65 * scale))

    top_lines = _split_words_into_lines(draw, top_text, font_top, max_width, 2)
    bottom_lines = _split_words_into_lines(draw, bottom_text, font_bottom, max_width, 2) if (bottom_text or "").strip() else []

    y = top_y
    for ln in top_lines:
        draw.text((margin_x, y), ln, fill=(0, 0, 0), font=font_top)
        bbox = draw.textbbox((0, 0), ln, font=font_top)
        y += (bbox[3] - bbox[1]) + line_spacing

    bottom_heights: list[int] = []
    for ln in bottom_lines:
        bbox = draw.textbbox((0, 0), ln, font=font_bottom)
        bottom_heights.append(bbox[3] - bbox[1])
    if bottom_heights:
        bottom_block_h = sum(bottom_heights) + (line_spacing * max(0, len(bottom_heights) - 1))
        bottom_y = size - bottom_margin - bottom_block_h
        y2 = bottom_y
        for i, ln in enumerate(bottom_lines):
            draw.text((margin_x, y2), ln, fill=(0, 0, 0), font=font_bottom)
            y2 += bottom_heights[i] + line_spacing

    out_img = canvas.resize((CARD_SIZE, CARD_SIZE), resample=Image.Resampling.LANCZOS)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    out_img.save(out_path)
    return {"color": base_color, "path": out_path}


def _cloudinary_config() -> dict[str, str]:
    try:
        from stream.core.config_manager import Config
    except Exception:
        Config = None

    cloud_name = (getattr(Config, "CLOUDINARY_CLOUD_NAME", "") or "").strip() if Config else ""
    api_key = (getattr(Config, "CLOUDINARY_API_KEY", "") or "").strip() if Config else ""
    api_secret = (getattr(Config, "CLOUDINARY_API_SECRET", "") or "").strip() if Config else ""

    if not cloud_name:
        cloud_name = (os.environ.get("CLOUDINARY_CLOUD_NAME") or "").strip()
    if not api_key:
        api_key = (os.environ.get("CLOUDINARY_API_KEY") or "").strip()
    if not api_secret:
        api_secret = (os.environ.get("CLOUDINARY_API_SECRET") or "").strip()
    return {"cloud_name": cloud_name, "api_key": api_key, "api_secret": api_secret}


def _cloudinary_signature(params: dict[str, str], api_secret: str) -> str:
    pieces = [f"{k}={params[k]}" for k in sorted(params.keys()) if params.get(k) is not None]
    raw = "&".join(pieces) + api_secret
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()

def _file_key_for_id(cover_id: str) -> str:
    return hashlib.sha1(cover_id.encode("utf-8", errors="ignore")).hexdigest()[:24]


def upload_to_cloudinary(*, file_path: str, folder: str, public_id: str) -> str | None:
    cfg = _cloudinary_config()
    cloud_name = cfg["cloud_name"]
    api_key = cfg["api_key"]
    api_secret = cfg["api_secret"]
    if not cloud_name or not api_key or not api_secret:
        raise RuntimeError("Cloudinary credentials missing (CLOUDINARY_CLOUD_NAME/CLOUDINARY_API_KEY/CLOUDINARY_API_SECRET)")

    ts = str(int(time.time()))
    sign_params = {"folder": folder, "public_id": public_id, "timestamp": ts}
    signature = _cloudinary_signature(sign_params, api_secret)
    url = f"https://api.cloudinary.com/v1_1/{cloud_name}/image/upload"

    with open(file_path, "rb") as f:
        resp = requests.post(
            url,
            data={**sign_params, "api_key": api_key, "signature": signature},
            files={"file": f},
            timeout=60,
        )
    payload = resp.json() if resp.content else {}
    secure_url = payload.get("secure_url") if isinstance(payload, dict) else None
    if resp.status_code not in (200, 201) or not isinstance(secure_url, str) or not secure_url.strip():
        err = payload.get("error") if isinstance(payload, dict) else None
        msg = ""
        if isinstance(err, dict) and isinstance(err.get("message"), str):
            msg = err.get("message") or ""
        msg = msg.strip()
        raise RuntimeError(f"Cloudinary upload failed status={resp.status_code}" + (f" error={msg}" if msg else ""))
    return secure_url.strip()


async def ensure_cover(
    *,
    cover_id: str,
    top_text: str,
    bottom_text: str,
    kind: str,
    folder: str = "covers",
    force: bool = False,
) -> dict[str, Any]:
    cover_id = (cover_id or "").strip()
    if not cover_id:
        raise ValueError("cover_id is required")
    kind = (kind or "").strip() or "cover"
    file_key = _file_key_for_id(cover_id)

    col = db_handler.get_collection("covers").collection
    existing = await col.find_one({"_id": cover_id})
    if isinstance(existing, dict) and not force:
        existing_cloud = existing.get("cloud_url")
        if isinstance(existing_cloud, str) and existing_cloud.strip():
            out = {"cover_id": cover_id, "file_key": existing.get("file_key") or file_key}
            out["cloud_url"] = existing_cloud.strip()
            out["local_path"] = existing.get("local_path") if isinstance(existing.get("local_path"), str) else None
            out["url"] = existing_cloud.strip()
            return out
        out = {"cover_id": cover_id, "file_key": existing.get("file_key") or file_key}
        out["cloud_url"] = existing.get("cloud_url") if isinstance(existing.get("cloud_url"), str) else None
        out["local_path"] = existing.get("local_path") if isinstance(existing.get("local_path"), str) else None
        out["url"] = existing.get("url") if isinstance(existing.get("url"), str) else None
        force = True

    out_dir = _gen_covers_dir()
    filename = f"{file_key}.png"
    out_path = os.path.join(out_dir, filename)
    render = render_cover(top_text=top_text, bottom_text=bottom_text, out_path=out_path)

    cloud_url = upload_to_cloudinary(file_path=out_path, folder=folder, public_id=file_key)
    url = cloud_url

    now = time.time()
    await col.update_one(
        {"_id": cover_id},
        {
            "$set": {
                "kind": kind,
                "file_key": file_key,
                "top_text": top_text,
                "bottom_text": bottom_text,
                "local_path": out_path,
                "cloud_url": cloud_url,
                "url": url,
                "color": render.get("color"),
                "updated_at": now,
            },
            "$setOnInsert": {"created_at": now},
        },
        upsert=True,
    )
    return {"cover_id": cover_id, "file_key": file_key, "cloud_url": cloud_url, "local_path": out_path, "url": url}


async def ensure_daily_playlist_cover(*, key: str, date: str, channel_id: int | None, force: bool = False) -> dict[str, Any]:
    key2 = (key or "").strip().lower()
    if key2 in {"random", "mix", "daily", "daily-playlist"}:
        key2 = "random"
    elif key2 in {"top", "top-played", "top-playlist"}:
        key2 = "top-played"
    elif key2 in {"trending-today"}:
        key2 = "trending"
    elif key2 in {"late-night-mix", "night"}:
        key2 = "late-night"
    elif key2 in {"rising-tracks"}:
        key2 = "rising"
    elif key2 in {"surprise-me"}:
        key2 = "surprise"
    date2 = (date or "").strip()
    scope = str(int(channel_id)) if channel_id is not None else "global"
    cover_id = f"daily:{date2}:{key2}:{scope}"

    if key2 in {"top", "top-played"}:
        top_text = "Top Played"
    elif key2 == "trending":
        top_text = "Trending Today"
    elif key2 == "rediscover":
        top_text = "Rediscover"
    elif key2 == "late-night":
        top_text = "Late Night Mix"
    elif key2 == "rising":
        top_text = "Rising Tracks"
    elif key2 == "surprise":
        top_text = "Surprise Me"
    else:
        top_text = "Daily Playlist"
    bottom_text = ""

    return await ensure_cover(
        cover_id=cover_id,
        top_text=top_text,
        bottom_text=bottom_text,
        kind="daily_playlist",
        folder="covers",
        force=bool(force),
    )


async def ensure_user_playlist_cover(*, playlist_id: str, name: str, force: bool = False) -> dict[str, Any]:
    pid = (playlist_id or "").strip()
    cover_id = f"user-playlist:{pid}"
    top_text = (name or "").strip() or "Playlist"
    bottom_text = ""
    return await ensure_cover(
        cover_id=cover_id,
        top_text=top_text,
        bottom_text=bottom_text,
        kind="user_playlist",
        folder="covers",
        force=bool(force),
    )


async def ensure_user_top_played_cover(*, user_id: int, force: bool = False) -> dict[str, Any]:
    uid = int(user_id)
    cover_id = f"user-top-played:{uid}"
    return await ensure_cover(
        cover_id=cover_id,
        top_text="Top Played",
        bottom_text="",
        kind="user_top_played",
        folder="covers",
        force=bool(force),
    )


async def ensure_user_favourites_cover(*, user_id: int, force: bool = False) -> dict[str, Any]:
    uid = int(user_id)
    cover_id = f"user-favourites:{uid}"
    return await ensure_cover(
        cover_id=cover_id,
        top_text="Favourites",
        bottom_text="",
        kind="user_favourites",
        folder="covers",
        force=bool(force),
    )
