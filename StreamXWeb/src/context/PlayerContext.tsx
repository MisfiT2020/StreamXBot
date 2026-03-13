import { createContext, useContext, useState, useRef, useEffect, useCallback, useMemo } from 'react'
import type { Dispatch, ReactNode, SetStateAction } from 'react'
import type { Song } from '../types/index.js'
import { api, getAuthCookieEnabled, getAuthToken, setAuthCookieEnabled } from '../services/api.js'

type RepeatMode = 'off' | 'all' | 'one'
type StreamMode = 'balanced' | 'saver' | 'aggressive'

const STREAM_MODE_STORAGE_KEY = 'streamw:audio:stream_mode'
const SYNC_LYRICS_STORAGE_KEY = 'streamw:audio:sync_lyrics'
const AUDIO_UNLOCK_KEY = '__streamw_audio_unlocked__'

const unlockAudioOnce = async () => {
  if (typeof window === 'undefined') return
  const w = window as unknown as Record<string, unknown>
  if (w[AUDIO_UNLOCK_KEY] === true) return
  w[AUDIO_UNLOCK_KEY] = true

  try {
    const AudioContextCtor =
      (window as unknown as { AudioContext?: typeof AudioContext }).AudioContext ||
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext

    if (AudioContextCtor) {
      const ctx = new AudioContextCtor()
      try {
        if (ctx.state !== 'running') {
          await ctx.resume()
        }
        const osc = ctx.createOscillator()
        const gain = ctx.createGain()
        gain.gain.value = 0
        osc.connect(gain)
        gain.connect(ctx.destination)
        osc.start()
        osc.stop(ctx.currentTime + 0.01)
      } finally {
        try {
          await ctx.close()
        } catch {
          void 0
        }
      }
    }
  } catch {
    void 0
  }

  try {
    const a = document.createElement('audio')
    a.muted = true
    a.setAttribute('playsinline', 'true')
    a.setAttribute('webkit-playsinline', 'true')
    a.src = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAESsAACJWAAACABAAZGF0YQAAAAA='
    await a.play()
    a.pause()
  } catch {
    void 0
  }
}

type StreamProfile = {
  audioPreload: 'auto' | 'metadata'
  prefetchNextAudio: boolean
  prefetchWhenRemainingSec: number
  eagerFetchFull: boolean
}

const STREAM_PROFILES: Record<StreamMode, StreamProfile> = {
  balanced: {
    audioPreload: 'auto',
    prefetchNextAudio: false,
    prefetchWhenRemainingSec: 0,
    eagerFetchFull: false,
  },
  saver: {
    audioPreload: 'metadata',
    prefetchNextAudio: false,
    prefetchWhenRemainingSec: 0,
    eagerFetchFull: false,
  },
  aggressive: {
    audioPreload: 'auto',
    prefetchNextAudio: true,
    prefetchWhenRemainingSec: 45,
    eagerFetchFull: false,
  },
}

const readStoredStreamMode = (): StreamMode => {
  if (typeof window === 'undefined') return 'balanced'
  try {
    const raw = window.localStorage.getItem(STREAM_MODE_STORAGE_KEY)
    if (raw === 'balanced' || raw === 'saver' || raw === 'aggressive') return raw
    return 'balanced'
  } catch {
    return 'balanced'
  }
}

const readStoredSyncLyrics = (): boolean => {
  if (typeof window === 'undefined') return true
  try {
    const raw = window.localStorage.getItem(SYNC_LYRICS_STORAGE_KEY)
    if (raw === 'false') return false
    return true
  } catch {
    return true
  }
}

interface PlayerPlaybackContextType {
  currentSong: Song | null
  isPlaying: boolean
  isExternalNowPlaying: boolean
  externalJamId: string | null
  externalCanEditQueue: boolean
  volume: number
  audioRef: React.RefObject<HTMLAudioElement | null>
  favouriteIds: ReadonlySet<string>
  favouritesTotal: number
  favouritesLastUpdatedAtMs: number | null
  refreshFavourites: () => Promise<void>
  toggleFavourite: (trackId: string) => Promise<void>
  setExternalNowPlaying: (song: Song | null) => void
  externalUpcoming: Song[]
  setExternalUpcoming: Dispatch<SetStateAction<Song[]>>
  setExternalJamContext: (ctx: { jamId: string | null; canEditQueue: boolean }) => void
  setExternalTogglePlay: (fn: (() => void | Promise<void>) | null) => void
  playSong: (song: Song) => void
  playSongFromList: (song: Song, songs: Song[]) => void
  playFromQueue: (song: Song) => void
  playNextTrack: (song: Song) => void
  addTrackToQueue: (song: Song) => void
  togglePlay: () => void
  playPrevious: () => void
  playNext: () => void
  setVolume: (volume: number) => void
  queueHistory: Song[]
  queue: Song[]
  setQueue: Dispatch<SetStateAction<Song[]>>
  history: Song[]
  setHistory: Dispatch<SetStateAction<Song[]>>
  upcoming: Song[]
  setUpcoming: Dispatch<SetStateAction<Song[]>>
  isShuffleOn: boolean
  setIsShuffleOn: (next: boolean) => void
  repeatMode: RepeatMode
  setRepeatMode: (next: RepeatMode) => void
  streamMode: StreamMode
  setStreamMode: (next: StreamMode) => void
  eagerFetchCurrentTrack: () => void
  isSyncLyricsOn: boolean
  setIsSyncLyricsOn: (next: boolean) => void
}

