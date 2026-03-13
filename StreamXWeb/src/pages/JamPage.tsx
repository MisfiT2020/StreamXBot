import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { useJam, type Jam } from '../hooks/useJam.js'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { API_BASE_URL, getAuthToken, getAuthUserInfo } from '../services/api.js'
import { jamPlay, jamPause, jamNext, jamEnd, joinJam, jamQueueReorder, jamLeave } from '../services/jamApi.js'
import { api } from '../services/api.js'
import type { Song, TrackDetailsResponse } from '../types/index.js'
import { platform } from '../platform.js'
import pauseIconSvg from '../assets/pause.svg?raw'
import resumeIconSvg from '../assets/resume.svg?raw'
import forwardIconSvg from '../assets/forward.svg?raw'
import {
  DndContext,
  PointerSensor,
  TouchSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import { restrictToFirstScrollableAncestor, restrictToVerticalAxis } from '@dnd-kit/modifiers'
import { SortableContext, arrayMove, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import './JamPage.css'

const DRIFT_THRESHOLD = 0.75
const JAM_ACTIVE_KEY = 'streamw:jam:activeId'
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

const toSongFromDetails = (details: TrackDetailsResponse): Song => {
  const audio = details.audio ?? {}
  const title = (audio.title ?? '').trim() || 'Unknown title'
  const artist = (audio.artist ?? '').trim() || 'Unknown artist'
  const album = audio.album ?? null
  const duration_sec = typeof audio.duration_sec === 'number' && Number.isFinite(audio.duration_sec) ? audio.duration_sec : 0
  const cover_url = details.spotify?.cover_url ?? null
  const spotify_url = details.spotify?.url ?? null
  const type = typeof audio.type === 'string' ? audio.type : ''
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

const RawSvgIcon = ({ svg }: { svg: string }) => (
  <span aria-hidden="true" dangerouslySetInnerHTML={{ __html: svg }} />
)

const fallbackCover = 'https://via.placeholder.com/48'

const SortableJamQueueItem = ({
  track,
  disabled,
}: {
  track: Song
  disabled: boolean
}) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: track._id,
    disabled,
    transition: {
      duration: 350,
      easing: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
    },
  })

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition: isDragging ? 'none' : transition,
    touchAction: isDragging ? 'none' : 'pan-y',
  }

  return (
    <div ref={setNodeRef} className="jam-queue-item" role="listitem" data-dragging={isDragging ? 'true' : 'false'} style={style}>
      <img src={track.cover_url || fallbackCover} alt="" className="jam-queue-cover" aria-hidden="true" />
      <div className="jam-queue-info">
        <div className="jam-queue-track-title">{track.title}</div>
        <div className="jam-queue-track-artist">{track.artist}</div>
      </div>
      <div className="jam-queue-grip" {...attributes} {...listeners} data-disabled={disabled ? 'true' : 'false'} aria-hidden="true" />
    </div>
  )
}

