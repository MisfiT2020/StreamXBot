import os
import re
import time
import shutil
import psutil
import random

from PIL import Image, ImageDraw, ImageFont
from pyrogram.types import InputMediaPhoto
from pyrogram import Client, filters
from pyrogram.types import (
    InlineKeyboardMarkup,
    InlineKeyboardButton,
    Message,
    CallbackQuery,
    InputMediaPhoto
)
from pyrogram.errors.exceptions.bad_request_400 import MessageNotModified

from stream import BotStartTime
from stream.helpers.filters import *
from stream.core.config_manager import Config
from stream.helpers.functions import get_readable_bytes, get_readable_time
from stream.database.MongoDb import db_handler

COOKIES_DIR = Config.COOKIES_DIR
os.makedirs(COOKIES_DIR, exist_ok=True)

edit_states = {}
user_states = {}

BOTSETTINGS_IDS = ["Assets/cover.jpg"]

def update_bot_settings_ids(msg):
    global BOTSETTINGS_IDS
    if msg and msg.photo and "Assets/cover.jpg" in BOTSETTINGS_IDS:
        BOTSETTINGS_IDS = [msg.photo.file_id if x == "Assets/cover.jpg" else x for x in BOTSETTINGS_IDS]

async def get_settings_keyboard(page=0, items_per_page=12, edit_mode=False):
    
    settings = Config.get_all_config()
    all_keys = [key for key in settings.keys() if key.isupper() and key != "_id"]
    
    total = len(all_keys)
    start = page * items_per_page
    end = start + items_per_page
    keys_page = all_keys[start:end]
    
    keyboard = []
    row = []
    for key in keys_page:
        callback_data = f"edit_{key}" if edit_mode else f"setting_{key}"
        row.append(InlineKeyboardButton(key, callback_data=callback_data))
        if len(row) == 2:
            keyboard.append(row)
            row = []
    if row:
        keyboard.append(row)
    
    keyboard.append([
        InlineKeyboardButton("View" if edit_mode else "Edit", 
                           callback_data="view_mode" if edit_mode else "edit_mode"),
        InlineKeyboardButton("Back", callback_data=f"back_main_{page}_{edit_mode}")
    ])
    keyboard.append([InlineKeyboardButton("Close", callback_data="close_settings")])
    
    page_buttons = []
    total_pages = (total + items_per_page - 1) // items_per_page
    for i in range(total_pages):
        page_buttons.append(
            InlineKeyboardButton(
                f"{'•' if i == page else str(i)}", 
                callback_data=f"page_{i}_{edit_mode}"
            )
        )
    keyboard.append(page_buttons)
    
    return InlineKeyboardMarkup(keyboard)

def get_readable_size(size_in_bytes):
    units = ["B", "KB", "MB", "GB", "TB"]
    unit_index = 0
    while size_in_bytes >= 1024 and unit_index < len(units) - 1:
        size_in_bytes /= 1024
        unit_index += 1
    return f"{size_in_bytes:.2f} {units[unit_index]}"

@Client.on_message(filters.command("sudo") & sudo_cmd)
async def admin_handler(client, message: Message):
    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("Config", callback_data="config"),
            InlineKeyboardButton("Cookies", callback_data="cookies")
        ],
        [
            InlineKeyboardButton("Stats", callback_data="stats"),
            InlineKeyboardButton("Database", callback_data="database")
        ]
    ])
    sent = await message.reply_photo(
        photo=random.choice(BOTSETTINGS_IDS),
        caption="Admin Panel:",
        reply_markup=keyboard
    )
    update_bot_settings_ids(sent)


@Client.on_callback_query(filters.regex("^config$") & dev_cmd)
async def sys_callback(client, callback_query: CallbackQuery):
    await handle_config(callback_query)

@Client.on_callback_query(filters.regex("^(cookies|stats|database|refresh|back_main)$") & dev_cmd)
async def system_callback(client, callback_query: CallbackQuery):
    cmd = callback_query.data
    try:
        if cmd == "cookies":
            await handle_cookies(callback_query)
        elif cmd == "stats":
            await handle_stats(callback_query)
        elif cmd == "database":
            await handle_database(callback_query)
        elif cmd == "refresh":
            await refresh_panel(callback_query)
        elif cmd == "back_main":
            await refresh_panel(callback_query)

    except MessageNotModified:
        await callback_query.answer("Already up-to-date", show_alert=True)
    except Exception as e:
        await callback_query.message.edit_text(f"Error: {str(e)}")
    finally:
        await callback_query.answer()

@Client.on_callback_query(filters.regex("^refresh$") & dev_cmd)
async def config_refresh_callback(client, callback_query: CallbackQuery):
    try:
        await refresh_panel(callback_query)
    except MessageNotModified:
        await callback_query.answer("Already up-to-date", show_alert=True)
    except Exception as e:
        await callback_query.message.edit_text(f"Error: {str(e)}")
    finally:
        await callback_query.answer()

@Client.on_message(filters.command("bs") & filters.user(Config.OWNER_ID))
async def settings_menu(client: Client, message: Message):
    keyboard = await get_settings_keyboard()
    await message.reply_text(
        "Config Variables | Page: 0 | State: view",
        reply_markup=keyboard
    )

async def edit_message(query: CallbackQuery, text: str, reply_markup=None):
    if query.message.photo:
        return await query.message.edit_caption(caption=text, reply_markup=reply_markup)
    return await query.message.edit_text(text=text, reply_markup=reply_markup)

