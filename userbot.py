from pyrogram import Client
from config import *

with Client(
    name="USS",
    api_id=API_ID,
    api_hash=API_HASH,
    in_memory=True,
    device_model="Firefox",
    system_version="Windows 10",
    app_version="109.0",
    lang_code="en",
    system_lang_code="en-US",
) as app:
    print(app.export_session_string())
