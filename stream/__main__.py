import os
import asyncio

if os.name != "nt":
    try:
        import uvloop

        uvloop.install()
    except Exception:
        pass

try:
    from pyrogram import idle
except Exception:
    idle = None
from uvicorn import Config as UvicornConfig, Server as UvicornServer

from . import bot, scheduler, add_daily_playlist_jobs
from .database.MongoDb import db_handler
from .helpers.logger import LOGGER
from stream.core.config_manager import Config


def get_api_port() -> int:
    if port := os.environ.get("PORT"):
        try:
            return int(port)
        except ValueError:
            pass
    try:
        return int(getattr(Config, "API_PORT", 8000))
    except Exception:
        return 8000


async def start_api(log):
    try:
        from Api.main import app as fastapi_app

        cfg = UvicornConfig(
            app=fastapi_app,
            host="0.0.0.0",
            port=get_api_port(),
            loop="asyncio",
            log_level="info",
        )

        server = UvicornServer(cfg)
        task = asyncio.create_task(server.serve())

        log.info(f"FastAPI started on http://0.0.0.0:{cfg.port}")
        return server, task

    except Exception as e:
        log.warning(f"FastAPI failed to start: {e}")
        return None, None


async def cancel_pyrogram_pending_tasks(*, timeout: float = 3.0) -> None:
    tasks: list[asyncio.Task] = []
    for t in asyncio.all_tasks():
        if t is asyncio.current_task() or t.done():
            continue
        coro = t.get_coro()
        module = getattr(coro, "__module__", "") or ""
        if module.startswith("pyrogram."):
            tasks.append(t)

    if not tasks:
        return

    for t in tasks:
        t.cancel()

    try:
        await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=timeout)
    except asyncio.TimeoutError:
        pass


async def close_aiohttp_sessions() -> None:
    try:
        from Api.services.stream_service import close_stream_hubs

        await close_stream_hubs()
    except Exception:
        pass

async def main():
    log = LOGGER(__name__)

    log.info("Initializing MongoDB...")
    await db_handler.initialize()

    await Config.load_from_db()

    only_api = bool(getattr(Config, "ONLY_API", False))

    if scheduler is not None:
        try:
            add_daily_playlist_jobs(log)
            if not bool(getattr(scheduler, "running", False)):
                scheduler.start()
        except Exception as e:
            log.warning(f"Scheduler failed to start: {e}")

    if only_api:
        server, server_task = await start_api(log)
        log.info("API-only mode enabled. Running until stopped.")
        try:
            if server_task is not None:
                try:
                    await server_task
                except asyncio.CancelledError:
                    pass
            else:
                try:
                    await asyncio.Event().wait()
                except asyncio.CancelledError:
                    pass
        finally:
            log.info("Shutting down...")

            if server:
                server.should_exit = True
                if server_task:
                    try:
                        _, pending = await asyncio.wait({server_task}, timeout=10)
                        if pending:
                            raise TimeoutError
                    except TimeoutError:
                        try:
                            server.force_exit = True
                        except Exception:
                            pass
                        try:
                            _, pending = await asyncio.wait({server_task}, timeout=3)
                            if pending:
                                raise TimeoutError
                        except TimeoutError:
                            server_task.cancel()
                            try:
                                await server_task
                            except asyncio.CancelledError:
                                pass
                            except Exception:
                                pass
                        except asyncio.CancelledError:
                            pass
                        except Exception:
                            pass
                    except asyncio.CancelledError:
                        pass
                    except Exception:
                        server_task.cancel()
                        try:
                            await server_task
                        except asyncio.CancelledError:
                            pass
                        except Exception:
                            pass

            await close_aiohttp_sessions()
            if scheduler is not None:
                try:
                    scheduler.shutdown(wait=False)
                except Exception:
                    pass
            await db_handler.close()
            await cancel_pyrogram_pending_tasks()
            log.info("Client stopped.")
        return

    if bot is None:
        raise SystemExit("bot is disabled but ONLY_API is False")

    from stream import initialize_multi_clients, stop_multi_clients
    from stream.plugins.userBot import start_userbot_service, stop_userbot_service

    await bot.start()
    me = await bot.get_me()
    await initialize_multi_clients(log, primary_user_id=int(getattr(me, "id")))

    server, server_task = await start_api(log)

    log.info("Client started. Running until stopped.")
    userbot, userbot_task = await start_userbot_service(log)
    log.info(f"{me.first_name} (@{me.username}) [ID: {me.id}]")

    try:
        if idle is None:
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                pass
        else:
            try:
                await idle()
            except asyncio.CancelledError:
                pass
    finally:
        log.info("Shutting down...")

        if server:
            server.should_exit = True
            if server_task:
                try:
                    _, pending = await asyncio.wait({server_task}, timeout=10)
                    if pending:
                        raise TimeoutError
                except TimeoutError:
                    try:
                        server.force_exit = True
                    except Exception:
                        pass
                    try:
                        _, pending = await asyncio.wait({server_task}, timeout=3)
                        if pending:
                            raise TimeoutError
                    except TimeoutError:
                        server_task.cancel()
                        try:
                            await server_task
                        except asyncio.CancelledError:
                            pass
                        except Exception:
                            pass
                    except asyncio.CancelledError:
                        pass
                    except Exception:
                        pass
                except asyncio.CancelledError:
                    pass
                except Exception:
                    server_task.cancel()
                    try:
                        await server_task
                    except asyncio.CancelledError:
                        pass
                    except Exception:
                        pass

        await stop_userbot_service(userbot, userbot_task)

        await close_aiohttp_sessions()
        if scheduler is not None:
            try:
                scheduler.shutdown(wait=False)
            except Exception:
                pass
        await stop_multi_clients(log)
        await bot.stop()
        await db_handler.close()
        await cancel_pyrogram_pending_tasks()
        log.info("Client stopped.")


if __name__ == "__main__":    
    try:
        if bot is not None and getattr(bot, "loop", None) is not None:
            bot.loop.run_until_complete(main())
        else:
            asyncio.run(main())
    except KeyboardInterrupt:
        pass
