from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from Api.deps.db import init_db
from Api.routers.browse import router as browse_router
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
from stream.core.config_manager import Config

@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    await Config.load_from_db()
    yield

app = FastAPI(lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins= Config.CORS_ORIGIN,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Accept-Ranges", "Content-Range", "Content-Length"],
)

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
