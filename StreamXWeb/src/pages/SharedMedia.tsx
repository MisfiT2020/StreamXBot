import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { SongList } from '../components/SongList.js'
import { WebSongList } from '../components/WebSongList.js'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { api, getWebSongListEnabled } from '../services/api.js'
import { buildSharedAlbumLink, buildSharedTrackLink, canOpenStreamXApp, getRuntimeApiBaseUrl, isAutoOpenLink, openStreamXTarget } from '../deepLinks.js'
import { platform } from '../platform.js'
import type { PlaylistTrack, SharedAlbumResponse, Song, TrackDetailsResponse } from '../types/index.js'
import emptyPlaylistUrl from '../assets/mptyPlaylist.svg'
import './Home.css'
import './Favorites.css'

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

const toSongFromDetails = (details: TrackDetailsResponse): Song => {
  const audio = details.audio ?? {}
  const title = (audio.title ?? details.title ?? '').trim() || 'Unknown title'
  const artist = (audio.artist ?? audio.performer ?? details.artist ?? '').trim() || 'Unknown artist'
  const album = audio.album ?? details.album ?? null
  const durationRaw = audio.duration_sec ?? details.duration_sec
  const duration_sec = typeof durationRaw === 'number' && Number.isFinite(durationRaw) ? durationRaw : 0
  const cover_url = details.spotify?.cover_url ?? details.cover_url ?? null
  const spotify_url = details.spotify?.url ?? null
  const type = typeof audio.type === 'string' ? audio.type : typeof details.type === 'string' ? details.type : ''
  const sampling_rate_hz = typeof audio.sampling_rate_hz === 'number' && Number.isFinite(audio.sampling_rate_hz) ? audio.sampling_rate_hz : 0

  return {
    _id: details._id,
    title,
    artist,
    album,
    duration_sec,
    cover_url,
    spotify_url,
    spotify: details.spotify,
    source_chat_id: 0,
    source_message_id: 0,
    type,
    sampling_rate_hz,
    updated_at: Date.now(),
  }
}

const copyText = async (text: string): Promise<boolean> => {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    document.body.appendChild(textArea)
    textArea.select()
    try {
      document.execCommand('copy')
      return true
    } catch {
      return false
    } finally {
      document.body.removeChild(textArea)
    }
  }
}

type SharedCollectionViewProps = {
  entityId: string
  title: string
  songs: Song[]
  coverUrl?: string | null
  loading: boolean
  error: string | null
  useWebSongList: boolean
  showOpenInApp: boolean
  isTelegram: boolean
  onGoBack: () => void
  onPreview: () => void
  onCopyLink: () => void
  onOpenInApp: () => void
}

