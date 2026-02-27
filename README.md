<div align="center">
  <p>
    <img src="Assets/cover.jpg" alt="cover" width="300" />
  </p>
  <h2>StreamXBot</h2>
  <p>A Self Hosted Telegram WebApp Streaming bot that lets you listen to music directly in the browser, even without Telegram. (free of cost)</p>
  <p>
    • <code>Frontend: <a href="https://github.com/MisfiT2020/StreamXWeb">MisfiT2020/StreamXWeb</a></code><br>
    • <code>Support: <a href="https://t.me/RaidenEiSupport">Group</a></code>
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

## API REQUIRED
- [Cloudinary](https://cloudinary.com/) for thumbnail storage.
- [Spotify API](https://developer.spotify.com/) for music metadata.


## BOT COMMANDS

- /ping - Ping the bot to check if it's alive.
- /bs - to get the bot settings and edit the vars without deploying again.
- /restart - to restart the bot.


## Configuration

<details>
<summary>Show</summary>

<details>
<summary>API RELATED</summary>

- `SPOTIFY_CLIENT_ID` (Spotify API client ID)
- `SPOTIFY_CLIENT_SECRET` (Spotify API client secret)
- `CLOUDINARY_CLOUD_NAME` (Cloudinary cloud name)
- `CLOUDINARY_API_KEY` (Cloudinary API key)
- `CLOUDINARY_API_SECRET` (Cloudinary API secret)

</details>

<details>
<summary>BOT RELATED</summary>

- `BOT_TOKEN` (Telegram bot token, `123456:ABC...`)
- `API_ID` (integer)
- `API_HASH` (string)

</details>

<details>
<summary>BACKEND RELATED</summary>

- `CHANNEL_ID` to fetch the tracks from.
- `MONGO_URI` (`mongodb://...` or `mongodb+srv://...`)
- `OWNER_ID` (one ID or a list; the loader turns it into a list)
- `DATABASE_NAME` 
- `MULTI_CLIENT` (True/False) to stream via multiple Bots and load balancing
- `MULTI_CLIENTS_1`, `MULTI_CLIENTS_2`, ... (additional bot tokens/session strings for multi-client mode)
- `MUSIXMATCH`OR `LRCLIB` to get lyrics: MUSIXMATCH OVERRIDES LRCLIB
- `DUMP_CHANNEL_ID` Dumps the Tracks into this channel when multiclient bot doesn't have the track in its database.
- `SUDO_USERS` (user with sudo access)
- `ONLY_API` (True/False) to only use the API and disable the bot.
- `DEBUG` (True/False) to enable debug mode.

</details>

<details>
<summary>SESSION RELATED (FRONTEND) (Optional)</summary>

- `SECRET_KEY` (for session storage)
- `COOKIE_SECURE` (True/False) to enable secure cookies.
- `CORS_ORIGIN` to allow domain
- `COOKIE_SAMESITE` if the backend/frontend are on different domains, set this to `none` and `COOKIE_SECURE` to `true`.

</details>

<details>
<summary>USERBOT</summary>

- `SESSION_STRING` (enables the userbot worker)
- `SOURCE_CHANNEL_IDS` for userbot to dump tracks from one or more channels.

</details>

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

## Quick health check

Once deployed, the API should respond:

- `GET /health` → `{"status":"ok", ...}` (see [health.py](https://github.com/MisfiT2020/StreamXBot/blob/main/Api/routers/health.py))

Example:

```bash
curl -sS https://YOUR_BACKEND_URL/health
```

---

## Frontend deployment (Vite / Vercel / Netlify)

Frontend source in [MisfiT2020/StreamXWeb](https://github.com/MisfiT2020/StreamXWeb) and calls this backend.

### Required frontend env var

Set this in your frontend build env:

- `VITE_API_BASE_URL` = your backend base URL

## BOT CONFIGURATION
Follow these steps to set MiniApp in the bot:

- Open your bot on BotFather
- Go to Bot Settings > Configure Mini App > Edit Mini App URL 
- Set the Mini App URL to your frontend domain (example: `https://your-frontend.vercel.app`)
- Go to Change Mode > Set it to "Full Screen" for better experience.

<details>
<summary>Screenshots</summary>

| 1 |
| --- |
| ![1](https://github.com/MisfiT2020/src/blob/407ebb02fa3bf94e75dccaae4fecefcfb95029d2/streamx/bot1.jpg) |

| 2 | 3 | 4 |
|---|---|---|
| ![2](https://github.com/MisfiT2020/src/blob/407ebb02fa3bf94e75dccaae4fecefcfb95029d2/streamx/bot2.jpg) | ![3](https://github.com/MisfiT2020/src/blob/407ebb02fa3bf94e75dccaae4fecefcfb95029d2/streamx/bot3.jpg) | ![4](https://github.com/MisfiT2020/src/blob/407ebb02fa3bf94e75dccaae4fecefcfb95029d2/streamx/bot4.jpg) |

| 5 | 6 | 7 |
|---|---|---|
| ![5](https://github.com/MisfiT2020/src/blob/407ebb02fa3bf94e75dccaae4fecefcfb95029d2/streamx/bot5.jpg) | ![6](https://github.com/MisfiT2020/src/blob/407ebb02fa3bf94e75dccaae4fecefcfb95029d2/streamx/bot6.jpg) | ![7](https://github.com/MisfiT2020/src/blob/407ebb02fa3bf94e75dccaae4fecefcfb95029d2/streamx/bot7.jpg) |

</details>
