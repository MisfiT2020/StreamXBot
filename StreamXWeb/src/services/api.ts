import type { BrowseResponse, PlaylistTracksResponse, PlaylistsResponse, TrackDetailsResponse, TrackLyricsResponse, AvailablePlaylistsResponse, AvailablePlaylistTracksResponse } from '../types/index.js'
import { platform } from '../platform.js'

const normalizeBaseUrl = (raw: unknown): string | null => {
  if (typeof raw !== 'string') return null
  const trimmed = raw.trim()
  if (!trimmed) return null
  return trimmed.replace(/\/+$/, '')
}

const resolvedApiBaseUrl = normalizeBaseUrl(import.meta.env.VITE_API_BASE_URL) || (typeof window !== 'undefined' ? window.location.origin : '')

export const API_BASE_URL = resolvedApiBaseUrl
const CACHE_ENABLED_STORAGE_KEY = 'streamw:cache:enabled'
const AUTH_TOKEN_STORAGE_KEY = 'streamw:auth:token'
const AUTH_COOKIE_ENABLED_STORAGE_KEY = 'streamw:auth:cookieEnabled'
const AUTH_USER_INFO_STORAGE_KEY = 'streamw:auth:userInfo'
const AUTH_USERNAME_STORAGE_KEY = 'streamw:auth:username'
const WEB_SONGLIST_STORAGE_KEY = 'streamw:ui:webSonglist'
const RED_SELECTOR_STORAGE_KEY = 'streamw:ui:redSelector'
const AUTO_HIDE_PLAYER_STORAGE_KEY = 'streamw:ui:autoHidePlayer'
const THEME_STORAGE_KEY = 'streamw:ui:theme'
const FLOATING_NAV_TOP_PAD_STORAGE_KEY = 'streamw:ui:floatingNavTopPad'
export const CACHE_TTL_MS = 3 * 60 * 60 * 1000

export type AuthUserInfo = {
  first_name?: string
  user_id?: number
  profile_url?: string
  photo_url?: string
}

const decodeBase64UrlJson = (raw: string): Record<string, unknown> | null => {
  try {
    const normalized = raw.replace(/-/g, '+').replace(/_/g, '/')
    const padLength = normalized.length % 4 ? 4 - (normalized.length % 4) : 0
    const padded = normalized + '='.repeat(padLength)
    const json = window.atob(padded)
    return JSON.parse(json) as Record<string, unknown>
  } catch {
    return null
  }
}

const deriveAuthUserInfoFromToken = (token: string): { first_name?: string; user_id?: number } | null => {
  const parts = token.split('.')
  if (parts.length < 2) return null
  const payload = decodeBase64UrlJson(parts[1] || '')
  if (!payload) return null

  const rawUserId =
    payload.user_id ?? payload.userId ?? payload.uid ?? payload.id ?? payload.sub ?? payload.user ?? payload.account_id

  let user_id: number | undefined
  if (typeof rawUserId === 'number' && Number.isFinite(rawUserId)) {
    user_id = rawUserId
  } else if (typeof rawUserId === 'string') {
    const parsed = Number.parseInt(rawUserId, 10)
    if (Number.isFinite(parsed)) user_id = parsed
  }

  const rawFirstName = payload.first_name ?? payload.firstName ?? payload.given_name ?? payload.name
  const first_name = typeof rawFirstName === 'string' && rawFirstName.trim() ? rawFirstName.trim() : undefined

  if (user_id == null && !first_name) return null
  return { user_id, first_name }
}

export const getAuthToken = (): string | null => {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)
  } catch {
    return null
  }
}

