from pyrogram import filters
from stream import bot

@bot.on_message(filters.command("id"))
async def get_id(_, message):
    if message.reply_to_message:
        user_id = message.reply_to_message.from_user.id
        await message.reply_text(f"Replied user ID: `{user_id}`")
    else:
        chat_id = message.chat.id
        text = f"Chat ID: `{chat_id}`"
        if message.from_user:
            text += f"\nYour User ID: `{message.from_user.id}`"
        await message.reply_text(text)