async def handle_config(callback_query: CallbackQuery):
    keyboard = await get_settings_keyboard()
    await edit_message(callback_query, "Config Variables | Page: 0 | State: view", keyboard)

@Client.on_message(filters.document & filters.user(Config.OWNER_ID))
async def handle_cookie_upload(client, message: Message):
    if message.from_user.id not in user_states:
        return

    document = message.document
    if not document.file_name.endswith(".txt"):
        await message.reply("Only .txt files are allowed.")
        return

    try:
        file_path = os.path.join(COOKIES_DIR, document.file_name)
        await message.download(file_path)
        
        if os.path.exists(file_path):
            await message.reply(f"Cookies file saved: {file_path}")
        else:
            await message.reply("Failed to save cookie file")
            
        await message.delete()

        original_message_id = user_states.pop(message.from_user.id)
        await client.edit_message_text(
            chat_id=message.chat.id,
            message_id=original_message_id,
            text="Admin Panel:",
            reply_markup=InlineKeyboardMarkup([
                [
                    InlineKeyboardButton("Config", callback_data="config"),
                    InlineKeyboardButton("Cookies", callback_data="cookies")
                ],
                [
                    InlineKeyboardButton("Stats", callback_data="stats"),
                    InlineKeyboardButton("Database", callback_data="database")
                ]
            ])
        )
    except Exception as e:
        await message.reply(f"Error: {e}")

async def handle_cookies(callback_query: CallbackQuery):
    await edit_message(
        callback_query, 
        "Send .txt file for cookies storage\nExample: yt.txt",
        InlineKeyboardMarkup([[InlineKeyboardButton("Back", callback_data="refresh")]])
    )
    user_states[callback_query.from_user.id] = callback_query.message.id

async def handle_stats(callback_query: CallbackQuery):
    start_time = time.time()
    image = Image.open("Assets/statsbg.png").convert("RGB")
    font = ImageFont.truetype("Assets/IronFont.otf", 42)
    draw = ImageDraw.Draw(image)

    disk_total, disk_used, disk_free = get_disk_usage()
    ram_total, ram_used, ram_percent = get_ram_usage()
    cpu_percent, cpu_count = get_cpu_info()
    net_upload, net_download = get_network_usage()
    bot_uptime = get_readable_time(time.time() - BotStartTime)
    os_uptime = get_readable_time(time.time() - psutil.boot_time())
    bot_usage = f"{psutil.Process(os.getpid()).memory_info().rss / 1024 ** 2:.1f} MiB"

    def draw_progressbar(y, percentage):
        x_start = 120
        x_end = 120 + int(percentage * 10.8)
        draw.ellipse((105, y-25, 127, y), fill="#DDFD35")
        draw.rectangle([(x_start, y-25), (x_end, y)], fill="#DDFD35")
        draw.ellipse((x_end-7, y-25, x_end+15, y), fill="#DDFD35")

    draw_progressbar(243, cpu_percent)
    draw.text((225, 153), f"{cpu_count} cores | {cpu_percent}%", (255,255,255), font=font)
    draw_progressbar(395, psutil.disk_usage('/').percent)
    draw.text((335, 302), f"{disk_used}/{disk_total}", (255,255,255), font=font)
    draw_progressbar(533, ram_percent)
    draw.text((225, 445), f"{ram_used}/{ram_total}", (255,255,255), font=font)
    draw.text((290, 590), bot_uptime, (255,255,255), font=font)
    draw.text((910, 590), f"{(time.time()-start_time)*1000:.1f} ms", (255,255,255), font=font)

    image.save("stats_temp.png")
    await callback_query.message.edit_media(
        media=InputMediaPhoto(
            "stats_temp.png",
            caption=f"OS Uptime: {os_uptime}\nBot RAM: {bot_usage}\n"
                    f"Storage: {disk_free} free\nNetwork: ↓{net_download} ↑{net_upload}"
        ),
        reply_markup=InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Refresh", callback_data="stats"),
                InlineKeyboardButton("Back", callback_data="refresh")
            ]
        ])
    )
    os.remove("stats_temp.png")

async def handle_database(callback_query: CallbackQuery):
    try:
        TotalUsers = await db_handler.users.total_documents()
        TotalChats = await db_handler.chats_collection.total_documents()
        TotalChannels = await db_handler.channels_collection.total_documents()
        
        stats_string = (
            "**Database Statistics**\n\n"
            f"• Users: {TotalUsers}\n"
            f"• Chats: {TotalChats}\n"
            f"• Channels: {TotalChannels}"
        )
    except Exception as e:
        stats_string = f"**Database Statistics**\n\nError: {str(e)}"
    
    await edit_message(
        callback_query,
        stats_string,
        InlineKeyboardMarkup([[InlineKeyboardButton("Back", callback_data="refresh")]])
    )

async def refresh_panel(callback_query: CallbackQuery):
    if callback_query.from_user.id in edit_states:
        del edit_states[callback_query.from_user.id]
    if callback_query.from_user.id in user_states:
        del user_states[callback_query.from_user.id]

    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("Config", callback_data="config"),
            InlineKeyboardButton("Cookies", callback_data="cookies")
        ],
        [
            InlineKeyboardButton("Stats", callback_data="stats"),
            InlineKeyboardButton("Database", callback_data="database")
        ]
    ])

    current_photo = callback_query.message.photo
    is_cover = False
    if current_photo:
        for cid in BOTSETTINGS_IDS:
            if current_photo.file_id == cid:
                is_cover = True
                break
    
    if is_cover:
        await callback_query.message.edit_caption(
            caption="Admin Panel",
            reply_markup=keyboard
        )
    else:
        sent = await callback_query.message.edit_media(
            media=InputMediaPhoto(
                random.choice(BOTSETTINGS_IDS),
                caption="Admin Panel"
            ),
            reply_markup=keyboard
        )
        update_bot_settings_ids(sent)