interface PlayerLibraryContextType {
  songs: Song[]
  setSongs: Dispatch<SetStateAction<Song[]>>
}

const PlayerPlaybackContext = createContext<PlayerPlaybackContextType | undefined>(undefined)
const PlayerLibraryContext = createContext<PlayerLibraryContextType | undefined>(undefined)

export const PlayerProvider = ({ children }: { children: ReactNode }) => {
  const [currentSong, setCurrentSong] = useState<Song | null>(null)
  const currentSongId = currentSong?._id ?? null
  const [isPlaying, setIsPlaying] = useState(false)
  const [isExternalNowPlaying, setIsExternalNowPlaying] = useState(false)
  const [externalUpcoming, setExternalUpcoming] = useState<Song[]>([])
  const [externalJamId, setExternalJamId] = useState<string | null>(null)
  const [externalCanEditQueue, setExternalCanEditQueue] = useState(false)
  const [volume, setVolume] = useState(0.61)
  const [favouriteIds, setFavouriteIds] = useState<Set<string>>(() => new Set())
  const [favouritesTotal, setFavouritesTotal] = useState(0)
  const [favouritesLastUpdatedAtMs, setFavouritesLastUpdatedAtMs] = useState<number | null>(null)
  const [songs, setSongs] = useState<Song[]>([])
  const [queueHistory, setQueueHistory] = useState<Song[]>([])
  const [queue, setQueue] = useState<Song[]>([])
  const [isShuffleOn, setIsShuffleOnState] = useState(false)
  const [repeatMode, setRepeatMode] = useState<RepeatMode>('off')
  const [streamMode, setStreamModeState] = useState<StreamMode>(() => readStoredStreamMode())
  const [isSyncLyricsOn, setIsSyncLyricsOnState] = useState(() => readStoredSyncLyrics())
  const streamModeRef = useRef<StreamMode>('balanced')
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const shouldBePlayingRef = useRef(false)
  const objectUrlRef = useRef<string | null>(null)
  const aggressiveObjectUrlRef = useRef<string | null>(null)
  const aggressiveObjectUrlSongIdRef = useRef<string | null>(null)
  const fallbackAttemptSongIdRef = useRef<string | null>(null)
  const authFallbackAttemptSongIdRef = useRef<string | null>(null)
  const fallbackAbortRef = useRef<AbortController | null>(null)
  const aggressiveFetchAbortRef = useRef<AbortController | null>(null)
  const currentSongIdRef = useRef<string | null>(null)
  const isExternalNowPlayingRef = useRef(false)
  const externalTogglePlayRef = useRef<(() => void | Promise<void>) | null>(null)
  const favouriteIdsRef = useRef<Set<string>>(new Set())
  const refreshFavouritesInFlightRef = useRef<Promise<void> | null>(null)
  const prefetchAudioRef = useRef<HTMLAudioElement | null>(null)
  const prefetchedNextSongIdRef = useRef<string | null>(null)
  const currentTimeRef = useRef(0)
  const lastPositionStateAtRef = useRef(0)
  const stallRetryRef = useRef<{ songId: string | null; attempts: number; lastAttemptAt: number }>({
    songId: null,
    attempts: 0,
    lastAttemptAt: 0,
  })
  const stallTimerRef = useRef<number | null>(null)
  const saverSeekReloadAtRef = useRef(0)
  const aggressiveSeekSwitchAtRef = useRef(0)

  useEffect(() => {
    if (typeof window === 'undefined') return
    const onFirstGesture = () => {
      void unlockAudioOnce()
    }
    window.addEventListener('pointerdown', onFirstGesture, { passive: true, once: true })
    window.addEventListener('touchstart', onFirstGesture, { passive: true, once: true })
    return () => {
      window.removeEventListener('pointerdown', onFirstGesture)
      window.removeEventListener('touchstart', onFirstGesture)
    }
  }, [])

  const refreshFavourites = useCallback((): Promise<void> => {
    const existing = refreshFavouritesInFlightRef.current
    if (existing) return existing

    const normalizeUpdatedAtMs = (value: unknown): number | null => {
      if (typeof value !== 'number' || !Number.isFinite(value)) return null
      if (value > 1_000_000_000_000) return value
      if (value > 1_000_000_000) return value * 1000
      return null
    }

    const promise = api
      .getFavouriteIds(1, 200)
      .then((result) => {
        const next = new Set(result.ids)
        favouriteIdsRef.current = next
        setFavouriteIds(next)
        setFavouritesTotal(typeof result.total === 'number' && result.total >= 0 ? result.total : next.size)
        const ms = normalizeUpdatedAtMs(result.last_updated_at)
        setFavouritesLastUpdatedAtMs(ms)
        if (ms !== null) {
          try {
            window.localStorage.setItem('streamw:favourites:lastUpdatedAtMs', String(ms))
          } catch {
            void 0
          }
        }
      })
      .finally(() => {
        refreshFavouritesInFlightRef.current = null
      })

    refreshFavouritesInFlightRef.current = promise
    return promise
  }, [])

  const toggleFavourite = useCallback(
    async (trackId: string) => {
      const wasFav = favouriteIdsRef.current.has(trackId)
      const next = new Set(favouriteIdsRef.current)
      if (wasFav) next.delete(trackId)
      else next.add(trackId)
      favouriteIdsRef.current = next
      setFavouriteIds(next)

      try {
        if (wasFav) await api.removeFromFavourites(trackId)
        else await api.addToFavourites(trackId)
        await refreshFavourites()
      } catch {
        refreshFavourites().catch(() => {})
      }
    },
    [refreshFavourites],
  )

  useEffect(() => {
    let cancelled = false
    refreshFavourites()
      .then(() => {
        if (cancelled) return
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [refreshFavourites])

  const resolveUrl = useCallback((url: string) => {
    if (typeof window === 'undefined') return url
    try {
      return new URL(url, window.location.href).href
    } catch (err) {
      void err
      return url
    }
  }, [])

  const revokeObjectUrl = useCallback(() => {
    const existing = objectUrlRef.current
    if (!existing) return
    objectUrlRef.current = null
    URL.revokeObjectURL(existing)
  }, [])

  const revokeAggressiveObjectUrl = useCallback(() => {
    const existing = aggressiveObjectUrlRef.current
    if (!existing) return
    aggressiveObjectUrlSongIdRef.current = null
    aggressiveObjectUrlRef.current = null
    URL.revokeObjectURL(existing)
  }, [])

  const inferPlaybackMime = useCallback((song: Song) => {
    const normalized = (song.type || '').toLowerCase().trim()
    if (normalized.includes('flac')) return 'audio/flac'
    if (normalized.includes('alac')) return 'audio/mp4'
    if (normalized.includes('mp3') || normalized.includes('mpeg')) return 'audio/mpeg'
    if (normalized.includes('aac') || normalized.includes('m4a') || normalized.includes('mp4')) return 'audio/mp4'
    return null
  }, [])

  const shuffleSongs = useCallback((items: Song[]) => {
    if (items.length <= 1) return items
    const next = items.slice()
    for (let i = next.length - 1; i > 0; i -= 1) {
      const j = Math.floor(Math.random() * (i + 1))
      const tmp = next[i]
      next[i] = next[j]
      next[j] = tmp
    }
    return next
  }, [])

  const setIsShuffleOn = useCallback(
    (next: boolean) => {
      setIsShuffleOnState(next)
      if (next) {
        setQueue((prev) => shuffleSongs(prev))
      }
    },
    [shuffleSongs],
  )

  const buildUpcoming = useCallback((song: Song, baseSongs: Song[]) => {
    if (baseSongs.length === 0) return []
    const currentIndex = baseSongs.findIndex((s) => s._id === song._id)
    if (currentIndex < 0) return []
    const upcoming = baseSongs.slice(currentIndex + 1)
    return isShuffleOn ? shuffleSongs(upcoming) : upcoming
  }, [isShuffleOn, shuffleSongs])

  const getNextSong = useCallback(() => {
    if (queue.length > 0) return queue[0] ?? null
    if (repeatMode !== 'all') return null
    const replay = [...queueHistory, ...(currentSong ? [currentSong] : [])]
    return replay[0] ?? null
  }, [currentSong, queue, queueHistory, repeatMode])

  const eagerFetchSong = useCallback((song: Song) => {
    if (streamModeRef.current !== 'aggressive') return
    if (aggressiveObjectUrlRef.current && aggressiveObjectUrlSongIdRef.current === song._id) return

    aggressiveFetchAbortRef.current?.abort()
    const controller = new AbortController()
    aggressiveFetchAbortRef.current = controller

    const streamUrl = resolveUrl(api.streamTrack(song._id))
    fetch(streamUrl, { signal: controller.signal, cache: 'no-store' })
      .then(async (response) => {
        if (!response.ok) throw new Error(String(response.status))
        const buffer = await response.arrayBuffer()
        if (controller.signal.aborted) return
        if (streamModeRef.current !== 'aggressive') return
        if (currentSongIdRef.current !== song._id) return
        const headerType = response.headers.get('content-type')?.split(';')[0]?.trim() || null
        const mime = inferPlaybackMime(song) ?? headerType
        const blobUrl = URL.createObjectURL(mime ? new Blob([buffer], { type: mime }) : new Blob([buffer]))
        if (streamModeRef.current !== 'aggressive' || currentSongIdRef.current !== song._id) {
          URL.revokeObjectURL(blobUrl)
          return
        }
        const previous = aggressiveObjectUrlRef.current
        aggressiveObjectUrlRef.current = blobUrl
        aggressiveObjectUrlSongIdRef.current = song._id

        if (previous && previous !== blobUrl) URL.revokeObjectURL(previous)
      })
      .catch(() => {})
  }, [inferPlaybackMime, resolveUrl])

  const eagerFetchCurrentTrack = useCallback(() => {
    if (!currentSong) return
    eagerFetchSong(currentSong)
  }, [currentSong, eagerFetchSong])

  const setStreamMode = useCallback((next: StreamMode) => {
    setStreamModeState(next)
  }, [])

  const setIsSyncLyricsOn = useCallback((next: boolean) => {
    setIsSyncLyricsOnState(next)
    try {
      window.localStorage.setItem(SYNC_LYRICS_STORAGE_KEY, String(next))
    } catch {
      // ignore
    }
  }, [])

  const startPlayback = useCallback((song: Song) => {
    setIsExternalNowPlaying(false)
    isExternalNowPlayingRef.current = false
    fallbackAbortRef.current?.abort()
    fallbackAbortRef.current = null
    fallbackAttemptSongIdRef.current = null
    authFallbackAttemptSongIdRef.current = null
    aggressiveFetchAbortRef.current?.abort()
    aggressiveFetchAbortRef.current = null
    revokeObjectUrl()
    revokeAggressiveObjectUrl()
    prefetchedNextSongIdRef.current = null
    shouldBePlayingRef.current = true
    const prefetchAudio = prefetchAudioRef.current
    if (prefetchAudio) {
      prefetchAudio.src = ''
    }

    setCurrentSong(song)
    currentSongIdRef.current = song._id
    currentTimeRef.current = 0

    const audio = audioRef.current
    if (!audio) return

    const nextSrc = resolveUrl(api.streamTrack(song._id))
    if (resolveUrl(audio.src) !== nextSrc) {
      audio.src = nextSrc
    }
    try {
      audio.currentTime = 0
    } catch (err) {
      void err
    }
    audio.play().catch(() => {})
    const profile = STREAM_PROFILES[streamModeRef.current]
    api.warmTrack(song._id).catch(() => {})
    if (profile.eagerFetchFull) eagerFetchSong(song)
  }, [eagerFetchSong, resolveUrl, revokeAggressiveObjectUrl, revokeObjectUrl])

  const setExternalNowPlaying = useCallback((song: Song | null) => {
    const isActive = Boolean(song)
    setIsExternalNowPlaying(isActive)
    isExternalNowPlayingRef.current = isActive
    setCurrentSong(song)
    currentSongIdRef.current = song?._id ?? null
    if (!isActive) {
      setExternalUpcoming([])
      setExternalJamId(null)
      setExternalCanEditQueue(false)
      externalTogglePlayRef.current = null
    }
  }, [])

  const setExternalJamContext = useCallback((ctx: { jamId: string | null; canEditQueue: boolean }) => {
    setExternalJamId(ctx.jamId)
    setExternalCanEditQueue(Boolean(ctx.jamId) && Boolean(ctx.canEditQueue))
  }, [])

  const setExternalTogglePlay = useCallback((fn: (() => void | Promise<void>) | null) => {
    externalTogglePlayRef.current = fn
  }, [])

  const playSongFromList = useCallback(
    (song: Song, list: Song[]) => {
      setQueueHistory([])
      setQueue(buildUpcoming(song, list))
      startPlayback(song)
    },
    [buildUpcoming, startPlayback],
  )

  const playSong = useCallback((song: Song) => {
    playSongFromList(song, songs)
  }, [playSongFromList, songs])

  const playFromQueue = useCallback(
    (song: Song) => {
      const idx = queue.findIndex((s) => s._id === song._id)
      if (idx < 0) {
        playSong(song)
        return
      }
      setQueueHistory((prev) => (currentSong ? [...prev, currentSong] : prev))
      setQueue(queue.slice(idx + 1))
      startPlayback(song)
    },
    [currentSong, playSong, queue, startPlayback],
  )

  const playNextTrack = useCallback(
    (song: Song) => {
      if (!currentSong) {
        playSong(song)
        return
      }
      setQueue((prev) => [song, ...prev.filter((s) => s._id !== song._id)])
    },
    [currentSong, playSong],
  )

  const addTrackToQueue = useCallback(
    (song: Song) => {
      if (!currentSong) {
        playSong(song)
        return
      }
      setQueue((prev) => (prev.some((s) => s._id === song._id) ? prev : [...prev, song]))
    },
    [currentSong, playSong],
  )

  const playNext = useCallback(() => {
    if (isExternalNowPlaying) return
    if (!currentSong) return

    if (repeatMode === 'one') {
      const audio = audioRef.current
      if (!audio) return
      shouldBePlayingRef.current = true
      currentTimeRef.current = 0
      try {
        audio.currentTime = 0
      } catch (err) {
        void err
      }
      audio.play().catch(() => {})
      return
    }

    if (queue.length > 0) {
      const [next, ...rest] = queue
      setQueueHistory((prev) => (currentSong ? [...prev, currentSong] : prev))
      setQueue(rest)
      startPlayback(next)
      return
    }

    if (repeatMode === 'all') {
      const replay = [...queueHistory, ...(currentSong ? [currentSong] : [])]
      if (replay.length === 0) return
      const [next, ...rest] = replay
      setQueueHistory([])
      setQueue(isShuffleOn ? shuffleSongs(rest) : rest)
      startPlayback(next)
      return
    }

    shouldBePlayingRef.current = false
    audioRef.current?.pause()
  }, [currentSong, isExternalNowPlaying, isShuffleOn, queue, queueHistory, repeatMode, shuffleSongs, startPlayback])

  const playPrevious = useCallback(() => {
    if (isExternalNowPlaying) return
    if (!currentSong) return

    if (queueHistory.length > 0) {
      const previous = queueHistory[queueHistory.length - 1]
      setQueueHistory(queueHistory.slice(0, -1))
      setQueue(currentSong ? [currentSong, ...queue] : queue)
      startPlayback(previous)
      return
    }

    if (songs.length === 0) return
    const currentIndex = songs.findIndex((s) => s._id === currentSong._id)
    if (currentIndex <= 0) return
    const previous = songs[currentIndex - 1]
    setQueueHistory([])
    setQueue(buildUpcoming(previous, songs))
    startPlayback(previous)
  }, [buildUpcoming, currentSong, isExternalNowPlaying, queue, queueHistory, songs, startPlayback])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return

    audio.volume = volume

    const resetStallStateIfNeeded = () => {
      const songId = currentSong ? currentSong._id : null
      if (stallRetryRef.current.songId === songId) return
      stallRetryRef.current.songId = songId
      stallRetryRef.current.attempts = 0
      stallRetryRef.current.lastAttemptAt = 0
    }

    const clearStallTimer = () => {
      if (stallTimerRef.current === null) return
      window.clearTimeout(stallTimerRef.current)
      stallTimerRef.current = null
    }

    const attemptResume = () => {
      if (isExternalNowPlayingRef.current) return
      if (!shouldBePlayingRef.current) return
      if (!currentSong?._id) return
      if (audio.seeking) return
      resetStallStateIfNeeded()

      const state = stallRetryRef.current
      const now = performance.now()
      if (state.attempts >= 3 && now - state.lastAttemptAt < 30000) return
      if (now - state.lastAttemptAt < 1200) return

      state.attempts += 1
      state.lastAttemptAt = now

      const playNow = () => {
        audio.play().catch(() => {})
      }

      if (state.attempts === 1) {
        playNow()
        return
      }

      if (audio.seekable && audio.seekable.length > 0) {
        const resumeTime = Math.max(0, audio.currentTime - 0.35)
        try {
          audio.currentTime = resumeTime
        } catch (err) {
          void err
        }
      }

      playNow()
    }

    const updateTime = () => {
      currentTimeRef.current = audio.currentTime
      const session = typeof navigator !== 'undefined' && 'mediaSession' in navigator ? navigator.mediaSession : null
      if (session && Number.isFinite(audio.duration) && audio.duration > 0) {
        const now = performance.now()
        if (now - lastPositionStateAtRef.current >= 1000) {
          lastPositionStateAtRef.current = now
          try {
            session.setPositionState({
              duration: audio.duration,
              playbackRate: audio.playbackRate,
              position: audio.currentTime,
            })
          } catch (err) {
            void err
          }
        }
      }
      if (isExternalNowPlayingRef.current) return
      const profile = STREAM_PROFILES[streamModeRef.current]
      const nextSong = getNextSong()
      if (!nextSong) return
      if (prefetchedNextSongIdRef.current === nextSong._id) return
      if (!profile.prefetchNextAudio) return
      if (!Number.isFinite(audio.duration) || audio.duration <= 0) return
      const remaining = audio.duration - audio.currentTime
      if (remaining > profile.prefetchWhenRemainingSec) return
      const prefetchAudio = prefetchAudioRef.current
      if (!prefetchAudio) return
      prefetchedNextSongIdRef.current = nextSong._id
      prefetchAudio.preload = 'auto'
      prefetchAudio.src = api.streamTrack(nextSong._id)
      prefetchAudio.load()
    }
    const handleEnded = () => {
      setIsPlaying(false)
      if (isExternalNowPlayingRef.current) {
        shouldBePlayingRef.current = false
        return
      }
      if (shouldBePlayingRef.current) playNext()
    }
    const handlePlay = () => {
      shouldBePlayingRef.current = true
      setIsPlaying(true)
      clearStallTimer()
      resetStallStateIfNeeded()
    }
    const handlePause = () => {
      setIsPlaying(false)
      if (isExternalNowPlayingRef.current) return
      if (!shouldBePlayingRef.current) return
      if (audio.seeking) return
      clearStallTimer()
      stallTimerRef.current = window.setTimeout(() => {
        stallTimerRef.current = null
        attemptResume()
      }, 520)
    }
    const handleWaiting = () => {
      if (isExternalNowPlayingRef.current) return
      if (!shouldBePlayingRef.current) return
      if (audio.seeking) return
      clearStallTimer()
      stallTimerRef.current = window.setTimeout(() => {
        stallTimerRef.current = null
        attemptResume()
      }, 900)
    }
    const handleStalled = () => {
      if (isExternalNowPlayingRef.current) return
      if (!shouldBePlayingRef.current) return
      if (audio.seeking) return
      clearStallTimer()
      stallTimerRef.current = window.setTimeout(() => {
        stallTimerRef.current = null
        attemptResume()
      }, 900)
    }

    const handleSeeking = () => {
      if (isExternalNowPlayingRef.current) return
      const song = currentSong
      if (!song) return
      const now = performance.now()
      const seekTo = Math.max(0, audio.currentTime || 0)
      const wasPlaying = shouldBePlayingRef.current && !audio.paused
      if (streamModeRef.current === 'saver') {
        if (now - saverSeekReloadAtRef.current < 360) return
        saverSeekReloadAtRef.current = now

        const streamUrl = resolveUrl(api.streamTrack(song._id))
        const nextSrc = `${streamUrl}${streamUrl.includes('?') ? '&' : '?'}saver_seek=${encodeURIComponent(
          String(Math.floor(seekTo * 1000)),
        )}&ts=${encodeURIComponent(String(Date.now()))}`

        const resume = () => {
          audio.removeEventListener('loadedmetadata', resume)
          audio.removeEventListener('canplay', resume)
          if (currentSongIdRef.current !== song._id) return
          try {
            audio.currentTime = seekTo
          } catch (err) {
            void err
          }
          if (wasPlaying) audio.play().catch(() => {})
        }

        audio.addEventListener('loadedmetadata', resume)
        audio.addEventListener('canplay', resume)
        audio.src = nextSrc
        audio.load()
        return
      }

      if (streamModeRef.current !== 'aggressive') return

      const blobUrl = aggressiveObjectUrlRef.current
      if (!blobUrl) return
      if (aggressiveObjectUrlSongIdRef.current !== song._id) return
      if (audio.src === blobUrl) return
      if (now - aggressiveSeekSwitchAtRef.current < 360) return
      aggressiveSeekSwitchAtRef.current = now

      const resume = () => {
        audio.removeEventListener('loadedmetadata', resume)
        audio.removeEventListener('canplay', resume)
        if (currentSongIdRef.current !== song._id) return
        try {
          audio.currentTime = seekTo
        } catch (err) {
          void err
        }
        if (wasPlaying) audio.play().catch(() => {})
      }

      audio.addEventListener('loadedmetadata', resume)
      audio.addEventListener('canplay', resume)
      audio.src = blobUrl
      audio.load()
    }
    
    audio.addEventListener('timeupdate', updateTime)
    audio.addEventListener('ended', handleEnded)
    audio.addEventListener('play', handlePlay)
    audio.addEventListener('pause', handlePause)
    audio.addEventListener('waiting', handleWaiting)
    audio.addEventListener('stalled', handleStalled)
    audio.addEventListener('seeking', handleSeeking)
    
    return () => {
      clearStallTimer()
      audio.removeEventListener('timeupdate', updateTime)
      audio.removeEventListener('ended', handleEnded)
      audio.removeEventListener('play', handlePlay)
      audio.removeEventListener('pause', handlePause)
      audio.removeEventListener('waiting', handleWaiting)
      audio.removeEventListener('stalled', handleStalled)
      audio.removeEventListener('seeking', handleSeeking)
    }
  }, [currentSong, getNextSong, playNext, volume, streamMode, queue, resolveUrl])

  useEffect(() => {
    const audio = new Audio()
    audio.preload = 'auto'
    audio.muted = true
    ;(audio as unknown as { setAttribute?: (k: string, v: string) => void }).setAttribute?.('playsinline', 'true')
    prefetchAudioRef.current = audio
    return () => {
      prefetchedNextSongIdRef.current = null
      audio.src = ''
      prefetchAudioRef.current = null
    }
  }, [])

  useEffect(() => {
    currentSongIdRef.current = currentSongId
  }, [currentSongId])

  useEffect(() => {
    streamModeRef.current = streamMode
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(STREAM_MODE_STORAGE_KEY, streamMode)
    } catch {
      void 0
    }
  }, [streamMode])

  useEffect(() => {
    if (streamMode === 'aggressive') return
    aggressiveFetchAbortRef.current?.abort()
    aggressiveFetchAbortRef.current = null
    revokeAggressiveObjectUrl()
  }, [streamMode, revokeAggressiveObjectUrl])

  useEffect(() => {
    if (STREAM_PROFILES[streamMode].prefetchNextAudio) return
    prefetchedNextSongIdRef.current = null
    const prefetchAudio = prefetchAudioRef.current
    if (prefetchAudio) prefetchAudio.src = ''
  }, [streamMode])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    audio.preload = STREAM_PROFILES[streamMode].audioPreload
  }, [streamMode])

  useEffect(() => {
    if (!STREAM_PROFILES[streamMode].eagerFetchFull) return
    eagerFetchCurrentTrack()
  }, [streamMode, eagerFetchCurrentTrack, currentSongId])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return

    const handleError = () => {
      if (isExternalNowPlayingRef.current) return
      if (audio.error?.code === 1) return
      const song = currentSong
      if (!song) return

      const cookieEnabled = getAuthCookieEnabled()
      const token = getAuthToken()
      if (cookieEnabled && token && authFallbackAttemptSongIdRef.current !== song._id) {
        authFallbackAttemptSongIdRef.current = song._id
        setAuthCookieEnabled(false)
        const nextSrc = resolveUrl(api.getStreamUrlWithToken(song._id, token))
        audio.src = nextSrc
        audio.currentTime = 0
        audio.load()
        audio.play().catch(() => {})
        return
      }

      if (fallbackAttemptSongIdRef.current === song._id) return

      const mime = inferPlaybackMime(song)
      if (!mime) return
      if (mime === 'audio/flac') return
      if (audio.canPlayType(mime) === '') return

      fallbackAttemptSongIdRef.current = song._id
      fallbackAbortRef.current?.abort()
      const controller = new AbortController()
      fallbackAbortRef.current = controller

      fetch(api.streamTrack(song._id), { signal: controller.signal })
        .then(async (response) => {
          if (!response.ok) throw new Error(String(response.status))
          const buffer = await response.arrayBuffer()
          if (controller.signal.aborted) return
          const blobUrl = URL.createObjectURL(new Blob([buffer], { type: mime }))
          revokeObjectUrl()
          objectUrlRef.current = blobUrl
          audio.src = blobUrl
          audio.currentTime = 0
          audio.load()
          audio.play().catch(() => {})
        })
        .catch(() => {})
    }

    audio.addEventListener('error', handleError)
    return () => {
      audio.removeEventListener('error', handleError)
    }
  }, [currentSong, inferPlaybackMime, revokeObjectUrl, resolveUrl])

  useEffect(() => {
    return () => {
      fallbackAbortRef.current?.abort()
      fallbackAbortRef.current = null
      aggressiveFetchAbortRef.current?.abort()
      aggressiveFetchAbortRef.current = null
      revokeObjectUrl()
      revokeAggressiveObjectUrl()
    }
  }, [revokeAggressiveObjectUrl, revokeObjectUrl])

  useEffect(() => {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
    const session = navigator.mediaSession

    if (!currentSong) {
      session.metadata = null
      return
    }

    let cancelled = false

    const setMetadata = (coverUrl?: string) => {
      session.metadata = new MediaMetadata({
        title: currentSong.title,
        artist: currentSong.artist,
        album: currentSong.album || '',
        artwork: coverUrl
          ? [
              { src: coverUrl, sizes: '96x96', type: 'image/jpeg' },
              { src: coverUrl, sizes: '128x128', type: 'image/jpeg' },
              { src: coverUrl, sizes: '192x192', type: 'image/jpeg' },
              { src: coverUrl, sizes: '256x256', type: 'image/jpeg' },
              { src: coverUrl, sizes: '384x384', type: 'image/jpeg' },
              { src: coverUrl, sizes: '512x512', type: 'image/jpeg' },
            ]
          : undefined,
      })
    }

    const fallbackCoverUrl = currentSong.cover_url || undefined
    setMetadata(fallbackCoverUrl)

    api
      .getSpotifyCoverUrl(currentSong._id, { title: currentSong.title, artist: currentSong.artist })
      .then((cover) => {
        if (cancelled) return
        if (!cover || cover === fallbackCoverUrl) return
        setMetadata(cover)
      })
      .catch(() => {})

    session.playbackState = isPlaying ? 'playing' : 'paused'

    try {
      session.setActionHandler('play', () => {
        const audio = audioRef.current
        if (!audio) return
        shouldBePlayingRef.current = true
        audio.play().catch(() => {})
      })
      session.setActionHandler('pause', () => {
        shouldBePlayingRef.current = false
        audioRef.current?.pause()
      })
      session.setActionHandler('previoustrack', () => {
        if (isExternalNowPlayingRef.current) return
        playPrevious()
      })
      session.setActionHandler('nexttrack', () => {
        if (isExternalNowPlayingRef.current) return
        playNext()
      })
    } catch (err) {
      void err
    }

    return () => {
      cancelled = true
    }
  }, [audioRef, currentSong, isPlaying, playNext, playPrevious])

  const togglePlay = useCallback(() => {
    if (isExternalNowPlayingRef.current) {
      const externalToggle = externalTogglePlayRef.current
      if (externalToggle) {
        void externalToggle()
        return
      }
    }

    const audio = audioRef.current
    if (!audio) return

    if (isPlaying) {
      shouldBePlayingRef.current = false
      audio.pause()
    } else {
      shouldBePlayingRef.current = true
      void unlockAudioOnce().finally(() => {
        audio.play().catch(() => {})
      })
    }
  }, [isPlaying])

  const playbackValue = useMemo<PlayerPlaybackContextType>(
    () => ({
      currentSong,
      isPlaying,
      isExternalNowPlaying,
      externalJamId,
      externalCanEditQueue,
      volume,
      audioRef,
      favouriteIds,
      favouritesTotal,
      favouritesLastUpdatedAtMs,
      refreshFavourites,
      toggleFavourite,
      setExternalNowPlaying,
      externalUpcoming,
      setExternalUpcoming,
      setExternalJamContext,
      setExternalTogglePlay,
      playSong,
      playSongFromList,
      playFromQueue,
      playNextTrack,
      addTrackToQueue,
      togglePlay,
      playPrevious,
      playNext,
      setVolume,
      queueHistory,
      queue,
      setQueue,
      history: queueHistory,
      setHistory: setQueueHistory,
      upcoming: queue,
      setUpcoming: setQueue,
      isShuffleOn,
      setIsShuffleOn,
      repeatMode,
      setRepeatMode,
      streamMode,
      setStreamMode,
      eagerFetchCurrentTrack,
      isSyncLyricsOn,
      setIsSyncLyricsOn,
    }),
    [
      addTrackToQueue,
      currentSong,
      externalCanEditQueue,
      externalJamId,
      externalUpcoming,
      eagerFetchCurrentTrack,
      favouriteIds,
      favouritesLastUpdatedAtMs,
      favouritesTotal,
      isExternalNowPlaying,
      isPlaying,
      isShuffleOn,
      playFromQueue,
      playNext,
      playNextTrack,
      playPrevious,
      playSong,
      playSongFromList,
      queue,
      queueHistory,
      refreshFavourites,
      repeatMode,
      setIsShuffleOn,
      setExternalNowPlaying,
      setExternalJamContext,
      setExternalTogglePlay,
      setExternalUpcoming,
      togglePlay,
      toggleFavourite,
      volume,
      streamMode,
      setStreamMode,
      isSyncLyricsOn,
      setIsSyncLyricsOn,
    ],
  )

  const libraryValue = useMemo<PlayerLibraryContextType>(() => ({ songs, setSongs }), [songs])

  return (
    <PlayerLibraryContext.Provider value={libraryValue}>
      <PlayerPlaybackContext.Provider value={playbackValue}>{children}</PlayerPlaybackContext.Provider>
    </PlayerLibraryContext.Provider>
  )
}

export const usePlayerPlayback = () => {
  const context = useContext(PlayerPlaybackContext)
  if (!context) throw new Error('usePlayerPlayback must be used within PlayerProvider')
  return context
}

export const usePlayerLibrary = () => {
  const context = useContext(PlayerLibraryContext)
  if (!context) throw new Error('usePlayerLibrary must be used within PlayerProvider')
  return context
}

export const usePlayer = () => {
  return { ...usePlayerLibrary(), ...usePlayerPlayback() }
}
