import { memo, useCallback, useEffect, useMemo, useState, type KeyboardEvent, type MouseEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Playlist } from '../types/index.js'
import { api, CACHE_TTL_MS, getCacheEnabled, getCachedUsername, setCachedUsername, getAuthUserInfo } from '../services/api.js'
import latestArrowUrl from '../assets/latestArrow.svg'
import emptyPlaylistUrl from '../assets/mptyPlaylist.svg'
import './PlaylistList.css'

const normalizeThumbnailUrls = (value: unknown): string[] => {
  if (!Array.isArray(value)) return []
  const urls = value
    .filter((v): v is string => typeof v === 'string')
    .map((v) => v.trim())
    .filter((v) => v.length > 0)
  return urls
}

const normalizeRemoteUrl = (value: unknown): string | null => {
  if (typeof value !== 'string') return null
  let url = value.trim()
  url = url.replace(/^`+/, '').replace(/`+$/, '').trim()
  url = url.replace(/^"+/, '').replace(/"+$/, '').trim()
  url = url.replace(/^'+/, '').replace(/'+$/, '').trim()
  if (!url) return null
  return url
}

interface PlaylistListProps {
  userId?: string | number | null
  title?: string
  onSelectPlaylist?: (playlist: Playlist) => void
}

export const UpdatedPlaylists = () => {
  const navigate = useNavigate()
  const [playlists, setPlaylists] = useState<import('../types/index.js').AvailablePlaylist[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    const cacheEnabled = getCacheEnabled()
    const cacheKey = 'streamw:availablePlaylists'
    const cacheTtlMs = CACHE_TTL_MS
    const now = Date.now()

    // Try to load from cache first if enabled
    if (cacheEnabled) {
      try {
        const cachedRaw = localStorage.getItem(cacheKey)
        if (cachedRaw) {
          const cached = JSON.parse(cachedRaw) as { ts?: number; items?: import('../types/index.js').AvailablePlaylist[] }
          if (Array.isArray(cached.items) && cached.items.length > 0) {
            const cacheFresh = typeof cached.ts === 'number' && now - cached.ts < cacheTtlMs
            Promise.resolve().then(() => {
              if (cancelled) return
              setPlaylists(cached.items ?? [])
              if (cacheFresh) setLoading(false)
            })
            if (cacheFresh) {
              return () => { cancelled = true }
            }
          }
        }
      } catch {
        void 0
      }
    }

    api
      .getAvailablePlaylists()
      .then((res) => {
        if (cancelled) return
        const items = res.items || []
        setPlaylists(items)
        
        // Cache the results if enabled
        if (cacheEnabled) {
          try {
            localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), items }))
          } catch {
            void 0
          }
        }
      })
      .catch((err) => {
        if (cancelled) return
        console.error('Failed to fetch available playlists:', err)
        setPlaylists([])
      })
      .finally(() => {
        if (cancelled) return
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [])

  const handlePlaylistClick = useCallback((playlist: import('../types/index.js').AvailablePlaylist) => {
    navigate(playlist.endpoint, { state: { playlist } })
  }, [navigate])

  return (
    <section className="playlist-section">
      <h2 className="playlist-section-title">
        <span className="playlist-section-title-text">Updated Playlists</span>
        <img className="playlist-section-title-arrow" src={latestArrowUrl} alt="" aria-hidden="true" />
      </h2>

      {loading ? (
        <div className="playlist-loading" role="status" aria-label="Loading">
          <div className="playlist-list-spinner" aria-hidden="true" />
        </div>
      ) : (
        <div className={`updated-playlists-grid${playlists.length > 8 ? ' updated-playlists-grid--wrap8' : ' updated-playlists-grid--nowrap'}`} role="list">
          {playlists.map((playlist) => {
            const thumb = normalizeRemoteUrl(playlist.thumbnail_url) ?? emptyPlaylistUrl
            return (
              <button
                key={playlist.id}
                className="playlist-tile"
                type="button"
                role="listitem"
                onClick={() => handlePlaylistClick(playlist)}
              >
                <div className="playlist-art playlist-art-thumb" aria-hidden="true">
                  <img className="playlist-art-thumb-img" src={thumb} alt="" loading="lazy" />
                </div>
                <div className="playlist-name" title={playlist.name}>
                  {playlist.name}
                </div>
              </button>
            )
          })}
        </div>
      )}
    </section>
  )
}

