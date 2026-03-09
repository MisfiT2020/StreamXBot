from fastapi import APIRouter
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
import firebase_admin
from firebase_admin import messaging

router = APIRouter()


@router.get("/test")
async def test():
    return {"status": "ok"}


class FCMTestRequest(BaseModel):
    token: str
    title: str = "Test Notification"
    body: str = "This is a test notification from the backend."

@router.post("/test-fcm")
async def test_fcm_notification(req: FCMTestRequest):
    try:
        if not firebase_admin._apps:
            from firebase_admin import credentials
            from stream.core.config_manager import Config
            import os
            import json
            import base64
            
            b64_cred = os.environ.get("FIREBASE_CRED_B64")
            if b64_cred:
                cred_dict = json.loads(base64.b64decode(b64_cred).decode("utf-8"))
                cred = credentials.Certificate(cred_dict)
            else:
                cred_cfg = Config.FIREBASE_CREDENTIALS
                if cred_cfg and isinstance(cred_cfg, str) and len(cred_cfg) > 20:
                    # XOR Decode using the SECRET_KEY
                    key = Config.SECRET_KEY.encode('utf-8')
                    enc_data = base64.b64decode(cred_cfg)
                    dec_data = bytes(a ^ b for a, b in zip(enc_data, (key * (len(enc_data) // len(key) + 1))[:len(enc_data)]))
                    cred_dict = json.loads(dec_data.decode("utf-8"))
                    cred = credentials.Certificate(cred_dict)
                else:
                    if not cred_cfg or not isinstance(cred_cfg, str):
                        cred_cfg = os.path.join(os.path.dirname(__file__), "..", "..", "service_account.json")
                    cred = credentials.Certificate(cred_cfg)
                
            firebase_admin.initialize_app(cred)
            
        message = messaging.Message(
            notification=messaging.Notification(
                title=req.title,
                body=req.body,
            ),
            token=req.token,
        )
        response = messaging.send(message)
        return {"status": "success", "message_id": response}
    except Exception as e:
        return {"status": "error", "detail": str(e)}


@router.get("/test-stream", response_class=HTMLResponse)
async def test_stream():
    html = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Stream Test</title>

  <style>
    body {
      font-family: system-ui, -apple-system, Segoe UI, Roboto, Arial, sans-serif;
      margin: 24px;
    }
    .row {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
      align-items: center;
    }
    input, button {
      padding: 8px 10px;
      font-size: 14px;
    }
    input { min-width: 360px; }
    audio { width: min(900px, 100%); margin-top: 12px; }
    .hint { color: #555; margin-top: 10px; }
    .small { font-size: 12px; color: #666; }
    .log {
      margin-top: 12px;
      padding: 10px;
      background: #f6f6f6;
      border-radius: 8px;
      white-space: pre-wrap;
    }
    .list { margin-top: 14px; max-width: 980px; }
    .item {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 10px;
      align-items: center;
      padding: 10px 12px;
      border: 1px solid #e7e7e7;
      border-radius: 10px;
      margin-bottom: 10px;
      background: #fff;
    }
    .meta { display: grid; gap: 2px; }
    .title { font-weight: 600; }
    .sub { color: #666; font-size: 13px; }
  </style>
</head>

<body>
  <h2>/test-stream</h2>

  <div class="row">
    <input id="trackId" placeholder="track_id (from /browse or DB _id)" />
    <button id="playBtn">Play</button>
    <button id="browseBtn">Refresh browse</button>
  </div>

  <div class="hint">
    Direct streaming from <code>/tracks/&lt;track_id&gt;/stream</code>
    <div class="small">Range requests supported</div>
  </div>

  <audio id="player" controls preload="metadata"></audio>

  <div id="list" class="list"></div>
  <div id="log" class="log"></div>

  <script>
    const trackIdEl = document.getElementById("trackId");
    const playerEl = document.getElementById("player");
    const logEl = document.getElementById("log");
    const listEl = document.getElementById("list");

    function log(msg) {
      logEl.textContent = msg;
    }

    function makeSrc(trackId) {
      return `/tracks/${encodeURIComponent(trackId)}/stream`;
    }

    async function playTrack(trackId) {
      if (!trackId) {
        log("Enter a track_id first.");
        return;
      }

      const src = makeSrc(trackId);

      if (playerEl.src !== location.origin + src) {
        playerEl.src = src;
      }

      try {
        await playerEl.play();
        log(`Playing: ${src}`);
      } catch (e) {
        log("Playback blocked by browser. Click play once.");
        console.error(e);
      }
    }

    function safeText(v) {
      return v == null ? "" : String(v);
    }

    function fmtItem(it) {
      return {
        id: safeText(it._id || it.id),
        title: safeText(it.title) || "(untitled)",
        sub: [it.artist, it.album].filter(Boolean).join(" • ")
      };
    }

    function renderList(items) {
      listEl.innerHTML = "";

      if (!items.length) {
        listEl.innerHTML = '<div class="small">No items found.</div>';
        return;
      }

      items.forEach(raw => {
        const it = fmtItem(raw);
        if (!it.id) return;

        const row = document.createElement("div");
        row.className = "item";

        const meta = document.createElement("div");
        meta.className = "meta";

        const t = document.createElement("div");
        t.className = "title";
        t.textContent = it.title;

        const s = document.createElement("div");
        s.className = "sub";
        s.textContent = it.sub || it.id;

        meta.appendChild(t);
        meta.appendChild(s);

        const btn = document.createElement("button");
        btn.textContent = "Play";
        btn.onclick = () => {
          trackIdEl.value = it.id;
          playTrack(it.id);
        };

        row.appendChild(meta);
        row.appendChild(btn);
        listEl.appendChild(row);
      });
    }

    async function refreshBrowse() {
      log("Loading browse...");
      const res = await fetch("/browse?page=1", { cache: "no-store" });
      if (!res.ok) {
        log(`Browse failed: ${res.status}`);
        return;
      }
      const data = await res.json();
      renderList(data.items || []);
      log("Browse loaded.");
    }

    document.getElementById("playBtn").onclick = () => {
      playTrack(trackIdEl.value.trim());
    };

    document.getElementById("browseBtn").onclick = refreshBrowse;

    refreshBrowse().catch(() => {});
    log("Ready.");
  </script>
</body>
</html>
"""
    return HTMLResponse(content=html)
