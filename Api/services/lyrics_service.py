import asyncio

from fastapi import HTTPException

from Api.deps.db import get_audio_tracks_collection
from Api.services.musixmatch import fetch_track_lyrics_from_musixmatch
from stream.core.config_manager import Config
from stream.helpers.logger import LOGGER
from stream.plugins.db.lyrics import fetch_best_lyrics
from stream.plugins.db.telegraph import publish_lyrics_text_to_graph

_TELEGRAPH_TASKS: dict[str, asyncio.Task] = {}
_TELEGRAPH_TASKS_LOCK = asyncio.Lock()

LOG = LOGGER(__name__)

def _clean_telegraph_url(value: str) -> str:
    s = (value or "").strip()
    if s.startswith("`") and s.endswith("`") and len(s) >= 2:
        s = s[1:-1].strip()
    if s.startswith("'") and s.endswith("'") and len(s) >= 2:
        s = s[1:-1].strip()
    if s.startswith('"') and s.endswith('"') and len(s) >= 2:
        s = s[1:-1].strip()
    return s


def _extract_cached_lyrics(doc: dict) -> dict | None:
    cache = doc.get("lyrics_cache") if isinstance(doc.get("lyrics_cache"), dict) else {}
    text = cache.get("text")
    if not isinstance(text, str) or not text.strip():
        return None
    kind = cache.get("kind")
    source = cache.get("source")
    out = {
        "lyrics": text,
        "kind": str(kind) if isinstance(kind, str) and kind.strip() else None,
        "source": str(source) if isinstance(source, str) and source.strip() else None,
    }
    return out


async def _publish_telegraph_and_store(
    *,
    track_id: str,
    title: str,
    artist: str,
    album: str | None,
    lyrics: str,
) -> None:
    try:
        tg = await publish_lyrics_text_to_graph(
            track_id=track_id,
            title=title,
            artist=artist,
            album=album,
            lyrics=lyrics,
        )
    except Exception:
        tg = None

    url = ""
    if isinstance(tg, dict):
        url = _clean_telegraph_url(str(tg.get("url") or ""))
    if not url:
        return

    try:
        col = get_audio_tracks_collection()
        await col.update_one({"_id": track_id}, {"$set": {"lyrics": url}}, upsert=False)
    except Exception:
        return


async def _ensure_telegraph_background(
    *,
    track_id: str,
    title: str,
    artist: str,
    album: str | None,
    lyrics: str,
) -> None:
    track_id = (track_id or "").strip()
    if not track_id:
        return

    async with _TELEGRAPH_TASKS_LOCK:
        t = _TELEGRAPH_TASKS.get(track_id)
        if t and not t.done():
            return

        task = asyncio.create_task(
            _publish_telegraph_and_store(
                track_id=track_id,
                title=title,
                artist=artist,
                album=album,
                lyrics=lyrics,
            )
        )
        _TELEGRAPH_TASKS[track_id] = task

        def _done(_t: asyncio.Task):
            async def _cleanup():
                async with _TELEGRAPH_TASKS_LOCK:
                    cur = _TELEGRAPH_TASKS.get(track_id)
                    if cur is _t:
                        _TELEGRAPH_TASKS.pop(track_id, None)

            asyncio.create_task(_cleanup())

        task.add_done_callback(_done)


