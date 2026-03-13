import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { SongList } from '../components/SongList.js'
import { WebSongList } from '../components/WebSongList.js'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { api, getAuthUserInfo, getCachedUsername, setCachedUsername, getWebSongListEnabled } from '../services/api.js'
import { platform } from '../platform.js'
import type { Song } from '../types/index.js'
import './Home.css'
import './Favorites.css'

type LocationState = { autoplay?: boolean } | null

export const FavoritesPage = () => {
  const navigate = useNavigate()
  const location = useLocation()
  const { favouriteIds, refreshFavourites, favouritesTotal, favouritesLastUpdatedAtMs, playSongFromList, currentSong, isPlaying, togglePlay } = usePlayerPlayback()
  const useWebSongList = getWebSongListEnabled()
  const isTelegram = platform.isTelegram
  const autoplayRequested = Boolean((location.state as LocationState)?.autoplay)

  const [songs, setSongs] = useState<Song[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [favoritesInfo, setFavoritesInfo] = useState<{ total: number; userName: string }>({ 
    total: 0, 
    userName: getCachedUsername() || 'USER' 
  })
  const [removingSongIds, setRemovingSongIds] = useState<Set<string>>(new Set())
  const [isAnimating, setIsAnimating] = useState(false)
  const [reloadNonce, setReloadNonce] = useState(0)
  const didAutoplayRef = useRef(false)
  const hasLoadedUserNameRef = useRef(false)
  const removalTimerRef = useRef<number | null>(null)
  const songsRef = useRef<Song[]>([])
  const favouriteIdsLiveRef = useRef<ReadonlySet<string>>(favouriteIds)
  const hasLoadedOnceRef = useRef(false)
  const lastReloadNonceRef = useRef(-1)

  const favouriteIdList = useMemo(() => Array.from(favouriteIds), [favouriteIds])
  const prevFavouriteIdsRef = useRef<ReadonlySet<string>>(favouriteIds)

  useEffect(() => {
    songsRef.current = songs
  }, [songs])

  useEffect(() => {
    favouriteIdsLiveRef.current = favouriteIds
  }, [favouriteIds])

  useEffect(() => {
    return () => {
      if (removalTimerRef.current !== null) {
        window.clearTimeout(removalTimerRef.current)
      }
    }
  }, [])

  useEffect(() => {
    const prevIds = prevFavouriteIdsRef.current
    const currentIds = favouriteIds
    
    const removedIds = new Set<string>()
    prevIds.forEach((id) => {
      if (!currentIds.has(id)) {
        removedIds.add(id)
      }
    })

    const addedIds = new Set<string>()
    currentIds.forEach((id) => {
      if (!prevIds.has(id)) {
        addedIds.add(id)
      }
    })

    queueMicrotask(() => {
      if (removedIds.size > 0) {
        setIsAnimating(true)

        setRemovingSongIds((prev) => new Set([...prev, ...removedIds]))

        if (removalTimerRef.current !== null) {
          window.clearTimeout(removalTimerRef.current)
        }

        removalTimerRef.current = window.setTimeout(() => {
          const stillRemoved = new Set<string>()
          const liveIds = favouriteIdsLiveRef.current
          removedIds.forEach((id) => {
            if (!liveIds.has(id)) stillRemoved.add(id)
          })

          setSongs((prev) => (stillRemoved.size === 0 ? prev : prev.filter((song) => !stillRemoved.has(song._id))))
          setRemovingSongIds((prev) => {
            const next = new Set(prev)
            removedIds.forEach((id) => next.delete(id))
            return next
          })
          setIsAnimating(false)
        }, 400)
      }

      if (addedIds.size > 0) {
        setRemovingSongIds((prev) => {
          const next = new Set(prev)
          addedIds.forEach((id) => next.delete(id))
          return next
        })

        if (songsRef.current.length > 0) {
          const idsInOrder = Array.from(currentIds)
          const order = new Map<string, number>()
          idsInOrder.forEach((id, idx) => order.set(id, idx))

          const missing = new Set<string>()
          const existing = songsRef.current
          addedIds.forEach((id) => {
            if (!existing.some((song) => song._id === id)) missing.add(id)
          })

          const reorderExisting = () => {
            setSongs((prev) => {
              const filtered = prev.filter((song) => currentIds.has(song._id))
              filtered.sort(
                (a, b) =>
                  (order.get(a._id) ?? Number.MAX_SAFE_INTEGER) - (order.get(b._id) ?? Number.MAX_SAFE_INTEGER),
              )
              return filtered
            })
          }

          if (missing.size === 0) {
            reorderExisting()
          } else {
            ;(async () => {
              const remaining = new Set(missing)
              const found: Song[] = []

              for (let page = 1; page <= 40; page += 1) {
                const res = await api.browseTracks(page)
                const items = Array.isArray(res.items) ? res.items : []
                items.forEach((song) => {
                  if (!remaining.has(song._id)) return
                  remaining.delete(song._id)
                  found.push(song)
                })

                if (remaining.size === 0) break
                const per = typeof res.per_page === 'number' && res.per_page > 0 ? res.per_page : items.length || 0
                const total = typeof res.total === 'number' && res.total > 0 ? res.total : 0
                if (per > 0 && total > 0 && page * per >= total) break
                if (items.length === 0) break
              }

              if (found.length === 0) {
                setReloadNonce((n) => n + 1)
                return
              }

              setSongs((prev) => {
                const byId = new Map<string, Song>()
                prev.forEach((song) => {
                  if (currentIds.has(song._id)) byId.set(song._id, song)
                })
                found.forEach((song) => {
                  if (currentIds.has(song._id)) byId.set(song._id, song)
                })
                const merged = Array.from(byId.values())
                merged.sort(
                  (a, b) =>
                    (order.get(a._id) ?? Number.MAX_SAFE_INTEGER) - (order.get(b._id) ?? Number.MAX_SAFE_INTEGER),
                )
                return merged
              })
            })().catch(() => {
              setReloadNonce((n) => n + 1)
            })
          }
        }
      }
    })

    prevFavouriteIdsRef.current = currentIds
  }, [favouriteIds])

  useEffect(() => {
    queueMicrotask(() => {
      setFavoritesInfo((prev) => ({ ...prev, total: favouritesTotal }))
    })
  }, [favouritesTotal])

  // Load username and favorites info once
  useEffect(() => {
    if (hasLoadedUserNameRef.current) return
    hasLoadedUserNameRef.current = true

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
    queueMicrotask(() => {
      setFavoritesInfo((prev) => ({
        ...prev,
        userName: userName || 'USER',
      }))
    })
  }, [])

  // Refresh favorites once on mount
  useEffect(() => {
    refreshFavourites().catch(() => {})
  }, [refreshFavourites])

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      const ids = favouriteIdList
      if (ids.length === 0) {
        setSongs([])
        setLoading(false)
        setError(null)
        return
      }

      if (isAnimating) return
      if (hasLoadedOnceRef.current && reloadNonce === lastReloadNonceRef.current) return
      lastReloadNonceRef.current = reloadNonce

      setLoading(true)
      setError(null)

      const order = new Map<string, number>()
      ids.forEach((id, idx) => order.set(id, idx))

      const remaining = new Set(ids)
      const found: Song[] = []

      try {
        for (let page = 1; page <= 40; page += 1) {
          if (cancelled) return
          const res = await api.browseTracks(page)
          const items = Array.isArray(res.items) ? res.items : []
          items.forEach((song) => {
            if (!remaining.has(song._id)) return
            remaining.delete(song._id)
            found.push(song)
          })

          if (remaining.size === 0) break
          const per = typeof res.per_page === 'number' && res.per_page > 0 ? res.per_page : items.length || 0
          const total = typeof res.total === 'number' && res.total > 0 ? res.total : 0
          if (per > 0 && total > 0 && page * per >= total) break
          if (items.length === 0) break
        }

        found.sort((a, b) => (order.get(a._id) ?? 0) - (order.get(b._id) ?? 0))
        if (cancelled) return
        setSongs(found)
        hasLoadedOnceRef.current = true
        setLoading(false)
      } catch (err) {
        if (cancelled) return
        setSongs([])
        setLoading(false)
        setError(err instanceof Error ? err.message : 'Failed to load favorites')
      }
    }

    load().catch(() => {})

    return () => {
      cancelled = true
    }
  }, [favouriteIdList, isAnimating, reloadNonce])

  useEffect(() => {
    if (!autoplayRequested) return
    if (didAutoplayRef.current) return
    if (songs.length === 0) return

    const first = songs[0]
    const isCurrent = currentSong?._id === first._id
    if (isCurrent) {
      if (!isPlaying) togglePlay()
    } else {
      playSongFromList(first, songs)
    }
    didAutoplayRef.current = true
  }, [autoplayRequested, currentSong?._id, isPlaying, playSongFromList, songs, togglePlay])

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

  const totalDuration = useMemo(() => {
    const totalSeconds = songs.reduce((sum, song) => sum + (song.duration_sec || 0), 0)
    const minutes = Math.floor(totalSeconds / 60)
    return minutes
  }, [songs])

  const [nowMs, setNowMs] = useState<number>(0)

  useEffect(() => {
    const t = window.setTimeout(() => {
      setNowMs(Date.now())
    }, 0)
    return () => {
      window.clearTimeout(t)
    }
  }, [songs.length])

  const favoritesUpdatedLabel = useMemo(() => {
    const formatUpdatedLabel = (updatedAtMs: number): string => {
      const ageMs = Math.max(0, nowMs - updatedAtMs)
      const hourMs = 60 * 60 * 1000
      const dayMs = 24 * 60 * 60 * 1000
      const days = Math.floor(ageMs / dayMs)

      if (ageMs < hourMs) return 'Just Updated'
      if (ageMs < dayMs) {
        const hours = Math.floor(ageMs / hourMs)
        return `Updated ${Math.max(1, hours)}h ago`
      }

      if (days >= 1) {
        const date = new Date(updatedAtMs)
        const now = new Date(nowMs)

        if (date.getFullYear() === now.getFullYear()) {
          return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
        }
        return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
      }

      return 'Just Updated'
    }

    let updatedAtMs = favouritesLastUpdatedAtMs
    if (updatedAtMs === null) {
      try {
        const raw = window.localStorage.getItem('streamw:favourites:lastUpdatedAtMs')
        const parsed = raw ? Number(raw) : NaN
        updatedAtMs = Number.isFinite(parsed) ? parsed : null
      } catch {
        updatedAtMs = null
      }
    }

    if (updatedAtMs === null) return 'Updated'
    return formatUpdatedLabel(updatedAtMs)
  }, [favouritesLastUpdatedAtMs, nowMs])

  return (
    <div className="audio-page latest-songs-page favorites-page">
      <div className={`audio-topbar${useWebSongList ? ' audio-topbar--web' : ''}`}>
        {isTelegram && (
          <button className="audio-back-pill" type="button" onClick={goBack} aria-label="Back">
            <svg className="audio-back-icon" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 0 0 1 0-1.4l7-7a1 0 0 1 1.4 0Z" />
            </svg>
          </button>
        )}
        <button className="favorites-menu-btn" type="button" aria-label="More options">
          <div className="favorites-menu-dots">
            <span className="favorites-menu-dot"></span>
            <span className="favorites-menu-dot"></span>
            <span className="favorites-menu-dot"></span>
          </div>
        </button>
      </div>

      <main className={`content${useWebSongList ? ' latest-songs-content--web' : ''}`}>
        <div className="favorites-header">
          <div className="favorites-card">
            <svg className="favorites-card-star" viewBox="0 0 60 60" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
              <path
                fill="#EF3340"
                d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99z"
              />
            </svg>
          </div>
          <div className="favorites-info">
            <h1 className="favorites-title">Favourite Songs</h1>
            <div className="favorites-subtitle">{favoritesInfo.userName}</div>
            <div className="favorites-updated">{favoritesUpdatedLabel}</div>
            <button className="favorites-preview-btn" type="button" onClick={handlePreview}>
              <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                <path
                  fill="currentColor"
                  d="M9 7.56C9 6.12 10.57 5.22 11.82 5.95L18.69 9.89C19.94 10.62 19.94 12.38 18.69 13.11L11.82 17.05C10.57 17.78 9 16.88 9 15.44V7.56Z"
                />
              </svg>
              Preview
            </button>
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
            <WebSongList songs={songs} title="Favorites" loading={false} showTitle={false} hideFavoriteStar />
            {songs.length > 0 && (
              <div className="favorites-summary">
                {songs.length} song{songs.length !== 1 ? 's' : ''}, {totalDuration} minute{totalDuration !== 1 ? 's' : ''}
              </div>
            )}
          </>
        ) : (
          <>
            <SongList songs={songs} title="Favorites" layout="single" loading={false} showHeader={false} density="compact" removingSongIds={removingSongIds} hideFavoriteStar />
            {songs.length > 0 && (
              <div className="favorites-summary">
                {songs.length} song{songs.length !== 1 ? 's' : ''}, {totalDuration} minute{totalDuration !== 1 ? 's' : ''}
              </div>
            )}
          </>
        )}
      </main>
    </div>
  )
}
