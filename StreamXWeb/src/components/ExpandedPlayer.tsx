import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent, type PointerEventHandler } from 'react'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { api } from '../services/api.js'
import previousIconUrl from '../assets/previous.svg'
import forwardIconUrl from '../assets/forward.svg'
import pauseIconUrl from '../assets/pause.svg'
import resumeIconUrl from '../assets/resume.svg'
import mp3IconUrl from '../assets/Mp3.svg'
import type { Playlist, Song, TrackDetailsResponse, TrackLyricsResponse } from '../types/index.js'
import { platform } from '../platform.js'
import nextInfoUrl from '../assets/nextInfo.svg'
import lyricsUrl from '../assets/lyrics.svg'
import { NextInfo } from './NextInfo.js'

type ExpandedPlayerProps = {
  isOpen: boolean
  isClosing?: boolean
  isDragging?: boolean
  sheetOffsetY?: number
  sheetTiltDeg?: number
  backdropOpacity?: number
  suppressSheetClicks?: boolean
  onClose: () => void
  onCollapseClick?: () => void
  onCollapsePointerDown?: PointerEventHandler<HTMLButtonElement>
  onCollapsePointerMove?: PointerEventHandler<HTMLButtonElement>
  onCollapsePointerUp?: PointerEventHandler<HTMLButtonElement>
  onCollapsePointerCancel?: PointerEventHandler<HTMLButtonElement>
  onCollapseLostPointerCapture?: PointerEventHandler<HTMLButtonElement>
  onArtworkPointerDown?: PointerEventHandler<HTMLDivElement>
  onArtworkPointerMove?: PointerEventHandler<HTMLDivElement>
  onArtworkPointerUp?: PointerEventHandler<HTMLDivElement>
  onArtworkPointerCancel?: PointerEventHandler<HTMLDivElement>
  onArtworkLostPointerCapture?: PointerEventHandler<HTMLDivElement>
}

