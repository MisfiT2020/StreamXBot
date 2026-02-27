import json
import re
from importlib import import_module
from stream.helpers.logger import LOGGER

def _get_db_handler():
    from stream.database.MongoDb import db_handler
    return db_handler

class RestartRequired(RuntimeError):
    """Raised after a config value is persisted that requires a process restart."""

class Config:
    SECRET_KEYS = {
        "BOT_TOKEN",
        "API_ID",
        "API_HASH",
        "SESSION_STRING",
        "MONGO_URI",
        "MULTI_CLIENTS",
        "ONLY_API",
        "CLOUDINARY_API_SECRET",
    }
    ONLY_API = False
    BOT_TOKEN = ""
    API_ID = 0
    API_HASH = ""
    SESSION_STRING = ""
    MONGO_URI = ""
    DATABASE_NAME = ""
    OWNER_ID = []      
    SUDO_USERS = [] 
    SECRET_KEY = ""
    DEBUG = ""
    CLOUDINARY_CLOUD_NAME = ""
    CLOUDINARY_API_KEY = ""
    CLOUDINARY_API_SECRET = ""
    CORS_ORIGIN = ""
    COOKIE_SECURE = ""
    COOKIE_SAMESITE = ""
    CHANNEL_ID = 0
    DUMP_CHANNEL_ID = 0
    LRCLIB = False
    MUSIXMATCH = True
    SPOTIFY_CLIENT_ID = ""
    SPOTIFY_CLIENT_SECRET = ""
    SOURCE_CHANNEL_IDS = []
    USERBOT_COOLDOWN_SEC = 2
    USERBOT_POLL_INTERVAL_SEC = 300
    USERBOT_BATCH_SIZE = 50
    MULTI_CLIENTS = True
    MULTI_CLIENTS_1 = ""
    MULTI_CLIENTS_2 = ""
    MULTI_CLIENTS_3 = ""
    MULTI_CLIENTS_4 = ""
    MULTI_CLIENT_TOKENS: list[str] = []

    @classmethod
    def _is_secret_key(cls, key: str) -> bool:
        k = str(key or "")
        if k in cls.SECRET_KEYS:
            return True
        if k == "MULTI_CLIENT_TOKENS":
            return True
        if k.startswith("MULTI_CLIENTS_"):
            return True
        return False

    @staticmethod
    def _parse_str_list(value):
        if value is None:
            return []
        if isinstance(value, str):
            s = value.strip()
            if not s:
                return []
            if s.startswith("["):
                try:
                    raw = json.loads(s)
                    return Config._parse_str_list(raw)
                except Exception:
                    return []
            parts = re.split(r"[\s,]+", s)
            return [p for p in (x.strip() for x in parts) if p]
        if isinstance(value, (list, tuple, set)):
            out: list[str] = []
            for v in value:
                sv = str(v or "").strip()
                if sv:
                    out.append(sv)
            return out
        return []

    @classmethod
    def _collect_multi_client_tokens(cls, mapping: dict[str, object]) -> list[str]:
        out: list[str] = []

        for k in ("MULTI_CLIENTS_1", "MULTI_CLIENTS_2", "MULTI_CLIENTS_3", "MULTI_CLIENTS_4"):
            v = (mapping.get(k) or "").strip() if isinstance(mapping.get(k), str) else str(mapping.get(k) or "").strip()
            if v:
                out.append(v)

        extras: list[tuple[int, str]] = []
        extras2: list[str] = []
        for k, v in mapping.items():
            ks = str(k or "").strip()
            if not ks.startswith("MULTI_CLIENTS_") or ks in {"MULTI_CLIENTS", "MULTI_CLIENT_TOKENS"}:
                continue
            suffix = ks[len("MULTI_CLIENTS_") :]
            raw = str(v or "").strip()
            if not raw:
                continue
            if suffix.isdigit():
                extras.append((int(suffix), raw))
            else:
                extras2.append(raw)

        for _, v in sorted(extras, key=lambda x: x[0]):
            out.append(v)
        out.extend(sorted(extras2))

        extra_list = cls._parse_str_list(mapping.get("MULTI_CLIENT_TOKENS"))
        out.extend(extra_list)

        deduped: list[str] = []
        seen: set[str] = set()
        for v in out:
            vv = str(v or "").strip()
            if not vv or vv in seen:
                continue
            seen.add(vv)
            deduped.append(vv)
        return deduped

    @classmethod
    def load(cls):
        """Load defaults from config.py once at boot."""
        external = import_module("config")
        ext_map = {k: getattr(external, k) for k in dir(external) if k.isupper()}
        for key in dir(external):
            if key.isupper() and hasattr(cls, key):
                value = getattr(external, key)
                if key in {"OWNER_ID", "SOURCE_CHANNEL_IDS", "SUDO_USERS", "PREMIUM_USERS"}:
                    value = cls._parse_id_list(value)
                setattr(cls, key, value)
        cls.MULTI_CLIENT_TOKENS = cls._collect_multi_client_tokens(ext_map)

    @staticmethod
    def _is_empty_value(value):
        """Return True for None, empty string, empty list/tuple/set/dict. Keep 0 and False as valid."""
        if value is None:
            return True
        if isinstance(value, str) and value.strip() == "":
            return True
        if isinstance(value, (list, tuple, set, dict)) and len(value) == 0:
            return True
        return False

    @classmethod
    async def load_from_db(cls):
        """Merge config.py → ensure doc exists → apply DB overrides → validate."""
        try:
            cls.load()
            dbh = _get_db_handler()
            doc = await dbh.botsettings.read_document("bot_config")
            if not doc:
                LOGGER(__name__).info("Creating bot_config with defaults")
                await dbh.botsettings.update_document("bot_config", cls.get_all_config())
                doc = await dbh.botsettings.read_document("bot_config")
            else:
                # backfill any missing or empty keys without clobbering non-empty DB values
                defaults = cls.get_all_config()
                updates = {}
                for k, v in defaults.items():
                    # if key missing OR DB value is empty while default is non-empty -> backfill
                    if k not in doc or (cls._is_empty_value(doc.get(k)) and not cls._is_empty_value(v)):
                        updates[k] = v
                if updates:
                    await dbh.botsettings.update_document("bot_config", updates)
                    doc.update(updates)

            # apply DB overrides with type processing
            for key in (k for k in dir(cls) if k.isupper() and k != "SECRET_KEYS"):
                if cls._is_secret_key(key):
                    if key in doc and str(doc.get(key) or "") != str(getattr(cls, key) or ""):
                        LOGGER(__name__).warning(f"{key} in DB differs from ENV — ENV is being used")
                    continue
                if key in doc:
                    setattr(cls, key, cls._process_value(key, doc[key]))

            for key in doc.keys():
                if cls._is_secret_key(str(key)) and str(key) not in dir(cls):
                    LOGGER(__name__).warning(f"{key} in DB differs from ENV — ENV is being used")

            cls._validate_config()
        except Exception as e:
            LOGGER(__name__).error(f"Config loading failed: {e}")
            raise SystemExit(1)

    @classmethod
    async def reload_config(cls):
        """Reload in-memory config from DB without touching the DB."""
        dbh = _get_db_handler()
        data = await dbh.botsettings.read_document("bot_config") or {}
        for key in (k for k in dir(cls) if k.isupper() and k != "SECRET_KEYS"):
            if cls._is_secret_key(key):
                continue
            if key in data:
                setattr(cls, key, cls._process_value(key, data[key]))
        cls._validate_config()

    @classmethod
    async def update_config(cls, key, value):
        """Persist a single key and refresh in-memory state. Raise if restart is needed."""
        if not key.isupper() or not hasattr(cls, key):
            raise KeyError(f"Unknown config key: {key}")
        if key == "SECRET_KEYS":
            raise KeyError(f"Unknown config key: {key}")

        previous = getattr(cls, key)
        processed = cls._process_value(key, value)
        setattr(cls, key, processed)

        dbh = _get_db_handler()
        try:
            await dbh.botsettings.update_document("bot_config", {key: processed})
        except Exception as e:
            # Roll back in-memory change to keep runtime consistent
            setattr(cls, key, previous)
            LOGGER(__name__).error(f"Config.update_config failed to persist '{key}': {e}")
            raise

        if key in cls.SECRET_KEYS:
            raise RestartRequired(f"{key} updated and persisted; restart required")

        await cls.reload_config()
        return processed

    @staticmethod
    def _parse_id_list(value):
        if value is None:
            return []
        if isinstance(value, int):
            return [value]
        if isinstance(value, (list, tuple, set)):
            out = []
            for v in value:
                try:
                    out.append(int(v))
                except Exception:
                    pass
            return sorted(set(out))
        if isinstance(value, str):
            s = value.strip()
            if s.startswith("["):
                try:
                    return Config._parse_id_list(json.loads(s))
                except json.JSONDecodeError:
                    pass
            parts = re.split(r"[\s,]+", s)
            return sorted({int(p) for p in parts if p.isdigit()})
        return []

    @classmethod
    def _process_value(cls, key, value):
        if key in {"OWNER_ID", "SOURCE_CHANNEL_IDS"}:
            return cls._parse_id_list(value)
        if key == "MULTI_CLIENT_TOKENS":
            return cls._parse_str_list(value)

        target_type = type(getattr(cls, key))

        if target_type is bool:
            if isinstance(value, str):
                return value.strip().lower() in {"true", "1", "yes", "y", "on"}
            return bool(value)

        # numerics
        if target_type in (int, float):
            try:
                return target_type(value)
            except (TypeError, ValueError):
                return getattr(cls, key)

        # strings
        if target_type is str:
            return "" if value is None else str(value)

        # fallback
        try:
            return target_type(value)
        except Exception:
            return getattr(cls, key)

    @classmethod
    def _validate_config(cls):
        """Fail fast on obv broken configs."""
        only_api = bool(getattr(cls, "ONLY_API", False))
        if only_api:
            missing = [f for f in ["MONGO_URI", "DATABASE_NAME", "SECRET_KEY"] if not getattr(cls, f)]
            if missing:
                raise SystemExit(f"Missing required fields: {', '.join(missing)}")
        else:
            missing = [f for f in ["API_ID", "API_HASH", "BOT_TOKEN", "OWNER_ID", "MONGO_URI"] if not getattr(cls, f)]
            if missing:
                raise SystemExit(f"Missing required fields: {', '.join(missing)}")

            if not isinstance(cls.OWNER_ID, list) or not cls.OWNER_ID:
                raise SystemExit("OWNER_ID must be a non-empty list of ints")

            if not isinstance(cls.API_ID, int) or cls.API_ID <= 0:
                raise SystemExit("API_ID must be a positive int")

            if not re.fullmatch(r"\d+:[A-Za-z0-9_\-]{20,}", cls.BOT_TOKEN or ""):
                raise SystemExit("BOT_TOKEN format looks invalid")

        if cls.MONGO_URI and not str(cls.MONGO_URI).startswith(("mongodb://", "mongodb+srv://")):
            raise SystemExit("MONGO_URI must start with mongodb:// or mongodb+srv://")

    @classmethod
    def get(cls, key):
        return getattr(cls, key, None)

    @classmethod
    def get_all_config(cls) -> dict:
        return {k: getattr(cls, k) for k in dir(cls) if k.isupper() and k != "SECRET_KEYS"}
