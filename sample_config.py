# - BOT_TOKEN: Get from @BotFather on Telegram (create a new bot).
# - API_ID & API_HASH: Get from https://my.telegram.org/auth (API Development tools).
#   WARNING: Keep these secure! Never share or commit to version control.
BOT_TOKEN = ""  # e.g., "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
API_ID = 00000000  # e.g., 12345678
API_HASH = ""  # e.g., "abcdef1234567890abcdef1234567890"

# =============================================================================
# Database Configuration (MongoDB)
# =============================================================================
# Uncomment ONE option below based on your setup:

# Docker Compose (default)
MONGO_URI = "mongodb://admin:password@mongodb:27017/Stream" 
# make sure docker-compose.yml has the same username/password and that the streamx-bot service has MONGO_URI in its environment variables

# MongoDB Atlas Cloud (comment out Docker option if using this)
# MONGO_URI = "mongodb+srv://username:password@cluster0.mongodb.net/Stream?retryWrites=true&w=majority"

DATABASE_NAME = "Stream"  
OWNER_ID = 100000000  # e.g., 123456789
SUDO_USERS = [100000000]  # e.g., [123456789, 987654321]


SECRET_KEY = ""  # e.g., "your-super-secret-random-key-here"
FIREBASE_CREDENTIALS = ""  # e.g., '{"type": "service_account", ...}'

# DEBUG
DEBUG = False

# THUMBNAIL GENERATION
COLLEGE = False
TEXT_COLOR = "#000000"

# API
CORS_ORIGINS= "*"
COOKIE_SECURE=False
COOKIE_SAMESITE="none"

# CLOUDINARY
CLOUDINARY_CLOUD_NAME = ""
CLOUDINARY_API_KEY = ""
CLOUDINARY_API_SECRET = ""

# USERBOT
SESSION_STRING = ""
SOURCE_CHANNEL_IDS = []

# MISC
CHANNEL_ID = -1000000000
DUMP_CHANNEL_ID = 0

# LYRICS API
LRCLIB = False
MUSIXMATCH = True

# SPOTIFY
SPOTIFY_CLIENT_ID = ""
SPOTIFY_CLIENT_SECRET = ""

# MULTI-CLIENTS
MULTI_CLIENTS = False
MULTI_CLIENTS_1 = ""
MULTI_CLIENTS_2 = ""
MULTI_CLIENTS_3 = ""
MULTI_CLIENTS_4 = ""
