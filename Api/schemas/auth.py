from pydantic import BaseModel


class TgLoginRequest(BaseModel):
    init_data: str
    username: str | None = None
    password: str | None = None


class PasswordLoginRequest(BaseModel):
    username: str
    password: str


class SetCredentialsRequest(BaseModel):
    username: str
    password: str


class SetCookieRequest(BaseModel):
    token: str


class FCMTokenRequest(BaseModel):
    fcm_token: str

class RegisterRequest(BaseModel):
    userid: int
    username: str
    password: str

class ValidateOTPRequest(BaseModel):
    userid: int
    otp: str