export const setAuthToken = (token: string | null): void => {
  if (typeof window === 'undefined') return
  try {
    if (token) {
      const prevToken = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)
      window.localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token)
      try {
        const rawExisting = window.localStorage.getItem(AUTH_USER_INFO_STORAGE_KEY)
        const existing = rawExisting ? (JSON.parse(rawExisting) as AuthUserInfo) : null
        const derived = deriveAuthUserInfoFromToken(token)
        const derivedUserId = derived?.user_id
        const derivedFirstName = derived?.first_name

        const tokenChanged = Boolean(prevToken && prevToken !== token)

        const shouldOverwriteUserId =
          typeof derivedUserId === 'number' &&
          Number.isFinite(derivedUserId) &&
          (existing?.user_id == null || existing.user_id !== derivedUserId)

        const shouldClearStaleUserId = tokenChanged && (derivedUserId == null) && existing?.user_id != null
        const shouldBackfillName = !existing?.first_name && Boolean(derivedFirstName)

        if (shouldOverwriteUserId || shouldClearStaleUserId || shouldBackfillName) {
          setAuthUserInfo({
            first_name: existing?.first_name ?? derivedFirstName,
            user_id: shouldOverwriteUserId ? derivedUserId : (shouldClearStaleUserId ? undefined : existing?.user_id),
            profile_url: existing?.profile_url,
            photo_url: existing?.photo_url,
          })
        }
      } catch {
        void 0
      }
    } else {
      window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY)
    }
  } catch {
    void 0
  }
}

export const getAuthUserInfo = (): AuthUserInfo | null => {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(AUTH_USER_INFO_STORAGE_KEY)
    if (raw) return JSON.parse(raw)

    const token = getAuthToken()
    if (!token) return null
    const derived = deriveAuthUserInfoFromToken(token)
    if (!derived) return null
    setAuthUserInfo(derived)
    return derived
  } catch {
    return null
  }
}

export const setAuthUserInfo = (userInfo: AuthUserInfo | null): void => {
  if (typeof window === 'undefined') return
  try {
    if (userInfo) {
      window.localStorage.setItem(AUTH_USER_INFO_STORAGE_KEY, JSON.stringify(userInfo))
    } else {
      window.localStorage.removeItem(AUTH_USER_INFO_STORAGE_KEY)
    }
  } catch {
    void 0
  }

  try {
    window.dispatchEvent(new CustomEvent('streamw:authUserInfoChanged'))
  } catch {
    void 0
  }
}

export const getCachedUsername = (): string | null => {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage.getItem(AUTH_USERNAME_STORAGE_KEY)
  } catch {
    return null
  }
}

export const setCachedUsername = (username: string | null): void => {
  if (typeof window === 'undefined') return
  try {
    if (username) {
      window.localStorage.setItem(AUTH_USERNAME_STORAGE_KEY, username)
    } else {
      window.localStorage.removeItem(AUTH_USERNAME_STORAGE_KEY)
    }
  } catch {
    void 0
  }
}

export const getAuthCookieEnabled = (): boolean => {
  if (typeof window === 'undefined') return false
  try {
    return window.localStorage.getItem(AUTH_COOKIE_ENABLED_STORAGE_KEY) === 'true'
  } catch {
    return false
  }
}

export const setAuthCookieEnabled = (enabled: boolean): void => {
  if (typeof window === 'undefined') return
  try {
    if (enabled) {
      window.localStorage.setItem(AUTH_COOKIE_ENABLED_STORAGE_KEY, 'true')
    } else {
      window.localStorage.removeItem(AUTH_COOKIE_ENABLED_STORAGE_KEY)
    }
  } catch {
    void 0
  }
}

const getCookieDomainForApi = (): string | null => {
  try {
    const hostname = new URL(API_BASE_URL).hostname
    if (!hostname || hostname === 'localhost' || hostname === '127.0.0.1') return null
    return hostname
  } catch {
    return null
  }
}

export const ensureAuthCookieFromToken = async (token: string | null = getAuthToken()): Promise<boolean> => {
  if (!token) {
    setAuthCookieEnabled(false)
    return false
  }

  try {
    if (platform.isTelegram && platform.isAndroid) {
      setAuthCookieEnabled(false)
      return false
    }
  } catch {
    void 0
  }

  try {
    const cookieDomain = getCookieDomainForApi()
    const cookieUrl = new URL(`${API_BASE_URL}/auth/cookie`)
    if (cookieDomain) cookieUrl.searchParams.set('cookie_domain', cookieDomain)

    const response = await fetch(cookieUrl.toString(), {
      method: 'POST',
      headers: {
        accept: 'application/json',
        'Content-Type': 'application/json',
      },
      credentials: 'include',
      body: JSON.stringify({ token }),
    })

    if (!response.ok) {
      setAuthCookieEnabled(false)
      return false
    }

    setAuthCookieEnabled(true)
    return true
  } catch {
    setAuthCookieEnabled(false)
    return false
  }
}