def get_disk_usage():
    usage = shutil.disk_usage('.')
    return (
        get_readable_bytes(usage.total),
        get_readable_bytes(usage.used),
        get_readable_bytes(usage.free)
    )

def get_ram_usage():
    mem = psutil.virtual_memory()
    return (
        get_readable_bytes(mem.total),
        get_readable_bytes(mem.used),
        mem.percent
    )

def get_cpu_info():
    return psutil.cpu_percent(), psutil.cpu_count()

def get_network_usage():
    net = psutil.net_io_counters()
    return (
        get_readable_bytes(net.bytes_sent),
        get_readable_bytes(net.bytes_recv)
    )

@Client.on_callback_query(filters.regex(r"^back_main_") & dev_cmd)
async def back_main(client: Client, query: CallbackQuery):
    if query.from_user.id in edit_states:
        del edit_states[query.from_user.id]
    if query.from_user.id in user_states:
        del user_states[query.from_user.id]

    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("Config", callback_data="config"),
            InlineKeyboardButton("Cookies", callback_data="cookies")
        ],
        [
            InlineKeyboardButton("Stats", callback_data="stats"),
            InlineKeyboardButton("Database", callback_data="database")
        ]
    ])

    current_photo = query.message.photo
    is_cover = False
    if current_photo:
        for cid in BOTSETTINGS_IDS:
            if current_photo.file_id == cid:
                is_cover = True
                break
    
    if is_cover:
        await query.message.edit_caption(
            caption="Admin Panel:",
            reply_markup=keyboard
        )
    else:
        sent = await query.message.edit_media(
            media=InputMediaPhoto(
                random.choice(BOTSETTINGS_IDS),
                caption="Admin Panel:"
            ),
            reply_markup=keyboard
        )
        update_bot_settings_ids(sent)

@Client.on_callback_query(filters.regex(r"^setting_") & dev_cmd)
async def handle_setting(client: Client, query: CallbackQuery):
    key = query.data.split("_", 1)[1]
    value = Config.get(key)
    await query.answer(f"{key}: {value}", show_alert=True)

@Client.on_callback_query(filters.regex(r"^edit_mode$") & dev_cmd)
async def toggle_edit_mode(client: Client, query: CallbackQuery):
    current_page = 0
    for row in query.message.reply_markup.inline_keyboard:
        for button in row:
            if "•" in button.text:
                current_page = int(button.callback_data.split("_")[1])
                break
    
    keyboard = await get_settings_keyboard(page=current_page, edit_mode=True)
    await edit_message(
        query,
        f"Config Variables | Page: {current_page} | State: edit",
        keyboard
    )

@Client.on_callback_query(filters.regex(r"^view_mode$") & dev_cmd)
async def toggle_view_mode(client: Client, query: CallbackQuery):
    current_page = 0
    for row in query.message.reply_markup.inline_keyboard:
        for button in row:
            if "•" in button.text:
                current_page = int(button.callback_data.split("_")[1])
                break
    
    keyboard = await get_settings_keyboard(page=current_page, edit_mode=False)
    await edit_message(
        query,
        f"Config Variables | Page: {current_page} | State: view",
        keyboard
    )

@Client.on_callback_query(filters.regex(r"^page_") & dev_cmd)
async def change_page(client: Client, query: CallbackQuery):
    data = query.data.split("_")
    page = int(data[1])
    edit_mode = data[2] == "True" if len(data) > 2 else False
    keyboard = await get_settings_keyboard(page, edit_mode=edit_mode)
    state = "edit" if edit_mode else "view"
    try:
        await edit_message(
            query,
            f"Config Variables | Page: {page} | State: {state}",
            keyboard
        )
    except MessageNotModified:
        try:
            await query.answer("Already on this page", show_alert=False)
        except Exception:
            pass
    except Exception:
        try:
            await query.answer("Failed to update page", show_alert=False)
        except Exception:
            pass
    finally:
        try:
            await query.answer()
        except Exception:
            pass

