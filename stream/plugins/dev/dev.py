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

edit_states = {}
user_states = {}

BOTSETTINGS_IDS = [
   "AgACAgUAAxkBAAP2aWxqNan3oFTA5RKNufU71wABx3stAAKMDWsb6rxpV2aqK8ToBALEAAgBAAMCAAN5AAceBA",
]

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
        ],
        [
            InlineKeyboardButton("Stats", callback_data="stats"),
            InlineKeyboardButton("Database", callback_data="database")
        ]
    ])
    await message.reply_photo(
        photo=random.choice(BOTSETTINGS_IDS),
        caption="Admin Panel:",
        reply_markup=keyboard
    )


@Client.on_callback_query(filters.regex("^config$") & dev_cmd)
async def sys_callback(client, callback_query: CallbackQuery):
    await handle_config(callback_query)

@Client.on_callback_query(filters.regex("^(stats|database|refresh|back_main)$") & dev_cmd)
async def system_callback(client, callback_query: CallbackQuery):
    cmd = callback_query.data
    try:
        if cmd == "stats":
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

async def handle_config(callback_query: CallbackQuery):
    keyboard = await get_settings_keyboard()
    await callback_query.message.edit_media(
        media=InputMediaPhoto(
            random.choice(BOTSETTINGS_IDS),
            caption="Config Variables | Page: 0 | State: view"
        ),
        reply_markup=keyboard
    )

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
    TotalUsers = await db_handler.users.total_documents()
    TotalChats = await db_handler.chats_collection.total_documents()
    TotalChannels = await db_handler.channels_collection.total_documents()
    
    stats_string = (
        "**Database Statistics**\n\n"
        f"• Users: {TotalUsers}\n"
        f"• Chats: {TotalChats}\n"
        f"• Channels: {TotalChannels}"
    )
    
    await callback_query.message.edit_media(
        media=InputMediaPhoto(
            random.choice(BOTSETTINGS_IDS),
            caption=stats_string
        ),
        reply_markup=InlineKeyboardMarkup([
            [InlineKeyboardButton("Back", callback_data="refresh")]
        ])
    )

async def refresh_panel(callback_query: CallbackQuery):
    
    if callback_query.from_user.id in edit_states:
        del edit_states[callback_query.from_user.id]

    
    await callback_query.message.edit_media(
        media=InputMediaPhoto(
            random.choice(BOTSETTINGS_IDS),
            caption="Admin Panel"
        ),
        reply_markup=InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Config", callback_data="config"),
            ],
            [
                InlineKeyboardButton("Stats", callback_data="stats"),
                InlineKeyboardButton("Database", callback_data="database")
            ]
        ])
    )

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

    
    await query.message.edit_media(
        media=InputMediaPhoto(
            random.choice(BOTSETTINGS_IDS),
            caption="Admin Panel:"
        ),
        reply_markup=InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Config", callback_data="config"),
            ],
            [
                InlineKeyboardButton("Stats", callback_data="stats"),
                InlineKeyboardButton("Database", callback_data="database")
            ]
        ])
    )

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
    await query.message.edit_text(
        f"Config Variables | Page: {current_page} | State: edit",
        reply_markup=keyboard
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
    await query.message.edit_text(
        f"Config Variables | Page: {current_page} | State: view",
        reply_markup=keyboard
    )

@Client.on_callback_query(filters.regex(r"^page_") & dev_cmd)
async def change_page(client: Client, query: CallbackQuery):
    data = query.data.split("_")
    page = int(data[1])
    edit_mode = data[2] == "True" if len(data) > 2 else False
    keyboard = await get_settings_keyboard(page, edit_mode=edit_mode)
    state = "edit" if edit_mode else "view"
    try:
        await query.message.edit_text(
            f"Config Variables | Page: {page} | State: {state}",
            reply_markup=keyboard
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
    
    # Special handling for OWNER_ID and SUDO_USERS: provide Add/Remove buttons
    if key in ("OWNER_ID", "SUDO_USERS"):
        edit_states[user_id] = {
            "key": key,
            "message_id": query.message.id,
            "page": current_page,
            "edit_mode": True
        }

        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Add", callback_data=f"manage_add_{key}"),
                InlineKeyboardButton("Remove", callback_data=f"manage_remove_{key}")
            ],
            [
                InlineKeyboardButton("Back", callback_data=f"back_settings_{current_page}_True"),
                InlineKeyboardButton("Close", callback_data="close_settings")
            ]
        ])

        await query.message.edit_text(
            f"Manage {key}: Choose action.",
            reply_markup=keyboard
        )
        return

    # Default edit flow for all other keys
    edit_states[user_id] = {
        "key": key,
        "message_id": query.message.id,
        "page": current_page,
        "edit_mode": True
    }

    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("Back", callback_data=f"back_settings_{current_page}_True"),
            InlineKeyboardButton("Close", callback_data="close_settings")
        ]
    ])

    await query.message.edit_text(
        f"Send new value for {key}:",
        reply_markup=keyboard
    )