export const signOut = (): void => {
  setAuthToken(null)
  setAuthUserInfo(null)
  setCachedUsername(null)
  setAuthCookieEnabled(false)
}

export const getCacheEnabled = (): boolean => {
  if (typeof window === 'undefined') return true
  try {
    const raw = window.localStorage.getItem(CACHE_ENABLED_STORAGE_KEY)
    if (raw === 'false') return false
    return true
  } catch {
    return true
  }
}

export const setCacheEnabled = (next: boolean): void => {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(CACHE_ENABLED_STORAGE_KEY, String(next))
  } catch {
    void 0
  }
}

export const getWebSongListEnabled = (): boolean => {
  if (typeof window === 'undefined') return false
  try {
    const raw = window.localStorage.getItem(WEB_SONGLIST_STORAGE_KEY)
    if (raw === 'true') return true
    return false
  } catch {
    return false
  }
}

export const setWebSongListEnabled = (next: boolean): void => {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(WEB_SONGLIST_STORAGE_KEY, String(next))
  } catch {
    void 0
  }
}

export const getRedSelectorEnabled = (): boolean => {
  if (typeof window === 'undefined') return true
  try {
    const raw = window.localStorage.getItem(RED_SELECTOR_STORAGE_KEY)
    if (raw === 'false') return false
    return true
  } catch {
    return true
  }
}

export const setRedSelectorEnabled = (next: boolean): void => {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(RED_SELECTOR_STORAGE_KEY, String(next))
  } catch {
    void 0
  }

  try {
    window.dispatchEvent(new CustomEvent('streamw:redSelectorChanged'))
  } catch {
    void 0
  }
}

export const getAutoHidePlayerEnabled = (): boolean => {
  if (typeof window === 'undefined') return false
  try {
    const raw = window.localStorage.getItem(AUTO_HIDE_PLAYER_STORAGE_KEY)
    if (raw === 'true') return true
    return false
  } catch {
    return false
  }
}

export const setAutoHidePlayerEnabled = (next: boolean): void => {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(AUTO_HIDE_PLAYER_STORAGE_KEY, String(next))
  } catch {
    void 0
  }

  try {
    window.dispatchEvent(new CustomEvent('streamw:autoHidePlayerChanged'))
  } catch {
    void 0
  }
}

export type ThemeMode = 'dark' | 'light'

export const getThemeMode = (): ThemeMode => {
  if (typeof window === 'undefined') return 'dark'
  try {
    const raw = window.localStorage.getItem(THEME_STORAGE_KEY)
    if (raw === 'light' || raw === 'dark') return raw
  } catch {
    void 0
  }

  try {
    if (window.matchMedia?.('(prefers-color-scheme: light)').matches) return 'light'
  } catch {
    void 0
  }

  return 'dark'
}

export const setThemeMode = (next: ThemeMode): void => {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, next)
  } catch {
    void 0
  }

  try {
    window.dispatchEvent(new CustomEvent('streamw:themeChanged'))
  } catch {
    void 0
  }
}

export const getFloatingNavTopPad = (): number | null => {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(FLOATING_NAV_TOP_PAD_STORAGE_KEY)
    if (!raw) return null
    const parsed = Number.parseInt(raw, 10)
    if (!Number.isFinite(parsed)) return null
    return parsed
  } catch {
    return null
  }
}