export const PlaylistList = memo(({ userId, title = 'Updated Playlists', onSelectPlaylist }: PlaylistListProps) => {
  const navigate = useNavigate()
  const [playlists, setPlaylists] = useState<Playlist[]>([])
  const [loading, setLoading] = useState(false)
  const [favMenuPosition, setFavMenuPosition] = useState<{ x: number; y: number } | null>(null)
  const [favoritesInfo, setFavoritesInfo] = useState<{ total: number; userName: string }>({ 
    total: 0, 
    userName: getCachedUsername() || 'USER' 
  })
  const [nowMs, setNowMs] = useState<number | null>(null)

  useEffect(() => {
    const t = window.setTimeout(() => {
      setNowMs(Date.now())
    }, 0)
    return () => {
      window.clearTimeout(t)
    }
  }, [])

  const favoritesUpdatedLabel = useMemo(() => {
    const formatUpdatedLabel = (updatedAtMs: number): string => {
      if (nowMs === null) return 'Updated'
      const ageMs = Math.max(0, nowMs - updatedAtMs)
      const hourMs = 60 * 60 * 1000
      const dayMs = 24 * 60 * 60 * 1000
      if (ageMs < hourMs) return 'Just Updated'
      if (ageMs < dayMs) {
        const hours = Math.floor(ageMs / hourMs)
        return `Updated ${Math.max(1, hours)}h ago`
      }
      const days = Math.floor(ageMs / dayMs)
      if (days < 7) return `Updated ${Math.max(1, days)}d ago`
      const weeks = Math.floor(days / 7)
      return `Updated ${Math.max(1, weeks)}w ago`
    }

    try {
      const raw = window.localStorage.getItem('streamw:favourites:lastUpdatedAtMs')
      const parsed = raw ? Number(raw) : NaN
      if (!Number.isFinite(parsed)) return 'Updated'
      return formatUpdatedLabel(parsed)
    } catch {
      return 'Updated'
    }
  }, [nowMs])

  const [createPlaylistOpen, setCreatePlaylistOpen] = useState(false)
  const [createPlaylistName, setCreatePlaylistName] = useState('')
  const [creatingPlaylist, setCreatingPlaylist] = useState(false)

  const cachePlaylists = useCallback(
    (items: Playlist[]) => {
      try {
        const cacheKey = `streamw:playlists:${String(userId)}`
        localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), items }))
      } catch {
        void 0
      }
    },
    [userId],
  )

  const openCreatePlaylist = useCallback(() => {
    setCreatePlaylistOpen(true)
    setCreatePlaylistName('')
    setCreatingPlaylist(false)
  }, [])

  const closeCreatePlaylist = useCallback(() => {
    setCreatePlaylistOpen(false)
    setCreatePlaylistName('')
    setCreatingPlaylist(false)
  }, [])

  const dismissCreatePlaylist = useCallback(() => {
    if (creatingPlaylist) return
    closeCreatePlaylist()
  }, [closeCreatePlaylist, creatingPlaylist])

  const handleCreatePlaylist = useCallback(async () => {
    const name = createPlaylistName.trim()
    if (!name) return
    if (creatingPlaylist) return

    setCreatingPlaylist(true)
    try {
      const created = await api.createMyPlaylist(name)
      setPlaylists((prev) => {
        const next = [created, ...prev.filter((p) => p.playlist_id !== created.playlist_id)]
        cachePlaylists(next)
        return next
      })
      closeCreatePlaylist()
      onSelectPlaylist?.(created)
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to create playlist')
      setCreatingPlaylist(false)
    }
  }, [cachePlaylists, closeCreatePlaylist, createPlaylistName, creatingPlaylist, onSelectPlaylist])

  useEffect(() => {
    if (!createPlaylistOpen) return
    const onKeyDown = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        dismissCreatePlaylist()
        return
      }
      if (e.key === 'Enter') {
        e.preventDefault()
        handleCreatePlaylist()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [createPlaylistOpen, dismissCreatePlaylist, handleCreatePlaylist])

  const openFavorites = useCallback(
    (opts?: { autoplay?: boolean }) => {
      navigate('/favorites', opts ? { state: opts } : undefined)
    },
    [navigate],
  )

  const closeFavMenu = useCallback(() => setFavMenuPosition(null), [])

  const onFavoritesKeyDown = useCallback(
    (e: KeyboardEvent<HTMLDivElement>) => {
      if (e.key !== 'Enter' && e.key !== ' ') return
      e.preventDefault()
      openFavorites()
    },
    [openFavorites],
  )

  useEffect(() => {
    let cancelled = false

    // Get username from cache first
    let userName = getCachedUsername() || ''

    // If not cached, get from Telegram or auth
    if (!userName) {
      type TelegramWebApp = {
        initDataUnsafe?: { user?: { first_name?: string; last_name?: string; username?: string } }
      }
      const getTg = (): TelegramWebApp | null => {
        const w = window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }
        return w.Telegram?.WebApp ?? null
      }

      const tg = getTg()
      
      if (tg?.initDataUnsafe && typeof tg.initDataUnsafe === 'object' && 'user' in tg.initDataUnsafe) {
        const user = (tg.initDataUnsafe as { user?: { first_name?: string; last_name?: string; username?: string } }).user
        if (user) {
          const firstName = user.first_name || ''
          const lastName = user.last_name || ''
          userName = `${firstName}${lastName ? ' ' + lastName : ''}`.trim().toUpperCase()
          if (!userName && user.username) {
            userName = user.username.toUpperCase()
          }
        }
      }

      // If not from Telegram, try to get from auth
      if (!userName) {
        const authUserInfo = getAuthUserInfo()
        if (authUserInfo?.first_name) {
          userName = authUserInfo.first_name.toUpperCase()
        }
      }

      // Cache the username
      if (userName) {
        setCachedUsername(userName)
      }
    }

    const normalizeUpdatedAtMs = (value: unknown): number | null => {
      if (typeof value !== 'number' || !Number.isFinite(value)) return null
      if (value > 1_000_000_000_000) return value
      if (value > 1_000_000_000) return value * 1000
      return null
    }

    api
      .getFavouriteIds(1, 200)
      .then((res) => {
        if (cancelled) return
        setFavoritesInfo({
          total: typeof res.total === 'number' && res.total >= 0 ? res.total : 0,
          userName: userName || 'USER',
        })

        const ms = normalizeUpdatedAtMs(res.last_updated_at)
        if (ms !== null) {
          try {
            window.localStorage.setItem('streamw:favourites:lastUpdatedAtMs', String(ms))
          } catch {
            void 0
          }
        }
      })
      .catch(() => {
        if (cancelled) return
        setFavoritesInfo((prev) => ({
          ...prev,
          userName: userName || 'USER',
        }))
      })

    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false

    const cacheEnabled = getCacheEnabled()

    if (!cacheEnabled) {
      queueMicrotask(() => {
        if (cancelled) return
        setLoading(true)
      })

      api
        .getPlaylists(userId)
        .then((res) => {
          if (cancelled) return
          const items = Array.isArray(res.items) ? res.items : []
          setPlaylists(items)
        })
        .catch((err) => {
          if (cancelled) return
          setPlaylists([])
          void err
        })
        .finally(() => {
          if (cancelled) return
          setLoading(false)
        })

      return () => {
        cancelled = true
      }
    }

    const cacheKey = `streamw:playlists:${String(userId)}`
    const cacheTtlMs = CACHE_TTL_MS
    const now = Date.now()
    let hasCachedList = false
    let cachedItems: Playlist[] | null = null
    let cacheFresh = false

    try {
      const cachedRaw = localStorage.getItem(cacheKey)
      if (cachedRaw) {
        const cached = JSON.parse(cachedRaw) as { ts?: number; items?: Playlist[] }
        if (Array.isArray(cached.items)) {
          hasCachedList = cached.items.length > 0
          cachedItems = cached.items
        }
        cacheFresh = typeof cached.ts === 'number' && now - cached.ts < cacheTtlMs && Array.isArray(cached.items)
      }
    } catch (err) {
      void err
    }

    if (cachedItems) {
      queueMicrotask(() => {
        if (cancelled) return
        setPlaylists(cachedItems)
      })
    }

    if (cacheFresh) {
      queueMicrotask(() => {
        if (cancelled) return
        setLoading(false)
      })
      return () => {
        cancelled = true
      }
    }

    queueMicrotask(() => {
      if (cancelled) return
      setLoading(!hasCachedList)
    })

    api
      .getPlaylists(userId)
      .then((res) => {
        if (cancelled) return
        const items = Array.isArray(res.items) ? res.items : []
        setPlaylists(items)
        try {
          localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), items }))
        } catch (err) {
          void err
        }
      })
      .catch((err) => {
        if (cancelled) return
        if (!hasCachedList) setPlaylists([])
        void err
      })
      .finally(() => {
        if (cancelled) return
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [userId])

  const items = useMemo(() => playlists, [playlists])

  const playlistById = useMemo(() => {
    const map = new Map<string, Playlist>()
    playlists.forEach((playlist) => map.set(playlist.playlist_id, playlist))
    return map
  }, [playlists])

  const onSelect = useCallback(
    (e: MouseEvent<HTMLButtonElement>) => {
      const id = e.currentTarget.dataset.playlistId
      if (!id) return
      const playlist = playlistById.get(id)
      if (!playlist) return
      onSelectPlaylist?.(playlist)
    },
    [onSelectPlaylist, playlistById],
  )

  const renderPlaylistArt = useCallback((playlist: Playlist) => {
    const thumbnails = normalizeThumbnailUrls(playlist.thumbnails)
    if (thumbnails.length === 1) {
      return (
        <div className="playlist-art playlist-art-thumb" aria-hidden="true">
          <img className="playlist-art-thumb-img" src={thumbnails[0]} alt="" loading="lazy" />
        </div>
      )
    }

    if (thumbnails.length === 3) {
      return (
        <div className="playlist-art playlist-art-thumb playlist-art-thumb--grid" aria-hidden="true">
          <div className="playlist-art-thumb-grid">
            {thumbnails.map((url, index) => (
              <img key={`${playlist.playlist_id}-${index}`} className="playlist-art-thumb-grid-img" src={url} alt="" loading="lazy" />
            ))}
            <div className="playlist-art-thumb-grid-empty" />
          </div>
        </div>
      )
    }

    if (thumbnails.length >= 4) {
      const firstFour = thumbnails.slice(0, 4)
      return (
        <div className="playlist-art playlist-art-thumb playlist-art-thumb--grid" aria-hidden="true">
          <div className="playlist-art-thumb-grid">
            {firstFour.map((url, index) => (
              <img key={`${playlist.playlist_id}-${index}`} className="playlist-art-thumb-grid-img" src={url} alt="" loading="lazy" />
            ))}
          </div>
        </div>
      )
    }

    const initial = (playlist.name || 'P').trim().slice(0, 1).toUpperCase()
    return (
      <div className="playlist-art playlist-art-thumb playlist-art-thumb--empty" aria-hidden="true">
        <img className="playlist-art-thumb-empty-icon" src={emptyPlaylistUrl} alt="" loading="lazy" />
        <div className="playlist-art-thumb-fallback" data-hidden="true">
          {initial}
        </div>
      </div>
    )
  }, [])

  return (
    <section className="playlist-section">
      <h2 className="playlist-section-title">
        <span className="playlist-section-title-text">{title}</span>
        <img className="playlist-section-title-arrow" src={latestArrowUrl} alt="" aria-hidden="true" />
      </h2>

      <div className="playlist-grid" role="list">
        <button
          className="playlist-tile playlist-tile-action"
          type="button"
          role="listitem"
          aria-label="New playlist"
          onClick={() => {
            openCreatePlaylist()
          }}
        >
          <div className="playlist-art playlist-art-action playlist-art-action-create" aria-hidden="true">
            <div className="playlist-create-circle" aria-hidden="true">
              <svg className="playlist-create-plus-icon" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                <path fill="currentColor" d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
              </svg>
            </div>
          </div>
          <div className="playlist-name">Create</div>
        </button>

        <div
          className="playlist-tile playlist-tile-action playlist-tile-favorites"
          role="listitem"
          tabIndex={0}
          onKeyDown={onFavoritesKeyDown}
          onClick={() => openFavorites()}
        >
          <button
            className="playlist-tile-favorites-mobile-menu"
            type="button"
            aria-label="Favorites menu"
            onClick={(e) => {
              e.stopPropagation()
              const rect = e.currentTarget.getBoundingClientRect()
              const menuWidth = 240
              const menuHeight = 140
              const padding = 8
              let x = rect.left
              let y = rect.bottom + 6
              if (x + menuWidth > window.innerWidth - padding) x = rect.right - menuWidth
              if (y + menuHeight > window.innerHeight - padding) y = rect.top - menuHeight - 6
              if (x < padding) x = padding
              if (y < padding) y = padding
              setFavMenuPosition({ x, y })
            }}
          >
            <svg width="20" height="20" viewBox="0 0 28 28" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <circle cx="14" cy="14" r="14" fill="transparent" />
              <path
                fill="currentColor"
                d="M10.105 14c0-.87-.687-1.55-1.564-1.55-.862 0-1.557.695-1.557 1.55 0 .848.695 1.55 1.557 1.55.855 0 1.564-.702 1.564-1.55zm5.437 0c0-.87-.68-1.55-1.542-1.55A1.55 1.55 0 0012.45 14c0 .848.695 1.55 1.55 1.55.848 0 1.542-.702 1.542-1.55zm5.474 0c0-.87-.687-1.55-1.557-1.55-.87 0-1.564.695-1.564 1.55 0 .848.694 1.55 1.564 1.55.848 0 1.557-.702 1.557-1.55z"
              />
            </svg>
          </button>
          <div className="playlist-art playlist-art-action playlist-art-action-favorites">
            <svg className="playlist-favorites-star" viewBox="0 0 60 60" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
              <path
                fill="#EF3340"
                d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99z"
              />
            </svg>
            <div className="playlist-tile-favorites-actions">
              <button
                className="playlist-tile-favorites-action playlist-tile-favorites-action--play"
                type="button"
                aria-label="Play favorites"
                onClick={(e) => {
                  e.stopPropagation()
                  openFavorites({ autoplay: true })
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path
                    fill="currentColor"
                    d="M9 7.56C9 6.12 10.57 5.22 11.82 5.95L18.69 9.89C19.94 10.62 19.94 12.38 18.69 13.11L11.82 17.05C10.57 17.78 9 16.88 9 15.44V7.56Z"
                  />
                </svg>
              </button>
              <button
                className="playlist-tile-favorites-action playlist-tile-favorites-action--menu"
                type="button"
                aria-label="Favorites menu"
                onClick={(e) => {
                  e.stopPropagation()
                  const rect = e.currentTarget.getBoundingClientRect()
                  const menuWidth = 240
                  const menuHeight = 140
                  const padding = 8
                  let x = rect.left
                  let y = rect.bottom + 6
                  if (x + menuWidth > window.innerWidth - padding) x = rect.right - menuWidth
                  if (y + menuHeight > window.innerHeight - padding) y = rect.top - menuHeight - 6
                  if (x < padding) x = padding
                  if (y < padding) y = padding
                  setFavMenuPosition({ x, y })
                }}
              >
                <svg width="22" height="22" viewBox="0 0 28 28" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <circle cx="14" cy="14" r="14" fill="transparent" />
                  <path
                    fill="currentColor"
                    d="M10.105 14c0-.87-.687-1.55-1.564-1.55-.862 0-1.557.695-1.557 1.55 0 .848.695 1.55 1.557 1.55.855 0 1.564-.702 1.564-1.55zm5.437 0c0-.87-.68-1.55-1.542-1.55A1.55 1.55 0 0012.45 14c0 .848.695 1.55 1.55 1.55.848 0 1.542-.702 1.542-1.55zm5.474 0c0-.87-.687-1.55-1.557-1.55-.87 0-1.564.695-1.564 1.55 0 .848.694 1.55 1.564 1.55.848 0 1.557-.702 1.557-1.55z"
                  />
                </svg>
              </button>
            </div>
          </div>
          <div className="playlist-tile-favorites-info">
            <div className="playlist-name">Favourite Songs</div>
            <div className="playlist-tile-favorites-subtitle">{favoritesInfo.userName}</div>
            <div className="playlist-tile-favorites-updated">{favoritesUpdatedLabel}</div>
          </div>
          <div className="playlist-name playlist-tile-favorites-desktop-name">Favorites</div>
        </div>

        {items.length > 0
          ? items.map((playlist) => (
              <button
                key={playlist.playlist_id}
                className="playlist-tile playlist-tile-og"
                type="button"
                role="listitem"
                data-playlist-id={playlist.playlist_id}
                onClick={onSelect}
              >
                {renderPlaylistArt(playlist)}
                <div className="playlist-name" title={playlist.name}>
                  {playlist.name}
                </div>
              </button>
            ))
          : null}
      </div>

      {createPlaylistOpen ? (
        <>
          <div className="playlist-create-modal-backdrop" onClick={dismissCreatePlaylist} onPointerDown={dismissCreatePlaylist} />
          <div className="playlist-create-modal" role="dialog" aria-modal="true" aria-label="Create playlist" onClick={(e) => e.stopPropagation()}>
            <div className="playlist-create-modal-header">
              <div className="playlist-create-modal-title">New playlist</div>
              <button className="playlist-create-modal-close" type="button" onClick={dismissCreatePlaylist} aria-label="Close" disabled={creatingPlaylist}>
                <span aria-hidden="true">×</span>
              </button>
            </div>

            <div className="playlist-create-modal-content">
              <input
                className="playlist-create-modal-input"
                type="text"
                value={createPlaylistName}
                placeholder="Playlist name"
                aria-label="Playlist name"
                autoFocus
                disabled={creatingPlaylist}
                onChange={(e) => setCreatePlaylistName(e.target.value)}
              />
            </div>

            <div className="playlist-create-modal-footer">
              <button className="playlist-create-modal-btn playlist-create-modal-btn--cancel" type="button" onClick={dismissCreatePlaylist} disabled={creatingPlaylist}>
                Cancel
              </button>
              <button
                className="playlist-create-modal-btn playlist-create-modal-btn--primary"
                type="button"
                onClick={handleCreatePlaylist}
                disabled={creatingPlaylist || createPlaylistName.trim().length === 0}
              >
                {creatingPlaylist ? 'Creating…' : 'Create'}
              </button>
            </div>
          </div>
        </>
      ) : null}

      {favMenuPosition ? (
        <>
          <div
            className="song-menu-backdrop"
            onClick={closeFavMenu}
            onPointerDown={closeFavMenu}
          />
          <div
            className="song-menu"
            style={{
              top: `${favMenuPosition.y}px`,
              left: `${favMenuPosition.x}px`,
            }}
          >
            <button
              className="song-menu-item"
              type="button"
              onClick={() => {
                closeFavMenu()
                openFavorites()
              }}
            >
              <svg className="song-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                <path fill="currentColor" d="M10 17l5-5-5-5v10z" />
              </svg>
              <span>Open</span>
            </button>
            <button
              className="song-menu-item"
              type="button"
              onClick={() => {
                closeFavMenu()
                openFavorites({ autoplay: true })
              }}
            >
              <svg className="song-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M9 7.56C9 6.12 10.57 5.22 11.82 5.95L18.69 9.89C19.94 10.62 19.94 12.38 18.69 13.11L11.82 17.05C10.57 17.78 9 16.88 9 15.44V7.56Z"
                />
              </svg>
              <span>Play</span>
            </button>
          </div>
        </>
      ) : null}

      {loading && items.length === 0 ? (
        <div className="playlist-loading" role="status" aria-label="Loading">
          <div className="playlist-list-spinner" aria-hidden="true" />
        </div>
      ) : null}
    </section>
  )
})

PlaylistList.displayName = 'PlaylistList'
