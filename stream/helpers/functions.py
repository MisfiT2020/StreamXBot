from typing import Union
from pyrogram.enums import ChatMemberStatus, ChatType
from pyrogram.types import Message, CallbackQuery
#from stream.core.config_manager import Config

async def isAdmin(message_or_callback: Union[Message, CallbackQuery]) -> bool:
    if isinstance(message_or_callback, CallbackQuery):
        message = message_or_callback.message
        user_id = message_or_callback.from_user.id
    else:
        message = message_or_callback
        user_id = message.from_user.id if message.from_user else None

    if not user_id:
        return False

    if message.chat.type not in [ChatType.SUPERGROUP, ChatType.CHANNEL]:
        return False

#    if user_id in Config.SUDO_USERID:
#        return True

    check_status = await message.chat.get_member(user_id)
    return check_status.status in [
        ChatMemberStatus.OWNER,
        ChatMemberStatus.ADMINISTRATOR,
    ]

def get_readable_time(seconds: int) -> str:
    """ Return a human-readable time format from seconds. """

    result = ""
    (days, remainder) = divmod(seconds, 86400)
    days = int(days)

    if days != 0:
        result += f"{days}d "
    (hours, remainder) = divmod(remainder, 3600)
    hours = int(hours)

    if hours != 0:
        result += f"{hours}h "
    (minutes, seconds) = divmod(remainder, 60)
    minutes = int(minutes)

    if minutes != 0:
        result += f"{minutes}m "

    seconds = int(seconds)
    result += f"{seconds}s "
    return result

def get_readable_bytes(size: Union[int, float, str]) -> str:
    """Return a human-readable file size from bytes.
    Accepts int/float and coerces numeric strings; returns empty string on invalid input.
    """
    if size is None:
        return ""
    try:
        if isinstance(size, str):
            size = float(size)
        else:
            size = float(size)
    except Exception:
        return ""
    if size < 0:
        size = 0.0

    dict_power_n = {0: "", 1: "Ki", 2: "Mi", 3: "Gi", 4: "Ti"}
    power = 2 ** 10
    raised_to_pow = 0

    while size >= power and raised_to_pow < 4:
        size /= power
        raised_to_pow += 1

    return f"{str(round(size, 2))} {dict_power_n[raised_to_pow]}B"