@Client.on_callback_query(filters.regex(r"^edit_") & dev_cmd)
async def edit_setting(client: Client, query: CallbackQuery):
    key = query.data.split("_", 1)[1]
    user_id = query.from_user.id
    
    current_page = 0
    for row in query.message.reply_markup.inline_keyboard:
        for button in row:
            if "•" in button.text:
                current_page = int(button.callback_data.split("_")[1])
                break
    
    if key in ("OWNER_ID", "SUDO_USERS"):
        edit_states[user_id] = {
            "key": key,
            "message_id": query.message.id,
            "page": current_page,
            "edit_mode": True,
            "is_photo": bool(query.message.photo)
        }

        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Add", callback_data=f"manage_add_{key}"),
                InlineKeyboardButton("Remove", callback_data=f"manage_remove_{key}")
            ],
            [
                InlineKeyboardButton("Type", callback_data=f"type_menu_{key}"),
                InlineKeyboardButton("Clear", callback_data=f"clear_setting_{key}")
            ],
            [
                InlineKeyboardButton("Back", callback_data=f"back_settings_{current_page}_True"),
                InlineKeyboardButton("Close", callback_data="close_settings")
            ]
        ])

        await edit_message(
            query,
            f"Manage {key}: Choose action.",
            keyboard
        )
        return

    edit_states[user_id] = {
        "key": key,
        "message_id": query.message.id,
        "page": current_page,
        "edit_mode": True,
        "is_photo": bool(query.message.photo)
    }

    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("Type", callback_data=f"type_menu_{key}"),
            InlineKeyboardButton("Clear", callback_data=f"clear_setting_{key}")
        ],
        [
            InlineKeyboardButton("Back", callback_data=f"back_settings_{current_page}_True"),
            InlineKeyboardButton("Close", callback_data="close_settings")
        ]
    ])

    await edit_message(
        query,
        f"Send new value for {key}:",
        keyboard
    )


@Client.on_callback_query(filters.regex(r"^clear_setting_") & dev_cmd)
async def clear_setting_callback(client: Client, query: CallbackQuery):
    key = query.data.split("_", 2)[2]
    user_id = query.from_user.id
    
    current_page = 0
    for row in query.message.reply_markup.inline_keyboard:
        for button in row:
            if "•" in button.text:
                current_page = int(button.callback_data.split("_")[1])
                break
                
    current_value = Config.get(key)
    if isinstance(current_value, list):
        empty_val = []
    elif isinstance(current_value, bool):
        empty_val = False
    elif isinstance(current_value, int):
        empty_val = 0
    elif isinstance(current_value, float):
        empty_val = 0.0
    else:
        empty_val = ""
        
    try:
        await Config.update_config(key, empty_val)
        await query.answer(f"Cleared {key}!", show_alert=True)
        keyboard = await get_settings_keyboard(page=current_page, edit_mode=True)
        await edit_message(
            query,
            f"Config Variables | Page: {current_page} | State: edit\n\nCleared {key}.",
            keyboard
        )
    except Exception as e:
        await query.answer(f"Failed to clear {key}: {e}", show_alert=True)


@Client.on_callback_query(filters.regex(r"^type_menu_") & dev_cmd)
async def type_menu_callback(client: Client, query: CallbackQuery):
    key = query.data.split("_", 2)[2]
    user_id = query.from_user.id
    
    current_page = 0
    for row in query.message.reply_markup.inline_keyboard:
        for button in row:
            if "•" in button.text:
                current_page = int(button.callback_data.split("_")[1])
                break

    override_type = Config._OVERRIDE_TYPES.get(key, "default")
    default_type = type(getattr(Config, key)).__name__
    
    caption = (
        f"**Type Configuration for {key}**\n\n"
        f"• Default type: `{default_type}`\n"
        f"• Active type: `{override_type if override_type != 'default' else default_type + ' (default)'}`\n\n"
        f"Choose a new type for this setting:"
    )
    
    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("str" + (" ✓" if override_type == "str" else ""), callback_data=f"set_type_{key}_str"),
            InlineKeyboardButton("int" + (" ✓" if override_type == "int" else ""), callback_data=f"set_type_{key}_int"),
        ],
        [
            InlineKeyboardButton("bool" + (" ✓" if override_type == "bool" else ""), callback_data=f"set_type_{key}_bool"),
            InlineKeyboardButton("list" + (" ✓" if override_type == "list" else ""), callback_data=f"set_type_{key}_list"),
        ],
        [
            InlineKeyboardButton("Default" + (" ✓" if override_type == "default" else ""), callback_data=f"set_type_{key}_default"),
        ],
        [
            InlineKeyboardButton("Back", callback_data=f"edit_{key}"),
            InlineKeyboardButton("Close", callback_data="close_settings")
        ]
    ])
    
    await edit_message(query, caption, keyboard)


@Client.on_callback_query(filters.regex(r"^set_type_") & dev_cmd)
async def set_type_callback(client: Client, query: CallbackQuery):
    parts = query.data.split("_")
    type_str = parts[-1]
    key = "_".join(parts[2:-1])
    
    try:
        await Config.update_config_type(key, type_str)
        await query.answer(f"Type of {key} updated to {type_str}!", show_alert=True)
    except Exception as e:
        await query.answer(f"Failed to update type: {e}", show_alert=True)
        return
        
    override_type = Config._OVERRIDE_TYPES.get(key, "default")
    default_type = type(getattr(Config, key)).__name__
    
    caption = (
        f"**Type Configuration for {key}**\n\n"
        f"• Default type: `{default_type}`\n"
        f"• Active type: `{override_type if override_type != 'default' else default_type + ' (default)'}`\n\n"
        f"Choose a new type for this setting:"
    )
    
    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("str" + (" ✓" if override_type == "str" else ""), callback_data=f"set_type_{key}_str"),
            InlineKeyboardButton("int" + (" ✓" if override_type == "int" else ""), callback_data=f"set_type_{key}_int"),
        ],
        [
            InlineKeyboardButton("bool" + (" ✓" if override_type == "bool" else ""), callback_data=f"set_type_{key}_bool"),
            InlineKeyboardButton("list" + (" ✓" if override_type == "list" else ""), callback_data=f"set_type_{key}_list"),
        ],
        [
            InlineKeyboardButton("Default" + (" ✓" if override_type == "default" else ""), callback_data=f"set_type_{key}_default"),
        ],
        [
            InlineKeyboardButton("Back", callback_data=f"edit_{key}"),
            InlineKeyboardButton("Close", callback_data="close_settings")
        ]
    ])
    
    await edit_message(query, caption, keyboard)

