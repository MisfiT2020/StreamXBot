from stream.database.MongoDb import db_handler

async def init_db():
    await db_handler.initialize()

def get_audio_tracks_collection():
    return db_handler.audio_collection.collection

