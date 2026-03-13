import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { SongList } from '../components/SongList.js'
import { WebSongList } from '../components/WebSongList.js'
import type { AvailablePlaylist, Playlist, PlaylistTrack, Song } from '../types/index.js'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { api, CACHE_TTL_MS, getCacheEnabled, getAuthUserInfo, getWebSongListEnabled } from '../services/api.js'
import { platform } from '../platform.js'
import emptyPlaylistUrl from '../assets/mptyPlaylist.svg'
import './Home.css'
import './Favorites.css'

type LocationState = { playlistName?: string } | null
type AvailablePlaylistLocationState = { playlist?: AvailablePlaylist } | null

const normalizeRemoteUrl = (value: unknown): string | null => {
  if (typeof value !== 'string') return null
  let url = value.trim()
  url = url.replace(/^`+/, '').replace(/`+$/, '').trim()
  url = url.replace(/^"+/, '').replace(/"+$/, '').trim()
  url = url.replace(/^'+/, '').replace(/'+$/, '').trim()
  if (!url) return null
  return url
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

export const PlaylistPage = () => {
  const navigate = useNavigate()
  const location = useLocation()
  const { playlistId } = useParams()
  const { playSongFromList, currentSong, isPlaying, togglePlay } = usePlayerPlayback()
  const useWebSongList = getWebSongListEnabled()
  const isTelegram = platform.isTelegram
  const authUserInfo = getAuthUserInfo()
  const userId = authUserInfo?.user_id?.toString() || null

  const playlistNameFromState = (location.state as LocationState)?.playlistName ?? null
  const [playlistName, setPlaylistName] = useState<string | null>(playlistNameFromState)
  const [songs, setSongs] = useState<Song[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [suggestedSongs, setSuggestedSongs] = useState<Song[]>([])
  const [loadingSuggested, setLoadingSuggested] = useState(false)
  const [suggestedKey, setSuggestedKey] = useState(0)
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const [loadingPlaylistName, setLoadingPlaylistName] = useState(!playlistNameFromState)

  useEffect(() => {
    let cancelled = false
    if (!playlistId) return () => {
      cancelled = true
    }

    queueMicrotask(() => {
      if (cancelled) return
      if (!playlistNameFromState) {
        setPlaylistName(null)
        setLoadingPlaylistName(true)
      }
    })

    const cacheEnabled = getCacheEnabled()
    const cacheKey = `streamw:playlists:${String(userId)}`
    const cacheTtlMs = CACHE_TTL_MS
    const now = Date.now()

    if (cacheEnabled) {
      try {
        const cachedRaw = localStorage.getItem(cacheKey)
        if (cachedRaw) {
          const cached = JSON.parse(cachedRaw) as { ts?: number; items?: Playlist[] }
          const cachedItems = Array.isArray(cached.items) ? cached.items : null
          const cachedOk = cachedItems && typeof cached.ts === 'number' && now - cached.ts < cacheTtlMs
          if (cachedItems) {
            const match = cachedItems.find((item) => item.playlist_id === playlistId)
            if (match?.name && !playlistNameFromState) {
              queueMicrotask(() => {
                if (cancelled) return
                setPlaylistName(match.name)
                setLoadingPlaylistName(false)
              })
            }
          }
          if (cachedOk) {
            return () => {
              cancelled = true
            }
          }
        }
      } catch (err) {
        void err
      }
    }

    api
      .getPlaylists(userId)
      .then((res) => {
        if (cancelled) return
        const match = res.items?.find((item) => item.playlist_id === playlistId)
        if (match?.name && !playlistNameFromState) {
          queueMicrotask(() => {
            if (cancelled) return
            setPlaylistName(match.name)
            setLoadingPlaylistName(false)
          })
        }
        if (cacheEnabled) {
          try {
            if (Array.isArray(res.items)) {
              localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), items: res.items }))
            }
          } catch (err) {
            void err
          }
        }
      })
      .catch((err) => {
        void err
      })

    return () => {
      cancelled = true
    }
  }, [playlistId, playlistNameFromState, userId])

  useEffect(() => {
    let cancelled = false
    if (!playlistId) return () => {
      cancelled = true
    }

    queueMicrotask(() => {
      if (cancelled) return
      setLoading(true)
      setError(null)
    })

    api
      .getPlaylistTracks({ playlistId, userId, page: 1, limit: 50 })
      .then((res) => {
        if (cancelled) return
        const tracks = Array.isArray(res.items) ? res.items : []
        setSongs(tracks.map(toSong))
        setLoading(false)
      })
      .catch((err) => {
        if (cancelled) return
        setSongs([])
        setError(err instanceof Error ? err.message : 'Failed to load playlist tracks')
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [playlistId, userId])

  const goBack = useCallback(() => {
    if (window.history.length > 1) {
      navigate(-1)
    } else {
      navigate('/', { replace: true })
    }
  }, [navigate])

  const handlePreview = useCallback(() => {
    if (songs.length === 0) return
    const first = songs[0]
    const isCurrent = currentSong?._id === first._id
    if (isCurrent) {
      if (!isPlaying) togglePlay()
    } else {
      playSongFromList(first, songs)
    }
  }, [currentSong?._id, isPlaying, playSongFromList, songs, togglePlay])

  // Fetch suggested songs when playlist is empty
  useEffect(() => {
    if (loading || songs.length > 0) return
    
    let cancelled = false
    queueMicrotask(() => {
      if (cancelled) return
      setLoadingSuggested(true)
    })
    
    api.browseTracks(1)
      .then((res) => {
        if (cancelled) return
        const items = Array.isArray(res.items) ? res.items : []
        setSuggestedSongs(items.slice(0, 5))
        setLoadingSuggested(false)
      })
      .catch(() => {
        if (cancelled) return
        setSuggestedSongs([])
        setLoadingSuggested(false)
      })
    
    return () => {
      cancelled = true
    }
  }, [loading, songs.length, suggestedKey])

  const refreshSuggestedSongs = useCallback(() => {
    if (loadingSuggested) return
    setSuggestedKey((v) => v + 1)
  }, [loadingSuggested])

  const handleAddSuggestedSong = useCallback(async (song: Song) => {
    if (!playlistId) return
    try {
      await api.addTrackToMyPlaylist(playlistId, song._id)
      setSongs((prev) => [...prev, song])
      setSuggestedSongs((prev) => prev.filter((s) => s._id !== song._id))
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to add track')
    }
  }, [playlistId])

  const handleRemoveFromPlaylist = useCallback(async (trackId: string) => {
    if (!playlistId) return
    try {
      await api.removeTrackFromMyPlaylist(playlistId, trackId)
      setSongs((prev) => prev.filter((s) => s._id !== trackId))
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to remove track from playlist')
    }
  }, [playlistId])

  const handleCopyLink = useCallback(async () => {
    if (!playlistId) return
    const link = `${window.location.origin}/playlists/${playlistId}`
    try {
      await navigator.clipboard.writeText(link)
      setIsMenuOpen(false)
      // Could add a toast notification here
    } catch {
      // Fallback for older browsers
      const textArea = document.createElement('textarea')
      textArea.value = link
      textArea.style.position = 'fixed'
      textArea.style.left = '-999999px'
      document.body.appendChild(textArea)
      textArea.select()
      try {
        document.execCommand('copy')
        setIsMenuOpen(false)
      } catch {
        alert('Failed to copy link')
      }
      document.body.removeChild(textArea)
    }
  }, [playlistId])

  const totalDuration = useMemo(() => {
    const totalSeconds = songs.reduce((sum, song) => sum + (song.duration_sec || 0), 0)
    const minutes = Math.floor(totalSeconds / 60)
    return minutes
  }, [songs])

  const cardThumbUrls = useMemo(() => {
    const urls: string[] = []
    const seen = new Set<string>()
    for (const song of songs) {
      const url = typeof song.cover_url === 'string' ? song.cover_url.trim() : ''
      if (!url) continue
      if (seen.has(url)) continue
      seen.add(url)
      urls.push(url)
      if (urls.length >= 4) break
    }
    return urls
  }, [songs])

  if (!playlistId) return null

  return (
    <div className="audio-page latest-songs-page favorites-page playlist-page">
      <div className={`audio-topbar${useWebSongList ? ' audio-topbar--web' : ''}`}>
        {isTelegram && (
          <button className="audio-back-pill" type="button" onClick={goBack} aria-label="Back">
            <svg className="audio-back-icon" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
            </svg>
          </button>
        )}
        <button className="favorites-menu-btn" type="button" aria-label="More options" onClick={() => setIsMenuOpen(true)}>
          <div className="favorites-menu-dots-horizontal"><span className="favorites-menu-dot-red"></span><span className="favorites-menu-dot-red"></span><span className="favorites-menu-dot-red"></span></div>
        </button>
      </div>

      {isMenuOpen && (
        <>
          <div className="favorites-menu-backdrop" onClick={() => setIsMenuOpen(false)} />
          <div className="favorites-menu">
            <button className="favorites-menu-item" type="button" onClick={() => { setIsMenuOpen(false); /* TODO: Add edit functionality */ }}>
              <span className="favorites-menu-item-text">Edit</span>
            </button>
            <div className="favorites-menu-divider" />
            <button className="favorites-menu-item" type="button" onClick={handleCopyLink}>
              <span className="favorites-menu-item-text">Copy Link</span>
              <svg className="favorites-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"
                />
              </svg>
            </button>
          </div>
        </>
      )}

      <main className={`content${useWebSongList ? ' latest-songs-content--web' : ''}`}>
        <div className="favorites-header">
          <div
            className="favorites-card favorites-card--playlist"
            data-has-thumb={cardThumbUrls.length > 0 ? 'true' : 'false'}
            data-loading={loading ? 'true' : 'false'}
          >
            {!loading && cardThumbUrls.length >= 4 ? (
              <div className="favorites-card-thumb favorites-card-thumb--grid" aria-hidden="true">
                <div className="favorites-card-thumb-grid">
                  {cardThumbUrls.slice(0, 4).map((url, index) => (
                    <img key={`${playlistId}-${index}`} className="favorites-card-thumb-grid-img" src={url} alt="" loading="lazy" />
                  ))}
                </div>
              </div>
            ) : !loading && cardThumbUrls.length > 0 ? (
              <div className="favorites-card-thumb" aria-hidden="true">
                <img className="favorites-card-thumb-img" src={cardThumbUrls[0]} alt="" loading="lazy" />
              </div>
            ) : !loading ? (
              <div className="favorites-card-thumb favorites-card-thumb--empty" aria-hidden="true">
                <img className="favorites-card-thumb-empty-icon" src={emptyPlaylistUrl} alt="" loading="lazy" />
              </div>
            ) : null}
          </div>
          <div className="favorites-info">
            {loadingPlaylistName ? (
              <div className="favorites-title-skeleton" aria-label="Loading playlist name">
                <div className="skeleton-bar skeleton-bar--title"></div>
              </div>
            ) : (
              <h1 className="favorites-title">{playlistName || 'Playlist'}</h1>
            )}
            {songs.length > 0 ? (
              <button className="favorites-preview-btn" type="button" onClick={handlePreview}>
                <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path
                    fill="currentColor"
                    d="M9 7.56C9 6.12 10.57 5.22 11.82 5.95L18.69 9.89C19.94 10.62 19.94 12.38 18.69 13.11L11.82 17.05C10.57 17.78 9 16.88 9 15.44V7.56Z"
                  />
                </svg>
                Preview
              </button>
            ) : (
              <button className="favorites-preview-btn favorites-preview-btn--add" type="button" onClick={() => navigate('/home')}>
                <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
                </svg>
                Add Music
              </button>
            )}
          </div>
        </div>
        {error ? (
          <div className="playlist-state">{error}</div>
        ) : loading ? (
          <div className="song-loading" role="status" aria-label="Loading">
            <div className="song-list-spinner" aria-hidden="true" />
          </div>
        ) : useWebSongList ? (
          <>
            <WebSongList songs={songs} title={playlistName || 'Playlist'} loading={false} showTitle={false} />
            {songs.length > 0 ? (
              <div className="favorites-summary">
                {songs.length} song{songs.length !== 1 ? 's' : ''}, {totalDuration} minute{totalDuration !== 1 ? 's' : ''}
              </div>
            ) : (
              <div className="suggested-songs-section">
                <div className="suggested-songs-card" role="region" aria-label="Suggested songs">
                  <div className="suggested-songs-card-header">
                    <div className="suggested-songs-card-heading">
                      <div className="suggested-songs-card-title">Suggested Songs</div>
                      <div className="suggested-songs-card-subtitle">Preview and add to playlist</div>
                    </div>
                    <button className="suggested-songs-refresh" type="button" onClick={refreshSuggestedSongs} aria-label="Refresh suggestions" disabled={loadingSuggested}>
                      <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                        <path
                          fill="currentColor"
                          d="M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.45 11H17.2A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L14 10h6V4l-2.35 2.35Z"
                        />
                      </svg>
                    </button>
                  </div>

                  {loadingSuggested ? (
                    <div className="suggested-songs-loading" role="status" aria-label="Loading suggestions">
                      <div className="song-list-spinner" aria-hidden="true" />
                    </div>
                  ) : (
                    <div className="suggested-songs-list" role="list">
                      {suggestedSongs.map((song) => (
                        <div key={song._id} className="suggested-song-item" role="listitem">
                          {song.cover_url ? (
                            <img className="suggested-song-cover" src={song.cover_url} alt="" loading="lazy" />
                          ) : (
                            <div className="suggested-song-cover suggested-song-cover--empty" aria-hidden="true" />
                          )}
                          <div className="suggested-song-info">
                            <div className="suggested-song-title">{song.title}</div>
                            <div className="suggested-song-artist">{song.artist}</div>
                          </div>
                          <button className="suggested-song-add-btn" type="button" onClick={() => handleAddSuggestedSong(song)} aria-label="Add to playlist">
                            <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                              <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" />
                              <path fill="currentColor" d="M11 7h2v10h-2z" />
                              <path fill="currentColor" d="M7 11h10v2H7z" />
                            </svg>
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )}
          </>
        ) : (
          <>
            <SongList 
              songs={songs} 
              title={playlistName || 'Playlist'} 
              layout="single" 
              loading={false} 
              showHeader={false} 
              density="compact"
              playlistId={playlistId}
              onRemoveFromPlaylist={handleRemoveFromPlaylist}
            />
            {songs.length > 0 ? (
              <div className="favorites-summary">
                {songs.length} song{songs.length !== 1 ? 's' : ''}, {totalDuration} minute{totalDuration !== 1 ? 's' : ''}
              </div>
            ) : (
              <div className="suggested-songs-section">
                <div className="suggested-songs-card" role="region" aria-label="Suggested songs">
                  <div className="suggested-songs-card-header">
                    <div className="suggested-songs-card-heading">
                      <div className="suggested-songs-card-title">Suggested Songs</div>
                      <div className="suggested-songs-card-subtitle">Preview and add to playlist</div>
                    </div>
                    <button className="suggested-songs-refresh" type="button" onClick={refreshSuggestedSongs} aria-label="Refresh suggestions" disabled={loadingSuggested}>
                      <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                        <path
                          fill="currentColor"
                          d="M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.45 11H17.2A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L14 10h6V4l-2.35 2.35Z"
                        />
                      </svg>
                    </button>
                  </div>

                  {loadingSuggested ? (
                    <div className="suggested-songs-loading" role="status" aria-label="Loading suggestions">
                      <div className="song-list-spinner" aria-hidden="true" />
                    </div>
                  ) : (
                    <div className="suggested-songs-list" role="list">
                      {suggestedSongs.map((song) => (
                        <div key={song._id} className="suggested-song-item" role="listitem">
                          {song.cover_url ? (
                            <img className="suggested-song-cover" src={song.cover_url} alt="" loading="lazy" />
                          ) : (
                            <div className="suggested-song-cover suggested-song-cover--empty" aria-hidden="true" />
                          )}
                          <div className="suggested-song-info">
                            <div className="suggested-song-title">{song.title}</div>
                            <div className="suggested-song-artist">{song.artist}</div>
                          </div>
                          <button className="suggested-song-add-btn" type="button" onClick={() => handleAddSuggestedSong(song)} aria-label="Add to playlist">
                            <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                              <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" />
                              <path fill="currentColor" d="M11 7h2v10h-2z" />
                              <path fill="currentColor" d="M7 11h10v2H7z" />
                            </svg>
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )}
          </>
        )}
      </main>
    </div>
  )
}

export const AvailablePlaylistPage = () => {
  const navigate = useNavigate()
  const location = useLocation()
  const useWebSongList = getWebSongListEnabled()
  const isTelegram = platform.isTelegram
  const { playSongFromList, currentSong, isPlaying, togglePlay } = usePlayerPlayback()

  const endpoint = location.pathname
  const playlistFromState = (location.state as AvailablePlaylistLocationState)?.playlist ?? null

  const [playlist, setPlaylist] = useState<AvailablePlaylist | null>(playlistFromState)
  const [loadingPlaylistMeta, setLoadingPlaylistMeta] = useState(!playlistFromState)
  const [playlistName, setPlaylistName] = useState<string | null>(playlistFromState?.name ?? null)
  const [playlistThumb, setPlaylistThumb] = useState<string | null>(() => normalizeRemoteUrl(playlistFromState?.thumbnail_url))

  const [songs, setSongs] = useState<Song[]>([])
  const [coverUrl, setCoverUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [isMenuOpen, setIsMenuOpen] = useState(false)

  useEffect(() => {
    if (playlistFromState) return
    let cancelled = false

    queueMicrotask(() => {
      if (cancelled) return
      setLoadingPlaylistMeta(true)
      setPlaylistName(null)
      setPlaylistThumb(null)
    })

    api
      .getAvailablePlaylists()
      .then((res) => {
        if (cancelled) return
        const items = Array.isArray(res.items) ? res.items : []
        const match = items.find((item) => item.endpoint === endpoint) ?? null
        setPlaylist(match)
        setPlaylistName(match?.name ?? null)
        setPlaylistThumb(normalizeRemoteUrl(match?.thumbnail_url))
      })
      .catch(() => {
        if (cancelled) return
        setPlaylist(null)
        setPlaylistName(null)
        setPlaylistThumb(null)
      })
      .finally(() => {
        if (cancelled) return
        setLoadingPlaylistMeta(false)
      })

    return () => {
      cancelled = true
    }
  }, [endpoint, playlistFromState])

  useEffect(() => {
    let cancelled = false

    queueMicrotask(() => {
      if (cancelled) return
      setLoading(true)
      setError(null)
    })

    api
      .getAvailablePlaylistTracks({ endpoint, page: 1, limit: 75, requiresAuth: playlist?.requires_auth })
      .then((res) => {
        if (cancelled) return
        const items = Array.isArray(res.items) ? res.items : []
        setCoverUrl(normalizeRemoteUrl(res.cover_url))
        setSongs(items)
        setLoading(false)
      })
      .catch((err) => {
        if (cancelled) return
        setSongs([])
        setCoverUrl(null)
        setError(err instanceof Error ? err.message : 'Failed to load playlist tracks')
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [endpoint, playlist?.requires_auth])

  const goBack = useCallback(() => {
    if (window.history.length > 1) {
      navigate(-1)
    } else {
      navigate('/', { replace: true })
    }
  }, [navigate])

  const handleCopyLink = useCallback(async () => {
    const link = `${window.location.origin}${endpoint}`
    try {
      await navigator.clipboard.writeText(link)
      setIsMenuOpen(false)
    } catch {
      const textArea = document.createElement('textarea')
      textArea.value = link
      textArea.style.position = 'fixed'
      textArea.style.left = '-999999px'
      document.body.appendChild(textArea)
      textArea.select()
      try {
        document.execCommand('copy')
        setIsMenuOpen(false)
      } catch {
        alert('Failed to copy link')
      }
      document.body.removeChild(textArea)
    }
  }, [endpoint])

  const handlePreview = useCallback(() => {
    if (songs.length === 0) return
    const first = songs[0]
    const isCurrent = currentSong?._id === first._id
    if (isCurrent) {
      if (!isPlaying) togglePlay()
    } else {
      playSongFromList(first, songs)
    }
  }, [currentSong?._id, isPlaying, playSongFromList, songs, togglePlay])

  const totalDuration = useMemo(() => {
    const totalSeconds = songs.reduce((sum, song) => sum + (song.duration_sec || 0), 0)
    const minutes = Math.floor(totalSeconds / 60)
    return minutes
  }, [songs])

  const cardThumbUrls = useMemo(() => {
    const primary = normalizeRemoteUrl(coverUrl ?? playlistThumb)
    if (primary) return [primary]

    const urls: string[] = []
    const seen = new Set<string>()
    for (const song of songs) {
      const url = normalizeRemoteUrl(song.cover_url)
      if (!url) continue
      if (seen.has(url)) continue
      seen.add(url)
      urls.push(url)
      if (urls.length >= 4) break
    }
    return urls
  }, [coverUrl, playlistThumb, songs])

  const displayTitle = playlistName || playlist?.name || 'Playlist'
  const hasThumb = cardThumbUrls.length > 0

  return (
    <div className="audio-page latest-songs-page favorites-page playlist-page available-playlist-page">
      <div className={`audio-topbar${useWebSongList ? ' audio-topbar--web' : ''}`}>
        {isTelegram && (
          <button className="audio-back-pill" type="button" onClick={goBack} aria-label="Back">
            <svg className="audio-back-icon" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
            </svg>
          </button>
        )}
        <button className="favorites-menu-btn" type="button" aria-label="More options" onClick={() => setIsMenuOpen(true)}>
          <div className="favorites-menu-dots-horizontal"><span className="favorites-menu-dot-red"></span><span className="favorites-menu-dot-red"></span><span className="favorites-menu-dot-red"></span></div>
        </button>
      </div>

      {isMenuOpen && (
        <>
          <div className="favorites-menu-backdrop" onClick={() => setIsMenuOpen(false)} />
          <div className="favorites-menu">
            <button className="favorites-menu-item" type="button" onClick={handleCopyLink}>
              <svg className="favorites-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"
                />
              </svg>
              Copy Link
            </button>
          </div>
        </>
      )}

      <main className={`content${useWebSongList ? ' latest-songs-content--web' : ''}`}>
        <div className="favorites-header">
          <div
            className="favorites-card favorites-card--playlist"
            data-has-thumb={hasThumb ? 'true' : 'false'}
            data-loading={loading ? 'true' : 'false'}
          >
            {!loading && cardThumbUrls.length >= 4 ? (
              <div className="favorites-card-thumb favorites-card-thumb--grid" aria-hidden="true">
                <div className="favorites-card-thumb-grid">
                  {cardThumbUrls.slice(0, 4).map((url, index) => (
                    <img key={`${endpoint}-${index}`} className="favorites-card-thumb-grid-img" src={url} alt="" loading="lazy" />
                  ))}
                </div>
              </div>
            ) : !loading && cardThumbUrls.length > 0 ? (
              <div className="favorites-card-thumb" aria-hidden="true">
                <img className="favorites-card-thumb-img" src={cardThumbUrls[0]} alt="" loading="lazy" />
              </div>
            ) : !loading ? (
              <div className="favorites-card-thumb favorites-card-thumb--empty" aria-hidden="true">
                <img className="favorites-card-thumb-empty-icon" src={emptyPlaylistUrl} alt="" loading="lazy" />
              </div>
            ) : null}
          </div>
          <div className="favorites-info">
            {loadingPlaylistMeta ? (
              <div className="favorites-title-skeleton" aria-label="Loading playlist name">
                <div className="skeleton-bar skeleton-bar--title"></div>
              </div>
            ) : (
              <h1 className="favorites-title">{displayTitle}</h1>
            )}
            {songs.length > 0 ? (
              <button className="favorites-preview-btn" type="button" onClick={handlePreview}>
                <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path
                    fill="currentColor"
                    d="M9 7.56C9 6.12 10.57 5.22 11.82 5.95L18.69 9.89C19.94 10.62 19.94 12.38 18.69 13.11L11.82 17.05C10.57 17.78 9 16.88 9 15.44V7.56Z"
                  />
                </svg>
                Preview
              </button>
            ) : null}
          </div>
        </div>

        {error ? (
          <div className="playlist-state">{error}</div>
        ) : loading ? (
          <div className="song-loading" role="status" aria-label="Loading">
            <div className="song-list-spinner" aria-hidden="true" />
          </div>
        ) : useWebSongList ? (
          <>
            <WebSongList songs={songs} title={displayTitle} loading={false} showTitle={false} />
            {songs.length > 0 ? (
              <div className="favorites-summary">
                {songs.length} song{songs.length !== 1 ? 's' : ''}, {totalDuration} minute{totalDuration !== 1 ? 's' : ''}
              </div>
            ) : null}
          </>
        ) : (
          <>
            <SongList songs={songs} title={displayTitle} layout="single" loading={false} showHeader={false} density="compact" />
            {songs.length > 0 ? (
              <div className="favorites-summary">
                {songs.length} song{songs.length !== 1 ? 's' : ''}, {totalDuration} minute{totalDuration !== 1 ? 's' : ''}
              </div>
            ) : null}
          </>
        )}
      </main>
    </div>
  )
}