@Client.on_callback_query(filters.regex(r"^back_settings_") & dev_cmd)
async def back_settings(client: Client, query: CallbackQuery):
    data = query.data.split("_")
    page = int(data[2])  
    edit_mode = data[3] == "True"  

    
    if query.from_user.id in edit_states:
        del edit_states[query.from_user.id]

    
    keyboard = await get_settings_keyboard(page=page, edit_mode=edit_mode)
    state = "edit" if edit_mode else "view"

    
    await edit_message(
        query,
        f"Config Variables | Page: {page} | State: {state}",
        keyboard
    )

@Client.on_callback_query(filters.regex(r"^manage_(add|remove)_(OWNER_ID|SUDO_USERS)$") & dev_cmd)
async def manage_owner_sudo_action(client: Client, query: CallbackQuery):
    parts = query.data.split("_", 2)
    try:
        action = parts[1]
        key = parts[2]
    except Exception:
        try:
            await query.answer("Invalid action.", show_alert=True)
        except Exception:
            pass
        return

    if key == "SUDO":
        key = "SUDO_USERS"
    if key == "OWNER":
        key = "OWNER_ID"

    user_id = query.from_user.id
    state = edit_states.get(user_id) or {}
    state.update({"action": action, "key": key, "message_id": query.message.id})
    edit_states[user_id] = state

    try:
        await query.answer(f"Send the user ID to {action}.")
    except Exception:
        pass

    page = state.get("page", 0)
    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("Back", callback_data=f"back_settings_{page}_True"),
            InlineKeyboardButton("Close", callback_data="close_settings")
        ]
    ])
    await edit_message(
        query,
        f"Send the Telegram user ID to {action} {key}:",
        keyboard
    )

async def edit_panel_message(client: Client, chat_id: int, state: dict, text: str, reply_markup=None):
    if state.get("is_photo"):
        return await client.edit_message_caption(chat_id, state["message_id"], caption=text, reply_markup=reply_markup)
    return await client.edit_message_text(chat_id, state["message_id"], text=text, reply_markup=reply_markup)

@Client.on_message(filters.user(Config.OWNER_ID) & filters.text & ~filters.command(["sudo"]), group=7)
async def receive_add_remove_value(client: Client, message: Message):
    user_id = message.from_user.id
    if user_id not in edit_states:
        return
    state = edit_states[user_id]
    key = state.get("key")
    if key == "SUDO":
        key = "SUDO_USERS"
    if key == "OWNER":
        key = "OWNER_ID"
    action = state.get("action")
    if key not in ("OWNER_ID", "SUDO_USERS") or action not in ("add", "remove"):
        return

    try:
        await message.delete()
    except Exception:
        pass

    text = (message.text or "").strip()
    tokens = [t for t in re.split(r"[\s,]+", text) if t]
    ids = []
    for t in tokens:
        try:
            ids.append(int(t))
        except Exception:
            pass
    if not ids:
        page = state.get("page", 0)
        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Back", callback_data=f"back_settings_{page}_True"),
                InlineKeyboardButton("Close", callback_data="close_settings")
            ]
        ])
        await edit_panel_message(
            client,
            message.chat.id,
            state,
            f"Invalid ID. Please try again.",
            keyboard
        )
        return

    current = list(Config.get(key) or [])
    if action == "add":
        current = sorted(set(current + ids))
    else:
        current = [uid for uid in current if uid not in set(ids)]

    try:
        await Config.update_config(key, current)
    except Exception as e:
        page = state.get("page", 0)
        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Back", callback_data=f"back_settings_{page}_True"),
                InlineKeyboardButton("Close", callback_data="close_settings")
            ]
        ])
        await edit_panel_message(
            client,
            message.chat.id,
            state,
            f"ㄨ Error: {str(e)}",
            keyboard
        )
        del edit_states[user_id]
        return

    page = state.get("page", 0)
    keyboard = await get_settings_keyboard(page=page, edit_mode=True)
    await edit_panel_message(
        client,
        message.chat.id,
        state,
        f"Config Variables | Page: {page} | State: edit",
        keyboard
    )

    del edit_states[user_id]
@Client.on_message(filters.user(Config.OWNER_ID) & filters.text & ~filters.command(["sudo"]), group=6)
async def receive_new_value(client: Client, message: Message):
    user_id = message.from_user.id
    if user_id not in edit_states:
        return
    
    state = edit_states[user_id]
    key = state["key"]
    if key in ("OWNER_ID", "SUDO_USERS"):
        try:
            await message.delete()
        except Exception:
            pass
        return
    page = state["page"]
    edit_mode = state["edit_mode"]
    new_value = message.text
    
    await message.delete()
    
    try:
        
        current_value = Config.get(key)
        new_processed_value = await Config.update_config(key, new_value)
        
        
        feedback = f"Updated {key}:\n"
        feedback += f"Old value: {current_value}\n"
        feedback += f"New value: {new_processed_value}"
        
        
        if type(current_value) != type(new_processed_value):
            feedback += f"\n\nNote: Value type changed from {type(current_value).__name__} to {type(new_processed_value).__name__}"
        
        keyboard = await get_settings_keyboard(page=page, edit_mode=edit_mode)
        await edit_panel_message(
            client,
            message.chat.id,
            state,
            f"Config Variables | Page: {page} | State: {'edit' if edit_mode else 'view'}\n\n{feedback}",
            keyboard
        )
    except ValueError as ve:
        
        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Back", callback_data=f"back_settings_{page}_{edit_mode}"),
                InlineKeyboardButton("Close", callback_data="close_settings")
            ]
        ])
        await edit_panel_message(
            client,
            message.chat.id,
            state,
            f"ⓘ {str(ve)}",
            keyboard
        )
    except Exception as error:
        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Back", callback_data=f"back_settings_{page}_{edit_mode}"),
                InlineKeyboardButton("Close", callback_data="close_settings")
            ]
        ])
        await edit_panel_message(
            client,
            message.chat.id,
            state,
            f"ㄨ Error updating setting: {str(error)}",
            keyboard
        )
    finally:
        del edit_states[user_id]

