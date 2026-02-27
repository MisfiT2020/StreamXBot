<div align="center">
  <p>
    <img src="Assets/cover.jpg" alt="cover" width="300" />
  </p>
  <h2>StreamXBot</h2>
  <p>A Telegram WebApp Streaming bot that lets you listen to music directly in the browser, even without Telegram.</p>
  <p>
    • <code>Frontend: <a href="https://github.com/MisfiT2020/StreamXWeb">MisfiT2020/StreamXWeb</a></code>
  </p>

  <p align="center">
    <a href="https://render.com/deploy">
      <img src="https://render.com/images/deploy-to-render-button.svg" alt="Deploy to Render" />
    </a>
  </p>

  <p align="center">
    <a href="https://cron.raiden.ovh">
      <img src="https://img.shields.io/badge/Cron-FFFFFF?style=for-the-badge&logo=clockify&logoColor=000000&labelColor=FFFFFF&color=FFFFFF" alt="Cron" />
    </a>
  </p>

  <p>
    <small>
      Register your Render deployment URL at <a href="https://cron.raiden.ovh">Cron</a> to keep your service awake and avoid cold starts.
    </small>
  </p>
</div>

---


## Features

- Telegram-free web app: use StreamX directly in the browser, even without Telegram.
- Fast music discovery with browse, search, and shuffle modes.
- Curated daily playlists plus playlist-based exploration.
- Smooth track streaming with quick warm-up playback support.
- Built-in lyrics view for supported songs.
- Personal listening space with favourites and top-played history.
- Create, edit, and manage your own custom playlists.
- Real-time Jam rooms for synchronized group listening and queue control.

## Preview

| 1 | 2 | 3 |
|---|---|---|
| ![1](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/1.jpg) | ![2](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/2.jpg) | ![3](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/3.jpg) |

| 4 | 5 | 6 |
|---|---|---|
| ![4](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/4.jpg) | ![5](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/5.jpg) | ![6](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/7.jpg) |

| 7 | 8 | 9 |
|---|---|---|
| ![7](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/8.jpg) | ![8](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/9.jpg) | ![9](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/10.jpg) |

| 10 | 11 | 12 |
|---|---|---|
| ![10](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/11.jpg) | ![11](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/12.jpg) | ![12](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/13.jpg) |

| 13 | 14 | 15 |
|---|---|---|
| ![13](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/16.jpg) | ![14](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/17.jpg) | ![15](https://raw.githubusercontent.com/MisfiT2020/src/main/streamx/18.png) |

## Quick health check

Once deployed, the API should respond:

- `GET /health` → `{"status":"ok", ...}` (see [health.py](https://github.com/MisfiT2020/StreamXBot/blob/main/Api/routers/health.py))

Example:

```bash
curl -sS https://YOUR_BACKEND_URL/health
```

---

## Configuration

<details>
<summary>Required env vars</summary>

These must be set for the app to boot (see [config_manager.py](https://github.com/MisfiT2020/StreamXBot/blob/main/stream/core/config_manager.py)):

- `BOT_TOKEN` (Telegram bot token, `123456:ABC...`)
- `API_ID` (integer)
- `API_HASH`
- `MONGO_URI` (`mongodb://...` or `mongodb+srv://...`)
- `OWNER_ID` (one ID or a list; the loader turns it into a list)

</details>

<details>
<summary>Common optional env vars</summary>

- `DATABASE_NAME` (default is `"Stream"` in [sample_config.py](https://github.com/MisfiT2020/StreamXBot/blob/main/sample_config.py))
- `DEBUG` (`true/false`)
- `CORS_ORIGINS` (comma/space-separated list of allowed origins)
- `COOKIE_DOMAIN` (set only if you need cross-subdomain cookies)
- `COOKIE_SECURE` (`true/false`)
- `COOKIE_SAMESITE` (`lax|strict|none`)
- `SESSION_STRING` (enables the userbot worker)
- `SOURCE_CHANNEL_IDS` (space/comma-separated IDs; only used if userbot ingest is enabled)
- `MULTI_CLIENTS` (`true/false`)
- `MULTI_CLIENTS_1`, `MULTI_CLIENTS_2`, ... (additional bot tokens/session strings for multi-client mode)

For a full example, copy and edit [sample_config.py](https://github.com/MisfiT2020/StreamXBot/blob/main/sample_config.py).

</details>

---

## Deployment

Fork this repo, then clone your fork for deployment (so your own changes and secrets stay in your repo).

- **Render & Heroku**: `CONFIG_GIST` – provide a raw GitHub Gist URL that contains a complete `config.py`.  
  At build time the gist is downloaded and written to `config.py`, letting you keep secrets out of the repo.


<details>
<summary>Render (recommended)</summary>

Create a new **Web Service**:

- Runtime: Docker
- Build command: (Render detects Dockerfile)
- Start command: default (runs `start.sh`)

Set environment variables in Render dashboard:

- Required: `CONFIG_GIST` with a raw GitHub Gist URL that contains a complete `config.py`.
- For frontend: set `CORS_ORIGINS` to your frontend domain (example: `https://your-frontend.vercel.app`)

</details>

<details>
<summary>VPS (Docker Compose)</summary>

1. Install Docker + Docker Compose on the VPS
2. Clone the backend repo
3. Copy `sample_config.py` to `config.py` and fill in the required variables
4. Run:

</details>

<details>
<summary>Heroku (GitHub Actions + Docker)</summary>

This repo ships a workflow: [heroku-docker.yml](https://github.com/MisfiT2020/StreamXBot/blob/main/.github/workflows/heroku-docker.yml).

Behavior:

- If Heroku secrets are present, it builds/pushes/releases the Docker image
- If secrets are missing, it skips Heroku steps and the workflow still passes

To enable Heroku deploy, add GitHub repo secrets:

- `HEROKU_APP_NAME`
- `HEROKU_API_KEY` (or `HEROKU_KEY`)

Optional:

- `CONFIG_GIST` = a raw GitHub Gist URL pointing to a `config.py`-style file. If set, the workflow converts it into Heroku config vars and applies them before releasing.

</details>

---

## Frontend deployment (Vite / Vercel / Netlify)

Frontend source in [MisfiT2020/StreamXWeb](https://github.com/MisfiT2020/StreamXWeb) and calls this backend.

### Required frontend env var

Set this in your frontend build env:

- `VITE_API_BASE_URL` = your backend base URL
