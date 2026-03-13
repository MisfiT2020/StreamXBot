import { API_BASE_URL, getAuthToken } from './api.js'

async function apiFetch(path: string, method = 'GET', body: unknown = null) {
  const token = getAuthToken()
  if (!token) {
    throw new Error('Not authenticated')
  }

  const opts: RequestInit = {
    method,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
  }
  if (body) opts.body = JSON.stringify(body)

  const res = await fetch(`${API_BASE_URL}${path}`, opts)
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`API ${res.status}: ${text}`)
  }
  return res.json()
}

export interface CreateJamParams {
  track_id: string
  position_sec?: number
  is_playing?: boolean
  queue?: string[]
  settings?: {
    allow_seek?: boolean
    allow_queue_edit?: boolean
  }
}

export interface JamPlayback {
  track_id: string
  position_sec: number
  started_at: number
  is_playing: boolean
}

export interface JamSession {
  _id: string
  host_user_id: number
  playback: JamPlayback
  queue: string[]
  settings: {
    allow_seek?: boolean
    allow_queue_edit?: boolean
  }
  members?: Array<{ user_id: number; role: string }>
  created_at?: number
  updated_at?: number
}

export interface JamJoinResponse {
  ok: boolean
  jam: JamSession
}

export async function createJam(params: CreateJamParams): Promise<JamJoinResponse> {
  return apiFetch('/jam/create', 'POST', {
    track_id: params.track_id,
    position_sec: params.position_sec ?? 0,
    is_playing: params.is_playing ?? true,
    queue: params.queue ?? [],
    settings: params.settings ?? { allow_seek: false, allow_queue_edit: false },
  })
}

export async function joinJam(jamId: string): Promise<JamJoinResponse> {
  return apiFetch(`/jam/${jamId}/join`, 'POST')
}

export async function getJam(jamId: string): Promise<JamJoinResponse> {
  return apiFetch(`/jam/${jamId}`, 'GET')
}

export async function jamPlay(jamId: string) {
  return apiFetch(`/jam/${jamId}/play`, 'POST')
}

export async function jamPause(jamId: string) {
  return apiFetch(`/jam/${jamId}/pause`, 'POST')
}

export async function jamSeek(jamId: string, positionSec: number) {
  return apiFetch(`/jam/${jamId}/seek`, 'POST', { position_sec: positionSec })
}

export async function jamAddQueue(jamId: string, trackId: string, position: number | null = null) {
  return apiFetch(`/jam/${jamId}/queue/add`, 'POST', { track_id: trackId, position })
}

export async function jamQueueReorder(jamId: string, queue: string[]) {
  return apiFetch(`/jam/${jamId}/queue/reorder`, 'POST', { queue })
}

export async function jamNext(jamId: string) {
  return apiFetch(`/jam/${jamId}/next`, 'POST')
}

export async function jamLeave(jamId: string) {
  return apiFetch(`/jam/${jamId}/leave`, 'POST')
}

export async function jamEnd(jamId: string) {
  // Use leave endpoint - there is no separate end endpoint
  return apiFetch(`/jam/${jamId}/leave`, 'POST')
}
