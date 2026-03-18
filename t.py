import asyncio
import aiohttp
import json

URL = "https://www.shazam.com/services/amapi/v1/catalog/IN/search"

HEADERS = {
    "User-Agent": "Mozilla/5.0",
    "Accept": "application/json",
    "Referer": "https://www.shazam.com/",
}


async def main():
    params = {
        "types": "artists",
        "term": "Blackpink",
        "limit": 3
    }

    async with aiohttp.ClientSession(headers=HEADERS) as session:
        async with session.get(URL, params=params) as resp:
            print("Status:", resp.status)

            data = await resp.json()

            # ✅ Pretty print full response
            print("\n--- FULL RESPONSE ---\n")
            print(json.dumps(data, indent=2))


asyncio.run(main())