export const ExpandedPlayer = ({
  isOpen,
  isClosing = false,
  isDragging = false,
  sheetOffsetY = 0,
  sheetTiltDeg = 0,
  backdropOpacity = 1,
  suppressSheetClicks = false,
  onClose,
  onCollapseClick,
}: ExpandedPlayerProps) => {
  const {
    currentSong,
    isPlaying,
    togglePlay,
    playPrevious,
    playNext,
    volume,
    setVolume,
    audioRef,
    isShuffleOn,
    setIsShuffleOn,
    repeatMode,
    setRepeatMode,
    isSyncLyricsOn,
    isExternalNowPlaying,
    externalCanEditQueue,
    externalUpcoming,
    favouriteIds,
    toggleFavourite,
    playNextTrack,
  } =
    usePlayerPlayback()

  const [duration, setDuration] = useState(0)
  const [currentTime, setCurrentTime] = useState(0)
  const [isSeekingProgress, setIsSeekingProgress] = useState(false)
  const [isInteractingProgress, setIsInteractingProgress] = useState(false)
  const [progressDraft, setProgressDraft] = useState<number | null>(null)
  const [isSeekingVolume, setIsSeekingVolume] = useState(false)
  const [isInteractingVolume, setIsInteractingVolume] = useState(false)
  const [volumeDraft, setVolumeDraft] = useState<number | null>(null)
  const [trackDetails, setTrackDetails] = useState<TrackDetailsResponse | null>(null)
  const [isQualityOpen, setIsQualityOpen] = useState(false)
  const [queueState, setQueueState] = useState<{ songId: string | null; isOpen: boolean; enterStage: 'idle' | 'pre' | 'run' }>({
    songId: null,
    isOpen: false,
    enterStage: 'idle',
  })
  const [lyricsState, setLyricsState] = useState<{ songId: string | null; isOpen: boolean }>({ songId: null, isOpen: false })
  const [lyricsResponse, setLyricsResponse] = useState<TrackLyricsResponse | null>(null)
  const [lyricsStatus, setLyricsStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const [menuSong, setMenuSong] = useState<Song | null>(null)
  const [menuPosition, setMenuPosition] = useState<{ x: number; y: number } | null>(null)
  const [menuView, setMenuView] = useState<'root' | 'playlist'>('root')
  const [myPlaylists, setMyPlaylists] = useState<Playlist[]>([])
  const [playlistsLoading, setPlaylistsLoading] = useState(false)
  const [playlistsError, setPlaylistsError] = useState<string | null>(null)
  const [addingPlaylistId, setAddingPlaylistId] = useState<string | null>(null)
  const [createPlaylistOpen, setCreatePlaylistOpen] = useState(false)
  const [createPlaylistName, setCreatePlaylistName] = useState('')
  const [creatingPlaylist, setCreatingPlaylist] = useState(false)

  const trackDetailsCacheRef = useRef<Map<string, TrackDetailsResponse>>(new Map())
  const trackDetailsInFlightRef = useRef<Map<string, Promise<TrackDetailsResponse>>>(new Map())
  const lyricsCacheRef = useRef<Map<string, TrackLyricsResponse>>(new Map())
  const lyricsInFlightRef = useRef<Map<string, Promise<TrackLyricsResponse>>>(new Map())
  const lyricsScrollRef = useRef<HTMLDivElement | null>(null)
  const lyricsLineRefs = useRef<Map<number, HTMLDivElement>>(new Map())

  const wasPlayingBeforeSeekRef = useRef(false)
  const progressPointerIdRef = useRef<number | null>(null)
  const volumePointerIdRef = useRef<number | null>(null)
  const progressGestureRef = useRef<{ pointerId: number | null; startX: number; startY: number }>({ pointerId: null, startX: 0, startY: 0 })
  const volumeGestureRef = useRef<{ pointerId: number | null; startX: number; startY: number }>({ pointerId: null, startX: 0, startY: 0 })
  const queueEnterRafRef = useRef<number | null>(null)
  const menuOpenedAtRef = useRef(0)

  const [isDesktop, setIsDesktop] = useState(() => {
    if (typeof window === 'undefined') return false
    return window.matchMedia('(min-width: 769px)').matches
  })

  useEffect(() => {
    if (typeof window === 'undefined') return
    const media = window.matchMedia('(min-width: 769px)')
    const sync = () => setIsDesktop(media.matches)
    sync()
    media.addEventListener('change', sync)
    return () => {
      media.removeEventListener('change', sync)
    }
  }, [])

  const coverUrl =
    currentSong?.cover_url ||
    mp3IconUrl

  const currentSongId = currentSong?._id ?? null
  const isQueueOpen = queueState.songId === currentSongId ? queueState.isOpen : false
  const queueEnterStage = isQueueOpen && queueState.songId === currentSongId ? queueState.enterStage : 'idle'
  const isLyricsOpenRaw = lyricsState.songId === currentSongId ? lyricsState.isOpen : false
  const isLyricsOpen = isDesktop ? !isQueueOpen : isLyricsOpenRaw
  const view = isLyricsOpen ? 'lyrics' : isQueueOpen ? 'queue' : 'now-playing'
  const isFavourite = Boolean(currentSongId && favouriteIds.has(currentSongId))
  const canEditQueue = !isExternalNowPlaying || externalCanEditQueue
  const isMenuOpen = Boolean(menuSong && menuPosition && menuSong._id === currentSongId)

  const toggleQueue = useCallback(() => {
    if (!currentSongId) return
    if (isDesktop) {
      setQueueState(() => ({ songId: currentSongId, isOpen: true, enterStage: 'pre' }))
      return
    }
    setLyricsState((prev) => {
      if (prev.songId !== currentSongId || !prev.isOpen) return prev
      return { songId: currentSongId, isOpen: false }
    })
    setQueueState((prev) => {
      const isOpenNow = prev.songId === currentSongId ? prev.isOpen : false
      const nextIsOpen = !isOpenNow
      return { songId: currentSongId, isOpen: nextIsOpen, enterStage: nextIsOpen ? 'pre' : 'idle' }
    })
  }, [currentSongId, isDesktop])

  const toggleLyrics = useCallback(() => {
    if (!currentSongId) return
    if (isDesktop) {
      setQueueState(() => ({ songId: currentSongId, isOpen: false, enterStage: 'idle' }))
      return
    }
    setQueueState((prev) => {
      if (prev.songId !== currentSongId || !prev.isOpen) return prev
      return { songId: currentSongId, isOpen: false, enterStage: 'idle' }
    })
    setLyricsState((prev) => {
      const isOpenNow = prev.songId === currentSongId ? prev.isOpen : false
      return { songId: currentSongId, isOpen: !isOpenNow }
    })
  }, [currentSongId, isDesktop])

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

  const ensureMenuFits = useCallback((menuWidth: number, menuHeight: number) => {
    setMenuPosition((pos) => {
      if (!pos) return pos
      const padding = 8

      let x = pos.x
      let y = pos.y

      if (x + menuWidth > window.innerWidth - padding) x = window.innerWidth - padding - menuWidth
      if (y + menuHeight > window.innerHeight - padding) y = window.innerHeight - padding - menuHeight
      if (x < padding) x = padding
      if (y < padding) y = padding

      return { x, y }
    })
  }, [])

  const openMenuAt = useCallback((song: Song, x: number, y: number) => {
    const menuWidth = 240
    const menuHeight = 180
    const padding = 8

    let nextX = x
    let nextY = y

    if (nextX + menuWidth > window.innerWidth - padding) nextX = window.innerWidth - padding - menuWidth
    if (nextY + menuHeight > window.innerHeight - padding) nextY = window.innerHeight - padding - menuHeight
    if (nextX < padding) nextX = padding
    if (nextY < padding) nextY = padding

    menuOpenedAtRef.current = performance.now()
    setMenuSong(song)
    setMenuPosition({ x: nextX, y: nextY })
    setMenuView('root')
    setPlaylistsError(null)
    setAddingPlaylistId(null)
    setCreatePlaylistOpen(false)
    setCreatePlaylistName('')
    setCreatingPlaylist(false)
  }, [])

  const handleContextMenu = useCallback(
    (e: ReactMouseEvent<HTMLElement>) => {
      if (!currentSong) return
      e.preventDefault()
      e.stopPropagation()
      openMenuAt(currentSong, e.clientX, e.clientY)
    },
    [currentSong, openMenuAt],
  )

  const handleMenuButtonClick = useCallback(
    (e: ReactMouseEvent<HTMLButtonElement>) => {
      if (!currentSong) return
      e.preventDefault()
      e.stopPropagation()
      const rect = e.currentTarget.getBoundingClientRect()
      openMenuAt(currentSong, rect.left, rect.bottom + 6)
    },
    [currentSong, openMenuAt],
  )

  const handleToggleFavourite = useCallback(() => {
    if (!currentSongId) return
    toggleFavourite(currentSongId).catch(() => {})
  }, [currentSongId, toggleFavourite])

  const handleMenuToggleFavourite = useCallback(() => {
    if (!menuSong) return
    toggleFavourite(menuSong._id).catch(() => {})
    closeMenu()
  }, [closeMenu, menuSong, toggleFavourite])

  const handleMenuPlayNext = useCallback(() => {
    if (!menuSong) return
    if (!canEditQueue) return
    playNextTrack(menuSong)
    closeMenu()
  }, [canEditQueue, closeMenu, menuSong, playNextTrack])

  const handleMenuAddToPlaylist = useCallback(() => {
    if (!menuSong) return
    setMenuView('playlist')
    ensureMenuFits(300, 340)
    loadMyPlaylists().catch(() => {})
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

  useEffect(() => {
    if (!isMenuOpen) return
    let didClose = false
    const onScrollLike = () => {
      if (didClose) return
      if (performance.now() - menuOpenedAtRef.current < 140) return
      if (menuView === 'playlist') return
      didClose = true
      closeMenu()
    }
    window.addEventListener('scroll', onScrollLike, { passive: true, capture: true })
    window.addEventListener('wheel', onScrollLike, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScrollLike, { capture: true } as AddEventListenerOptions)
      window.removeEventListener('wheel', onScrollLike)
    }
  }, [closeMenu, isMenuOpen, menuView])

  useEffect(() => {
    if (queueEnterRafRef.current !== null) {
      window.cancelAnimationFrame(queueEnterRafRef.current)
      queueEnterRafRef.current = null
    }

    if (!isQueueOpen || queueEnterStage !== 'pre') return

    queueEnterRafRef.current = window.requestAnimationFrame(() => {
      queueEnterRafRef.current = null
      setQueueState((prev) => {
        if (prev.songId !== currentSongId) return prev
        if (!prev.isOpen) return prev
        if (prev.enterStage !== 'pre') return prev
        return { ...prev, enterStage: 'run' }
      })
    })
  }, [currentSongId, isQueueOpen, queueEnterStage])

  const clamp = useCallback((value: number, min: number, max: number) => {
    return Math.min(Math.max(value, min), max)
  }, [])

  const formatTime = useCallback((seconds: number) => {
    const safeSeconds = Math.max(0, Math.floor(seconds))
    const mins = Math.floor(safeSeconds / 60)
    const secs = safeSeconds % 60
    return `${mins}:${String(secs).padStart(2, '0')}`
  }, [])

  const formatKhz = useCallback((hz: number) => {
    const khz = hz / 1000
    const formatted = Number.isFinite(khz) ? (Math.round(khz * 10) / 10).toString() : '0'
    return formatted.endsWith('.0') ? formatted.slice(0, -2) : formatted
  }, [])

  const backgroundStyle = useMemo(() => {
    return { ['--expanded-bg' as never]: `url("${coverUrl}")` }
  }, [coverUrl])

  useEffect(() => {
    if (!isOpen) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [isOpen, onClose])

  useEffect(() => {
    if (!isOpen) return
    const prevHtmlOverflow = document.documentElement.style.overflow
    const prevBodyOverflow = document.body.style.overflow
    document.documentElement.style.overflow = 'hidden'
    document.body.style.overflow = 'hidden'
    return () => {
      document.documentElement.style.overflow = prevHtmlOverflow
      document.body.style.overflow = prevBodyOverflow
    }
  }, [isOpen])

  useEffect(() => {
    if (!isOpen) return
    const audio = audioRef.current
    if (!audio) return

    const syncDuration = () => {
      const next = audio.duration
      setDuration(typeof next === 'number' && Number.isFinite(next) ? next : 0)
    }

    const syncTime = () => {
      if (isSeekingProgress) return
      const next = audio.currentTime || 0
      setCurrentTime((prev) => {
        if (Math.abs(prev - next) < 0.05) return prev
        return next
      })
    }

    syncDuration()
    syncTime()

    audio.addEventListener('loadedmetadata', syncDuration)
    audio.addEventListener('durationchange', syncDuration)
    audio.addEventListener('timeupdate', syncTime)
    audio.addEventListener('seeked', syncTime)

    return () => {
      audio.removeEventListener('loadedmetadata', syncDuration)
      audio.removeEventListener('durationchange', syncDuration)
      audio.removeEventListener('timeupdate', syncTime)
      audio.removeEventListener('seeked', syncTime)
    }
  }, [audioRef, isOpen, isSeekingProgress, currentSong?._id])

  useEffect(() => {
    if (!isOpen) return
    let cancelled = false
    queueMicrotask(() => {
      if (cancelled) return
      setIsQualityOpen(false)
    })

    const songId = currentSong?._id ?? null
    if (!songId) {
      return () => {
        cancelled = true
      }
    }

    const cached = trackDetailsCacheRef.current.get(songId) ?? null
    if (cached) {
      queueMicrotask(() => {
        if (cancelled) return
        setTrackDetails(cached)
      })
      return () => {
        cancelled = true
      }
    }

    queueMicrotask(() => {
      if (cancelled) return
      setTrackDetails(null)
    })
    const existing = trackDetailsInFlightRef.current.get(songId)
    const request = existing ?? api.getTrackDetails(songId)
    if (!existing) trackDetailsInFlightRef.current.set(songId, request)

    request
      .then((details) => {
        trackDetailsInFlightRef.current.delete(songId)
        if (cancelled) return
        trackDetailsCacheRef.current.set(songId, details)
        setTrackDetails(details)
      })
      .catch(() => {
        trackDetailsInFlightRef.current.delete(songId)
        if (cancelled) return
        setTrackDetails(null)
      })

    return () => {
      cancelled = true
    }
  }, [currentSong?._id, isOpen])

  useEffect(() => {
    if (!isOpen) return
    if (!isLyricsOpen) return
    if (!currentSongId) return

    let cancelled = false
    const cached = lyricsCacheRef.current.get(currentSongId) ?? null
    if (cached) {
      queueMicrotask(() => {
        if (cancelled) return
        setLyricsResponse(cached)
        setLyricsStatus('ready')
      })
      return () => {
        cancelled = true
      }
    }

    queueMicrotask(() => {
      if (cancelled) return
      setLyricsResponse(null)
      setLyricsStatus('loading')
    })

    const existing = lyricsInFlightRef.current.get(currentSongId)
    const request = existing ?? api.getTrackLyrics(currentSongId)
    if (!existing) lyricsInFlightRef.current.set(currentSongId, request)

    request
      .then((data) => {
        lyricsInFlightRef.current.delete(currentSongId)
        if (cancelled) return
        lyricsCacheRef.current.set(currentSongId, data)
        setLyricsResponse(data)
        setLyricsStatus('ready')
      })
      .catch(() => {
        lyricsInFlightRef.current.delete(currentSongId)
        if (cancelled) return
        setLyricsResponse(null)
        setLyricsStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [currentSongId, isLyricsOpen, isOpen])

  const getPercentFromPointerX = useCallback(
    (target: HTMLElement, clientX: number) => {
      const rect = target.getBoundingClientRect()
      if (!rect.width) return 0
      return clamp((clientX - rect.left) / rect.width, 0, 1)
    },
    [clamp],
  )

  const progressValue = isSeekingProgress && progressDraft !== null ? progressDraft : currentTime
  const progressPercent = duration ? `${Math.min(Math.max((progressValue / duration) * 100, 0), 100)}%` : '0%'
  const volumeValue = isSeekingVolume && volumeDraft !== null ? volumeDraft : volume
  const volumePercent = `${Math.min(Math.max(volumeValue * 100, 0), 100)}%`
  const lyricsText = lyricsResponse?.lyrics ?? ''
  const lyricsLines = useMemo(() => {
    const raw = lyricsText
    if (!raw) return []
    const lines = raw.split(/\r?\n/)
    const parsed: Array<{ timeMs: number | null; text: string; isBreak?: true }> = []
    let lastWasBreak = true

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed) {
        if (!lastWasBreak) {
          parsed.push({ timeMs: null, text: '', isBreak: true })
          lastWasBreak = true
        }
        continue
      }

      const match = trimmed.match(/^\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?\]\s*(.*)$/)
      if (!match) {
        parsed.push({ timeMs: null, text: trimmed })
        lastWasBreak = false
        continue
      }

      const minutes = Number(match[1] ?? 0)
      const seconds = Number(match[2] ?? 0)
      const fracRaw = match[3] ?? ''
      const frac = fracRaw ? Number(fracRaw) : 0
      const fracMs = fracRaw.length === 1 ? frac * 100 : fracRaw.length === 2 ? frac * 10 : frac
      const timeMs = minutes * 60_000 + seconds * 1_000 + fracMs
      const text = (match[4] ?? '').trim()

      if (!text) {
        if (!lastWasBreak) {
          parsed.push({ timeMs: null, text: '', isBreak: true })
          lastWasBreak = true
        }
        continue
      }

      parsed.push({ timeMs, text })
      lastWasBreak = false
    }

    if (parsed.length && parsed[parsed.length - 1]?.isBreak) parsed.pop()

    return parsed
  }, [lyricsText])

  const timedLyricIndexes = useMemo(() => {
    if (!lyricsLines.length) return []
    const timed: Array<{ timeMs: number; index: number }> = []
    for (let i = 0; i < lyricsLines.length; i++) {
      const timeMs = lyricsLines[i]?.timeMs
      if (typeof timeMs === 'number' && Number.isFinite(timeMs)) timed.push({ timeMs, index: i })
    }
    return timed
  }, [lyricsLines])

  const activeLyricIndex = useMemo(() => {
    if (!isSyncLyricsOn) return -1 // Disable sync when turned off
    if (!timedLyricIndexes.length) return -1
    const tMs = Math.max(0, Math.floor(progressValue * 1000))
    let lo = 0
    let hi = timedLyricIndexes.length - 1
    let bestTimed = -1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      const candidate = timedLyricIndexes[mid]?.timeMs ?? 0
      if (candidate <= tMs) {
        bestTimed = mid
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    if (bestTimed < 0) return -1
    return timedLyricIndexes[bestTimed]?.index ?? -1
  }, [isSyncLyricsOn, progressValue, timedLyricIndexes])

  useEffect(() => {
    if (!isLyricsOpen) return
    if (activeLyricIndex < 0) return
    const container = lyricsScrollRef.current
    const el = lyricsLineRefs.current.get(activeLyricIndex) ?? null
    if (!container || !el) return
    const threshold = container.scrollTop + container.clientHeight * 0.7
    const elBottom = el.offsetTop + el.clientHeight
    if (elBottom <= threshold) return
    const nextTop = el.offsetTop - container.clientHeight * 0.3
    container.scrollTo({ top: Math.max(0, nextTop), behavior: 'smooth' })
  }, [activeLyricIndex, isLyricsOpen])

  const lyricsCard = useMemo(() => {
    return (
      <div className="expanded-player-queue" data-empty={lyricsStatus === 'ready' && !lyricsLines.length ? 'true' : 'false'} aria-label="Lyrics">
        <div className="expanded-player-queue-scroll" ref={lyricsScrollRef}>
          {lyricsStatus === 'loading' ? <div className="expanded-player-lyrics-status">Loading lyrics…</div> : null}
          {lyricsStatus === 'error' ? <div className="expanded-player-lyrics-status">Lyrics unavailable</div> : null}
          {lyricsStatus === 'ready' && !lyricsLines.length ? <div className="expanded-player-queue-empty">No lyrics</div> : null}
          {lyricsStatus === 'ready' && lyricsLines.length ? (
            <div className="expanded-player-lyrics-lines">
              {lyricsLines.map((line, index) => {
                if (line.isBreak) {
                  return <div key={`break-${index}`} className="expanded-player-lyrics-break" aria-hidden="true" />
                }

                return (
                  <div
                    key={`${index}-${line.timeMs ?? 'x'}`}
                    className={`expanded-player-lyrics-line${!isSyncLyricsOn ? ' no-sync' : index === activeLyricIndex ? ' is-active' : ''}`}
                    ref={(node) => {
                      const map = lyricsLineRefs.current
                      if (!node) map.delete(index)
                      else map.set(index, node)
                    }}
                  >
                    {line.text}
                  </div>
                )
              })}
            </div>
          ) : null}
        </div>
      </div>
    )
  }, [activeLyricIndex, isSyncLyricsOn, lyricsLines, lyricsStatus])

  if (!isOpen) return null

  const audioTypeRaw = (trackDetails?.audio?.type ?? currentSong?.type ?? '').trim()
  const audioTypeNormalized = audioTypeRaw.toLowerCase().replace(/\s+/g, ' ').trim()
  const isLossless = audioTypeNormalized.includes('flac') || audioTypeNormalized.includes('alac') || audioTypeNormalized.includes('wav') || audioTypeNormalized.includes('aiff')
  const isAac = audioTypeNormalized.includes('aac') || audioTypeNormalized.includes('m4a') || audioTypeNormalized.includes('mp4')
  const isMp3 = !isLossless && (audioTypeNormalized.includes('mpeg') || audioTypeNormalized.includes('mp3') || isAac)
  const isAlac = audioTypeNormalized.includes('alac')
  const codecLabel = isLossless && isAlac ? 'ALAC' : isLossless && audioTypeNormalized.includes('flac') ? 'FLAC' : isAac && !isLossless ? 'AAC LC' : audioTypeRaw || 'Unknown'
  const bitDepth = trackDetails?.audio?.bit_depth
  const sampleRate = trackDetails?.audio?.sampling_rate_hz ?? currentSong?.sampling_rate_hz
  const bitsKhzDetails = bitDepth && sampleRate ? `${bitDepth} bits/${formatKhz(sampleRate)} kHz` : sampleRate ? `${formatKhz(sampleRate)} kHz` : ''
  const qualitySubtitle = bitsKhzDetails ? `${codecLabel} ${bitsKhzDetails}` : codecLabel

  const state = !isClosing ? 'open' : 'closing'
  const showVolume = !(platform.isIOS || platform.isMac)
  const backdropStyle: CSSProperties | undefined = isDragging ? { opacity: backdropOpacity, transition: 'none' } : undefined
  const sheetStyle: CSSProperties = {
    ['--sheet-offset-y' as never]: `${sheetOffsetY}px`,
    ['--sheet-tilt' as never]: `${sheetTiltDeg}deg`,
    ...(isDragging ? { transition: 'none' } : null),
  }

  const nowPlayingHero = (
    <>
      <div className="expanded-player-artwork-wrap" data-playing={isPlaying ? 'true' : 'false'} onContextMenu={handleContextMenu}>
        <img className="expanded-player-artwork" src={coverUrl} alt={currentSong?.title || ''} />
      </div>

      <div className="expanded-player-meta" onContextMenu={handleContextMenu}>
        <div className="expanded-player-meta-row">
          <div className="expanded-player-meta-text">
            <div className="expanded-player-title">{currentSong?.title || 'Not Playing'}</div>
            <div className="expanded-player-artist">{currentSong?.artist || ''}</div>
          </div>
          <div className="expanded-player-meta-actions">
            <button
              className={`expanded-player-meta-action${isFavourite ? ' is-favourite' : ''}`}
              type="button"
              aria-label={isFavourite ? 'Remove from favourites' : 'Add to favourites'}
              aria-pressed={isFavourite}
              disabled={!currentSong}
              onClick={(e) => {
                if (isDragging || suppressSheetClicks) return
                e.stopPropagation()
                handleToggleFavourite()
              }}
            >
              <svg viewBox="0 0 60 60" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                {isFavourite ? (
                  <path
                    fill="currentColor"
                    d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99z"
                  />
                ) : (
                  <path
                    fill="currentColor"
                    d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99zm3.418-4.686c-.022-.03-.028-.051-.007-.123l2.575-7.008c.338-.969.296-1.407-.635-2.02l-6.197-4.162c-.053-.04-.075-.071-.064-.102.01-.03.042-.039.113-.039l7.46.262c1.03.026 1.439-.212 1.716-1.215l2.025-7.175c.02-.071.04-.095.071-.095.031 0 .051.024.073.095l2.026 7.175c.277 1.003.693 1.241 1.732 1.215l7.452-.262c.071 0 .103.009.113.04.01.03-.011.052-.064.101l-6.206 4.17c-.922.614-.973 1.043-.628 2.012l2.569 7.008c.02.072.015.094-.007.123-.022.029-.053 0-.105-.033l-5.873-4.615c-.827-.648-1.326-.648-2.162 0l-5.864 4.615c-.052.033-.083.062-.113.033z"
                  />
                )}
              </svg>
            </button>
            <button
              className="expanded-player-meta-action"
              type="button"
              aria-label="More options"
              disabled={!currentSong}
              onClick={handleMenuButtonClick}
            >
              <svg viewBox="0 0 28 28" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                <path
                  fill="currentColor"
                  d="M10.105 14c0-.87-.687-1.55-1.564-1.55-.862 0-1.557.695-1.557 1.55 0 .848.695 1.55 1.557 1.55.855 0 1.564-.702 1.564-1.55zm5.437 0c0-.87-.68-1.55-1.542-1.55A1.55 1.55 0 0012.45 14c0 .848.695 1.55 1.55 1.55.848 0 1.542-.702 1.542-1.55zm5.474 0c0-.87-.687-1.55-1.557-1.55-.87 0-1.564.695-1.564 1.55 0 .848.694 1.55 1.564 1.55.848 0 1.557-.702 1.557-1.55z"
                />
              </svg>
            </button>
          </div>
        </div>
      </div>
    </>
  )

  const bottomActions = (
    <div className="expanded-player-bottom-actions" aria-label="More controls">
      <button
        className={`expanded-player-meta-action expanded-player-lyrics-toggle${isLyricsOpen ? ' is-active' : ''}`}
        type="button"
        aria-label={isLyricsOpen ? 'Hide lyrics' : 'Show lyrics'}
        disabled={!currentSong}
        onClick={toggleLyrics}
      >
        <img className="expanded-player-lyrics-toggle-icon" src={lyricsUrl} alt="" aria-hidden="true" />
      </button>
      <button
        className={`expanded-player-meta-action expanded-player-queue-toggle${isQueueOpen ? ' is-active' : ''}`}
        type="button"
        aria-label={isQueueOpen ? 'Hide up next' : 'Show up next'}
        disabled={!currentSong}
        onClick={toggleQueue}
      >
        <img className="expanded-player-queue-toggle-icon" src={nextInfoUrl} alt="" aria-hidden="true" />
      </button>
    </div>
  )

  const playbackControlsCore = (
    <>
      <div className="expanded-player-progress">
        <input
          className={`expanded-player-progress-bar${isSeekingProgress ? ' is-seeking' : ''}${isInteractingProgress ? ' is-interacting' : ''}`}
          type="range"
          min={0}
          max={duration || 0}
          step={0.01}
          value={Math.min(progressValue, duration || 0)}
          disabled={!currentSong || !duration}
          style={{ ['--progress' as never]: progressPercent }}
          onPointerDown={(e) => {
            if (!duration) return
            setIsInteractingProgress(true)
            progressPointerIdRef.current = e.pointerId
            progressGestureRef.current = { pointerId: e.pointerId, startX: e.clientX, startY: e.clientY }
            try {
              e.currentTarget.setPointerCapture(e.pointerId)
            } catch {
              void 0
            }
          }}
          onPointerMove={(e) => {
            if (!duration) return
            if (isSeekingProgress) {
              e.preventDefault()
              const next = getPercentFromPointerX(e.currentTarget, e.clientX) * duration
              setProgressDraft(clamp(next, 0, duration))
              return
            }

            const gesture = progressGestureRef.current
            if (gesture.pointerId !== e.pointerId) return
            const dx = e.clientX - gesture.startX
            const dy = e.clientY - gesture.startY
            const adx = Math.abs(dx)
            const ady = Math.abs(dy)
            if (ady > adx + 14) {
              progressGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
              progressPointerIdRef.current = null
              return
            }
            if (adx < 24 || adx <= ady * 2.6) return

            try {
              e.currentTarget.setPointerCapture(e.pointerId)
            } catch {
              void 0
            }
            e.preventDefault()

            setIsSeekingProgress(true)
            const next = getPercentFromPointerX(e.currentTarget, e.clientX) * duration
            const clamped = clamp(next, 0, duration)
            setProgressDraft(clamped)
            const audio = audioRef.current
            if (audio) {
              wasPlayingBeforeSeekRef.current = !audio.paused
              audio.pause()
            }
          }}
          onPointerUp={(e) => {
            if (e.currentTarget.hasPointerCapture(e.pointerId)) {
              e.currentTarget.releasePointerCapture(e.pointerId)
            }
            const audio = audioRef.current
            if (!isSeekingProgress) {
              const gesture = progressGestureRef.current
              if (gesture.pointerId === e.pointerId) {
                const next = clamp(getPercentFromPointerX(e.currentTarget, e.clientX) * duration, 0, duration)
                if (audio) {
                  try {
                    audio.currentTime = next
                  } catch {
                    void 0
                  }
                }
                setCurrentTime(next)
              }
            } else {
              const next = progressDraft
              if (audio && typeof next === 'number' && duration) {
                try {
                  audio.currentTime = clamp(next, 0, duration)
                } catch {
                  void 0
                }
                setCurrentTime(clamp(next, 0, duration))
                if (wasPlayingBeforeSeekRef.current) audio.play().catch(() => {})
              } else if (audio && wasPlayingBeforeSeekRef.current) {
                audio.play().catch(() => {})
              }
            }
            wasPlayingBeforeSeekRef.current = false
            setIsSeekingProgress(false)
            setIsInteractingProgress(false)
            setProgressDraft(null)
            progressPointerIdRef.current = null
            progressGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
          }}
          onPointerCancel={() => {
            const audio = audioRef.current
            if (audio && wasPlayingBeforeSeekRef.current) audio.play().catch(() => {})
            wasPlayingBeforeSeekRef.current = false
            setIsSeekingProgress(false)
            setIsInteractingProgress(false)
            setProgressDraft(null)
            progressPointerIdRef.current = null
            progressGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
          }}
          onLostPointerCapture={() => {
            const audio = audioRef.current
            if (audio && wasPlayingBeforeSeekRef.current) audio.play().catch(() => {})
            wasPlayingBeforeSeekRef.current = false
            setIsSeekingProgress(false)
            setIsInteractingProgress(false)
            setProgressDraft(null)
            progressPointerIdRef.current = null
            progressGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
          }}
          onBlur={() => {
            if (progressPointerIdRef.current !== null) return
            setIsSeekingProgress(false)
            setIsInteractingProgress(false)
            setProgressDraft(null)
          }}
          onChange={(e) => {
            const next = Number(e.target.value)
            const audio = audioRef.current
            if (audio) {
              try {
                audio.currentTime = next
              } catch {
                void 0
              }
            }
            setCurrentTime(next)
          }}
          aria-label="Progress"
        />

        <div className="expanded-player-time" aria-hidden={!currentSong ? 'true' : 'false'}>
          <span className="expanded-player-time-start">{formatTime(progressValue)}</span>
          <button
            className="expanded-player-quality-btn expanded-player-quality-btn-inline"
            type="button"
            disabled={!currentSong}
            onClick={() => {
              if (isDragging || suppressSheetClicks) return
              setIsQualityOpen(true)
            }}
            aria-label={isMp3 ? 'MP3 info' : 'Hi-Res Lossless info'}
          >
            {!isMp3 ? (
              <svg className="expanded-player-quality-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 15 9" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M8.184,0.35C9.944,0.35 10.703,3.296 11.338,5.238C11.673,3.842 11.497,3.542 11.857,3.542C11.99,3.542 12.126,3.633 12.126,3.798C12.126,3.809 12.123,3.839 12.117,3.883L12.091,4.058C12.02,4.522 11.845,5.494 11.654,6.144C13.198,10.191 14.345,4.861 14.474,3.772C14.493,3.615 14.612,3.542 14.731,3.542C14.891,3.542 15.022,3.662 14.997,3.843C14.72,5.605 14.295,8.35 12.547,8.35C11.582,8.35 11.04,7.595 10.611,6.73C9.54,4.626 9.047,1.093 7.997,1.093C7.66,1.093 7.411,1.444 7.394,1.444C7.362,1.444 7.337,1.301 7.023,0.909C7.322,0.567 7.734,0.35 8.184,0.35ZM2.458,0.354C5.211,0.354 5.456,7.618 7.014,7.618C7.197,7.618 7.394,7.507 7.61,7.256C7.729,7.458 7.851,7.638 7.978,7.796C7.667,8.151 7.28,8.35 6.795,8.35C5.054,8.349 4.306,5.434 3.663,3.466C3.511,4.097 3.432,4.669 3.402,4.925C3.382,5.088 3.263,5.163 3.143,5.163C3.009,5.163 2.874,5.071 2.874,4.908L2.874,4.908L2.877,4.87C2.966,4.223 3.146,3.243 3.347,2.56C3.079,1.858 2.745,1.091 2.252,1.091C1.257,1.091 0.687,3.591 0.527,4.925C0.508,5.088 0.388,5.163 0.268,5.163C0.135,5.163 0,5.071 0,4.908C0,4.896 0.001,4.883 0.002,4.87C0.283,2.836 0.808,0.354 2.458,0.354ZM5.315,0.35C5.809,0.35 6.339,0.608 6.797,1.211C6.822,1.241 7.078,1.639 7.159,1.777C8.277,3.802 8.818,7.627 9.881,7.627C10.065,7.627 10.264,7.513 10.484,7.256C10.604,7.458 10.726,7.638 10.852,7.796C10.542,8.15 10.155,8.35 9.67,8.35C6.933,8.349 6.636,1.09 5.128,1.09C4.788,1.09 4.536,1.444 4.519,1.444C4.487,1.444 4.462,1.301 4.148,0.909C4.455,0.558 4.87,0.35 5.315,0.35Z"
                />
              </svg>
            ) : null}
            <span className="expanded-player-quality-text">{isMp3 ? (codecLabel === 'Unknown' || codecLabel === 'MPEG Audio' ? 'MP3' : codecLabel) : (codecLabel !== 'Unknown' ? codecLabel : 'Hi-Res Lossless')}</span>
          </button>
          <span className="expanded-player-time-end">{duration ? formatTime(duration) : '0:00'}</span>
        </div>
      </div>

      <div className="expanded-player-controls">
        <button className="expanded-control-btn" type="button" onClick={playPrevious} disabled={!currentSong} aria-label="Previous track">
          <img className="expanded-control-icon" src={previousIconUrl} alt="" aria-hidden="true" />
        </button>

        <button
          className="expanded-control-btn expanded-control-btn-primary"
          type="button"
          onClick={togglePlay}
          disabled={!currentSong}
          aria-label={isPlaying ? 'Pause' : 'Play'}
        >
          <img className="expanded-control-icon expanded-control-icon-primary" src={isPlaying ? pauseIconUrl : resumeIconUrl} alt="" aria-hidden="true" />
        </button>

        <button className="expanded-control-btn" type="button" onClick={playNext} disabled={!currentSong} aria-label="Next track">
          <img className="expanded-control-icon" src={forwardIconUrl} alt="" aria-hidden="true" />
        </button>
      </div>

      {showVolume ? (
        <div className="expanded-player-volume">
          <div className="expanded-player-volume-icon" aria-hidden="true">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10.802 16" width="11" height="16">
              <path
                d="M9.781 16c.598 0 1.021-.43 1.021-1.023V1.07c0-.583-.422-1.07-1.04-1.07-.424 0-.71.185-1.179.624L4.725 4.253a.332.332 0 0 1-.228.089H1.899C.669 4.342 0 5.012 0 6.327v3.375c0 1.305.67 1.984 1.899 1.984h2.598a.33.33 0 0 1 .228.08l3.858 3.666c.422.393.772.568 1.198.568z"
                fill="currentColor"
              />
            </svg>
          </div>
          <input
            className={`expanded-player-volume-slider${isSeekingVolume ? ' is-seeking' : ''}${isInteractingVolume ? ' is-interacting' : ''}`}
            type="range"
            min={0}
            max={1}
            step={0.01}
            value={volumeValue}
            style={{ ['--volume' as never]: volumePercent }}
            onPointerDown={(e) => {
              setIsInteractingVolume(true)
              volumePointerIdRef.current = e.pointerId
              volumeGestureRef.current = { pointerId: e.pointerId, startX: e.clientX, startY: e.clientY }
              try {
                e.currentTarget.setPointerCapture(e.pointerId)
              } catch {
                void 0
              }
            }}
            onPointerMove={(e) => {
              if (isSeekingVolume) {
                e.preventDefault()
                const next = clamp(getPercentFromPointerX(e.currentTarget, e.clientX), 0, 1)
                setVolumeDraft(next)
                setVolume(next)
                return
              }

              const gesture = volumeGestureRef.current
              if (gesture.pointerId !== e.pointerId) return
              const dx = e.clientX - gesture.startX
              const dy = e.clientY - gesture.startY
              const adx = Math.abs(dx)
              const ady = Math.abs(dy)
              if (ady > adx + 14) {
                volumeGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
                volumePointerIdRef.current = null
                return
              }
              if (adx < 24 || adx <= ady * 2.6) return

              try {
                e.currentTarget.setPointerCapture(e.pointerId)
              } catch {
                void 0
              }
              e.preventDefault()

              setIsSeekingVolume(true)
              const next = clamp(getPercentFromPointerX(e.currentTarget, e.clientX), 0, 1)
              setVolumeDraft(next)
              setVolume(next)
            }}
            onPointerUp={(e) => {
              if (e.currentTarget.hasPointerCapture(e.pointerId)) {
                e.currentTarget.releasePointerCapture(e.pointerId)
              }
              if (!isSeekingVolume) {
                const gesture = volumeGestureRef.current
                if (gesture.pointerId === e.pointerId) {
                  const next = clamp(getPercentFromPointerX(e.currentTarget, e.clientX), 0, 1)
                  setVolume(next)
                }
              } else {
                const next = clamp(getPercentFromPointerX(e.currentTarget, e.clientX), 0, 1)
                setVolume(next)
              }
              setIsSeekingVolume(false)
              setIsInteractingVolume(false)
              setVolumeDraft(null)
              volumePointerIdRef.current = null
              volumeGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
            }}
            onPointerCancel={() => {
              setIsSeekingVolume(false)
              setIsInteractingVolume(false)
              setVolumeDraft(null)
              volumePointerIdRef.current = null
              volumeGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
            }}
            onLostPointerCapture={() => {
              setIsSeekingVolume(false)
              setIsInteractingVolume(false)
              setVolumeDraft(null)
              volumePointerIdRef.current = null
              volumeGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
            }}
            onBlur={() => {
              if (volumePointerIdRef.current !== null) return
              setIsSeekingVolume(false)
              setIsInteractingVolume(false)
              setVolumeDraft(null)
            }}
            onChange={(e) => setVolume(clamp(Number(e.target.value), 0, 1))}
            aria-label="Volume"
          />
          <div className="expanded-player-volume-icon is-large" aria-hidden="true">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 22.239 16" width="22" height="16">
              <path
                d="M8.933 15.316c.538 0 .932-.392.932-.934v-12.7c0-.533-.394-.978-.95-.978-.387 0-.656.169-1.076.57L4.306 4.588a.281.281 0 0 1-.2.081H1.735C.611 4.669 0 5.28 0 6.482v3.082c0 1.192.611 1.812 1.734 1.812h2.373c.08 0 .15.024.2.074l3.532 3.347c.385.359.707.519 1.094.519zm3.88-3.782c.282.186.668.13.892-.192.634-.84 1.019-2.07 1.019-3.34 0-1.272-.385-2.493-1.02-3.342-.223-.322-.61-.378-.89-.183-.331.232-.388.639-.13.983.486.658.75 1.576.75 2.541 0 .965-.273 1.874-.75 2.541-.249.354-.201.75.13.992zm3.08 2.155c.308.204.686.14.91-.183 1.059-1.452 1.686-3.462 1.686-5.505 0-2.042-.618-4.07-1.686-5.505-.224-.323-.602-.387-.91-.183-.305.206-.36.593-.117.937.911 1.278 1.425 2.988 1.425 4.751 0 1.763-.531 3.464-1.425 4.75-.234.345-.188.732.118.938zm3.108 2.194c.29.213.696.13.916-.204 1.453-2.095 2.322-4.752 2.322-7.678s-.895-5.574-2.322-7.678C19.697-.02 19.29-.093 19 .12c-.305.213-.352.603-.122.948 1.28 1.892 2.09 4.277 2.09 6.934 0 2.649-.81 5.052-2.09 6.934-.23.345-.183.735.122.948z"
                fill="currentColor"
              />
            </svg>
          </div>
        </div>
      ) : null}
    </>
  )

  return (
    <div
      className="expanded-player-backdrop"
      data-state={state}
      data-view={isDesktop ? 'desktop' : view}
      data-platform={platform.isWindows ? 'windows' : 'other'}
      style={{ ...backdropStyle, ...(backgroundStyle as CSSProperties) }}
      onClick={() => {
        if (isDragging) return
        if (state === 'closing') return
        onClose()
      }}
      role="dialog"
      aria-modal="true"
    >
      <div className="expanded-player-background" style={backgroundStyle} aria-hidden="true" />
      <div className="expanded-player-scrim" aria-hidden="true" />
      {isMenuOpen ? (
        <>
          <div
            className="expanded-player-menu-backdrop"
            onClick={(e) => {
              e.stopPropagation()
              closeMenu()
            }}
          />
          <div
            className="expanded-player-menu"
            data-view={menuView}
            style={{
              top: `${menuPosition?.y ?? 0}px`,
              left: `${menuPosition?.x ?? 0}px`,
            }}
            role="menu"
            aria-label="Track options"
            onClick={(e) => e.stopPropagation()}
          >
            {menuView === 'root' ? (
              <>
                <button className="expanded-player-menu-item" type="button" onClick={handleMenuToggleFavourite}>
                  <svg className="expanded-player-menu-icon" viewBox="0 0 60 60" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                    <path
                      fill="currentColor"
                      d="M19.337 44.944c.851.647 1.887.448 3.031-.393l7.444-5.465 7.445 5.465c1.145.84 2.181 1.04 3.033.393.832-.63 1.006-1.68.55-2.99l-2.941-8.742 7.508-5.386c1.144-.806 1.644-1.747 1.3-2.756-.337-.992-1.282-1.476-2.679-1.459l-9.201.07-2.8-8.804c-.43-1.342-1.16-2.083-2.215-2.083-1.044 0-1.775.741-2.212 2.083l-2.8 8.805-9.21-.071c-1.389-.017-2.327.467-2.67 1.45-.345 1.018.163 1.959 1.3 2.765l7.507 5.386-2.94 8.742c-.456 1.31-.283 2.36.55 2.99z"
                    />
                  </svg>
                  <span>{menuSong && favouriteIds.has(menuSong._id) ? 'Remove from favourites' : 'Add to favourites'}</span>
                </button>

                <div className="expanded-player-menu-divider" aria-hidden="true" />

                <button className="expanded-player-menu-item" type="button" onClick={handleMenuAddToPlaylist}>
                  <svg className="expanded-player-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                    <path fill="currentColor" d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h13v2H3v-2zm16 0v3h3v2h-3v3h-2v-3h-3v-2h3v-3h2z" />
                  </svg>
                  <span>Add to playlist</span>
                </button>

                <button className="expanded-player-menu-item" type="button" onClick={handleMenuPlayNext} disabled={!canEditQueue}>
                  <svg className="expanded-player-menu-icon" viewBox="0 0 32 28" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                    <path
                      fill="currentColor"
                      d="M18.14 20.68c.365 0 .672-.107 1.038-.323l8.508-4.997c.623-.365.938-.814.938-1.37 0-.564-.307-.988-.938-1.361l-8.508-4.997c-.366-.216-.68-.324-1.046-.324-.73 0-1.337.556-1.337 1.569v4.773c-.108-.399-.406-.73-.904-1.021L7.382 7.632c-.357-.216-.672-.324-1.037-.324-.73 0-1.345.556-1.345 1.569v10.235c0 1.013.614 1.569 1.345 1.569.365 0 .68-.108 1.037-.324l8.509-4.997c.49-.29.796-.631.904-1.038v4.79c0 1.013.615 1.569 1.345 1.569z"
                    />
                  </svg>
                  <span>Play next</span>
                </button>
              </>
            ) : (
              <>
                <div className="expanded-player-menu-header">
                  <button
                    className="expanded-player-menu-back-btn"
                    type="button"
                    onClick={() => {
                      setMenuView('root')
                      ensureMenuFits(240, 180)
                    }}
                    aria-label="Back"
                  >
                    <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                      <path fill="currentColor" d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z" />
                    </svg>
                  </button>
                  <div className="expanded-player-menu-title">Add to playlist</div>
                </div>

                <button className="expanded-player-menu-item" type="button" onClick={openCreatePlaylist}>
                  <svg className="expanded-player-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                    <path fill="currentColor" d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
                  </svg>
                  <span>New playlist</span>
                </button>

                {playlistsError ? <div className="expanded-player-menu-error">{playlistsError}</div> : null}
                {playlistsLoading ? <div className="expanded-player-menu-loading">Loading…</div> : null}

                {!playlistsLoading && !playlistsError ? (
                  <div className="expanded-player-menu-playlist-list" role="presentation">
                    {playlistItems.recent.length ? <div className="expanded-player-menu-section-label">Recent</div> : null}
                    {playlistItems.recent.map((p) => (
                      <button
                        key={`recent-${p.playlist_id}`}
                        className="expanded-player-menu-item"
                        type="button"
                        onClick={() => handleSelectPlaylist(p.playlist_id)}
                        disabled={Boolean(addingPlaylistId)}
                      >
                        <svg className="expanded-player-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                          <path fill="currentColor" d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z" />
                        </svg>
                        <span>{p.name}</span>
                      </button>
                    ))}

                    {playlistItems.rest.length ? <div className="expanded-player-menu-section-label">All</div> : null}
                    {playlistItems.rest.map((p) => (
                      <button
                        key={`rest-${p.playlist_id}`}
                        className="expanded-player-menu-item"
                        type="button"
                        onClick={() => handleSelectPlaylist(p.playlist_id)}
                        disabled={Boolean(addingPlaylistId)}
                      >
                        <svg className="expanded-player-menu-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                          <path fill="currentColor" d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z" />
                        </svg>
                        <span>{p.name}</span>
                      </button>
                    ))}
                  </div>
                ) : null}
              </>
            )}
          </div>
        </>
      ) : null}
      {createPlaylistOpen ? (
        <div className="expanded-player-playlist-modal-backdrop" role="presentation" onClick={() => setCreatePlaylistOpen(false)}>
          <div className="expanded-player-playlist-modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
            <div className="expanded-player-playlist-modal-header">
              <div className="expanded-player-playlist-modal-title">New playlist</div>
              <button className="expanded-player-playlist-modal-close" type="button" onClick={() => setCreatePlaylistOpen(false)} aria-label="Close">
                <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7A1 1 0 0 0 5.7 7.11L10.59 12l-4.9 4.89a1 1 0 1 0 1.41 1.42L12 13.41l4.89 4.9a1 1 0 0 0 1.42-1.41L13.41 12l4.9-4.89a1 1 0 0 0-.01-1.4Z" />
                </svg>
              </button>
            </div>
            <div className="expanded-player-playlist-modal-content">
              <input
                className="expanded-player-playlist-modal-input"
                value={createPlaylistName}
                onChange={(e) => setCreatePlaylistName(e.target.value)}
                placeholder="Playlist name"
                autoFocus
              />
            </div>
            <div className="expanded-player-playlist-modal-footer">
              <button className="expanded-player-playlist-modal-btn expanded-player-playlist-modal-btn--cancel" type="button" onClick={() => setCreatePlaylistOpen(false)}>
                Cancel
              </button>
              <button
                className="expanded-player-playlist-modal-btn expanded-player-playlist-modal-btn--primary"
                type="button"
                onClick={handleCreatePlaylist}
                disabled={!createPlaylistName.trim() || creatingPlaylist}
              >
                Create
              </button>
            </div>
          </div>
        </div>
      ) : null}
      {isQualityOpen ? (
        <div
          className="expanded-player-quality-modal-backdrop"
          data-quality={isMp3 ? 'mp3' : 'hires'}
          onClick={() => setIsQualityOpen(false)}
          role="presentation"
        >
          <div className="expanded-player-quality-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
            {isMp3 ? (
              <div className="expanded-player-quality-modal-icon expanded-player-quality-modal-icon-mp3" aria-hidden="true">
                <img className="expanded-player-quality-modal-icon-img" src={mp3IconUrl} alt="" />
              </div>
            ) : (
              <div className="expanded-player-quality-modal-icon" aria-hidden="true">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 15 9">
                  <path
                    fill="currentColor"
                    d="M8.184,0.35C9.944,0.35 10.703,3.296 11.338,5.238C11.673,3.842 11.497,3.542 11.857,3.542C11.99,3.542 12.126,3.633 12.126,3.798C12.126,3.809 12.123,3.839 12.117,3.883L12.091,4.058C12.02,4.522 11.845,5.494 11.654,6.144C13.198,10.191 14.345,4.861 14.474,3.772C14.493,3.615 14.612,3.542 14.731,3.542C14.891,3.542 15.022,3.662 14.997,3.843C14.72,5.605 14.295,8.35 12.547,8.35C11.582,8.35 11.04,7.595 10.611,6.73C9.54,4.626 9.047,1.093 7.997,1.093C7.66,1.093 7.411,1.444 7.394,1.444C7.362,1.444 7.337,1.301 7.023,0.909C7.322,0.567 7.734,0.35 8.184,0.35ZM2.458,0.354C5.211,0.354 5.456,7.618 7.014,7.618C7.197,7.618 7.394,7.507 7.61,7.256C7.729,7.458 7.851,7.638 7.978,7.796C7.667,8.151 7.28,8.35 6.795,8.35C5.054,8.349 4.306,5.434 3.663,3.466C3.511,4.097 3.432,4.669 3.402,4.925C3.382,5.088 3.263,5.163 3.143,5.163C3.009,5.163 2.874,5.071 2.874,4.908L2.874,4.908L2.877,4.87C2.966,4.223 3.146,3.243 3.347,2.56C3.079,1.858 2.745,1.091 2.252,1.091C1.257,1.091 0.687,3.591 0.527,4.925C0.508,5.088 0.388,5.163 0.268,5.163C0.135,5.163 0,5.071 0,4.908C0,4.896 0.001,4.883 0.002,4.87C0.283,2.836 0.808,0.354 2.458,0.354ZM5.315,0.35C5.809,0.35 6.339,0.608 6.797,1.211C6.822,1.241 7.078,1.639 7.159,1.777C8.277,3.802 8.818,7.627 9.881,7.627C10.065,7.627 10.264,7.513 10.484,7.256C10.604,7.458 10.726,7.638 10.852,7.796C10.542,8.15 10.155,8.35 9.67,8.35C6.933,8.349 6.636,1.09 5.128,1.09C4.788,1.09 4.536,1.444 4.519,1.444C4.487,1.444 4.462,1.301 4.148,0.909C4.455,0.558 4.87,0.35 5.315,0.35Z"
                  />
                </svg>
              </div>
            )}
            {isMp3 ? (
              <>
                <div className="expanded-player-quality-modal-title">Lossy</div>
                <div className="expanded-player-quality-modal-subtitle">{`${codecLabel === 'Unknown' || codecLabel === 'MPEG Audio' ? 'MP3' : codecLabel}${bitsKhzDetails ? ` ${bitsKhzDetails}` : ''}`}</div>
              </>
            ) : qualitySubtitle ? (
              <>
                <div className="expanded-player-quality-modal-title">Hi-Res Lossless</div>
                <div className="expanded-player-quality-modal-subtitle">{qualitySubtitle}</div>
              </>
            ) : null}
            <div className="expanded-player-quality-modal-actions">
              <button className="expanded-player-quality-modal-ok" type="button" onClick={() => setIsQualityOpen(false)}>
                OK
              </button>
            </div>
          </div>
        </div>
      ) : null}

      <div
        className="expanded-player"
        data-view={isDesktop ? 'desktop' : view}
        data-queue-enter={queueEnterStage}
        style={sheetStyle}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="expanded-player-topbar">
          <button
            className="expanded-player-close"
            type="button"
            onClick={() => (onCollapseClick ? onCollapseClick() : onClose())}
            aria-label="Collapse player"
          >
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 90.64 30.831">
              <path
                d="m4.486 14.456 32.352 13.938c3.156 1.387 5.552 2.437 8.48 2.437 2.932 0 5.357-1.05 8.484-2.437l32.353-13.938c2.612-1.192 4.485-3.514 4.485-6.42C90.64 3.184 87.085 0 83 0c-2.279 0-5.172 1.325-7.569 2.42L42.845 16.358h4.95L15.21 2.42C12.812 1.325 9.948 0 7.636 0 3.55 0 0 3.184 0 8.036c0 2.906 1.873 5.228 4.486 6.42z"
                fill="currentColor"
              />
            </svg>
          </button>
        </div>

        {isDesktop ? (
          <div className="expanded-player-desktop">
            <div className="expanded-player-desktop-grid">
              <div className="expanded-player-desktop-panel expanded-player-desktop-panel--left">
                <div className="expanded-player-side-card">
                  {isQueueOpen ? (
                    <NextInfo
                      coverUrl={coverUrl}
                      title={currentSong?.title || 'Not Playing'}
                      artist={currentSong?.artist || ''}
                      isShuffleOn={isShuffleOn}
                      setIsShuffleOn={setIsShuffleOn}
                      repeatMode={repeatMode}
                      setRepeatMode={setRepeatMode}
                      upcomingTracks={isExternalNowPlaying ? externalUpcoming : null}
                      disabled={!currentSong || isExternalNowPlaying}
                    />
                  ) : (
                    lyricsCard
                  )}
                </div>
              </div>

              <div className="expanded-player-desktop-panel expanded-player-desktop-panel--right">
                <div className="expanded-player-desktop-now-playing">
                  {nowPlayingHero}
                  {bottomActions}
                </div>
                {playbackControlsCore}
              </div>
            </div>
          </div>
        ) : (
          <>
            <div className="expanded-player-view expanded-player-view--main" hidden={isQueueOpen || isLyricsOpen}>
              {nowPlayingHero}
            </div>

            <div className="expanded-player-view expanded-player-view--queue" hidden={!isQueueOpen}>
              <NextInfo
                coverUrl={coverUrl}
                title={currentSong?.title || 'Not Playing'}
                artist={currentSong?.artist || ''}
                isShuffleOn={isShuffleOn}
                setIsShuffleOn={setIsShuffleOn}
                repeatMode={repeatMode}
                setRepeatMode={setRepeatMode}
                upcomingTracks={isExternalNowPlaying ? externalUpcoming : null}
                disabled={!currentSong || isExternalNowPlaying}
              />
            </div>

            <div className="expanded-player-view expanded-player-view--lyrics" hidden={!isLyricsOpen}>
              <div className="expanded-player-queue-now" aria-label="Now playing">
                <img className="expanded-player-queue-now-cover" src={coverUrl} alt="" aria-hidden="true" />
                <div className="expanded-player-queue-now-text">
                  <div className="expanded-player-queue-now-title" title={currentSong?.title || undefined}>
                    {currentSong?.title || 'Not Playing'}
                  </div>
                  <div className="expanded-player-queue-now-artist" title={currentSong?.artist || undefined}>
                    {currentSong?.artist || ''}
                  </div>
                </div>
              </div>

              {lyricsCard}
            </div>

            {playbackControlsCore}
            {bottomActions}
          </>
        )}
      </div>
    </div>
  )
}

