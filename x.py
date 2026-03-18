import asyncio
import aiohttp

URL = "https://www.shazam.com/artist/blackpink/1141774019"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://www.google.com/",
}


async def main():
    async with aiohttp.ClientSession(headers=HEADERS) as session:
        async with session.get(URL) as resp:
            print("Status:", resp.status)

            html = await resp.text()

            # Save full response to file
            with open("shazam_artist.txt", "w", encoding="utf-8") as f:
                f.write(html)

            print("✅ Saved to shazam_artist.txt")


asyncio.run(main())