export const setFloatingNavTopPad = (next: number | null): void => {
  if (typeof window === 'undefined') return
  try {
    if (next == null || !Number.isFinite(next)) {
      window.localStorage.removeItem(FLOATING_NAV_TOP_PAD_STORAGE_KEY)
    } else {
      window.localStorage.setItem(FLOATING_NAV_TOP_PAD_STORAGE_KEY, String(next))
    }
  } catch {
    void 0
  }

  try {
    window.dispatchEvent(new CustomEvent('streamw:floatingNavTopPadChanged'))
  } catch {
    void 0
  }
}

export const clearAppCache = (): void => {
  spotifyCoverCache.clear()
  spotifyCoverInFlight.clear()
  warmTrackInFlight.clear()
  warmTrackAt.clear()
  browseTracksInFlight.clear()
  favouriteIdsInFlight.clear()

  if (typeof window === 'undefined') return

  const prefixes = ['streamw:latestSongs:', 'streamw:playlists:', 'streamw:availablePlaylists', 'streamw:randomMix']
  try {
    for (let i = window.localStorage.length - 1; i >= 0; i -= 1) {
      const key = window.localStorage.key(i)
      if (!key) continue
      if (prefixes.some((p) => key.startsWith(p))) {
        window.localStorage.removeItem(key)
      }
    }
  } catch {
    void 0
  }
}
const spotifyCoverCache = new Map<string, string | null>()
const spotifyCoverInFlight = new Map<string, Promise<string | null>>()
const warmTrackInFlight = new Map<string, Promise<void>>()
const warmTrackAt = new Map<string, number>()
const browseTracksInFlight = new Map<number, Promise<BrowseResponse>>()
const favouriteIdsInFlight = new Map<
  string,
  Promise<{ page: number; per_page: number; total: number; ids: string[]; exists?: boolean; last_updated_at?: number }>
>()

const shuffleItems = <T,>(items: T[]): T[] => {
  if (items.length <= 1) return items
  const next = items.slice()
  for (let i = next.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1))
    const tmp = next[i]
    next[i] = next[j]
    next[j] = tmp
  }
  return next
}

