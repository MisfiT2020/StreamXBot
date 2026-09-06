import time
from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from Api.utils.auth import require_admin_user_id
from stream.core.config_manager import Config
from stream.core.source_filter import (
    FilterMode,
    add_allowed_source,
    ban_source,
    get_all_allowed_sources,
    get_all_banned_sources,
    get_filter_mode,
    invalidate_cache,
    remove_allowed_source,
    unban_source,
)

router = APIRouter(prefix="/admin/sources", tags=["admin", "sources"])


class SetFilterModeRequest(BaseModel):
    mode: str | int = Field(
        ...,
        description="Filter mode: 0/'group_only', 1/'anyone', 2/'hybrid'",
    )


class AddAllowedSourceRequest(BaseModel):
    source_id: int | str = Field(
        ...,
        description="Channel, group, or user ID (e.g. -100123456789 or 123456789)",
    )
    source_type: Literal["channel", "group", "user"] = "channel"
    name: str = ""


class BanSourceRequest(BaseModel):
    source_id: int | str = Field(
        ...,
        description="Channel, group, or user ID to ban",
    )
    source_type: Literal["channel", "group", "user"] = "channel"
    name: str = ""
    reason: str = ""


@router.get("")
async def get_sources_overview(admin_id: int = Depends(require_admin_user_id)):
    """Get complete overview of filter mode, allowed sources, and banned sources."""
    mode = get_filter_mode()
    allowed = await get_all_allowed_sources()
    banned = await get_all_banned_sources()
    channel_id = getattr(Config, "CHANNEL_ID", 0)
    collaborators = getattr(Config, "COLLABORATOR_IDS", []) or []

    return {
        "ok": True,
        "filter_mode": mode,
        "filter_mode_name": FilterMode.to_string(mode),
        "channel_id": channel_id,
        "collaborator_ids": collaborators,
        "allowed_sources_count": len(allowed),
        "banned_sources_count": len(banned),
        "allowed_sources": allowed,
        "banned_sources": banned,
    }


@router.get("/mode")
async def get_current_filter_mode(admin_id: int = Depends(require_admin_user_id)):
    """Get the current filter mode."""
    mode = get_filter_mode()
    return {
        "ok": True,
        "filter_mode": mode,
        "filter_mode_name": FilterMode.to_string(mode),
    }


@router.post("/mode")
async def set_filter_mode(
    req: SetFilterModeRequest,
    admin_id: int = Depends(require_admin_user_id),
):
    """Update the filter mode (0: group_only, 1: anyone, 2: hybrid)."""
    parsed_mode = FilterMode.parse(req.mode)
    await Config.update_config("FILTER_MODE", parsed_mode)
    invalidate_cache()
    return {
        "ok": True,
        "filter_mode": parsed_mode,
        "filter_mode_name": FilterMode.to_string(parsed_mode),
    }


@router.get("/allowed")
async def list_allowed_sources(admin_id: int = Depends(require_admin_user_id)):
    """List all allowed contributor channels/groups/users."""
    sources = await get_all_allowed_sources()
    return {"ok": True, "sources": sources, "count": len(sources)}


@router.post("/allowed")
async def create_allowed_source(
    req: AddAllowedSourceRequest,
    admin_id: int = Depends(require_admin_user_id),
):
    """Add a channel, group, or user to allowed contributors."""
    try:
        doc = await add_allowed_source(
            source_id=req.source_id,
            source_type=req.source_type,
            name=req.name,
            added_by=admin_id,
        )
        return {"ok": True, "source": doc}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to add allowed source: {e}")


@router.delete("/allowed/{source_id}")
async def delete_allowed_source(
    source_id: str,
    admin_id: int = Depends(require_admin_user_id),
):
    """Remove a source from allowed contributors."""
    try:
        sid = int(source_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="Invalid source_id")

    removed = await remove_allowed_source(sid)
    return {"ok": True, "source_id": sid, "removed": removed}


@router.get("/banned")
async def list_banned_sources(admin_id: int = Depends(require_admin_user_id)):
    """List all banned channels/groups/users."""
    sources = await get_all_banned_sources()
    return {"ok": True, "sources": sources, "count": len(sources)}


@router.post("/banned")
async def create_banned_source(
    req: BanSourceRequest,
    admin_id: int = Depends(require_admin_user_id),
):
    """Ban a channel, group, or user from contributing or accessing the service."""
    try:
        doc = await ban_source(
            source_id=req.source_id,
            source_type=req.source_type,
            name=req.name,
            reason=req.reason,
            banned_by=admin_id,
        )
        return {"ok": True, "source": doc}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to ban source: {e}")


@router.delete("/banned/{source_id}")
async def delete_banned_source(
    source_id: str,
    admin_id: int = Depends(require_admin_user_id),
):
    """Unban a channel, group, or user.

    If the source was previously in the allowed sources collection,
    it immediately regains allowed status without needing to be re-added.
    """
    try:
        sid = int(source_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="Invalid source_id")

    removed = await unban_source(sid)
    return {"ok": True, "source_id": sid, "removed": removed}