@Client.on_callback_query(filters.regex("^close_settings$") & dev_cmd)
async def close_menu(client: Client, query: CallbackQuery):
    
    if query.from_user.id in edit_states:
        del edit_states[query.from_user.id]
    await query.message.delete()


@Client.on_message(filters.command(["filter_mode", "filtermode"]) & sudo_cmd)
async def filter_mode_handler(client: Client, message: Message):
    from stream.core.source_filter import (
        FilterMode,
        get_filter_mode,
        invalidate_cache,
    )

    args = message.text.split()[1:] if message.text else []
    if not args:
        current = get_filter_mode()
        await message.reply_text(
            f"**Current Filter Mode:** `{current}` (`{FilterMode.to_string(current)}`)\n\n"
            "**Modes:**\n"
            "• `0` / `group_only`: Preferred channel only\n"
            "• `1` / `anyone`: Accept files from any source\n"
            "• `2` / `hybrid`: Accept files from configured allowlist only\n\n"
            "**Usage:** `/filter_mode <0|1|2|hybrid>`"
        )
        return

    val = args[0].strip().lower()
    new_mode = FilterMode.parse(val)
    await Config.update_config("FILTER_MODE", new_mode)
    invalidate_cache()
    await message.reply_text(
        f"ꪜ Filter mode updated to `{new_mode}` (`{FilterMode.to_string(new_mode)}`)."
    )


