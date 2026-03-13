import { useEffect, useRef, useState, useCallback } from 'react'
import { API_BASE_URL } from '../services/api.js'

const WS_RECONNECT_BASE = 1000
const JAM_ACTIVE_KEY = 'streamw:jam:activeId'

interface JamPlayback {
  track_id: string
  position_sec: number
  started_at: number
  is_playing: boolean
}

interface JamSettings {
  allow_seek: boolean
  allow_queue_edit: boolean
}

interface JamMember {
  user_id: number
  role: string
  first_name?: string
  last_name?: string
  username?: string
  photo_url?: string
  profile_url?: string
}

export interface Jam {
  _id: string
  host_user_id: number
  playback: JamPlayback
  queue: string[]
  settings: JamSettings
  members: JamMember[]
  created_at: number
  updated_at: number
}

interface UseJamOptions {
  jamId: string | null
  authToken: string | null
  onJamState?: (jam: Jam) => void
}

export function useJam({ jamId, authToken, onJamState }: UseJamOptions) {
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectRef = useRef(0)
  const connectRef = useRef<(() => void) | null>(null)
  const reconnectTimeoutRef = useRef<number | null>(null)
  const activeRef = useRef(true)
  const [connected, setConnected] = useState(false)
  const [jam, setJam] = useState<Jam | null>(null)

  const connect = useCallback(() => {
    const normalizedJamId = (jamId || '').trim()
    const normalizedToken = (authToken || '').trim()
    if (!normalizedJamId || normalizedJamId === 'undefined' || normalizedJamId === 'null') return
    if (!normalizedToken || normalizedToken === 'undefined' || normalizedToken === 'null') return

    try {
      if (reconnectTimeoutRef.current != null) {
        window.clearTimeout(reconnectTimeoutRef.current)
        reconnectTimeoutRef.current = null
      }
    } catch {
      void 0
    }

    try {
      if (wsRef.current && (wsRef.current.readyState === WebSocket.OPEN || wsRef.current.readyState === WebSocket.CONNECTING)) {
        wsRef.current.close()
      }
    } catch {
      void 0
    }

    const protocol = new URL(API_BASE_URL).protocol === 'https:' ? 'wss:' : 'ws:'
    const host = new URL(API_BASE_URL).host
    const url = `${protocol}//${host}/jam/${encodeURIComponent(normalizedJamId)}/ws?token=${encodeURIComponent(normalizedToken)}`
    
    console.log('Connecting to WebSocket:', url)
    wsRef.current = new WebSocket(url)

    wsRef.current.onopen = () => {
      reconnectRef.current = 0
      setConnected(true)
      console.debug('jam ws open')
    }

    wsRef.current.onmessage = (ev) => {
      console.log('WebSocket message received:', ev.data)
      try {
        const msg = JSON.parse(ev.data)
        console.log('Parsed message:', msg)
        if (msg?.type === 'jam_state' && msg.jam) {
          console.log('Setting jam state:', msg.jam)
          setJam(msg.jam)
          if (onJamState) onJamState(msg.jam)
        } else if (msg?.type === 'jam_ended') {
          console.log('Jam ended')
          setJam(null)
          try {
            const current = window.localStorage.getItem(JAM_ACTIVE_KEY)
            if (current === normalizedJamId) window.localStorage.removeItem(JAM_ACTIVE_KEY)
          } catch {
            void 0
          }
        }
        // respond to ping automatically
        if (msg?.type === 'ping' && wsRef.current?.readyState === WebSocket.OPEN) {
          wsRef.current.send(JSON.stringify({ type: 'pong', t: Date.now() / 1000 }))
        }
      } catch (e) {
        console.error('invalid ws message', e)
      }
    }

    wsRef.current.onclose = () => {
      setConnected(false)
      console.debug('jam ws closed, scheduling reconnect')
      if (!activeRef.current) return
      reconnectRef.current = Math.min(30000, reconnectRef.current ? reconnectRef.current * 2 : WS_RECONNECT_BASE)
      reconnectTimeoutRef.current = window.setTimeout(
        () => connectRef.current?.(),
        reconnectRef.current || WS_RECONNECT_BASE,
      )
    }

    wsRef.current.onerror = (err) => {
      console.warn('jam ws error', err)
      try {
        wsRef.current?.close()
      } catch (e) {
        void e
      }
    }
  }, [jamId, authToken, onJamState])

  useEffect(() => {
    connectRef.current = connect
    activeRef.current = true
    connect()
    return () => {
      activeRef.current = false
      try {
        if (reconnectTimeoutRef.current != null) {
          window.clearTimeout(reconnectTimeoutRef.current)
          reconnectTimeoutRef.current = null
        }
      } catch {
        void 0
      }
      try {
        wsRef.current?.close()
      } catch (e) {
        void e
      }
    }
  }, [connect])

  useEffect(() => {
    const normalizedJamId = (jamId || '').trim()
    const normalizedToken = (authToken || '').trim()
    if (!normalizedJamId || normalizedJamId === 'undefined' || normalizedJamId === 'null') return
    if (!normalizedToken || normalizedToken === 'undefined' || normalizedToken === 'null') return
    if (jam) return

    const controller = new AbortController()
    fetch(`${API_BASE_URL}/jam/${encodeURIComponent(normalizedJamId)}`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
        'Authorization': `Bearer ${normalizedToken}`,
      },
      signal: controller.signal,
    })
      .then(async (res) => {
        if (!res.ok) return null
        return res.json() as Promise<unknown>
      })
      .then((data) => {
        if (!data || !activeRef.current) return
        const maybeJam =
          typeof data === 'object' && data && 'jam' in data
            ? (data as { jam?: unknown }).jam
            : data
        if (!maybeJam || typeof maybeJam !== 'object' || !('_id' in maybeJam)) return
        const nextJam = maybeJam as Jam
        setJam(nextJam)
        if (onJamState) onJamState(nextJam)
      })
      .catch(() => {})

    return () => {
      controller.abort()
    }
  }, [jamId, authToken, onJamState, jam])

  const sendPing = useCallback(() => {
    try {
      if (wsRef.current?.readyState === WebSocket.OPEN) {
        wsRef.current.send(JSON.stringify({ type: 'ping' }))
      }
    } catch (e) {
      void e
    }
  }, [])

  return {
    connected,
    jam,
    sendPing,
  }
}
