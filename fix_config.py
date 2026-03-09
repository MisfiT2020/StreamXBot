import re

with open('encoded_utf8.txt', 'r', encoding='utf-8') as f:
    b64 = f.read().strip()

with open('config.py', 'r', encoding='utf-8') as f:
    text = f.read()

text = re.sub(r'FIREBASE_CREDENTIALS\s*=\s*".*?"', f'FIREBASE_CREDENTIALS = "{b64}"', text, count=1, flags=re.DOTALL)

with open('config.py', 'w', encoding='utf-8') as f:
    f.write(text)

print("config.py updated successfully")