async def _resolve_target_and_peer(
    client: Client,
    message: Message,
    args: list[str],
) -> tuple[int | None, str, str, str]:
    """Resolve target ID, source_type, name, and remaining extra text.

    Resolves from:
    1. Replied message (forward_from_chat, sender_chat, from_user, forward_from)
    2. Arguments (ID or @username)
    3. Telegram client / Multi-client / Userbot peer resolution
    4. Database stored records (channels, chats, users)
    """
    reply = message.reply_to_message
    raw_target = None
    extra_tokens = []

    if args:
        raw_target = args[0].strip()
        extra_tokens = args[1:]
    elif reply:
        entity = (
            getattr(reply, "forward_from_chat", None)
            or getattr(reply, "sender_chat", None)
            or getattr(reply, "from_user", None)
            or getattr(reply, "forward_from", None)
        )
        if entity:
            raw_target = getattr(entity, "id", None)

    if raw_target is None or str(raw_target).strip() == "":
        return None, "", "", ""

    # Parse numeric IDs so Pyrogram does not treat them as phone numbers
    target_peer = raw_target
    if isinstance(raw_target, str):
        cleaned = raw_target.strip()
        # Handle t.me links e.g. https://t.me/c/2427151389/12 or https://t.me/channelname
        if "t.me/" in cleaned:
            parts = cleaned.rstrip("/").split("/")
            last_part = parts[-1]
            if len(parts) >= 2 and parts[-2] == "c" and last_part.isdigit():
                cleaned = f"-100{last_part}"
            elif len(parts) >= 3 and parts[-3] == "c" and parts[-2].isdigit():
                cleaned = f"-100{parts[-2]}"
            else:
                cleaned = last_part

        try:
            target_peer = int(cleaned)
        except (ValueError, TypeError):
            target_peer = cleaned

    # Attempt resolution via Telegram clients (primary bot, multi-clients, userbot)
    chat = None
    try:
        chat = await client.get_chat(target_peer)
    except Exception:
        chat = None

    if not chat:
        try:
            from stream import multi_clients

            for c in (multi_clients or {}).values():
                if c is not client:
                    try:
                        chat = await c.get_chat(target_peer)
                        if chat:
                            break
                    except Exception:
                        pass
        except Exception:
            pass

    if not chat:
        try:
            from stream.plugins.userBot.service import _USERBOT_INSTANCE

            if _USERBOT_INSTANCE and getattr(_USERBOT_INSTANCE, "is_connected", False):
                chat = await _USERBOT_INSTANCE.get_chat(target_peer)
        except Exception:
            chat = None

    if chat:
        source_id = int(chat.id)
        chat_type_str = str(getattr(chat, "type", "")).lower()

        if "channel" in chat_type_str:
            source_type = "channel"
            name = (
                getattr(chat, "title", None)
                or (f"@{chat.username}" if getattr(chat, "username", None) else "")
                or f"Channel {source_id}"
            )
        elif "group" in chat_type_str or "supergroup" in chat_type_str:
            source_type = "group"
            name = (
                getattr(chat, "title", None)
                or (f"@{chat.username}" if getattr(chat, "username", None) else "")
                or f"Group {source_id}"
            )
        else:
            source_type = "user"
            fn = getattr(chat, "first_name", "") or ""
            ln = getattr(chat, "last_name", "") or ""
            full_name = f"{fn} {ln}".strip()
            if full_name:
                name = (
                    f"{full_name} (@{chat.username})"
                    if getattr(chat, "username", None)
                    else full_name
                )
            elif getattr(chat, "username", None):
                name = f"@{chat.username}"
            else:
                name = f"User {source_id}"

        # Cache in DB
        try:
            from stream.database.MongoDb import db_handler

            if source_type == "channel":
                await db_handler.channels_collection.collection.update_one(
                    {"_id": source_id},
                    {
                        "$set": {
                            "title": name,
                            "type": source_type,
                            "username": getattr(chat, "username", None),
                        }
                    },
                    upsert=True,
                )
            elif source_type == "group":
                await db_handler.chats_collection.collection.update_one(
                    {"_id": source_id},
                    {
                        "$set": {
                            "title": name,
                            "type": source_type,
                            "username": getattr(chat, "username", None),
                        }
                    },
                    upsert=True,
                )
            elif source_type == "user":
                await db_handler.users.collection.update_one(
                    {"_id": source_id},
                    {
                        "$set": {
                            "name": name,
                            "username": getattr(chat, "username", None),
                        }
                    },
                    upsert=True,
                )
        except Exception:
            pass

        extra_text = " ".join(extra_tokens).strip()
        return source_id, source_type, name, extra_text

    # If get_chat failed, resolve from database / fallback
    try:
        source_id = int(target_peer)
    except (ValueError, TypeError):
        return None, "", "", f"Could not resolve '{raw_target}' to a valid Telegram chat or user ID."

    explicit_type = None
    if extra_tokens and extra_tokens[0].lower() in {"channel", "group", "user"}:
        explicit_type = extra_tokens[0].lower()
        extra_tokens = extra_tokens[1:]

    source_type = explicit_type or ("channel" if source_id < 0 else "user")

    # Check MongoDB: channels, chats, users, allowed_sources, banned_sources
    from stream.database.MongoDb import db_handler

    name = ""
    try:
        # 1. Check channels_collection
        doc = await db_handler.channels_collection.collection.find_one(
            {"$or": [{"_id": source_id}, {"_id": str(source_id)}]}
        )
        if doc and (doc.get("title") or doc.get("name")):
            name = doc.get("title") or doc.get("name")
            if not explicit_type:
                raw_t = str(doc.get("type", "")).lower()
                if "channel" in raw_t:
                    source_type = "channel"
                elif "group" in raw_t:
                    source_type = "group"
                else:
                    source_type = "channel" if source_id < 0 else "user"
            if doc.get("username") and f"@{doc.get('username')}" not in name:
                name += f" (@{doc.get('username')})"

        # 2. Check chats_collection
        if not name:
            doc = await db_handler.chats_collection.collection.find_one(
                {"$or": [{"_id": source_id}, {"_id": str(source_id)}]}
            )
            if doc and (doc.get("title") or doc.get("name")):
                name = doc.get("title") or doc.get("name")
                if not explicit_type:
                    raw_t = str(doc.get("type", "")).lower()
                    if "channel" in raw_t:
                        source_type = "channel"
                    elif "group" in raw_t:
                        source_type = "group"
                    else:
                        source_type = "group" if source_id < 0 else "user"
                if doc.get("username") and f"@{doc.get('username')}" not in name:
                    name += f" (@{doc.get('username')})"

        # 3. Check users collection
        if not name:
            doc = await db_handler.users.collection.find_one(
                {"$or": [{"_id": source_id}, {"_id": str(source_id)}]}
            )
            if doc:
                fn = doc.get("first_name") or ""
                ln = doc.get("last_name") or ""
                uname = doc.get("username")
                full_name = f"{fn} {ln}".strip() or doc.get("name")
                if full_name:
                    name = f"{full_name} (@{uname})" if uname else full_name
                elif uname:
                    name = f"@{uname}"
                if not explicit_type:
                    source_type = "user"

        # 4. Check allowed_sources / banned_sources
        if not name:
            doc = await db_handler.allowed_sources.collection.find_one(
                {"source_id": source_id}
            )
            if not doc:
                doc = await db_handler.banned_sources.collection.find_one(
                    {"source_id": source_id}
                )
            if (
                doc
                and doc.get("name")
                and not doc.get("name").startswith(("Channel -", "User ", "Group -"))
            ):
                name = doc.get("name")
                if not explicit_type and doc.get("source_type"):
                    source_type = doc.get("source_type")
    except Exception:
        pass

    if not name:
        name = f"{source_type.capitalize()} {source_id}"

    extra_text = " ".join(extra_tokens).strip()
    return source_id, source_type, name, extra_text


