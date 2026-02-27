import os

def main():
    try:
        import uvicorn
    except Exception as e:
        raise SystemExit(f"uvicorn is required to run the API: {e}")

    uvicorn.run("api.main:app", host="0.0.0.0",  port = int(os.environ.get("PORT", 8001)), reload=True)

if __name__ == "__main__":
    main()

