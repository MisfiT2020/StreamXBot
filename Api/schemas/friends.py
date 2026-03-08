from pydantic import BaseModel
from typing import Optional

class FriendRequestPayload(BaseModel):
    to: int

class AcceptRequestPayload(BaseModel):
    userId: int

class InviteJamPayload(BaseModel):
    toUserId: int
    jamId: str

class SettingsPayload(BaseModel):
    share_listening: Optional[str] = None
    allow_jam_invites: Optional[bool] = None

class FcmTokenPayload(BaseModel):
    token: str