export const JamPage = () => {
  const { jamId } = useParams<{ jamId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const { audioRef, setExternalNowPlaying, setQueue, setHistory, setExternalUpcoming, setExternalJamContext, setExternalTogglePlay, playSong } =
    usePlayerPlayback()
  const authToken = getAuthToken()
  const authUserInfo = getAuthUserInfo()
  const userId = authUserInfo?.user_id || null

  const lastSeekRef = useRef<number>(0) // seconds since epoch (Date.now()/1000)
  const joinAttemptedRef = useRef(false)
  const prefetchAudioRef = useRef<HTMLAudioElement | null>(null)
  const prefetchedForTrackIdRef = useRef<string | null>(null)
  const autoNextForTrackIdRef = useRef<string | null>(null)
  const [currentTrack, setCurrentTrack] = useState<Song | null>(null)
  const [queueTracks, setQueueTracks] = useState<Song[]>([])
  const [showInvite, setShowInvite] = useState(false)
  const [showCopiedToast, setShowCopiedToast] = useState(false)
  const copiedToastTimeoutRef = useRef<number | null>(null)
  const [progress, setProgress] = useState(0)
  const [autoplayBlocked, setAutoplayBlocked] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [isSeekingProgress, setIsSeekingProgress] = useState(false)
  const [progressDraft, setProgressDraft] = useState<number | null>(null)
  const wasPlayingBeforeSeekRef = useRef(false)
  const progressGestureRef = useRef<{ pointerId: number | null; startX: number; startY: number }>({ pointerId: null, startX: 0, startY: 0 })
  const isTelegram = platform.isTelegram
  const [joinedJam, setJoinedJam] = useState<Jam | null>(null)
  const [listenerHoldPaused, setListenerHoldPaused] = useState(false)
  const listenerHoldPausedRef = useRef(false)
  const [audioPaused, setAudioPaused] = useState(true)
  const [hostUiIsPlaying, setHostUiIsPlaying] = useState<boolean | null>(null)
  const [showListeners, setShowListeners] = useState(false)
  const [activeQueueId, setActiveQueueId] = useState<string | null>(null)

  useEffect(() => {
    listenerHoldPausedRef.current = listenerHoldPaused
  }, [listenerHoldPaused])

  useEffect(() => {
    if (typeof window === 'undefined') return
    if (!jamId) return
    try {
      window.localStorage.setItem(JAM_ACTIVE_KEY, jamId)
    } catch {
      void 0
    }
  }, [jamId])

  useEffect(() => {
    if (!jamId) return
    setQueue([])
    setHistory([])
  }, [jamId, setQueue, setHistory])

  useEffect(() => {
    if (!currentTrack) return
    setExternalNowPlaying(currentTrack)
  }, [currentTrack, setExternalNowPlaying])

  useEffect(() => {
    setExternalUpcoming(queueTracks)
  }, [queueTracks, setExternalUpcoming])

  useEffect(() => {
    if (prefetchAudioRef.current) return
    const audio = new Audio()
    audio.preload = 'auto'
    prefetchAudioRef.current = audio
  }, [])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return

    const sync = () => setAudioPaused(audio.paused)
    sync()

    audio.addEventListener('play', sync)
    audio.addEventListener('pause', sync)
    audio.addEventListener('ended', sync)

    return () => {
      audio.removeEventListener('play', sync)
      audio.removeEventListener('pause', sync)
      audio.removeEventListener('ended', sync)
    }
  }, [audioRef])

  // Helper to compute jam position
  const computeJamPosition = useCallback((pb: Jam['playback']) => {
    const nowSec = Date.now() / 1000
    let jamTime = pb.position_sec || 0
    if (pb.is_playing && pb.started_at) {
      jamTime += Math.max(0, nowSec - pb.started_at)
    }
    return Math.max(0, jamTime)
  }, [])

  const normalizePhotoUrl = useCallback((url: string | null | undefined) => {
    const trimmed = (url ?? '').trim()
    return trimmed.replace(/^`+|`+$/g, '').trim()
  }, [])

  // Sync audio to jam state
  const syncAudioToJam = useCallback((updatedJam: Jam | null) => {
    if (!updatedJam || !audioRef.current) return
    const audio = audioRef.current
    const pb = updatedJam.playback
    const nowSec = Date.now() / 1000
    const isHostForUpdatedJam = Boolean(userId != null && updatedJam.host_user_id === userId)
    const canAutoPlay = isHostForUpdatedJam || !listenerHoldPausedRef.current

    const clampToDuration = (t: number) => {
      const dur = isFinite(audio.duration) ? audio.duration : 0
      if (dur > 0) return Math.min(Math.max(0, t), Math.max(0, dur - 0.1))
      return Math.max(0, t)
    }

    // Ignore very recent local seeks to avoid feedback loops
    if (nowSec - lastSeekRef.current < 0.9) {
      // still update play/pause state but avoid aggressive seeking
      const jamTime = clampToDuration(computeJamPosition(pb))
      const local = audio.currentTime || 0
      const diff = Math.abs(local - jamTime)
      if (pb.is_playing && diff > 2.0) {
        // large drift still fix
        try {
          audio.currentTime = jamTime
          lastSeekRef.current = Date.now() / 1000
        } catch {
          void 0
        }
      }
    }

    // compute server position
    const jamTime = clampToDuration(computeJamPosition(pb))

    // detect track change using dataset.trackId (reliable)
    const currentDatasetTrack = audio.dataset.trackId || ''
    if (currentDatasetTrack !== (pb.track_id || '')) {
      // new track -> replace src and preload
      const streamUrl = api.getStreamUrl(pb.track_id)
      audio.pause()
      audio.autoplay = false
      audio.removeAttribute('src')
      audio.dataset.trackId = pb.track_id || ''
      audio.src = streamUrl
      audio.preload = 'auto'
      // ensure we reset metadata listeners (use once)
      const onLoadedMetadata = () => {
        const clampedTime = clampToDuration(jamTime)
        try {
          audio.currentTime = clampedTime
          lastSeekRef.current = Date.now() / 1000
        } catch (e) {
          console.warn('Seek during loadedmetadata failed', e)
        }

        setDuration(isFinite(audio.duration) ? audio.duration : 0)
        setCurrentTime(audio.currentTime || 0)

        if (pb.is_playing && canAutoPlay) {
          // attempt play — may be blocked
          window.setTimeout(() => {
            void unlockAudioOnce().finally(() => {
              audio.play()
                .then(() => {
                  setAutoplayBlocked(false)
                })
                .catch((err) => {
                  console.warn('Autoplay blocked on load:', err)
                  setAutoplayBlocked(true)
                })
            })
          }, 50)
        } else {
          // ensure paused if jam paused
          try { audio.pause() } catch { void 0 }
        }
      }

      audio.addEventListener('loadedmetadata', onLoadedMetadata, { once: true })

      // attach error handler once (logging)
      const onError = (e: Event) => {
        console.error('Audio element error', e, audio.error)
      }
      audio.addEventListener('error', onError)

      // start loading
      try {
        audio.load()
      } catch (e) {
        console.warn('audio.load() failed', e)
      }
      return
    }

    // same track: check drift and play/pause
    const local = audio.currentTime || 0
    const diff = Math.abs(local - jamTime)

    const shouldSeek =
      pb.is_playing
        ? diff > DRIFT_THRESHOLD
        : (local === 0 && jamTime > 0.5) || diff > 10

    if (shouldSeek) {
      try {
        audio.currentTime = jamTime
        lastSeekRef.current = Date.now() / 1000
      } catch (e) {
        console.warn('Drift seek failed:', e)
      }
    }

    // Sync play/pause
    if (pb.is_playing && audio.paused && canAutoPlay) {
      void unlockAudioOnce().finally(() => {
        audio.play()
          .then(() => setAutoplayBlocked(false))
          .catch((e) => {
            console.warn('Play attempt blocked:', e)
            setAutoplayBlocked(true)
          })
      })
    } else if (!pb.is_playing && !audio.paused) {
      try { audio.pause() } catch { void 0 }
    }
  }, [audioRef, computeJamPosition, userId])

  const { jam} = useJam({
    jamId: jamId || null,
    authToken,
    onJamState: syncAudioToJam,
  })

  const initialJam = (location.state as { initialJam?: unknown } | null)?.initialJam ?? null
  const jamForUi = (jam ?? joinedJam ?? (initialJam as Jam | null)) as Jam | null

  useEffect(() => {
    setHostUiIsPlaying(null)
  }, [jamForUi?.playback.is_playing])

  useEffect(() => {
    if (!jamId || !authToken) return
    if (joinAttemptedRef.current) return
    joinAttemptedRef.current = true
    joinJam(jamId)
      .then((res) => {
        const next = (res?.jam ?? null) as Jam | null
        if (next) setJoinedJam(next)
      })
      .catch(() => { void 0 })
  }, [jamId, authToken])

  // Initial jam data fetch (fallback if WebSocket is slow) — keep but ignore result because useJam provides state
  useEffect(() => {
    if (!jamId || !authToken || jam) return
    let cancelled = false
    const fetchJam = async () => {
      try {
        const response = await fetch(`${API_BASE_URL}/jam/${jamId}`, {
          headers: {
            'Authorization': `Bearer ${authToken}`,
            'Content-Type': 'application/json',
          },
        })
        if (!response.ok) throw new Error('Failed to fetch jam')
        const data = await response.json()
        if (!cancelled) {
          console.log('Initial jam fallback data:', data)
        }
      } catch (err) {
        console.error('Failed to fetch initial jam data:', err)
      }
    }
    fetchJam()
    return () => { cancelled = true }
  }, [jamId, authToken, jam])

  const isHost = Boolean(jamForUi && userId != null && jamForUi.host_user_id === userId)
  const hostStartRequestedRef = useRef(false)
  const jamForUiRef = useRef<Jam | null>(null)
  const isHostRef = useRef(false)
  const queueKey = jamForUi?.queue?.join('\u0001') ?? ''
  const canEditQueue = Boolean(isHost || (jamForUi?.settings?.allow_queue_edit ?? false))

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 120, tolerance: 6 } }),
  )

  useEffect(() => {
    jamForUiRef.current = jamForUi
  }, [jamForUi])

  useEffect(() => {
    isHostRef.current = isHost
  }, [isHost])

  useEffect(() => {
    if (!jamForUi) {
      setExternalJamContext({ jamId: null, canEditQueue: false })
      setExternalTogglePlay(null)
      return
    }

    const canEdit = isHost || Boolean(jamForUi.settings?.allow_queue_edit)
    setExternalJamContext({ jamId: jamForUi._id, canEditQueue: canEdit })
    setExternalTogglePlay(() => {
      const audio = audioRef.current
      if (!audio) return

      if (isHostRef.current) {
        if (audio.paused) {
          void unlockAudioOnce().finally(() => {
            audio.play().catch(() => {})
          })
          return jamPlay(jamForUi._id).catch(() => {
            try {
              audio.pause()
            } catch {
              void 0
            }
          })
        }

        try {
          audio.pause()
        } catch {
          void 0
        }
        return jamPause(jamForUi._id).catch(() => {
          void unlockAudioOnce().finally(() => {
            audio.play().catch(() => {})
          })
        })
      }

      if (audio.paused) {
        listenerHoldPausedRef.current = false
        void unlockAudioOnce().finally(() => {
          audio.play().catch(() => {})
        })
        return
      }

      listenerHoldPausedRef.current = true
      try {
        audio.pause()
      } catch {
        void 0
      }
    })
  }, [audioRef, isHost, jamForUi, setExternalJamContext, setExternalTogglePlay])

  const advanceToNextTrack = useCallback(async (state: Jam) => {
    try {
      const nextTrack = await jamNext(state._id)
      const audio = audioRef.current
      if (!audio) return
      const nextTrackId = nextTrack?.track_id ?? state.playback.track_id
      window.setTimeout(() => {
        syncAudioToJam({
          ...state,
          playback: {
            ...state.playback,
            track_id: nextTrackId,
            position_sec: 0,
            started_at: Date.now() / 1000,
            is_playing: true,
          },
        } as Jam)
      }, 400)
    } catch (err) {
      console.error('Failed to auto-advance:', err)
    }
  }, [audioRef, syncAudioToJam])

  useEffect(() => {
    if (!jamForUi || !isHost) return
    if (hostStartRequestedRef.current) return
    hostStartRequestedRef.current = true
    if (jamForUi.playback.is_playing) return
    jamPlay(jamForUi._id).catch(() => { void 0 })
  }, [jamForUi, isHost])

  // Trigger initial audio load when jam first becomes available
  useEffect(() => {
    if (jamForUi && audioRef.current) {
      syncAudioToJam(jamForUi)
    }
  }, [audioRef, jamForUi, syncAudioToJam])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return

    const onEnded = () => {
      const state = jamForUiRef.current
      if (!state) return
      if (!isHostRef.current) return
      if (!state.playback.is_playing) return
      if (state.queue && state.queue.length > 0) {
        if (autoNextForTrackIdRef.current === state.playback.track_id) return
        autoNextForTrackIdRef.current = state.playback.track_id
        advanceToNextTrack(state).catch(() => { void 0 })
        return
      }

      setHostUiIsPlaying(false)
      try {
        audio.pause()
        setAutoplayBlocked(false)
      } catch {
        void 0
      }
      jamPause(state._id).catch(() => { void 0 })
    }

    audio.addEventListener('ended', onEnded)
    return () => {
      audio.removeEventListener('ended', onEnded)
    }
  }, [advanceToNextTrack, audioRef])

  // Progress & time updater (using requestAnimationFrame for smooth 60fps updates)
  useEffect(() => {
    let rafId: number | null = null
    let lastPrefetchCheck = 0
    let lastAutoNextCheck = 0

    const updateProgress = () => {
      const audio = audioRef.current
      if (!audio) {
        rafId = requestAnimationFrame(updateProgress)
        return
      }

      const dur = isFinite(audio.duration) ? audio.duration : 0
      const cur = audio.currentTime || 0
      
      // Don't update time/progress while seeking
      if (!isSeekingProgress) {
        setDuration(dur)
        setCurrentTime(cur)
        const currentProgress = dur > 0 ? (cur / dur) * 100 : 0
        setProgress(currentProgress)
      }

      const now = performance.now()
      const state = jamForUiRef.current

      // Prefetch and auto-next logic (throttled to ~200ms)
      if (state && isHostRef.current && state.playback.is_playing && Number.isFinite(dur) && dur > 0) {
        const nextId = state.queue?.[0] ?? null
        const remaining = dur - cur

        // Prefetch check (throttled)
        if (now - lastPrefetchCheck > 200 && nextId && remaining <= 15 && prefetchedForTrackIdRef.current !== state.playback.track_id) {
          lastPrefetchCheck = now
          prefetchedForTrackIdRef.current = state.playback.track_id
          api.warmTrack(nextId).catch(() => { void 0 })
          const prefetchAudio = prefetchAudioRef.current
          if (prefetchAudio) {
            prefetchAudio.src = api.getStreamUrl(nextId)
            try {
              prefetchAudio.load()
            } catch {
              void 0
            }
          }
        }

        // Auto-next check (throttled)
        if (now - lastAutoNextCheck > 200 && remaining <= 0.4) {
          lastAutoNextCheck = now
          if (autoNextForTrackIdRef.current !== state.playback.track_id) {
            autoNextForTrackIdRef.current = state.playback.track_id
            advanceToNextTrack(state).catch(() => { void 0 })
          }
        }
      }

      rafId = requestAnimationFrame(updateProgress)
    }

    rafId = requestAnimationFrame(updateProgress)

    return () => {
      if (rafId !== null) {
        cancelAnimationFrame(rafId)
      }
    }
  }, [advanceToNextTrack, audioRef, isSeekingProgress])

  // Periodic drift correction (every 3s)
  useEffect(() => {
    if (!jamForUi) return
    const interval = setInterval(() => {
      const audio = audioRef.current
      if (!audio || !jamForUi) return
      const pb = jamForUi.playback
      const expectedPos = computeJamPosition(pb)
      const actualPos = audio.currentTime || 0
      const drift = Math.abs(actualPos - expectedPos)
      if (drift > 0.35 && pb.is_playing) {
        try {
          audio.currentTime = expectedPos
          lastSeekRef.current = Date.now() / 1000
        } catch (e) {
          console.warn('Drift correction failed:', e)
        }
      }
    }, 3000)
    return () => clearInterval(interval)
  }, [audioRef, jamForUi, computeJamPosition])

  // Load track details and queue details
  useEffect(() => {
    if (!jamForUi) return
    let cancelled = false
    const loadTrack = async () => {
      try {
        const track = await api.getTrackDetails(jamForUi.playback.track_id)
        if (cancelled) return
        setCurrentTrack(toSongFromDetails(track))
      } catch (err) {
        console.error('Failed to load track:', err)
      }
    }
    const loadQueue = async () => {
      if (!jamForUi.queue || jamForUi.queue.length === 0) {
        setQueueTracks([])
        return
      }
      try {
        const tracks = await Promise.all(
          jamForUi.queue.map(async (id) => {
            try {
              const details = await api.getTrackDetails(id)
              return toSongFromDetails(details)
            } catch {
              return {
                _id: id,
                title: 'Unknown title',
                artist: 'Unknown artist',
                album: null,
                duration_sec: 0,
                cover_url: null,
                spotify_url: null,
                spotify: undefined,
                source_chat_id: 0,
                source_message_id: 0,
                type: '',
                sampling_rate_hz: 0,
                updated_at: Date.now(),
              } satisfies Song
            }
          }),
        )
        if (cancelled) return
        setQueueTracks(tracks)
      } catch (err) {
        console.error('Failed to load queue:', err)
      }
    }
    loadTrack()
    loadQueue()
    return () => { cancelled = true }
  }, [jamForUi, queueKey])

  const queueItemIds = useMemo(() => queueTracks.map((t) => t._id), [queueTracks])

  const onQueueDragEnd = useCallback(async (event: DragEndEvent) => {
    const { active, over } = event
    setActiveQueueId(null)
    if (!over || active.id === over.id) return
    if (!jamForUi) return
    if (!canEditQueue) return

    const oldIndex = queueTracks.findIndex((t) => t._id === active.id)
    const newIndex = queueTracks.findIndex((t) => t._id === over.id)
    if (oldIndex < 0 || newIndex < 0) return

    const previous = queueTracks
    const next = arrayMove(queueTracks, oldIndex, newIndex)
    setQueueTracks(next)

    try {
      await jamQueueReorder(jamForUi._id, next.map((t) => t._id))
    } catch (err) {
      setQueueTracks(previous)
      console.error('Failed to reorder queue:', err)
    }
  }, [canEditQueue, jamForUi, queueTracks])

  // Host control handlers
  const handlePlayPause = useCallback(async () => {
    if (!jamForUi || !isHost) return
    const audio = audioRef.current
    const currentIsPlaying = hostUiIsPlaying ?? jamForUi.playback.is_playing
    const nextIsPlaying = !currentIsPlaying
    setHostUiIsPlaying(nextIsPlaying)

    if (!nextIsPlaying) {
      if (audio) {
        try {
          audio.pause()
          setAutoplayBlocked(false)
        } catch {
          void 0
        }
      }
    } else {
      if (audio) {
        try {
          void unlockAudioOnce().finally(() => {
            audio.play()
              .then(() => setAutoplayBlocked(false))
              .catch(() => setAutoplayBlocked(true))
          })
        } catch {
          void 0
        }
      }
    }

    try {
      if (currentIsPlaying) {
        await jamPause(jamForUi._id)
      } else {
        await jamPlay(jamForUi._id)
      }
    } catch (err) {
      setHostUiIsPlaying(null)
      if (audio) {
        try {
          if (currentIsPlaying) {
            void unlockAudioOnce().finally(() => {
              audio.play().catch(() => { void 0 })
            })
          } else {
            audio.pause()
          }
        } catch {
          void 0
        }
      }
      console.error('Failed to toggle play/pause:', err)
    }
  }, [audioRef, jamForUi, isHost, hostUiIsPlaying])

  const handleListenerPlayPause = useCallback(async () => {
    if (!jamForUi || isHost) return
    if (!audioRef.current) return
    if (!jamForUi.playback.is_playing) return

    const audio = audioRef.current

    if (listenerHoldPausedRef.current || audio.paused) {
      listenerHoldPausedRef.current = false
      setListenerHoldPaused(false)
      syncAudioToJam(jamForUi)
      try {
        await unlockAudioOnce().then(() => audio.play())
        setAutoplayBlocked(false)
      } catch {
        setAutoplayBlocked(true)
      }
    } else {
      try {
        audio.pause()
        setAutoplayBlocked(false)
        listenerHoldPausedRef.current = true
        setListenerHoldPaused(true)
      } catch {
        void 0
      }
    }
  }, [audioRef, jamForUi, isHost, syncAudioToJam])

  const handleNext = useCallback(async () => {
    if (!jamForUi || !isHost) return
    try {
      const nextTrack = await jamNext(jamForUi._id)
      // optimistic: play next locally (backend will broadcast jam_state)
      const audio = audioRef.current
      if (audio) {
        try {
          // small delay to allow server to update and stream to be ready
          setTimeout(() => {
            syncAudioToJam({
              ...jamForUi,
              playback: {
                ...jamForUi.playback,
                track_id: nextTrack?.track_id ?? jamForUi.playback.track_id,
                position_sec: 0,
                started_at: Date.now() / 1000,
                is_playing: true,
              },
            } as Jam)
          }, 400)
        } catch (e) {
          console.warn(e)
        }
      }
    } catch (err) {
      console.error('Failed to skip to next:', err)
    }
  }, [audioRef, jamForUi, isHost, syncAudioToJam])

  const handleSeek = useCallback(async (positionSec: number) => {
    if (!jamForUi) return
    if (!isHost && !jamForUi.settings.allow_seek) {
      console.log('Seek not allowed for listeners')
      return
    }
    lastSeekRef.current = Date.now() / 1000
    // optimistic local seek when host (reduces perceived latency)
    const audio = audioRef.current
    if (audio) {
      try {
        audio.currentTime = positionSec
      } catch (e) {
        console.warn('Local seek failed (optimistic):', e)
      }
    }
    try {
      await fetch(`${API_BASE_URL}/jam/${jamForUi._id}/seek`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${authToken}`,
        },
        body: JSON.stringify({ position_sec: positionSec }),
      })
      console.log('Seek successful to:', positionSec)
    } catch (err) {
      console.error('Failed to seek:', err)
    }
  }, [audioRef, jamForUi, isHost, authToken])

  const clamp = useCallback((value: number, min: number, max: number) => {
    return Math.min(Math.max(value, min), max)
  }, [])

  const getPercentFromPointerX = useCallback(
    (target: HTMLElement, clientX: number) => {
      const rect = target.getBoundingClientRect()
      if (!rect.width) return 0
      return clamp((clientX - rect.left) / rect.width, 0, 1)
    },
    [clamp],
  )

  const formatTime = useCallback((seconds: number) => {
    if (!isFinite(seconds) || seconds < 0) return '0:00'
    const mins = Math.floor(seconds / 60)
    const secs = Math.floor(seconds % 60)
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }, [])

  const handleEndJam = useCallback(async () => {
    if (!jamForUi || !isHost) return
    if (!confirm('Are you sure you want to end this jam session?')) return
    
    // First, transfer current track to normal player
    if (currentTrack && audioRef.current) {
      const audio = audioRef.current
      const isPlaying = !audio.paused
      
      try {
        if (isPlaying) {
          audio.pause()
        }
        
        // Clear jam context
        setExternalJamContext({ jamId: null, canEditQueue: false })
        setExternalTogglePlay(null)
        setExternalNowPlaying(null)
        setExternalUpcoming([])
        
        // Transfer to player
        playSong(currentTrack)
      } catch (err) {
        console.error('Failed to transfer to player:', err)
      }
    }
    
    // Then end the jam
    try {
      await jamEnd(jamForUi._id)
      try {
        const current = window.localStorage.getItem(JAM_ACTIVE_KEY)
        if (current === jamForUi._id) window.localStorage.removeItem(JAM_ACTIVE_KEY)
      } catch {
        void 0
      }
      navigate('/')
    } catch (err) {
      console.error('Failed to end jam:', err)
    }
  }, [jamForUi, isHost, currentTrack, audioRef, setExternalJamContext, setExternalTogglePlay, setExternalNowPlaying, setExternalUpcoming, playSong, navigate])

  const handleCopyLink = useCallback(() => {
    const link = `${window.location.origin}/jam/${jamId}`
    navigator.clipboard.writeText(link).then(() => {
      setShowInvite(false)
      setShowCopiedToast(true)
      if (copiedToastTimeoutRef.current != null) {
        window.clearTimeout(copiedToastTimeoutRef.current)
      }
      copiedToastTimeoutRef.current = window.setTimeout(() => {
        setShowCopiedToast(false)
        copiedToastTimeoutRef.current = null
      }, 1800)
    })
  }, [jamId])

  const handleShare = useCallback(() => {
    const link = `${window.location.origin}/jam/${jamId}`
    const tg = (window as unknown as { Telegram?: { WebApp?: { openTelegramLink?: (url: string) => void } } }).Telegram?.WebApp
    if (tg?.openTelegramLink) {
      tg.openTelegramLink(`https://t.me/share/url?url=${encodeURIComponent(link)}&text=${encodeURIComponent('Join my Jam Session!')}`)
    } else if (navigator.share) {
      navigator.share({ title: 'Join my Jam Session', url: link }).catch(() => { void 0 })
    } else {
      handleCopyLink()
    }
  }, [jamId, handleCopyLink])

  const goBack = useCallback(() => {
    if (window.history.length > 1) {
      navigate(-1)
    } else {
      navigate('/', { replace: true })
    }
  }, [navigate])

  const handleLeaveJam = useCallback(async () => {
    if (!jamForUi) return
    
    // First, transfer current track to normal player before leaving
    if (currentTrack && audioRef.current) {
      const audio = audioRef.current
      const isPlaying = !audio.paused
      
      try {
        if (isPlaying) {
          // Pause the jam audio first
          audio.pause()
        }
        
        // Clear jam context
        setExternalJamContext({ jamId: null, canEditQueue: false })
        setExternalTogglePlay(null)
        setExternalNowPlaying(null)
        setExternalUpcoming([])
        
        // Use the player context to play the track
        playSong(currentTrack)
      } catch (err) {
        console.error('Failed to transfer to player:', err)
      }
    }
    
    // Then try to leave the jam
    try {
      await jamLeave(jamForUi._id)
      console.log('Successfully left jam')
    } catch (err) {
      console.error('Failed to leave jam:', err)
      // Continue even if leave fails (jam might have already ended)
    }
    
    // Remove from localStorage
    try {
      const JAM_ACTIVE_KEY = 'streamw:jam:activeId'
      const current = window.localStorage.getItem(JAM_ACTIVE_KEY)
      if (current === jamForUi._id) {
        window.localStorage.removeItem(JAM_ACTIVE_KEY)
      }
    } catch {
      void 0
    }
    
    // Navigate back
    goBack()
  }, [jamForUi, currentTrack, goBack, playSong, audioRef, setExternalJamContext, setExternalTogglePlay, setExternalNowPlaying, setExternalUpcoming])

  useEffect(() => {
    return () => {
      if (copiedToastTimeoutRef.current != null) {
        try {
          window.clearTimeout(copiedToastTimeoutRef.current)
        } catch {
          void 0
        }
        copiedToastTimeoutRef.current = null
      }
    }
  }, [])

  if (!jamId || !authToken) {
    return (
      <div className="jam-page jam-page--error">
        <div className="jam-error">
          <p>Invalid jam session or not authenticated</p>
          <button onClick={() => navigate('/')}>Go Home</button>
        </div>
      </div>
    )
  }

  if (!jamForUi) {
    return (
      <div className="jam-page jam-page--loading">
        <div className="jam-loading">
          <div className="jam-loading-spinner" />
          <p>Connecting to jam session...</p>
        </div>
      </div>
    )
  }

  if (!currentTrack) {
    return (
      <div className="jam-page jam-page--loading">
        <div className="jam-loading">
          <div className="jam-loading-spinner" />
          <p>Loading track...</p>
        </div>
      </div>
    )
  }

  return (
    <div className={`jam-page ${isTelegram ? 'jam-page--telegram' : ''}`}>
      <main className="jam-content">
        {isTelegram && (
          <div className="jam-back-header">
            <button className="jam-back-btn" onClick={goBack} aria-label="Back">
              <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
              </svg>
            </button>
          </div>
        )}
        <div className="jam-player">
          <div className="jam-cover">
            <img 
              src={currentTrack.spotify?.cover_url || currentTrack.cover_url || 'https://via.placeholder.com/300'} 
              alt={currentTrack.title} 
              onError={(e) => {
                const img = e.target as HTMLImageElement
                if (img.src !== 'https://via.placeholder.com/300') {
                  img.src = 'https://via.placeholder.com/300'
                }
              }}
            />
          </div>

          <div className="jam-cover-status" aria-label="Jam status">
            <span className="jam-cover-status-dot" aria-hidden="true" />
            <span className="jam-cover-status-text">Jam</span>
          </div>

          <div className="jam-track-info">
            <h1 className="jam-track-title">{currentTrack.title}</h1>
            <p className="jam-track-artist">{currentTrack.artist}</p>
          </div>

          <div className="jam-progress-container">
            <div className="jam-time-display">
              <span className="jam-time-current">{formatTime(isSeekingProgress && progressDraft !== null ? progressDraft : currentTime)}</span>
              <span className="jam-time-duration">{formatTime(duration)}</span>
            </div>
            <div 
              className={`jam-progress${isSeekingProgress ? ' is-seeking' : ''}`}
              style={{ cursor: (isHost || jamForUi.settings.allow_seek) ? 'pointer' : 'default' }}
              onPointerDown={(e) => {
                if (!duration || (!isHost && !jamForUi.settings.allow_seek)) return
                e.preventDefault()
                progressGestureRef.current = { pointerId: e.pointerId, startX: e.clientX, startY: e.clientY }
                try {
                  e.currentTarget.setPointerCapture(e.pointerId)
                } catch {
                  void 0
                }
              }}
              onPointerMove={(e) => {
                if (!duration || (!isHost && !jamForUi.settings.allow_seek)) return
                
                if (isSeekingProgress) {
                  e.preventDefault()
                  const next = getPercentFromPointerX(e.currentTarget, e.clientX) * duration
                  const clamped = clamp(next, 0, duration)
                  setProgressDraft(clamped)
                  setCurrentTime(clamped)
                  const progressPercent = (clamped / duration) * 100
                  setProgress(clamp(progressPercent, 0, 100))
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
                setCurrentTime(clamped)
                const progressPercent = (clamped / duration) * 100
                setProgress(clamp(progressPercent, 0, 100))
                
                const audio = audioRef.current
                if (audio && isHost) {
                  wasPlayingBeforeSeekRef.current = !audio.paused
                  audio.pause()
                }
              }}
              onPointerUp={(e) => {
                if (!duration || (!isHost && !jamForUi.settings.allow_seek)) return
                
                if (e.currentTarget.hasPointerCapture(e.pointerId)) {
                  e.currentTarget.releasePointerCapture(e.pointerId)
                }
                
                const audio = audioRef.current
                if (!isSeekingProgress) {
                  const gesture = progressGestureRef.current
                  if (gesture.pointerId === e.pointerId) {
                    const next = clamp(getPercentFromPointerX(e.currentTarget, e.clientX) * duration, 0, duration)
                    handleSeek(next)
                  }
                } else {
                  const next = progressDraft
                  if (typeof next === 'number' && duration) {
                    handleSeek(clamp(next, 0, duration))
                    if (audio && isHost && wasPlayingBeforeSeekRef.current) {
                      audio.play().catch(() => {})
                    }
                  } else if (audio && isHost && wasPlayingBeforeSeekRef.current) {
                    audio.play().catch(() => {})
                  }
                }
                
                wasPlayingBeforeSeekRef.current = false
                setIsSeekingProgress(false)
                setProgressDraft(null)
                progressGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
              }}
              onPointerCancel={() => {
                const audio = audioRef.current
                if (audio && isHost && wasPlayingBeforeSeekRef.current) {
                  audio.play().catch(() => {})
                }
                wasPlayingBeforeSeekRef.current = false
                setIsSeekingProgress(false)
                setProgressDraft(null)
                progressGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
              }}
              onLostPointerCapture={() => {
                const audio = audioRef.current
                if (audio && isHost && wasPlayingBeforeSeekRef.current) {
                  audio.play().catch(() => {})
                }
                wasPlayingBeforeSeekRef.current = false
                setIsSeekingProgress(false)
                setProgressDraft(null)
                progressGestureRef.current = { pointerId: null, startX: 0, startY: 0 }
              }}
            >
              <div className="jam-progress-bar">
                <div className="jam-progress-fill" style={{ width: `${progress}%` }} />
                {(isHost || jamForUi.settings.allow_seek) && (
                  <div className="jam-progress-thumb" style={{ left: `${progress}%` }} />
                )}
              </div>
            </div>
          </div>

          {!isHost && (
            <div className="jam-listener-status jam-listener-status--top">
              <span>
                {(jamForUi.playback.is_playing && !(listenerHoldPaused || autoplayBlocked)) ? 'Listening' : 'Paused'}
              </span>
            </div>
          )}

          <div className="jam-controls">
            {isHost ? (
              <>
                <button 
                  className="jam-control-btn jam-control-btn--leave" 
                  onClick={handleEndJam}
                  aria-label="Leave jam"
                >
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path fill="currentColor" d="M10.09 15.59L11.5 17l5-5-5-5-1.41 1.41L12.67 11H3v2h9.67l-2.58 2.59zM19 3H5c-1.11 0-2 .9-2 2v4h2V5h14v14H5v-4H3v4c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z" />
                  </svg>
                </button>
                <button 
                  className="jam-control-btn jam-control-btn--primary" 
                  onClick={handlePlayPause}
                  aria-label={(hostUiIsPlaying ?? jamForUi.playback.is_playing) ? 'Pause' : 'Play'}
                >
                  {(hostUiIsPlaying ?? jamForUi.playback.is_playing) ? <RawSvgIcon svg={pauseIconSvg} /> : <RawSvgIcon svg={resumeIconSvg} />}
                </button>
                <button 
                  className="jam-control-btn jam-control-btn--secondary" 
                  onClick={handleNext}
                  aria-label="Next track"
                >
                  <RawSvgIcon svg={forwardIconSvg} />
                </button>
              </>
            ) : (
              <>
                <button 
                  className="jam-control-btn jam-control-btn--leave" 
                  onClick={handleLeaveJam}
                  aria-label="Leave jam"
                >
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path fill="currentColor" d="M10.09 15.59L11.5 17l5-5-5-5-1.41 1.41L12.67 11H3v2h9.67l-2.58 2.59zM19 3H5c-1.11 0-2 .9-2 2v4h2V5h14v14H5v-4H3v4c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z" />
                  </svg>
                </button>
                {jamForUi.playback.is_playing ? (
                  <button
                    className="jam-control-btn jam-control-btn--primary"
                    onClick={handleListenerPlayPause}
                    aria-label={(listenerHoldPaused || autoplayBlocked || audioPaused) ? 'Resume' : 'Pause'}
                  >
                    {(listenerHoldPaused || autoplayBlocked || audioPaused)
                      ? <RawSvgIcon svg={resumeIconSvg} />
                      : <RawSvgIcon svg={pauseIconSvg} />}
                  </button>
                ) : null}
              </>
            )}
          </div>
        </div>

        <div className={`jam-listeners ${showListeners ? 'jam-listeners--expanded' : ''}`}>
          <div className="jam-listeners-header">
            <button
              className="jam-listeners-toggle"
              onClick={() => setShowListeners(!showListeners)}
              aria-label="Toggle listeners"
            >
              <span className="jam-listeners-count">
                <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z" />
                </svg>
                {jamForUi.members?.length || 0} {(jamForUi.members?.length || 0) === 1 ? 'Listener' : 'Listeners'}
              </span>
            </button>
            <div className="jam-listeners-actions">
              <button className="jam-invite-btn" onClick={() => setShowInvite(true)} aria-label="Invite">
                Invite
              </button>
              <button
                className="jam-listeners-chevron-btn"
                onClick={() => setShowListeners(!showListeners)}
                aria-label="Toggle listeners"
              >
                <svg
                  className="jam-listeners-chevron"
                  viewBox="0 0 24 24"
                  xmlns="http://www.w3.org/2000/svg"
                  style={{ transform: showListeners ? 'rotate(180deg)' : 'rotate(0deg)' }}
                >
                  <path fill="currentColor" d="M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z" />
                </svg>
              </button>
            </div>
          </div>
          {showListeners && (
            <div className="jam-listeners-list">
              {jamForUi.members && jamForUi.members.length > 0 ? (
                jamForUi.members.map((member, index) => (
                  <div key={`${member.user_id}-${index}`} className="jam-listener-item">
                    <div className="jam-listener-avatar">
                      {normalizePhotoUrl(member.profile_url ?? member.photo_url) ? (
                        <img src={normalizePhotoUrl(member.profile_url ?? member.photo_url)} alt="" />
                      ) : (
                        <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                          <path fill="currentColor" d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" />
                        </svg>
                      )}
                    </div>
                    <div className="jam-listener-info">
                      <div className="jam-listener-name">
                        {member.first_name || member.username || `User ${member.user_id}`}
                        {member.user_id === jamForUi.host_user_id && (
                          <span className="jam-listener-badge">Host</span>
                        )}
                        {member.user_id === userId && member.user_id !== jamForUi.host_user_id && (
                          <span className="jam-listener-badge jam-listener-badge--you">You</span>
                        )}
                      </div>
                      <div className="jam-listener-role">
                        {member.user_id === jamForUi.host_user_id ? 'Hosting' : 'Listening'}
                      </div>
                    </div>
                  </div>
                ))
              ) : (
                <div className="jam-listeners-empty">No listeners yet</div>
              )}
            </div>
          )}
        </div>

        <div className="jam-queue">
          <h2 className="jam-queue-title">Up Next</h2>
          {queueTracks.length === 0 ? (
            <div className="jam-queue-empty">Nothing to show here</div>
          ) : (
            <DndContext
              sensors={sensors}
              collisionDetection={closestCenter}
              modifiers={[restrictToVerticalAxis, restrictToFirstScrollableAncestor]}
              onDragStart={(e) => setActiveQueueId(String(e.active.id))}
              onDragCancel={() => setActiveQueueId(null)}
              onDragEnd={onQueueDragEnd}
            >
              <SortableContext items={queueItemIds} strategy={verticalListSortingStrategy}>
                <div className="jam-queue-list" role="list" data-dragging={activeQueueId ? 'true' : 'false'}>
                  {queueTracks.map((track) => (
                    <SortableJamQueueItem key={track._id} track={track} disabled={!canEditQueue} />
                  ))}
                </div>
              </SortableContext>
            </DndContext>
          )}
        </div>
      </main>

      {showInvite && (
        <div className="jam-invite-modal" onClick={() => setShowInvite(false)}>
          <div className="jam-invite-content" onClick={(e) => e.stopPropagation()}>
            <h3>Invite Friends</h3>
            <p>Share this link to invite others:</p>
            <div className="jam-invite-link">
              <code>{`${window.location.origin}/jam/${jamId}`}</code>
            </div>
            <div className="jam-invite-actions">
              <button onClick={handleCopyLink}>Copy Link</button>
              <button onClick={handleShare}>Share</button>
            </div>
          </div>
        </div>
      )}

      {showCopiedToast && (
        <div className="jam-toast" role="status" aria-label="Copied">
          Copied
        </div>
      )}
    </div>
  )
}
