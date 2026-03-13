import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { usePlayerLibrary, usePlayerPlayback } from '../context/PlayerContext.js'
import { createJam, jamAddQueue } from '../services/jamApi.js'
import { api, CACHE_TTL_MS, getAuthUserInfo, getCacheEnabled, getWebSongListEnabled, setAuthUserInfo } from '../services/api.js'
import { platform } from '../platform.js'
import type { Playlist, Song } from '../types/index.js'
import './Home.css'
import '../components/SongList.css'

export const LatestSongsPage = () => {
  const location = useLocation()
  const navigate = useNavigate()
  const { songs, setSongs } = usePlayerLibrary()
  const { playSongFromList, playNextTrack, currentSong, favouriteIds, toggleFavourite, externalJamId, externalUpcoming, setExternalUpcoming } = usePlayerPlayback()
  const userId = getAuthUserInfo()?.user_id || null
  const [latestLoading, setLatestLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [currentPage, setCurrentPage] = useState(1)
  const [hasMore, setHasMore] = useState(true)
  const [menuSong, setMenuSong] = useState<Song | null>(null)
  const [menuPosition, setMenuPosition] = useState<{ x: number; y: number } | null>(null)
  const [menuView, setMenuView] = useState<'root' | 'playlist' | 'jamConfirm'>('root')
  const [myPlaylists, setMyPlaylists] = useState<Playlist[]>([])
  const [playlistsLoading, setPlaylistsLoading] = useState(false)
  const [playlistsError, setPlaylistsError] = useState<string | null>(null)
  const [addingPlaylistId, setAddingPlaylistId] = useState<string | null>(null)
  const [createPlaylistOpen, setCreatePlaylistOpen] = useState(false)
  const [createPlaylistName, setCreatePlaylistName] = useState('')
  const [creatingPlaylist, setCreatingPlaylist] = useState(false)
  const menuOpenedAtRef = useRef<number>(0)
  const useWebSongList = getWebSongListEnabled()
  const isTelegram = platform.isTelegram

  // Reset state when component mounts
  useEffect(() => {
    setCurrentPage(1)
    setHasMore(true)
    setLoadingMore(false)
  }, [])

  useEffect(() => {
    let cancelled = false

    const cacheEnabled = getCacheEnabled()

    if (!cacheEnabled) {
      queueMicrotask(() => {
        if (cancelled) return
        setLatestLoading(true)
      })
      api
        .browseTracks(1)
        .then((data) => {
          if (cancelled) return
          const items = Array.isArray(data.items) ? data.items : []
          setSongs(items)
          setLatestLoading(false)
          setHasMore(items.length >= 20)
        })
        .catch((err) => {
          void err
          if (cancelled) return
          setLatestLoading(false)
        })

      return () => {
        cancelled = true
      }
    }

    const cacheKey = `streamw:latestSongs:${String(userId)}:page:1`
    const cacheTtlMs = CACHE_TTL_MS
    const now = Date.now()
    let cachedItems: Song[] | null = null
    let cacheFresh = false
    let hasCachedList = false

    try {
      const cachedRaw = localStorage.getItem(cacheKey)
      if (cachedRaw) {
        const cached = JSON.parse(cachedRaw) as { ts?: number; items?: Song[] }
        cachedItems = Array.isArray(cached.items) ? cached.items : null
        hasCachedList = Boolean(cachedItems && cachedItems.length > 0)
        cacheFresh = Boolean(cachedItems) && typeof cached.ts === 'number' && now - cached.ts < cacheTtlMs
      }
    } catch (err) {
      void err
    }

    if (cachedItems) {
      queueMicrotask(() => {
        if (cancelled) return
        setSongs(cachedItems)
        setLatestLoading(false)
        setHasMore(cachedItems.length >= 20)
      })
    }

    if (cacheFresh) {
      queueMicrotask(() => {
        if (cancelled) return
        setLatestLoading(false)
      })
      return () => {
        cancelled = true
      }
    }

    queueMicrotask(() => {
      if (cancelled) return
      setLatestLoading(!hasCachedList)
    })

    api
      .browseTracks(1)
      .then((data) => {
        if (cancelled) return
        const items = Array.isArray(data.items) ? data.items : []
        setSongs(items)
        setLatestLoading(false)
        setHasMore(items.length >= 20)
        try {
          localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), items }))
        } catch (err) {
          void err
        }
      })
      .catch((err) => {
        void err
        if (cancelled) return
        setLatestLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [setSongs, userId])

  const loadMoreSongs = useCallback(() => {
    if (loadingMore || !hasMore) return
    
    const nextPage = currentPage + 1
    setLoadingMore(true)
    
    api
      .browseTracks(nextPage)
      .then((data) => {
        const items = Array.isArray(data.items) ? data.items : []
        if (items.length > 0) {
          setSongs((prev) => [...prev, ...items])
          setCurrentPage(nextPage)
          setHasMore(items.length >= 20)
        } else {
          setHasMore(false)
        }
        setLoadingMore(false)
      })
      .catch((err) => {
        void err
        setLoadingMore(false)
      })
  }, [currentPage, hasMore, loadingMore, setSongs])

  useEffect(() => {
    const handleScroll = () => {
      if (loadingMore || !hasMore || latestLoading) return
      
      const scrollTop = window.scrollY || document.documentElement.scrollTop
      const scrollHeight = document.documentElement.scrollHeight
      const clientHeight = window.innerHeight
      
      // Trigger when within 600px of bottom
      if (scrollTop + clientHeight >= scrollHeight - 600) {
        loadMoreSongs()
      }
    }

    // Also check on mount in case content is short
    const checkInitialScroll = () => {
      if (!latestLoading && !loadingMore && hasMore) {
        const scrollHeight = document.documentElement.scrollHeight
        const clientHeight = window.innerHeight
        
        // If content doesn't fill the screen, load more
        if (scrollHeight <= clientHeight + 100) {
          loadMoreSongs()
        }
      }
    }

    window.addEventListener('scroll', handleScroll, { passive: true })
    
    // Check after a short delay to ensure content is rendered
    const timer = setTimeout(checkInitialScroll, 300)
    
    return () => {
      window.removeEventListener('scroll', handleScroll)
      clearTimeout(timer)
    }
  }, [loadMoreSongs, loadingMore, hasMore, latestLoading])

  const goBack = useCallback(() => {
    if (window.history.length > 1) {
      navigate(-1)
    } else {
      navigate('/', { replace: true })
    }
  }, [navigate])

  const handleRowClick = useCallback((song: Song) => {
    const isCurrent = currentSong?._id === song._id
    if (isCurrent) {
      // Already playing, do nothing or toggle play
    } else {
      playSongFromList(song, songs)
    }
  }, [currentSong, playSongFromList, songs])

  const cacheMyPlaylists = useCallback((items: Playlist[]) => {
    try {
      window.localStorage.setItem('streamw:playlists:me', JSON.stringify({ ts: Date.now(), items }))
    } catch {
      void 0
    }
  }, [])

  const loadMyPlaylists = useCallback(async () => {
    if (playlistsLoading) return

    setPlaylistsError(null)

    try {
      const cachedRaw = window.localStorage.getItem('streamw:playlists:me')
      if (cachedRaw) {
        const cached = JSON.parse(cachedRaw) as { ts?: number; items?: Playlist[] }
        const cachedItems = Array.isArray(cached.items) ? cached.items : null
        const tsOk = typeof cached.ts === 'number' && Date.now() - cached.ts < 5 * 60 * 1000
        if (cachedItems) {
          setMyPlaylists(cachedItems)
          if (tsOk) return
        }
      }
    } catch {
      void 0
    }

    setPlaylistsLoading(true)
    try {
      const res = await api.getMyPlaylists()
      const items = Array.isArray(res.items) ? res.items : []
      setMyPlaylists(items)
      cacheMyPlaylists(items)
    } catch (err) {
      setPlaylistsError(err instanceof Error ? err.message : 'Failed to load playlists')
    } finally {
      setPlaylistsLoading(false)
    }
  }, [cacheMyPlaylists, playlistsLoading])

  const readRecentPlaylistIds = useCallback((): string[] => {
    try {
      const raw = window.localStorage.getItem('streamw:playlists:recent')
      if (!raw) return []
      const parsed = JSON.parse(raw) as unknown
      if (!Array.isArray(parsed)) return []
      return parsed.filter((v): v is string => typeof v === 'string' && v.trim().length > 0).slice(0, 8)
    } catch {
      return []
    }
  }, [])

  const writeRecentPlaylistIds = useCallback((ids: string[]) => {
    try {
      window.localStorage.setItem('streamw:playlists:recent', JSON.stringify(ids.slice(0, 8)))
    } catch {
      void 0
    }
  }, [])

  const bumpRecentPlaylistId = useCallback(
    (playlistId: string) => {
      const existing = readRecentPlaylistIds()
      const next = [playlistId, ...existing.filter((id) => id !== playlistId)].slice(0, 8)
      writeRecentPlaylistIds(next)
    },
    [readRecentPlaylistIds, writeRecentPlaylistIds],
  )

  const activeJamId = useMemo(() => {
    const match = location.pathname.match(/^\/jam\/([^/?#]+)/i)
    const fromPath = match?.[1] ?? null
    if (fromPath) return fromPath
    try {
      return window.localStorage.getItem('streamw:jam:activeId')
    } catch {
      return null
    }
  }, [location.pathname])

  const handleMenuClick = useCallback((song: Song, event: React.MouseEvent<HTMLButtonElement>) => {
    const rect = event.currentTarget.getBoundingClientRect()
    const menuWidth = 240
    const menuHeight = 180
    const padding = 8

    let x = rect.left
    let y = rect.bottom + 4

    if (x + menuWidth > window.innerWidth - padding) {
      x = rect.right - menuWidth
    }

    if (y + menuHeight > window.innerHeight - padding) {
      y = rect.top - menuHeight - 4
    }

    if (x < padding) {
      x = padding
    }

    if (y < padding) {
      y = padding
    }

    menuOpenedAtRef.current = performance.now()
    setMenuSong(song)
    setMenuPosition({ x, y })
    setMenuView('root')
    setPlaylistsError(null)
    setAddingPlaylistId(null)
    setCreatePlaylistOpen(false)
    setCreatePlaylistName('')
    setCreatingPlaylist(false)
  }, [])

  const closeMenu = useCallback(() => {
    setMenuSong(null)
    setMenuPosition(null)
    setMenuView('root')
    setPlaylistsError(null)
    setAddingPlaylistId(null)
    setCreatePlaylistOpen(false)
    setCreatePlaylistName('')
    setCreatingPlaylist(false)
  }, [])

  useEffect(() => {
    if (!menuSong) return
    let didClose = false
    const onScrollLike = () => {
      if (didClose) return
      if (performance.now() - menuOpenedAtRef.current < 140) return
      if (menuView !== 'root') return
      didClose = true
      closeMenu()
    }
    window.addEventListener('scroll', onScrollLike, { passive: true, capture: true })
    window.addEventListener('wheel', onScrollLike, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScrollLike, { capture: true } as AddEventListenerOptions)
      window.removeEventListener('wheel', onScrollLike)
    }
  }, [closeMenu, menuSong, menuView])

  const ensureMenuFits = useCallback((menuWidth: number, menuHeight: number) => {
    setMenuPosition((pos) => {
      if (!pos) return pos
      const padding = 8

      let x = pos.x
      let y = pos.y

      if (x + menuWidth > window.innerWidth - padding) {
        x = window.innerWidth - padding - menuWidth
      }
      if (y + menuHeight > window.innerHeight - padding) {
        y = window.innerHeight - padding - menuHeight
      }

      if (x < padding) x = padding
      if (y < padding) y = padding

      return { x, y }
    })
  }, [])

  const handleAdd = useCallback(() => {
    if (!menuSong) return
    if (activeJamId) {
      const shouldSyncUpcoming = externalJamId === activeJamId
      const previousUpcoming = shouldSyncUpcoming ? externalUpcoming : null
      const alreadyUpcoming = shouldSyncUpcoming ? externalUpcoming.some((song) => song._id === menuSong._id) : false
      if (shouldSyncUpcoming && previousUpcoming && !alreadyUpcoming) {
        setExternalUpcoming([...previousUpcoming, menuSong])
      }

      jamAddQueue(activeJamId, menuSong._id, null)
        .then(() => closeMenu())
        .catch(() => {
          if (shouldSyncUpcoming && previousUpcoming && !alreadyUpcoming) {
            setExternalUpcoming(previousUpcoming)
          }
          closeMenu()
        })
      navigate(`/jam/${activeJamId}`)
      return
    }
    setMenuView('jamConfirm')
    ensureMenuFits(240, 180)
  }, [activeJamId, closeMenu, ensureMenuFits, externalJamId, externalUpcoming, menuSong, navigate, setExternalUpcoming])

  const handleConfirmStartJam = useCallback(async () => {
    if (!menuSong) return
    closeMenu()
    try {
      const result = await createJam({ track_id: menuSong._id })
      const createdJamId = (result?.jam?._id || '').trim()
      if (!createdJamId || createdJamId === 'undefined' || createdJamId === 'null') {
        throw new Error('Invalid jam id from server')
      }

      const hostUserId = result?.jam?.host_user_id
      if (typeof hostUserId === 'number' && Number.isFinite(hostUserId)) {
        const existing = getAuthUserInfo()
        if (existing?.user_id !== hostUserId) {
          setAuthUserInfo({
            first_name: existing?.first_name,
            user_id: hostUserId,
          })
        }
      }

      try {
        window.localStorage.setItem('streamw:jam:activeId', createdJamId)
      } catch {
        void 0
      }

      navigate(`/jam/${createdJamId}`, { state: { initialJam: result?.jam ?? null } })
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to start jam')
    }
  }, [closeMenu, menuSong, navigate])

  const handleCancelStartJam = useCallback(() => {
    closeMenu()
  }, [closeMenu])

  const handleToggleFavorite = useCallback(() => {
    if (!menuSong) return
    closeMenu()
    toggleFavourite(menuSong._id).catch(() => {})
  }, [closeMenu, menuSong, toggleFavourite])

  const handlePlayNext = useCallback(() => {
    if (!menuSong) return
    playNextTrack(menuSong)
    closeMenu()
  }, [closeMenu, menuSong, playNextTrack])

  const handleAddToPlaylist = useCallback(async () => {
    if (!menuSong) return
    setMenuView('playlist')
    ensureMenuFits(300, 340)
    await loadMyPlaylists()
  }, [ensureMenuFits, loadMyPlaylists, menuSong])

  const handleSelectPlaylist = useCallback(
    async (playlistId: string) => {
      if (!menuSong) return
      if (addingPlaylistId) return

      setAddingPlaylistId(playlistId)
      bumpRecentPlaylistId(playlistId)
      closeMenu()

      try {
        await api.addTrackToMyPlaylist(playlistId, menuSong._id)
      } catch (err) {
        alert(err instanceof Error ? err.message : 'Failed to add track to playlist')
      }
    },
    [addingPlaylistId, bumpRecentPlaylistId, closeMenu, menuSong],
  )

  const openCreatePlaylist = useCallback(() => {
    if (!menuSong) return
    setCreatePlaylistName(menuSong.title ? `${menuSong.title}` : '')
    setCreatePlaylistOpen(true)
  }, [menuSong])

  const handleCreatePlaylist = useCallback(async () => {
    if (!menuSong) return
    const name = createPlaylistName.trim()
    if (!name) return
    if (creatingPlaylist) return

    setCreatingPlaylist(true)
    try {
      const created = await api.createMyPlaylist(name)
      await api.addTrackToMyPlaylist(created.playlist_id, menuSong._id)
      bumpRecentPlaylistId(created.playlist_id)
      setMyPlaylists((prev) => {
        const next = [created, ...prev.filter((p) => p.playlist_id !== created.playlist_id)]
        cacheMyPlaylists(next)
        return next
      })
      setCreatePlaylistOpen(false)
      closeMenu()
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to create playlist')
      setCreatingPlaylist(false)
    }
  }, [bumpRecentPlaylistId, cacheMyPlaylists, closeMenu, createPlaylistName, creatingPlaylist, menuSong])

  useEffect(() => {
    if (!createPlaylistOpen) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setCreatePlaylistOpen(false)
        return
      }
      if (e.key === 'Enter') {
        handleCreatePlaylist()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [createPlaylistOpen, handleCreatePlaylist])

  const playlistItems = useMemo(() => {
    const recentIds = readRecentPlaylistIds()
    const byId = new Map(myPlaylists.map((p) => [p.playlist_id, p]))

    const recent = recentIds.map((id) => byId.get(id)).filter((p): p is Playlist => Boolean(p))
    const rest = myPlaylists.filter((p) => !recentIds.includes(p.playlist_id))
    return { recent, rest }
  }, [myPlaylists, readRecentPlaylistIds])

  const formatDuration = useCallback((seconds: number) => {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins}:${String(secs).padStart(2, '0')}`
  }, [])

  return (
    <div className="audio-page latest-songs-page">
      <div className={`audio-topbar${useWebSongList ? ' audio-topbar--web' : ''}`}>
        {isTelegram ? (
          <button className="audio-back-pill" type="button" onClick={goBack} aria-label="Back">
            <svg className="audio-back-icon" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
            </svg>
          </button>
        ) : null}
        <div className="audio-topbar-title">Latest Songs</div>
      </div>

      <main className={`content${useWebSongList ? ' latest-songs-content--web' : ''}`}>
        <div className="table-song-list">
          <div className="table-song-header">
            <div className="table-song-header-cell table-song-header-cell--song">Song</div>
            <div className="table-song-header-cell table-song-header-cell--artist">Artist</div>
            <div className="table-song-header-cell table-song-header-cell--album">Album</div>
            <div className="table-song-header-cell table-song-header-cell--time">Time</div>
          </div>
          {latestLoading ? (
            <div className="song-loading" role="status" aria-label="Loading">
              <div className="song-list-spinner" aria-hidden="true" />
            </div>
          ) : (
            <div className="table-song-body">
              {songs.map((song, index) => {
                const isFavourite = favouriteIds.has(song._id)
                const isPlaying = currentSong?._id === song._id
                return (
                  <div 
                    key={song._id} 
                    className={`table-song-row${isPlaying ? ' is-playing' : ''}${index % 2 === 1 ? ' is-odd' : ''}`}
                    onClick={() => handleRowClick(song)}
                  >
                    <div className="table-song-cell table-song-cell--song">
                      <span className="song-fav-star" data-visible={isFavourite ? 'true' : 'false'} aria-hidden="true">
                        <svg viewBox="0 0 60 60" xmlns="http://www.w3.org/2000/svg">
                          <path
                            fill="currentColor"
                            d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99z"
                          />
                        </svg>
                      </span>
                      {song.cover_url ? (
                        <img className="table-song-cover" src={song.cover_url} alt="" loading="lazy" />
                      ) : (
                        <div className="table-song-cover table-song-cover--empty" />
                      )}
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div className="table-song-title">{song.title}</div>
                        <div className="table-song-artist-mobile">{song.artist}</div>
                      </div>
                    </div>
                    <div className="table-song-cell table-song-cell--artist">{song.artist}</div>
                    <div className="table-song-cell table-song-cell--album">{song.album || '—'}</div>
                    <div className="table-song-cell table-song-cell--time">
                      {formatDuration(song.duration_sec)}
                    </div>
                    <button 
                      className={`table-song-menu-btn${menuSong?._id === song._id ? ' is-open' : ''}`} 
                      type="button" 
                      aria-label="More options"
                      onClick={(e) => {
                        e.stopPropagation()
                        handleMenuClick(song, e)
                      }}
                    >
                      <span className="table-song-menu-dot"></span>
                      <span className="table-song-menu-dot"></span>
                      <span className="table-song-menu-dot"></span>
                    </button>
                  </div>
                )
              })}
            </div>
          )}
        </div>
        {loadingMore && (
          <div className="song-loading" role="status" aria-label="Loading more">
            <div className="song-list-spinner" aria-hidden="true" />
          </div>
        )}
        
        {menuSong && menuPosition && (
          <>
            <div className="song-menu-backdrop" onClick={closeMenu} />
            <div 
              className="song-menu" 
              style={{ 
                top: `${menuPosition.y}px`,
                left: `${menuPosition.x}px`,
              }}
            >
              {menuView === 'root' ? (
                <>
                  <div className="song-menu-top">
                    <button className="song-menu-icon-btn" onClick={handleAdd}>
                      <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                        <path fill="currentColor" d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
                      </svg>
                      <span>Add</span>
                    </button>

                    <button className="song-menu-icon-btn" onClick={handleToggleFavorite}>
                      {favouriteIds.has(menuSong._id) ? (
                        <>
                          <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                            <path fill="currentColor" d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
                          </svg>
                          <span>Undo</span>
                        </>
                      ) : (
                        <>
                          <svg viewBox="0 0 60 60" xmlns="http://www.w3.org/2000/svg">
                            <path
                              fill="currentColor"
                              d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99z"
                            />
                          </svg>
                          <span>Favourite</span>
                        </>
                      )}
                    </button>

                    <button className="song-menu-icon-btn" onClick={handlePlayNext}>
                      <svg viewBox="0 0 32 28" xmlns="http://www.w3.org/2000/svg">
                        <path
                          fill="currentColor"
                          d="M18.14 20.68c.365 0 .672-.107 1.038-.323l8.508-4.997c.623-.365.938-.814.938-1.37 0-.564-.307-.988-.938-1.361l-8.508-4.997c-.366-.216-.68-.324-1.046-.324-.73 0-1.337.556-1.337 1.569v4.773c-.108-.399-.406-.73-.904-1.021L7.382 7.632c-.357-.216-.672-.324-1.037-.324-.73 0-1.345.556-1.345 1.569v10.235c0 1.013.614 1.569 1.345 1.569.365 0 .68-.108 1.037-.324l8.509-4.997c.49-.29.796-.631.904-1.038v4.79c0 1.013.615 1.569 1.345 1.569z"
                        />
                      </svg>
                      <span>Play Next</span>
                    </button>
                  </div>

                  <div className="song-menu-divider" />

                  <button className="song-menu-item" onClick={handleAddToPlaylist}>
                    <svg className="song-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                      <path fill="currentColor" d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h13v2H3v-2zm16 0v3h3v2h-3v3h-2v-3h-3v-2h3v-3h2z" />
                    </svg>
                    <span>Add to Playlist</span>
                  </button>
                </>
              ) : menuView === 'jamConfirm' ? (
                <>
                  <button className="song-menu-item" type="button" disabled>
                    Do you wanna start the jam?
                  </button>
                  <div className="song-menu-divider" />
                  <button className="song-menu-item" type="button" onClick={handleConfirmStartJam}>
                    Yes
                  </button>
                  <button className="song-menu-item" type="button" onClick={handleCancelStartJam}>
                    No
                  </button>
                </>
              ) : (
                <>
                  <div className="song-menu-header">
                    <button
                      className="song-menu-back-btn"
                      type="button"
                      onClick={() => {
                        setMenuView('root')
                        ensureMenuFits(240, 180)
                      }}
                      aria-label="Back"
                    >
                      <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                        <path fill="currentColor" d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z" />
                      </svg>
                    </button>
                    <div className="song-menu-title">Add to playlist</div>
                  </div>

                  <button className="song-menu-item song-menu-item--create" type="button" onClick={openCreatePlaylist}>
                    <svg className="song-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                      <path fill="currentColor" d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
                    </svg>
                    <span>New Playlist</span>
                  </button>

                  <div className="song-menu-divider" />

                  {playlistsError ? (
                    <div className="song-menu-error">{playlistsError}</div>
                  ) : playlistsLoading ? (
                    <div className="song-menu-loading">Loading…</div>
                  ) : (
                    <>
                      {playlistItems.recent.length > 0 ? <div className="song-menu-section-label">Recent</div> : null}
                      {playlistItems.recent.map((pl) => (
                        <button
                          key={`recent-${pl.playlist_id}`}
                          className="song-menu-item"
                          type="button"
                          onClick={() => handleSelectPlaylist(pl.playlist_id)}
                          disabled={addingPlaylistId === pl.playlist_id}
                        >
                          <svg className="song-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                            <path fill="currentColor" d="M12 2a10 10 0 100 20 10 10 0 000-20zm1 11h4v-2h-3V7h-2v6z" />
                          </svg>
                          <span>{pl.name}</span>
                        </button>
                      ))}

                      {playlistItems.rest.length > 0 ? <div className="song-menu-section-label">Playlists</div> : null}
                      {playlistItems.rest.slice(0, 8).map((pl) => (
                        <button
                          key={pl.playlist_id}
                          className="song-menu-item"
                          type="button"
                          onClick={() => handleSelectPlaylist(pl.playlist_id)}
                          disabled={addingPlaylistId === pl.playlist_id}
                        >
                          <svg className="song-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                            <path fill="currentColor" d="M4 6h16v2H4V6zm0 5h16v2H4v-2zm0 5h10v2H4v-2z" />
                          </svg>
                          <span>{pl.name}</span>
                        </button>
                      ))}
                    </>
                  )}
                </>
              )}
            </div>
          </>
        )}

        {menuSong && createPlaylistOpen ? (
          <div
            className="playlist-modal-backdrop"
            role="dialog"
            aria-modal="true"
            onClick={() => {
              if (creatingPlaylist) return
              setCreatePlaylistOpen(false)
            }}
          >
            <div className="playlist-modal" onClick={(e) => e.stopPropagation()}>
              <div className="playlist-modal-header">
                <div className="playlist-modal-title">New playlist</div>
                <button
                  className="playlist-modal-close"
                  type="button"
                  onClick={() => {
                    if (creatingPlaylist) return
                    setCreatePlaylistOpen(false)
                  }}
                  aria-label="Close"
                >
                  <span aria-hidden="true">×</span>
                </button>
              </div>
              <div className="playlist-modal-content">
                <input
                  className="playlist-modal-input"
                  type="text"
                  value={createPlaylistName}
                  onChange={(e) => setCreatePlaylistName(e.target.value)}
                  placeholder="Playlist name"
                  autoFocus
                  disabled={creatingPlaylist}
                />
              </div>
              <div className="playlist-modal-footer">
                <button
                  className="playlist-modal-btn playlist-modal-btn--cancel"
                  type="button"
                  onClick={() => {
                    if (creatingPlaylist) return
                    setCreatePlaylistOpen(false)
                  }}
                >
                  Cancel
                </button>
                <button
                  className="playlist-modal-btn playlist-modal-btn--primary"
                  type="button"
                  onClick={handleCreatePlaylist}
                  disabled={!createPlaylistName.trim() || creatingPlaylist}
                >
                  {creatingPlaylist ? 'Creating…' : 'Create'}
                </button>
              </div>
            </div>
          </div>
        ) : null}
      </main>
    </div>
  )
}