export const api = {
  async getMyPlaylists(): Promise<PlaylistsResponse> {
    const token = getAuthToken()
    if (!token) {
      throw new Error('Not authenticated. Please use /verify in Telegram to get your token.')
    }

    const response = await fetch(`${API_BASE_URL}/me/playlists`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
        'X-Auth-Token': token,
        'Authorization': `Bearer ${token}`,
      },
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to fetch playlists: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async createMyPlaylist(name: string): Promise<{ playlist_id: string; name: string; created_at: number; updated_at: number }> {
    const token = getAuthToken()
    if (!token) {
      throw new Error('Not authenticated. Please use /verify in Telegram to get your token.')
    }

    const response = await fetch(`${API_BASE_URL}/me/playlists`, {
      method: 'POST',
      headers: {
        accept: 'application/json',
        'X-Auth-Token': token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ name }),
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to create playlist: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async addTrackToMyPlaylist(
    playlistId: string,
    trackId: string,
  ): Promise<{ ok: boolean; already_exists?: boolean }> {
    const token = getAuthToken()
    if (!token) {
      throw new Error('Not authenticated. Please use /verify in Telegram to get your token.')
    }

    const response = await fetch(`${API_BASE_URL}/me/playlists/${encodeURIComponent(playlistId)}/tracks`, {
      method: 'POST',
      headers: {
        accept: 'application/json',
        'X-Auth-Token': token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ track_id: trackId }),
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to add track to playlist: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async removeTrackFromMyPlaylist(
    playlistId: string,
    trackId: string,
  ): Promise<{ ok: boolean; deleted?: boolean }> {
    const token = getAuthToken()
    if (!token) {
      throw new Error('Not authenticated. Please use /verify in Telegram to get your token.')
    }

    const base = `${API_BASE_URL}/me/playlists/${encodeURIComponent(playlistId)}/tracks`
    const withTrackId = `${base}/${encodeURIComponent(trackId)}`

    const tryRemove = async (url: string, includeBody: boolean) => {
      const response = await fetch(url, {
        method: 'DELETE',
        headers: includeBody
          ? {
              accept: 'application/json',
              'X-Auth-Token': token,
              'Content-Type': 'application/json',
            }
          : {
              accept: 'application/json',
              'X-Auth-Token': token,
            },
        body: includeBody ? JSON.stringify({ track_id: trackId }) : undefined,
      })
      return response
    }

    const first = await tryRemove(withTrackId, false)
    const response = first.ok ? first : await tryRemove(base, true)

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to remove track from playlist: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async shuffleTracks(limit: number = 100): Promise<BrowseResponse> {
    const target = Number.isFinite(limit) ? Math.max(1, Math.floor(limit)) : 100
    const pool = new Map<string, BrowseResponse['items'][number]>()

    for (let page = 1; page <= 10 && pool.size < target; page += 1) {
      const data = await this.browseTracks(page)
      const items = Array.isArray(data.items) ? data.items : []
      if (items.length === 0) break
      for (const item of items) {
        if (!item?._id) continue
        pool.set(item._id, item)
        if (pool.size >= target) break
      }
    }

    const items = shuffleItems(Array.from(pool.values())).slice(0, target)
    return { page: 1, per_page: target, total: items.length, items }
  },
  async browseTracks(page: number = 1): Promise<BrowseResponse> {
    const existing = browseTracksInFlight.get(page)
    if (existing) return existing

    const promise = fetch(`${API_BASE_URL}/browse?page=${page}`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
      },
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error(`Failed to fetch tracks: ${response.statusText}`)
        }
        return response.json() as Promise<BrowseResponse>
      })
      .finally(() => {
        browseTracksInFlight.delete(page)
      })

    browseTracksInFlight.set(page, promise)
    return promise
  },

  async searchTracks(params: { q: string; page?: number; limit?: number }): Promise<BrowseResponse> {
    const q = params.q.trim()
    const page = params.page ?? 1
    const limit = params.limit ?? 20

    const search = new URLSearchParams({
      q,
      page: String(page),
      limit: String(limit),
    }).toString()

    const response = await fetch(`${API_BASE_URL}/tracks/search?${search}`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
      },
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to search tracks: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  getStreamUrl(trackId: string): string {
    const baseUrl = `${API_BASE_URL}/tracks/${encodeURIComponent(trackId)}/stream`
    if (getAuthCookieEnabled()) return baseUrl
    const token = getAuthToken()
    if (!token) return baseUrl
    try {
      const url = new URL(baseUrl)
      url.searchParams.set('token', token)
      return url.toString()
    } catch {
      const search = new URLSearchParams({ token }).toString()
      return `${baseUrl}?${search}`
    }
  },

  getStreamUrlWithToken(trackId: string, token: string | null = getAuthToken()): string {
    const baseUrl = `${API_BASE_URL}/tracks/${encodeURIComponent(trackId)}/stream`
    if (!token) return baseUrl
    try {
      const url = new URL(baseUrl)
      url.searchParams.set('token', token)
      return url.toString()
    } catch {
      const search = new URLSearchParams({ token }).toString()
      return `${baseUrl}?${search}`
    }
  },

  streamTrack(trackId: string): string {
    return this.getStreamUrl(trackId)
  },

  async warmTrack(trackId: string): Promise<void> {
    const now = Date.now()
    const lastWarmedAt = warmTrackAt.get(trackId) ?? 0
    if (now - lastWarmedAt < 10 * 60 * 1000) return
    const existing = warmTrackInFlight.get(trackId)
    if (existing) return existing

    try {
      const promise = fetch(`${API_BASE_URL}/tracks/${trackId}/warm`, {
        method: 'GET',
        headers: {
          accept: 'application/json',
        },
        cache: 'no-store',
      })
        .then((response) => {
          if (!response.ok) return
          warmTrackAt.set(trackId, Date.now())
        })
        .catch((err) => {
          void err
        })
        .finally(() => {
          warmTrackInFlight.delete(trackId)
        })

      warmTrackInFlight.set(trackId, promise)
      return promise
    } catch (err) {
      void err
    }
  },

  async getTrackDetails(trackId: string): Promise<TrackDetailsResponse> {
    const response = await fetch(`${API_BASE_URL}/tracks/${trackId}`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
      },
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to fetch track: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async getTrackLyrics(trackId: string): Promise<TrackLyricsResponse> {
    const response = await fetch(`${API_BASE_URL}/tracks/${trackId}/lyrics`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
      },
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(`Failed to fetch lyrics: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`)
    }

    const normalize = (payload: unknown): TrackLyricsResponse => {
      if (payload && typeof payload === 'object') {
        const obj = payload as Record<string, unknown>
        const lyrics = typeof obj.lyrics === 'string' ? obj.lyrics : undefined
        const url = typeof obj.url === 'string' ? obj.url : undefined
        const ok = typeof obj.ok === 'boolean' ? obj.ok : true
        const track_id = typeof obj.track_id === 'string' ? obj.track_id : trackId
        return { ok, track_id, url, lyrics }
      }
      return { ok: true, track_id: trackId, lyrics: typeof payload === 'string' ? payload : '' }
    }

    let rawText = ''
    try {
      rawText = await response.text()
    } catch {
      rawText = ''
    }

    const trimmed = rawText.trim()
    if (!trimmed) return { ok: true, track_id: trackId, lyrics: '' }

    try {
      return normalize(JSON.parse(trimmed))
    } catch {
      return { ok: true, track_id: trackId, lyrics: rawText }
    }
  },

  async getSpotifyCoverUrl(
    trackId: string,
    context?: { title?: string; artist?: string },
  ): Promise<string | null> {
    const debug =
      import.meta.env.DEV ||
      (typeof window !== 'undefined' && window.localStorage?.getItem('debug_spotify_cover') === '1')

    try {
      if (!getCacheEnabled()) {
        const details = await this.getTrackDetails(trackId)
        return details.spotify?.cover_url ?? null
      }

      if (spotifyCoverCache.has(trackId)) {
        const cached = spotifyCoverCache.get(trackId) ?? null
        if (debug) console.log('[spotify-cover] cache', { trackId, cached, context })
        return cached
      }

      const existing = spotifyCoverInFlight.get(trackId)
      if (existing) {
        if (debug) console.log('[spotify-cover] inflight', { trackId, context })
        return existing
      }

      const promise = (async () => {
        if (debug) console.log('[spotify-cover] start', { trackId, context })
        const details = await this.getTrackDetails(trackId)
        const cover = details.spotify?.cover_url ?? null
        spotifyCoverCache.set(trackId, cover)
        if (debug) {
          console.log('[spotify-cover] result', { trackId, cover, spotify: details.spotify })
          if (!cover) console.log('[spotify-cover] missing', { trackId, context, audio: details.audio, spotify: details.spotify })
        }
        return cover
      })()
        .catch((err) => {
          spotifyCoverCache.set(trackId, null)
          throw err
        })
        .finally(() => {
          spotifyCoverInFlight.delete(trackId)
        })

      spotifyCoverInFlight.set(trackId, promise)
      return promise
    } catch (err) {
      if (debug) console.log('[spotify-cover] error', { trackId, context, err })
      return null
    }
  },

  async getPlaylists(userId?: string | number | null): Promise<PlaylistsResponse> {
    const token = getAuthToken()
    if (!token && !userId) {
      return { items: [] }
    }
    const response = await fetch(
      token
        ? `${API_BASE_URL}/me/playlists`
        : `${API_BASE_URL}/me/playlists?user_id=${encodeURIComponent(String(userId))}`,
      {
        method: 'GET',
        headers: token
          ? {
              accept: 'application/json',
              'X-Auth-Token': token,
            }
          : {
              accept: 'application/json',
            },
      },
    )

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to fetch playlists: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async getAvailablePlaylists(): Promise<AvailablePlaylistsResponse> {
    const token = getAuthToken()
    const response = await fetch(`${API_BASE_URL}/playlists/available`, {
      method: 'GET',
      headers: token
        ? {
            accept: 'application/json',
            'X-Auth-Token': token,
            'Authorization': `Bearer ${token}`,
          }
        : {
            accept: 'application/json',
          },
      credentials: getAuthCookieEnabled() ? 'include' : 'same-origin',
    })

    if (!response.ok) {
      throw new Error(`Failed to fetch available playlists: ${response.status} ${response.statusText}`)
    }

    return response.json()
  },

  async getAvailablePlaylistTracks(params: { endpoint: string; page?: number; limit?: number; requiresAuth?: boolean }): Promise<AvailablePlaylistTracksResponse> {
    const endpoint = params.endpoint?.trim() || '/'
    const page = params.page ?? 1
    const limit = params.limit ?? 75
    const requiresAuth = Boolean(params.requiresAuth) || endpoint.startsWith('/me/')

    const token = getAuthToken()
    if (requiresAuth && !token && !getAuthCookieEnabled()) {
      throw new Error('Not authenticated. Please use /verify in Telegram to get your token.')
    }

    const base = endpoint.startsWith('/') ? `${API_BASE_URL}${endpoint}` : `${API_BASE_URL}/${endpoint}`
    const url = new URL(base)
    if (!url.searchParams.has('page')) url.searchParams.set('page', String(page))
    if (!url.searchParams.has('limit')) url.searchParams.set('limit', String(limit))

    const response = await fetch(url.toString(), {
      method: 'GET',
      headers: token
        ? {
            accept: 'application/json',
            'X-Auth-Token': token,
            'Authorization': `Bearer ${token}`,
          }
        : {
            accept: 'application/json',
          },
      credentials: getAuthCookieEnabled() ? 'include' : 'same-origin',
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to fetch playlist tracks: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async getPlaylistTracks(params: {
    playlistId: string
    userId?: string | number | null
    page?: number
    limit?: number
  }): Promise<PlaylistTracksResponse> {
    const page = params.page ?? 1
    const limit = params.limit ?? 50
    const token = getAuthToken()
    if (!token && !params.userId) {
      return { page, per_page: limit, total: 0, items: [] }
    }
    const response = await fetch(
      token
        ? `${API_BASE_URL}/me/playlists/${encodeURIComponent(params.playlistId)}/tracks?page=${encodeURIComponent(
            String(page),
          )}&limit=${encodeURIComponent(String(limit))}`
        : `${API_BASE_URL}/me/playlists/${encodeURIComponent(params.playlistId)}/tracks?page=${encodeURIComponent(
            String(page),
          )}&limit=${encodeURIComponent(String(limit))}&user_id=${encodeURIComponent(String(params.userId))}`,
      {
        method: 'GET',
        headers: token
          ? {
              accept: 'application/json',
              'X-Auth-Token': token,
            }
          : {
              accept: 'application/json',
            },
      },
    )

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to fetch playlist tracks: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async addToFavourites(trackId: string): Promise<{ ok: boolean; already_exists: boolean }> {
    const token = getAuthToken()
    if (!token) {
      throw new Error('Not authenticated. Please use /verify in Telegram to get your token.')
    }

    const response = await fetch(`${API_BASE_URL}/me/favourites`, {
      method: 'POST',
      headers: {
        accept: 'application/json',
        'X-Auth-Token': token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ track_id: trackId }),
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to add to favourites: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async removeFromFavourites(trackId: string): Promise<{ ok: boolean; deleted: boolean }> {
    const token = getAuthToken()
    if (!token) {
      throw new Error('Not authenticated. Please use /verify in Telegram to get your token.')
    }

    const response = await fetch(`${API_BASE_URL}/me/favourites/${encodeURIComponent(trackId)}`, {
      method: 'DELETE',
      headers: {
        accept: 'application/json',
        'X-Auth-Token': token,
      },
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to remove from favourites: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },

  async getFavouriteIds(
    page: number = 1,
    limit: number = 200,
  ): Promise<{ page: number; per_page: number; total: number; ids: string[]; exists?: boolean; last_updated_at?: number }> {
    const token = getAuthToken()
    if (!token) {
      return { page, per_page: limit, total: 0, ids: [], exists: false }
    }

    const inFlightKey = `${token}:${page}:${limit}`
    const inFlightExisting = favouriteIdsInFlight.get(inFlightKey)
    if (inFlightExisting) return inFlightExisting

    const search = new URLSearchParams({
      page: String(page),
      limit: String(limit),
    }).toString()

    const promise = fetch(`${API_BASE_URL}/me/favourites/ids?${search}`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
        'X-Auth-Token': token,
      },
    })
      .then((response) => {
        if (!response.ok) {
          return { page, per_page: limit, total: 0, ids: [], exists: false }
        }
        return response.json() as Promise<{
          page: number
          per_page: number
          total: number
          ids: string[]
          exists?: boolean
          last_updated_at?: number
        }>
      })
      .finally(() => {
        favouriteIdsInFlight.delete(inFlightKey)
      })

    favouriteIdsInFlight.set(inFlightKey, promise)
    return promise
  },

  async getFavourites(
    page: number = 1,
    limit: number = 20,
  ): Promise<{ page: number; per_page: number; total: number; items: unknown[] }> {
    const token = getAuthToken()
    if (!token) {
      return { page, per_page: limit, total: 0, items: [] }
    }

    const search = new URLSearchParams({
      page: String(page),
      limit: String(limit),
    }).toString()

    const response = await fetch(`${API_BASE_URL}/me/favourites?${search}`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
        'X-Auth-Token': token,
      },
    })

    if (!response.ok) {
      return { page, per_page: limit, total: 0, items: [] }
    }

    return response.json()
  },

  async verify(initData: string): Promise<{ token: string }> {
    const response = await fetch(`${API_BASE_URL}/webapp/verify`, {
      method: 'POST',
      headers: {
        accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({ init_data: initData }).toString(),
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to verify: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    const result = await response.json()
    
    // Store the token
    if (result.token) {
      setAuthToken(result.token)
      await ensureAuthCookieFromToken(result.token)
    }
    
    return result
  },

  async login(username: string, password: string): Promise<{ ok: boolean; user_id: number; token: string; first_name?: string; profile_url?: string; photo_url?: string }> {
    const isTgAndroid = (() => {
      try {
        return platform.isTelegram && platform.isAndroid
      } catch {
        return false
      }
    })()

    const cookieDomain = getCookieDomainForApi()
    const tokenUrl = new URL(`${API_BASE_URL}/auth/login`)
    const cookieUrl = new URL(tokenUrl.toString())
    cookieUrl.searchParams.set('set_cookie', 'true')
    if (cookieDomain) cookieUrl.searchParams.set('cookie_domain', cookieDomain)

    const response = await fetch((isTgAndroid ? tokenUrl : cookieUrl).toString(), {
      method: 'POST',
      headers: {
        accept: 'application/json',
        'Content-Type': 'application/json',
      },
      credentials: isTgAndroid ? 'omit' : 'include',
      body: JSON.stringify({ username, password }),
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to login: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    const result = await response.json()
    
    // Store the token and user info
    if (result.token) {
      setAuthToken(result.token)
      await ensureAuthCookieFromToken(result.token)
    }
    if (result.first_name || result.user_id || result.profile_url || result.photo_url) {
      setAuthUserInfo({
        first_name: result.first_name,
        user_id: result.user_id,
        profile_url: result.profile_url,
        photo_url: result.photo_url,
      })
    }
    
    return result
  },

  async setCredentials(username: string, password: string): Promise<{ ok: boolean }> {
    const token = getAuthToken()
    if (!token) {
      throw new Error('Not authenticated. Please use /verify in Telegram to get your token.')
    }

    const response = await fetch(`${API_BASE_URL}/auth/credentials`, {
      method: 'POST',
      headers: {
        accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Auth-Token': token,
        'Authorization': `Bearer ${token}`,
      },
      credentials: 'omit',
      body: JSON.stringify({ username, password }),
    })

    if (!response.ok) {
      let body = ''
      try {
        body = await response.text()
      } catch {
        body = ''
      }
      throw new Error(
        `Failed to save credentials: ${response.status} ${response.statusText}${body ? ` - ${body}` : ''}`,
      )
    }

    return response.json()
  },
}
