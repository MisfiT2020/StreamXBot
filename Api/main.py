import os
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from Api.deps.db import init_db
from Api.routers.browse import router as browse_router
from Api.routers.share import router as share_router
from Api.routers.auth import router as auth_router
from Api.routers.jam import router as jam_router
from Api.routers.webapp import router as webapp_router
from Api.routers.favourites import router as favourites_router
from Api.routers.playlists import router as playlists_router
from Api.routers.health import router as health_router
from Api.routers.test import router as test_router
from Api.routers.tracks import router as tracks_router
from Api.routers.cover import router as cover_router
from Api.routers.admin_refresh import router as admin_refresh_router
from Api.routers.friends import router as friends_router
from Api.routers.notifications import router as notifications_router
from Api.routers.presence import router as presence_router

from stream.core.config_manager import Config


BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST_DIR = os.path.join(BASE_DIR, "dist")
ASSETS_DIR = os.path.join(DIST_DIR, "assets")


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    await Config.load_from_db()
    yield


app = FastAPI(lifespan=lifespan)


# Mount assets only if they exist (prevents startup crash)
if os.path.exists(ASSETS_DIR):
    app.mount("/assets", StaticFiles(directory=ASSETS_DIR), name="assets")


app.add_middleware(
    CORSMiddleware,
    allow_origins=Config.CORS_ORIGIN,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Accept-Ranges", "Content-Range", "Content-Length"],
)


# API routers
app.include_router(health_router)
app.include_router(auth_router)
app.include_router(jam_router)
app.include_router(webapp_router)
app.include_router(browse_router)
app.include_router(tracks_router)
app.include_router(playlists_router)
app.include_router(favourites_router)
app.include_router(cover_router)
app.include_router(admin_refresh_router)
app.include_router(test_router)
app.include_router(friends_router)
app.include_router(notifications_router)
app.include_router(presence_router)
app.include_router(share_router)


@app.get("/")
async def serve_root():
    index_file = os.path.join(DIST_DIR, "index.html")
    if os.path.exists(index_file):
        return FileResponse(index_file)
    return {"status": "frontend not built"}


@app.get("/{full_path:path}")
async def serve_spa(full_path: str):
    file_path = os.path.join(DIST_DIR, full_path)

    if os.path.exists(file_path):
        return FileResponse(file_path)

    index_file = os.path.join(DIST_DIR, "index.html")
    if os.path.exists(index_file):
        return FileResponse(index_file)

    return {"status": "frontend not built"}