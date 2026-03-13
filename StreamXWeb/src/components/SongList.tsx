import { memo, useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { Playlist, Song } from '../types/index.js'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { createJam, jamAddQueue } from '../services/jamApi.js'
import { api, getAuthUserInfo, setAuthUserInfo } from '../services/api.js'
import latestArrowUrl from '../assets/latestArrow.svg'
import './SongList.css'

interface SongListProps {
  songs: Song[]
  title: string
  layout?: 'single' | 'two-column'
  rowsPerColumn?: number
  loading?: boolean
  onTitleClick?: () => void
  showHeader?: boolean
  showArrow?: boolean
  density?: 'default' | 'compact'
  hideFavoriteStar?: boolean
  removingSongIds?: Set<string>
  titleAction?: React.ReactNode
  playlistId?: string
  onRemoveFromPlaylist?: (trackId: string) => void
}

const SongItem = memo(
  ({
    song,
    isActive,
    isFavourite,
    playState,
    onActivate,
    onMenuClick,
    hideFavoriteStar,
    isRemoving,
  }: {
    song: Song
    isActive: boolean
    isFavourite: boolean
    playState: 'playing' | 'paused' | 'none'
    onActivate: (e: MouseEvent<HTMLDivElement>) => void
    onMenuClick: (song: Song, e: MouseEvent<HTMLButtonElement>) => void
    hideFavoriteStar?: boolean
    isRemoving?: boolean
  }) => {
    const showPlaying = playState === 'playing'
    const showPlay = playState === 'paused'
    const showOverlay = showPlaying || showPlay

    return (
      <div className={`song-item${isActive ? ' is-current' : ''}${isRemoving ? ' is-removing' : ''}`} data-song-id={song._id} onClick={onActivate}>
        {!hideFavoriteStar && (
          <span className="song-fav-star" data-visible={isFavourite ? 'true' : 'false'} aria-hidden="true">
            <svg viewBox="0 0 60 60" xmlns="http://www.w3.org/2000/svg">
              <path
                fill="currentColor"
                d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99z"
              />
            </svg>
          </span>
        )}
        <div className={`home-song-cover${showPlaying ? ' is-playing' : ''}`} aria-hidden="true">
          <img src={song.cover_url || 'https://via.placeholder.com/60'} alt="" className="song-cover" loading="lazy" />
          {showOverlay ? (
            <div className="home-song-cover-overlay">
              {showPlaying ? (
                <div className="home-playing-indicator" aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </div>
              ) : (
                <svg className="home-song-cover-icon" viewBox="0 0 24 24" aria-hidden="true">
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
        <button
          className="more-btn"
          onClick={(e) => {
            e.stopPropagation()
            onMenuClick(song, e)
          }}
          aria-label="More"
          type="button"
        >
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

SongItem.displayName = 'SongItem'

export const SongList = memo(
  ({
    songs,
    title,
    layout = 'single',
    rowsPerColumn = 4,
    loading = false,
    onTitleClick,
    showHeader = true,
    showArrow = true,
    density = 'default',
    hideFavoriteStar = false,
    removingSongIds = new Set(),
    titleAction,
    playlistId,
    onRemoveFromPlaylist,
  }: SongListProps) => {
  const location = useLocation()
  const navigate = useNavigate()
  const { playSongFromList, playNextTrack, currentSong, isPlaying, togglePlay, favouriteIds, toggleFavourite, externalJamId, externalUpcoming, setExternalUpcoming } = usePlayerPlayback()
  const carouselRef = useRef<HTMLDivElement | null>(null)
  const [hasLeftShadow, setHasLeftShadow] = useState(false)
  const [hasRightShadow, setHasRightShadow] = useState(false)
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

  const updateShadows = useCallback(() => {
    const el = carouselRef.current
    if (!el) return
    const maxScrollLeft = el.scrollWidth - el.clientWidth
    setHasLeftShadow(el.scrollLeft > 1)
    setHasRightShadow(el.scrollLeft < maxScrollLeft - 1)
  }, [])

  const handleCarouselScroll = useCallback(() => {
    updateShadows()
  }, [updateShadows])

  useEffect(() => {
    updateShadows()
    window.addEventListener('resize', updateShadows, { passive: true })
    return () => window.removeEventListener('resize', updateShadows)
  }, [updateShadows])

  const songById = useMemo(() => {
    const map = new Map<string, Song>()
    songs.forEach((song) => {
      map.set(song._id, song)
    })
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
      playSongFromList(song, songs)
    },
    [currentSongId, playSongFromList, songById, songs, togglePlay],
  )

  const handleMenuClick = useCallback((song: Song, event: MouseEvent<HTMLButtonElement>) => {
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
      if (performance.now() - menuOpenedAtRef.current < 50) return
      // Don't close menu when in a sub-view (user might be scrolling/selecting)
      if (menuView !== 'root') return
      didClose = true
      closeMenu()
    }
    window.addEventListener('scroll', onScrollLike, { passive: true, capture: true })
    window.addEventListener('wheel', onScrollLike, { passive: true })

    const carousel = carouselRef.current
    carousel?.addEventListener('scroll', onScrollLike, { passive: true })

    return () => {
      window.removeEventListener('scroll', onScrollLike, { capture: true } as AddEventListenerOptions)
      window.removeEventListener('wheel', onScrollLike)
      carousel?.removeEventListener('scroll', onScrollLike)
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
      closeMenu() // Close immediately, don't wait for response
      
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

  const padToRows = useCallback((items: Song[], count: number): (Song | null)[] => {
    if (items.length >= count) return items.slice(0, count)
    return [...items, ...Array.from({ length: count - items.length }, () => null)]
  }, [])

  const renderSongRowOrPlaceholder = useCallback(
    (song: Song | null, key: string) => {
      if (!song) {
        return (
          <div key={key} className="song-item is-placeholder" aria-hidden="true">
            <div className="song-cover" />
            <div className="song-info">
              <div className="song-title">.</div>
              <div className="song-artist">.</div>
            </div>
            <span className="more-btn" />
          </div>
        )
      }

      const isActive = currentSongId === song._id
      const playState = isActive ? (isPlaying ? 'playing' : 'paused') : 'none'
      const isFavourite = favouriteIds.has(song._id)
      const isRemoving = removingSongIds.has(song._id)
      return (
        <SongItem
          key={key}
          song={song}
          isActive={isActive}
          isFavourite={isFavourite}
          playState={playState}
          onActivate={onActivate}
          onMenuClick={handleMenuClick}
          hideFavoriteStar={hideFavoriteStar}
          isRemoving={isRemoving}
        />
      )
    },
    [currentSongId, favouriteIds, handleMenuClick, isPlaying, onActivate, hideFavoriteStar, removingSongIds],
  )

  const columns = useMemo(() => {
    if (layout !== 'two-column') return []
    const columnCount = Math.ceil(songs.length / rowsPerColumn)
    return Array.from({ length: columnCount }, (_, columnIndex) => {
      const start = columnIndex * rowsPerColumn
      const slice = songs.slice(start, start + rowsPerColumn)
      return {
        key: `${start}-${start + slice.length}`,
        rows: padToRows(slice, rowsPerColumn),
      }
    })
  }, [layout, padToRows, rowsPerColumn, songs])

  useEffect(() => {
    requestAnimationFrame(updateShadows)
  }, [layout, rowsPerColumn, songs.length, updateShadows])

  return (
    <section className="section" data-density={density}>
      {showHeader ? (
        <div className="section-header">
          <h2
            className="section-title"
            data-clickable={onTitleClick ? 'true' : 'false'}
            onClick={onTitleClick}
            role={onTitleClick ? 'button' : undefined}
            tabIndex={onTitleClick ? 0 : undefined}
            onKeyDown={
              onTitleClick
                ? (e) => {
                    if (e.key !== 'Enter' && e.key !== ' ') return
                    e.preventDefault()
                    onTitleClick()
                  }
                : undefined
            }
          >
            <span className="section-title-text">{title}</span>
            {showArrow ? <img className="section-title-arrow" src={latestArrowUrl} alt="" aria-hidden="true" /> : null}
          </h2>
          {titleAction ? <div className="section-title-action">{titleAction}</div> : null}
        </div>
      ) : null}
      {loading ? (
        <div className="song-loading" role="status" aria-label="Loading">
          <div className="song-list-spinner" aria-hidden="true" />
        </div>
      ) : layout === 'two-column' ? (
        <div
          className="song-carousel-container"
          data-left-shadow={hasLeftShadow ? 'true' : 'false'}
          data-right-shadow={hasRightShadow ? 'true' : 'false'}
        >
          <div className="song-carousel" ref={carouselRef} onScroll={handleCarouselScroll}>
            {columns.map((column) => (
              <div key={column.key} className="song-column song-column-card">
                {column.rows.map((song, index) => renderSongRowOrPlaceholder(song, `${column.key}-${index}`))}
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="song-list">
          {songs.map((song) => {
            const isActive = currentSongId === song._id
            const playState = isActive ? (isPlaying ? 'playing' : 'paused') : 'none'
            const isFavourite = favouriteIds.has(song._id)
            const isRemoving = removingSongIds.has(song._id)
            return <SongItem key={song._id} song={song} isActive={isActive} isFavourite={isFavourite} playState={playState} onActivate={onActivate} onMenuClick={handleMenuClick} hideFavoriteStar={hideFavoriteStar} isRemoving={isRemoving} />
          })}
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

                {playlistId && onRemoveFromPlaylist ? (
                  <button className="song-menu-item song-menu-item--remove" onClick={() => {
                    if (menuSong) {
                      onRemoveFromPlaylist(menuSong._id)
                      closeMenu()
                    }
                  }}>
                    <svg className="song-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                      <circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" strokeWidth="2" />
                      <path fill="currentColor" d="M7 11h10v2H7z" />
                    </svg>
                    <span>Remove from Playlist</span>
                  </button>
                ) : null}
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
    </section>
  )
  },
)

SongList.displayName = 'SongList'
