import os
import aiofiles
from fastapi import APIRouter, Depends, HTTPException
from Api.utils.auth import require_admin_user_id

router = APIRouter(prefix="/logs", tags=["Logs"])

@router.get("/")
async def get_logs(admin_id: int = Depends(require_admin_user_id)):
    """Retrieve the last 100 lines of logs (admin only)."""
    log_path = "log.txt"
    
    if not os.path.exists(log_path):
        raise HTTPException(status_code=404, detail="Log file not found")

    try:
        async with aiofiles.open(log_path, mode="r", encoding="utf-8") as f:
            content = await f.read()
            lines = content.splitlines()[-100:]
            return {"logs": lines}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error reading logs: {str(e)}")
