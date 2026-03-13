import { memo, useCallback, useEffect, useMemo, useState, type MouseEvent } from 'react'
import type { Playlist, PlaylistTrack, Song } from '../types/index.js'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { api } from '../services/api.js'
import './PlaylistSheet.css'
import './SongList.css'

const hashToHue = (value: string) => {
  let hash = 0
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash * 31 + value.charCodeAt(i)) | 0
  }
  return Math.abs(hash) % 360
}

const toSong = (track: PlaylistTrack): Song => {
  const audio = track.audio
  const title = audio?.title ?? ''
  const artist = audio?.artist ?? ''
  const album = audio?.album ?? null
  const duration_sec = audio?.duration_sec ?? 0
  const cover_url = track.spotify?.cover_url ?? null
  const spotify_url = track.spotify?.url ?? null
  const type = audio?.type ?? track.telegram?.mime_type ?? ''
  const sampling_rate_hz = audio?.sampling_rate_hz ?? 0
  return {
    _id: track._id,
    title,
    artist,
    album,
    duration_sec,
    cover_url,
    spotify_url,
    source_chat_id: track.source_chat_id,
    source_message_id: track.source_message_id,
    type,
    sampling_rate_hz,
    updated_at: track.updated_at,
  }
}

const PlaylistSongItem = memo(
  ({
    song,
    isActive,
    playState,
    onActivate,
  }: {
    song: Song
    isActive: boolean
    playState: 'playing' | 'paused' | 'none'
    onActivate: (e: MouseEvent<HTMLDivElement>) => void
  }) => {
    const showPlaying = playState === 'playing'
    const showPlay = playState === 'paused'
    const showOverlay = showPlaying || showPlay

    return (
      <div data-song-id={song._id} className={`song-item playlist-song-item${isActive ? ' is-current' : ''}`} onClick={onActivate}>
        <div className={`playlist-song-cover${showPlaying ? ' is-playing' : ''}`} aria-hidden="true">
          <img src={song.cover_url || 'https://via.placeholder.com/60'} alt="" className="song-cover" />
          {showOverlay ? (
            <div className="playlist-song-cover-overlay">
              {showPlaying ? (
                <div className="playlist-playing-indicator" aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </div>
              ) : (
                <svg className="playlist-song-cover-icon" viewBox="0 0 24 24" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M9 7.56C9 6.12 10.57 5.22 11.82 5.95L18.69 9.89C19.94 10.62 19.94 12.38 18.69 13.11L11.82 17.05C10.57 17.78 9 16.88 9 15.44V7.56Z"
                  />
                </svg>
              )}
            </div>
          ) : null}
        </div>
        <div className="song-info">
          <div className="song-title">{song.title}</div>
          <div className="song-artist">{song.artist}</div>
        </div>
        <button className="more-btn" onClick={(e) => e.stopPropagation()} aria-label="More">
          <svg width="28" height="28" viewBox="0 0 28 28" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
            <circle cx="14" cy="14" r="14" fill="transparent" />
            <path
              fill="currentColor"
              d="M10.105 14c0-.87-.687-1.55-1.564-1.55-.862 0-1.557.695-1.557 1.55 0 .848.695 1.55 1.557 1.55.855 0 1.564-.702 1.564-1.55zm5.437 0c0-.87-.68-1.55-1.542-1.55A1.55 1.55 0 0012.45 14c0 .848.695 1.55 1.55 1.55.848 0 1.542-.702 1.542-1.55zm5.474 0c0-.87-.687-1.55-1.557-1.55-.87 0-1.564.695-1.564 1.55 0 .848.694 1.55 1.564 1.55.848 0 1.557-.702 1.557-1.55z"
            />
          </svg>
        </button>
      </div>
    )
  },
)

PlaylistSongItem.displayName = 'PlaylistSongItem'

interface PlaylistSheetProps {
  userId: string | number
  playlist: Playlist
  state: 'open' | 'closing'
  onClose: () => void
}

export const PlaylistSheet = ({ userId, playlist, state, onClose }: PlaylistSheetProps) => {
  const { currentSong, isPlaying, playSong, togglePlay } = usePlayerPlayback()
  const [tracks, setTracks] = useState<PlaylistTrack[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    if (state !== 'open') return () => {
      cancelled = true
    }

    queueMicrotask(() => {
      if (cancelled) return
      setTracks([])
      setLoading(true)
      setError(null)
    })

    const fetchTimeoutId = window.setTimeout(() => {
      api
        .getPlaylistTracks({ playlistId: playlist.playlist_id, userId, page: 1, limit: 50 })
        .then((res) => {
          if (cancelled) return
          setTracks(Array.isArray(res.items) ? res.items : [])
        })
        .catch((err) => {
          if (cancelled) return
          setTracks([])
          setError(err instanceof Error ? err.message : 'Failed to load playlist tracks')
        })
        .finally(() => {
          if (cancelled) return
          setLoading(false)
        })
    }, 140)

    return () => {
      cancelled = true
      window.clearTimeout(fetchTimeoutId)
    }
  }, [playlist.playlist_id, state, userId])

  const hue = useMemo(() => hashToHue(playlist.playlist_id || playlist.name || ''), [playlist.name, playlist.playlist_id])

  const songs = useMemo(() => tracks.map(toSong), [tracks])
  const songById = useMemo(() => {
    const map = new Map<string, Song>()
    songs.forEach((song) => map.set(song._id, song))
    return map
  }, [songs])
  const currentSongId = currentSong?._id ?? null

  const onActivate = useCallback(
    (e: MouseEvent<HTMLDivElement>) => {
      const id = e.currentTarget.dataset.songId
      if (!id) return
      if (currentSongId === id) {
        togglePlay()
        return
      }
      const song = songById.get(id)
      if (!song) return
      playSong(song)
    },
    [currentSongId, playSong, songById, togglePlay],
  )

  return (
    <div
      className="playlist-sheet-backdrop"
      data-state={state}
      role="dialog"
      aria-modal="true"
      onClick={() => {
        if (state === 'closing') return
        onClose()
      }}
    >
      <div
        className="playlist-sheet"
        data-state={state}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="playlist-sheet-top">
          <button className="playlist-sheet-close" type="button" onClick={onClose} aria-label="Close playlist">
            <span aria-hidden="true">×</span>
          </button>

          <div className="playlist-sheet-hero">
            <div className="playlist-sheet-art" style={{ ['--playlist-hue' as never]: hue }} aria-hidden="true" />
            <div className="playlist-sheet-title" title={playlist.name}>
              {playlist.name}
            </div>
          </div>
        </div>

        <div className="playlist-sheet-body">
          {loading ? (
            <div className="playlist-sheet-loading" role="status" aria-label="Loading">
              <div className="playlist-spinner" aria-hidden="true" />
            </div>
          ) : error ? (
            <div className="playlist-sheet-state">{error}</div>
          ) : songs.length === 0 ? (
            <div className="playlist-sheet-state">No tracks</div>
          ) : (
            <div className="playlist-sheet-tracks">
              {songs.map((song) => {
                const isActive = currentSongId === song._id
                const playState = isActive ? (isPlaying ? 'playing' : 'paused') : 'none'
                return <PlaylistSongItem key={song._id} song={song} isActive={isActive} playState={playState} onActivate={onActivate} />
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