const SharedCollectionView = ({
  entityId,
  title,
  songs,
  coverUrl,
  loading,
  error,
  useWebSongList,
  showOpenInApp,
  isTelegram,
  onGoBack,
  onPreview,
  onCopyLink,
  onOpenInApp,
}: SharedCollectionViewProps) => {
  const [isMenuOpen, setIsMenuOpen] = useState(false)

  const totalDuration = useMemo(() => {
    const totalSeconds = songs.reduce((sum, song) => sum + (song.duration_sec || 0), 0)
    return Math.floor(totalSeconds / 60)
  }, [songs])

  const cardThumbUrls = useMemo(() => {
    const primary = normalizeRemoteUrl(coverUrl)
    if (primary) return [primary]

    const urls: string[] = []
    const seen = new Set<string>()
    for (const song of songs) {
      const url = normalizeRemoteUrl(song.cover_url)
      if (!url || seen.has(url)) continue
      seen.add(url)
      urls.push(url)
      if (urls.length >= 4) break
    }
    return urls
  }, [coverUrl, songs])

  const hasThumb = cardThumbUrls.length > 0

  return (
    <div className="audio-page latest-songs-page favorites-page playlist-page available-playlist-page">
      <div className={`audio-topbar${useWebSongList ? ' audio-topbar--web' : ''}`}>
        {isTelegram && (
          <button className="audio-back-pill" type="button" onClick={onGoBack} aria-label="Back">
            <svg className="audio-back-icon" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
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
            <button className="favorites-menu-item" type="button" onClick={() => { setIsMenuOpen(false); onCopyLink() }}>
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
                    <img key={`${entityId}-${index}`} className="favorites-card-thumb-grid-img" src={url} alt="" loading="lazy" />
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
            {loading ? (
              <div className="favorites-title-skeleton" aria-label="Loading name">
                <div className="skeleton-bar skeleton-bar--title"></div>
              </div>
            ) : (
              <h1 className="favorites-title">{title}</h1>
            )}
            {songs.length > 0 ? (
              <button className="favorites-preview-btn" type="button" onClick={onPreview}>
                <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path
                    fill="currentColor"
                    d="M9 7.56C9 6.12 10.57 5.22 11.82 5.95L18.69 9.89C19.94 10.62 19.94 12.38 18.69 13.11L11.82 17.05C10.57 17.78 9 16.88 9 15.44V7.56Z"
                  />
                </svg>
                Preview
              </button>
            ) : null}
            {showOpenInApp ? (
              <button className="favorites-preview-btn favorites-preview-btn--add" type="button" onClick={onOpenInApp}>
                Open App
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
            <WebSongList songs={songs} title={title} loading={false} showTitle={false} />
            {songs.length > 0 ? (
              <div className="favorites-summary">
                {songs.length} song{songs.length !== 1 ? 's' : ''}, {totalDuration} minute{totalDuration !== 1 ? 's' : ''}
              </div>
            ) : null}
          </>
        ) : (
          <>
            <SongList songs={songs} title={title} layout="single" loading={false} showHeader={false} density="compact" />
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

export const SharedAlbumPage = () => {
  const { albumId } = useParams<{ albumId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const { playSongFromList, currentSong, isPlaying, togglePlay } = usePlayerPlayback()
  const useWebSongList = getWebSongListEnabled()
  const isTelegram = platform.isTelegram
  const runtimeApiBaseUrl = useMemo(() => getRuntimeApiBaseUrl(location.search), [location.search])
  const autoLaunchAttemptedRef = useRef(false)
  const showOpenInApp = canOpenStreamXApp()

  const [album, setAlbum] = useState<SharedAlbumResponse | null>(null)
  const [songs, setSongs] = useState<Song[]>([])
  const [coverUrl, setCoverUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const shareLink = useMemo(() => (albumId ? buildSharedAlbumLink(albumId, runtimeApiBaseUrl) : ''), [albumId, runtimeApiBaseUrl])

  useEffect(() => {
    if (!albumId || !showOpenInApp || !isAutoOpenLink(location.search)) return
    if (autoLaunchAttemptedRef.current) return
    autoLaunchAttemptedRef.current = true
    openStreamXTarget(
      { kind: 'album', id: albumId },
      { apiBaseUrl: runtimeApiBaseUrl, fallbackUrl: window.location.href },
    )
  }, [albumId, location.search, runtimeApiBaseUrl, showOpenInApp])

  useEffect(() => {
    let cancelled = false
    if (!albumId) return () => {
      cancelled = true
    }

    queueMicrotask(() => {
      if (cancelled) return
      setLoading(true)
      setError(null)
    })

    api
      .getSharedAlbum(albumId, runtimeApiBaseUrl)
      .then((res) => {
        if (cancelled) return
        setAlbum(res)
        setCoverUrl(normalizeRemoteUrl(res.album?.cover_url))
        setSongs((Array.isArray(res.tracks) ? res.tracks : []).map(toSong))
        setLoading(false)
      })
      .catch((err) => {
        if (cancelled) return
        setAlbum(null)
        setCoverUrl(null)
        setSongs([])
        setError(err instanceof Error ? err.message : 'Failed to load shared album')
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [albumId, runtimeApiBaseUrl])

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

  const handleCopyLink = useCallback(() => {
    if (!shareLink) return
    copyText(shareLink).then((ok) => {
      if (!ok) alert('Failed to copy link')
    })
  }, [shareLink])

  const handleOpenInApp = useCallback(() => {
    if (!albumId) return
    openStreamXTarget(
      { kind: 'album', id: albumId },
      { apiBaseUrl: runtimeApiBaseUrl, fallbackUrl: window.location.href },
    )
  }, [albumId, runtimeApiBaseUrl])

  if (!albumId) return null

  return (
    <SharedCollectionView
      entityId={albumId}
      title={album?.album?.title || 'Album'}
      songs={songs}
      coverUrl={coverUrl}
      loading={loading}
      error={error}
      useWebSongList={useWebSongList}
      showOpenInApp={showOpenInApp}
      isTelegram={isTelegram}
      onGoBack={goBack}
      onPreview={handlePreview}
      onCopyLink={handleCopyLink}
      onOpenInApp={handleOpenInApp}
    />
  )
}

export const SharedTrackPage = () => {
  const { trackId } = useParams<{ trackId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const { playSongFromList, currentSong, isPlaying, togglePlay } = usePlayerPlayback()
  const useWebSongList = getWebSongListEnabled()
  const isTelegram = platform.isTelegram
  const runtimeApiBaseUrl = useMemo(() => getRuntimeApiBaseUrl(location.search), [location.search])
  const autoLaunchAttemptedRef = useRef(false)
  const showOpenInApp = canOpenStreamXApp()

  const [track, setTrack] = useState<TrackDetailsResponse | null>(null)
  const [songs, setSongs] = useState<Song[]>([])
  const [coverUrl, setCoverUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const shareLink = useMemo(() => (trackId ? buildSharedTrackLink(trackId, runtimeApiBaseUrl) : ''), [trackId, runtimeApiBaseUrl])

  useEffect(() => {
    if (!trackId || !showOpenInApp || !isAutoOpenLink(location.search)) return
    if (autoLaunchAttemptedRef.current) return
    autoLaunchAttemptedRef.current = true
    openStreamXTarget(
      { kind: 'track', id: trackId },
      { apiBaseUrl: runtimeApiBaseUrl, fallbackUrl: window.location.href },
    )
  }, [trackId, location.search, runtimeApiBaseUrl, showOpenInApp])

  useEffect(() => {
    let cancelled = false
    if (!trackId) return () => {
      cancelled = true
    }

    queueMicrotask(() => {
      if (cancelled) return
      setLoading(true)
      setError(null)
    })

    api
      .getSharedTrack(trackId, runtimeApiBaseUrl)
      .then((res) => {
        if (cancelled) return
        const song = toSongFromDetails(res)
        setTrack(res)
        setCoverUrl(normalizeRemoteUrl(song.cover_url))
        setSongs([song])
        setLoading(false)
      })
      .catch((err) => {
        if (cancelled) return
        setTrack(null)
        setCoverUrl(null)
        setSongs([])
        setError(err instanceof Error ? err.message : 'Failed to load shared track')
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [trackId, runtimeApiBaseUrl])

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

  const handleCopyLink = useCallback(() => {
    if (!shareLink) return
    copyText(shareLink).then((ok) => {
      if (!ok) alert('Failed to copy link')
    })
  }, [shareLink])

  const handleOpenInApp = useCallback(() => {
    if (!trackId) return
    openStreamXTarget(
      { kind: 'track', id: trackId },
      { apiBaseUrl: runtimeApiBaseUrl, fallbackUrl: window.location.href },
    )
  }, [trackId, runtimeApiBaseUrl])

  if (!trackId) return null

  return (
    <SharedCollectionView
      entityId={trackId}
      title={songs[0]?.title || track?.audio?.title || track?.title || 'Track'}
      songs={songs}
      coverUrl={coverUrl}
      loading={loading}
      error={error}
      useWebSongList={useWebSongList}
      showOpenInApp={showOpenInApp}
      isTelegram={isTelegram}
      onGoBack={goBack}
      onPreview={handlePreview}
      onCopyLink={handleCopyLink}
      onOpenInApp={handleOpenInApp}
    />
  )
}
