import base64
import os
import sys

sys.path.append(os.path.dirname(__file__))

try:
    from config import SECRET_KEY
except ImportError:
    print("Could not find SECRET_KEY in config.py")
    sys.exit(1)

def encode_file(file_path):
    if not os.path.exists(file_path):
        print(f"File {file_path} not found!")
        sys.exit(1)
        
    with open(file_path, 'rb') as f:
        data = f.read()
    
    key = SECRET_KEY.encode('utf-8')
    if not key:
        print("SECRET_KEY is empty in config.py")
        sys.exit(1)
        
    encrypted = bytes(a ^ b for a, b in zip(data, (key * (len(data) // len(key) + 1))[:len(data)]))
    
    b64 = base64.b64encode(encrypted).decode('utf-8')
    
    print("\n=== SUCCESS ===")
    print(f"File '{file_path}' has been successfully encoded!")
    print("Copy the ENCODED STRING below and paste it in your config.py as:")
    print(f'FIREBASE_CREDENTIALS = "{b64}"')
    print("\nENCODED STRING:")
    print(b64)
    print("\n===============\n")

if __name__ == "__main__":
    encode_file('service_account.json')
