from stream import bot
from stream.helpers.filters import dev_cmd
from pyrogram import filters
from pyrogram.types import Message

@bot.on_message(dev_cmd & filters.command("fileid"))
async def file_id_command(_, message: Message):
    if message.reply_to_message:
        if message.reply_to_message.photo:
            file_id = message.reply_to_message.photo.file_id
        elif message.reply_to_message.video:
            file_id = message.reply_to_message.video.file_id
        elif message.reply_to_message.document:
            file_id = message.reply_to_message.document.file_id
        elif message.reply_to_message.animation:
            file_id = message.reply_to_message.animation.file_id
        elif message.reply_to_message.sticker:
            file_id = message.reply_to_message.sticker.file_id
        elif message.reply_to_message.audio:
            file_id = message.reply_to_message.audio.file_id
        else:
            await message.reply("Please reply to an image, video, document, or GIF to get its file ID.")
            return
        await message.reply(f"**File ID:**\n`{file_id}`")
    else:
        await message.reply("Please reply to an image, video, document, or GIF to get its file ID.")
