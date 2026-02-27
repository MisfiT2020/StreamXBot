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