@Client.on_message(filters.command(["allow", "allow_source", "allowsource"]) & sudo_cmd)
async def allow_source_handler(client: Client, message: Message):
    from stream.core.source_filter import add_allowed_source

    args = message.text.split()[1:] if message.text else []
    source_id, source_type, name, extra_text = await _resolve_target_and_peer(
        client, message, args
    )

    if source_id is None:
        await message.reply_text(
            "**Usage:** `/allow <source_id|@username> [custom name]` (or reply to a message)\n"
            "Example: `/allow -100123456789 PZP Music`"
        )
        return

    # If extra text provided, use as custom name override
    if extra_text:
        name = extra_text

    try:
        doc = await add_allowed_source(
            source_id=source_id,
            source_type=source_type,
            name=name,
            added_by=message.from_user.id if message.from_user else 0,
        )
        await message.reply_text(
            f"ꪜ **Added to Allowed Sources:**\n"
            f"• **ID:** `{source_id}`\n"
            f"• **Name:** `{name or 'N/A'}`\n"
            f"• **Type:** `{source_type}`"
        )
    except Exception as e:
        await message.reply_text(f"ㄨ Error adding allowed source: {e}")


@Client.on_message(
    filters.command(["disallow", "disallow_source", "disallowsource"]) & sudo_cmd
)
async def disallow_source_handler(client: Client, message: Message):
    from stream.core.source_filter import remove_allowed_source

    args = message.text.split()[1:] if message.text else []
    source_id, source_type, name, _ = await _resolve_target_and_peer(
        client, message, args
    )

    if source_id is None:
        await message.reply_text(
            "**Usage:** `/disallow <source_id|@username>` (or reply to a message)"
        )
        return

    removed = await remove_allowed_source(source_id)
    if removed:
        await message.reply_text(
            f"ꪜ **Removed from Allowed Sources:**\n"
            f"• **ID:** `{source_id}`\n"
            f"• **Name:** `{name or 'N/A'}`\n"
            f"• **Type:** `{source_type}`"
        )
    else:
        await message.reply_text(
            f"ⓘ Source `{source_id}` was not found in allowed sources."
        )


@Client.on_message(
    filters.command(["ban", "ban_source", "bansources", "bansource"]) & sudo_cmd
)
async def ban_source_handler(client: Client, message: Message):
    from stream.core.source_filter import ban_source

    args = message.text.split()[1:] if message.text else []
    source_id, source_type, name, reason = await _resolve_target_and_peer(
        client, message, args
    )

    if source_id is None:
        await message.reply_text(
            "**Usage:** `/ban <source_id|@username> [reason]` (or reply to a message)\n"
            "Example: `/ban 123456789 Spamming tracks`"
        )
        return

    try:
        doc = await ban_source(
            source_id=source_id,
            source_type=source_type,
            name=name,
            reason=reason,
            banned_by=message.from_user.id if message.from_user else 0,
        )
        await message.reply_text(
            f"⊘ **Source Banned:**\n"
            f"• **ID:** `{source_id}`\n"
            f"• **Name:** `{name or 'N/A'}`\n"
            f"• **Type:** `{source_type}`\n"
            f"• **Reason:** `{reason or 'No reason provided'}`\n\n"
            "_This source is strictly blocked from adding tracks and using the service._"
        )
    except Exception as e:
        await message.reply_text(f"ㄨ Error banning source: {e}")


@Client.on_message(
    filters.command(["unban", "unban_source", "unbansources", "unbansource"]) & sudo_cmd
)
async def unban_source_handler(client: Client, message: Message):
    from stream.core.source_filter import unban_source

    args = message.text.split()[1:] if message.text else []
    source_id, source_type, name, _ = await _resolve_target_and_peer(
        client, message, args
    )

    if source_id is None:
        await message.reply_text(
            "**Usage:** `/unban <source_id|@username>` (or reply to a message)"
        )
        return

    removed = await unban_source(source_id)
    if removed:
        await message.reply_text(
            f"ꪜ **Source Unbanned:**\n"
            f"• **ID:** `{source_id}`\n"
            f"• **Name:** `{name or 'N/A'}`\n"
            f"• **Type:** `{source_type}`"
        )
    else:
        await message.reply_text(
            f"ⓘ Source `{source_id}` was not found in banned sources."
        )


@Client.on_message(filters.command("sources") & sudo_cmd)
async def sources_summary_handler(client: Client, message: Message):
    from stream.core.source_filter import (
        FilterMode,
        get_all_allowed_sources,
        get_all_banned_sources,
        get_filter_mode,
    )

    mode = get_filter_mode()
    allowed = await get_all_allowed_sources()
    banned = await get_all_banned_sources()
    channel_id = getattr(Config, "CHANNEL_ID", 0)

    text = [
        "**Sources & Filter Summary**",
        f"• **Filter Mode:** `{mode}` (`{FilterMode.to_string(mode)}`)",
        f"• **Main Channel ID:** `{channel_id}`",
        f"• **Allowed Sources:** {len(allowed)}",
        f"• **Banned Sources:** {len(banned)}",
        "",
    ]

    if allowed:
        text.append("**Allowed Sources:**")
        for s in allowed[:15]:
            name_part = f" - {s.get('name')}" if s.get("name") else ""
            text.append(
                f"• `{s.get('source_id')}` [{s.get('source_type', 'channel')}]{name_part}"
            )
        if len(allowed) > 15:
            text.append(f"  ...and {len(allowed) - 15} more")
        text.append("")

    if banned:
        text.append("**Banned Sources:**")
        for b in banned[:15]:
            name_part = f" - {b.get('name')}" if b.get("name") else ""
            reason_part = f" (Reason: {b.get('reason')})" if b.get("reason") else ""
            text.append(
                f"• `{b.get('source_id')}` [{b.get('source_type', 'channel')}]{name_part}{reason_part}"
            )
        if len(banned) > 15:
            text.append(f"  ...and {len(banned) - 15} more")

    await message.reply_text("\n".join(text))
