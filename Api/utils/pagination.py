def clamp_page(page: int) -> int:
    try:
        page = int(page)
    except Exception:
        return 1
    return page if page >= 1 else 1