@Client.on_callback_query(filters.regex(r"^back_settings_") & dev_cmd)
async def back_settings(client: Client, query: CallbackQuery):
    data = query.data.split("_")
    page = int(data[2])  
    edit_mode = data[3] == "True"  

    
    if query.from_user.id in edit_states:
        del edit_states[query.from_user.id]

    
    keyboard = await get_settings_keyboard(page=page, edit_mode=edit_mode)
    state = "edit" if edit_mode else "view"

    
    await query.message.edit_text(
        f"Config Variables | Page: {page} | State: {state}",
        reply_markup=keyboard
    )

@Client.on_callback_query(filters.regex(r"^manage_(add|remove)_(OWNER_ID|SUDO_USERS)$") & dev_cmd)
async def manage_owner_sudo_action(client: Client, query: CallbackQuery):
    # Split with maxsplit=2 to preserve keys containing underscores (e.g., SUDO_USERS)
    parts = query.data.split("_", 2)
    # expected: ["manage", "add"|"remove", "OWNER_ID"|"SUDO_USERS"]
    try:
        action = parts[1]
        key = parts[2]
    except Exception:
        try:
            await query.answer("Invalid action.", show_alert=True)
        except Exception:
            pass
        return

    # Normalize potential aliases just in case
    if key == "SUDO":
        key = "SUDO_USERS"
    if key == "OWNER":
        key = "OWNER_ID"

    user_id = query.from_user.id
    state = edit_states.get(user_id) or {}
    # Keep existing state, only set action
    state.update({"action": action, "key": key, "message_id": query.message.id})
    edit_states[user_id] = state

    # Prompt for an ID to add/remove
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
    await query.message.edit_text(
        f"Send the Telegram user ID to {action} {key}:",
        reply_markup=keyboard
    )

@Client.on_message(filters.user(Config.OWNER_ID) & filters.text & ~filters.command(["sudo"]), group=7)
async def receive_add_remove_value(client: Client, message: Message):
    user_id = message.from_user.id
    if user_id not in edit_states:
        return
    state = edit_states[user_id]
    key = state.get("key")
    # Normalize potential aliases
    if key == "SUDO":
        key = "SUDO_USERS"
    if key == "OWNER":
        key = "OWNER_ID"
    action = state.get("action")
    if key not in ("OWNER_ID", "SUDO_USERS") or action not in ("add", "remove"):
        return

    # Delete the admin's input message to keep the panel clean
    try:
        await message.delete()
    except Exception:
        pass

    # Parse one or more IDs from the text
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
        await client.edit_message_text(
            chat_id=message.chat.id,
            message_id=state.get("message_id"),
            text=f"Invalid ID. Please try again.",
            reply_markup=keyboard
        )
        # keep state so they can retry
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
        await client.edit_message_text(
            chat_id=message.chat.id,
            message_id=state.get("message_id"),
            text=f"❌ Error: {str(e)}",
            reply_markup=keyboard
        )
        del edit_states[user_id]
        return

    # Success: return to the edit panel without showing values
    page = state.get("page", 0)
    keyboard = await get_settings_keyboard(page=page, edit_mode=True)
    await client.edit_message_text(
        chat_id=message.chat.id,
        message_id=state.get("message_id"),
        text=f"Config Variables | Page: {page} | State: edit",
        reply_markup=keyboard
    )

    del edit_states[user_id]
@Client.on_message(filters.user(Config.OWNER_ID) & filters.text & ~filters.command(["sudo"]), group=6)
async def receive_new_value(client: Client, message: Message):
    user_id = message.from_user.id
    if user_id not in edit_states:
        return
    
    state = edit_states[user_id]
    key = state["key"]
    # For OWNER_ID and SUDO_USERS, use dedicated add/remove flow and ignore generic updates
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
        await client.edit_message_text(
            chat_id=message.chat.id,
            message_id=state["message_id"],
            text=f"Config Variables | Page: {page} | State: {'edit' if edit_mode else 'view'}\n\n{feedback}",
            reply_markup=keyboard
        )
    except ValueError as ve:
        
        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Back", callback_data=f"back_settings_{page}_{edit_mode}"),
                InlineKeyboardButton("Close", callback_data="close_settings")
            ]
        ])
        await client.edit_message_text(
            chat_id=message.chat.id,
            message_id=state["message_id"],
            text=f"⚠️ {str(ve)}",
            reply_markup=keyboard
        )
    except Exception as error:
        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("Back", callback_data=f"back_settings_{page}_{edit_mode}"),
                InlineKeyboardButton("Close", callback_data="close_settings")
            ]
        ])
        await client.edit_message_text(
            chat_id=message.chat.id,
            message_id=state["message_id"],
            text=f"❌ Error updating setting: {str(error)}",
            reply_markup=keyboard
        )
    finally:
        del edit_states[user_id]

@Client.on_callback_query(filters.regex("^close_settings$") & dev_cmd)
async def close_menu(client: Client, query: CallbackQuery):
    
    if query.from_user.id in edit_states:
        del edit_states[query.from_user.id]
    await query.message.delete()