async def get_track_lyrics(track_id: str) -> dict:
    track_id = (track_id or "").strip()
    if not track_id:
        raise HTTPException(status_code=400, detail="track_id is required")

    col = get_audio_tracks_collection()
    doc = await col.find_one(
        {"_id": track_id},
        projection={"audio": 1, "telegram": 1, "spotify": 1, "lyrics": 1, "lyrics_cache": 1},
    )
    if not doc:
        raise HTTPException(status_code=404, detail="track not found")

    audio = doc.get("audio") if isinstance(doc.get("audio"), dict) else {}
    telegram = doc.get("telegram") if isinstance(doc.get("telegram"), dict) else {}

    title = (audio.get("title") or "").strip() or (telegram.get("title") or "").strip()
    artist = (audio.get("artist") or "").strip() or (audio.get("performer") or "").strip() or (telegram.get("artist") or "").strip()
    album = (audio.get("album") or "").strip() or (telegram.get("album") or "").strip()

    if not title:
        return {"ok": False, "error": "missing_title"}

    cached_url = _clean_telegraph_url(doc.get("lyrics") if isinstance(doc.get("lyrics"), str) else "")
    cached = _extract_cached_lyrics(doc)
    if cached:
        out = {"ok": True, "track_id": track_id, "lyrics": cached["lyrics"]}
        if cached.get("kind"):
            out["kind"] = cached["kind"]
        if cached.get("source"):
            out["source"] = cached["source"]
        if cached_url:
            out["telegraph_url"] = cached_url
        else:
            try:
                asyncio.create_task(
                    _ensure_telegraph_background(
                        track_id=track_id,
                        title=title,
                        artist=artist or "Unknown",
                        album=album or None,
                        lyrics=str(cached["lyrics"] or ""),
                    )
                )
            except Exception:
                pass
        return out

    if bool(getattr(Config, "MUSIXMATCH", False)):
        if bool(getattr(Config, "DEBUG", False)):
            LOG.debug(f"[lyrics] method=musixmatch track_id={track_id!r}")
        mxm = await fetch_track_lyrics_from_musixmatch(track=doc)
        if mxm.get("ok"):
            mxm["track_id"] = track_id
            try:
                try:
                    col = get_audio_tracks_collection()
                    await col.update_one(
                        {"_id": track_id},
                        {
                            "$set": {
                                "lyrics_cache.text": str(mxm.get("lyrics") or ""),
                                "lyrics_cache.kind": str(mxm.get("kind") or ""),
                                "lyrics_cache.source": str(mxm.get("source") or "musixmatch"),
                                "lyrics_cache.updated_at": __import__("time").time(),
                            }
                        },
                        upsert=False,
                    )
                except Exception:
                    pass
                if cached_url:
                    mxm["telegraph_url"] = cached_url
                else:
                    asyncio.create_task(
                        _ensure_telegraph_background(
                            track_id=track_id,
                            title=title,
                            artist=artist or "Unknown",
                            album=album or None,
                            lyrics=str(mxm.get("lyrics") or ""),
                        )
                    )
                mxm["telegraph_url"] = _clean_telegraph_url(str(mxm.get("telegraph_url") or ""))
                telegraph_url = (mxm.get("telegraph_url") or "").strip()
                if telegraph_url and telegraph_url != cached_url:
                    col = get_audio_tracks_collection()
                    await col.update_one(
                        {"_id": track_id},
                        {"$set": {"lyrics": telegraph_url}},
                        upsert=False,
                    )
                if bool(getattr(Config, "DEBUG", False)):
                    LOG.debug(
                        f"[lyrics] ok source=musixmatch kind={mxm.get('kind')!r} telegraph_url={mxm.get('telegraph_url')!r}"
                    )
            except Exception:
                pass
            return mxm

    try:
        if not bool(getattr(Config, "LRCLIB", True)):
            return {"ok": False, "error": "lyrics_disabled"}
        if bool(getattr(Config, "DEBUG", False)):
            LOG.debug(f"[lyrics] method=lrclib track_id={track_id!r}")
        result = await fetch_best_lyrics(title=title, artist=artist or None, album=album or None)
    except Exception:
        return {"ok": False, "error": "lyrics_fetch_failed"}

    try:
        if result.get("ok"):
            try:
                col = get_audio_tracks_collection()
                await col.update_one(
                    {"_id": track_id},
                    {
                        "$set": {
                            "lyrics_cache.text": str(result.get("lyrics") or ""),
                            "lyrics_cache.kind": str(result.get("kind") or ""),
                            "lyrics_cache.source": str(result.get("source") or "lrclib"),
                            "lyrics_cache.updated_at": __import__("time").time(),
                        }
                    },
                    upsert=False,
                )
            except Exception:
                pass
            if cached_url:
                result["telegraph_url"] = cached_url
            else:
                asyncio.create_task(
                    _ensure_telegraph_background(
                        track_id=track_id,
                        title=title,
                        artist=artist or "Unknown",
                        album=album or None,
                        lyrics=str(result.get("lyrics") or ""),
                    )
                )
            result["telegraph_url"] = _clean_telegraph_url(str(result.get("telegraph_url") or ""))
            telegraph_url = (result.get("telegraph_url") or "").strip()
            if telegraph_url and telegraph_url != cached_url:
                col = get_audio_tracks_collection()
                await col.update_one(
                    {"_id": track_id},
                    {"$set": {"lyrics": telegraph_url}},
                    upsert=False,
                )
            if bool(getattr(Config, "DEBUG", False)):
                LOG.debug(
                    f"[lyrics] ok source=lrclib kind={result.get('kind')!r} telegraph_url={result.get('telegraph_url')!r}"
                )
    except Exception:
        pass

    result["track_id"] = track_id
    return result
