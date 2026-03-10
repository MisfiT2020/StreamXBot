import os
import json
import base64
import firebase_admin
from firebase_admin import credentials, messaging
from stream.core.config_manager import Config
from stream.helpers.logger import LOGGER

def initialize_firebase():
    if firebase_admin._apps:
        return
    try:
        b64_cred = os.environ.get("FIREBASE_CRED_B64")
        if b64_cred:
            cred_dict = json.loads(base64.b64decode(b64_cred).decode("utf-8"))
            cred = credentials.Certificate(cred_dict)
        else:
            cred_cfg = Config.FIREBASE_CREDENTIALS
            if cred_cfg and isinstance(cred_cfg, str) and len(cred_cfg) > 20:
                # XOR Decode using the SECRET_KEY
                key = Config.SECRET_KEY.encode('utf-8')
                enc_data = base64.b64decode(cred_cfg)
                dec_data = bytes(a ^ b for a, b in zip(enc_data, (key * (len(enc_data) // len(key) + 1))[:len(enc_data)]))
                cred_dict = json.loads(dec_data.decode("utf-8"))
                cred = credentials.Certificate(cred_dict)
            else:
                if not cred_cfg or not isinstance(cred_cfg, str):
                    cred_cfg = os.path.join(os.path.dirname(__file__), "..", "..", "service_account.json")
                cred = credentials.Certificate(cred_cfg)
            
        firebase_admin.initialize_app(cred)
        LOGGER(__name__).info("Firebase Admin initialized successfully.")
    except Exception as e:
        LOGGER(__name__).error(f"Failed to initialize Firebase Admin: {e}")

def send_push_notification(token: str, title: str, body: str, data: dict = None):
    initialize_firebase()
    if not firebase_admin._apps:
        return None
        
    try:
        message = messaging.Message(
            notification=messaging.Notification(
                title=title,
                body=body,
            ),
            data=data or {},
            token=token,
        )
        response = messaging.send(message)
        return response
    except Exception as e:
        LOGGER(__name__).error(f"Failed to send Firebase push notification: {e}")
        return None

def send_multicast_notification(tokens: list, title: str, body: str, data: dict = None):
    initialize_firebase()
    if not firebase_admin._apps or not tokens:
        return None
        
    try:
        message = messaging.MulticastMessage(
            notification=messaging.Notification(
                title=title,
                body=body,
            ),
            data=data or {},
            tokens=tokens,
        )
        response = messaging.send_each_for_multicast(message)
        return response
    except Exception as e:
        LOGGER(__name__).error(f"Failed to send Firebase multicast notification: {e}")
        return None
