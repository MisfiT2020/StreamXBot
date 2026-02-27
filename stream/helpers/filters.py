from pyrogram import filters
from pyrogram.types import Message, CallbackQuery
from stream.core.config_manager import Config

class SudoFilter(filters.Filter):
    def __init__(self):
        super().__init__()

    def __call__(self, client, message: Message) -> bool:
        return message.from_user.id in Config.SUDO_USERS if message.from_user else False

sudo = SudoFilter()

def _extract_update(*args):
    if not args:
        return None
    if len(args) >= 3:
        return args[2]
    if len(args) == 2:
        return args[1]
    return args[0]


def dev_users(*args) -> bool:
    update = _extract_update(*args)
    user = None
    if isinstance(update, (Message, CallbackQuery)):
        user = update.from_user

    return user and user.id in Config.OWNER_ID

def sudo_users(*args) -> bool:
    update = _extract_update(*args)
    user = None
    if isinstance(update, (Message, CallbackQuery)):
        user = update.from_user
    return bool(user and user.id in Config.SUDO_USERS)


dev_cmd = filters.create(dev_users)
sudo_cmd = filters.create(sudo_users)
