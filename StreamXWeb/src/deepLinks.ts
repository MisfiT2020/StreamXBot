import { platform } from './platform.js'
import { API_BASE_URL } from './services/api.js'

const APP_SCHEME = 'streamx'
const APP_PACKAGE = 'com.xstream.music'
const API_QUERY_PARAMETER = 'api'
const OPEN_APP_QUERY_PARAMETER = 'openApp'

export type StreamXTarget =
  | { kind: 'playlist'; id: string }
  | { kind: 'jam'; id: string }
  | { kind: 'album'; id: string }
  | { kind: 'track'; id: string }

export const normalizeApiBaseUrl = (raw: unknown): string | null => {
  if (typeof raw !== 'string') return null
  const trimmed = raw.trim()
  if (!trimmed || !/^https?:\/\//i.test(trimmed)) return null

  try {
    const url = new URL(trimmed)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
    url.hash = ''
    return url.toString().replace(/\/+$/, '')
  } catch {
    return null
  }
}

export const getRuntimeApiBaseUrl = (search: string, fallback = API_BASE_URL): string => {
  const params = new URLSearchParams(search)
  const apiFromQuery = normalizeApiBaseUrl(params.get(API_QUERY_PARAMETER))
  if (apiFromQuery) return apiFromQuery

  if (typeof window !== 'undefined') {
    const currentOrigin = normalizeApiBaseUrl(window.location.origin)
    if (currentOrigin && /^\/share\/(playlists|jam|albums|tracks)\//i.test(window.location.pathname)) {
      return currentOrigin
    }
  }

  return fallback
}

export const isAutoOpenLink = (search: string): boolean => {
  const params = new URLSearchParams(search)
  return params.get(OPEN_APP_QUERY_PARAMETER) === '1'
}

export const stripAutoOpenFromUrl = (href: string): string => {
  try {
    const url = new URL(href)
    url.searchParams.delete(OPEN_APP_QUERY_PARAMETER)
    return url.toString()
  } catch {
    return href
  }
}

const resolveApiOrigin = (apiBaseUrl?: string | null, origin?: string): string => {
  return normalizeApiBaseUrl(apiBaseUrl) ?? normalizeApiBaseUrl(origin) ?? API_BASE_URL
}

const buildBackendRoute = (path: string, apiBaseUrl?: string | null, autoOpen = true, origin?: string): string => {
  const normalizedApiBaseUrl = resolveApiOrigin(apiBaseUrl, origin)
  const url = new URL(path, normalizedApiBaseUrl)

  if (autoOpen) {
    url.searchParams.set(OPEN_APP_QUERY_PARAMETER, '1')
  }

  return url.toString()
}

export const buildSharedPlaylistLink = (
  playlistId: string,
  apiBaseUrl?: string | null,
  options?: { autoOpen?: boolean; origin?: string },
): string =>
  buildBackendRoute(`/share/playlists/${encodeURIComponent(playlistId)}`, apiBaseUrl, options?.autoOpen ?? true, options?.origin)

export const buildJamLink = (
  jamId: string,
  apiBaseUrl?: string | null,
  options?: { autoOpen?: boolean; origin?: string },
): string =>
  buildBackendRoute(`/share/jam/${encodeURIComponent(jamId)}`, apiBaseUrl, options?.autoOpen ?? true, options?.origin)

export const buildSharedAlbumLink = (
  albumId: string,
  apiBaseUrl?: string | null,
  options?: { autoOpen?: boolean; origin?: string },
): string =>
  buildBackendRoute(`/share/albums/${encodeURIComponent(albumId)}`, apiBaseUrl, options?.autoOpen ?? true, options?.origin)

export const buildSharedTrackLink = (
  trackId: string,
  apiBaseUrl?: string | null,
  options?: { autoOpen?: boolean; origin?: string },
): string =>
  buildBackendRoute(`/share/tracks/${encodeURIComponent(trackId)}`, apiBaseUrl, options?.autoOpen ?? true, options?.origin)

const buildCustomSchemeUrl = (target: StreamXTarget, apiBaseUrl?: string | null): string => {
  const url = new URL(`${APP_SCHEME}://${target.kind}/${encodeURIComponent(target.id)}`)
  const normalizedApiBaseUrl = normalizeApiBaseUrl(apiBaseUrl)

  if (normalizedApiBaseUrl) {
    url.searchParams.set(API_QUERY_PARAMETER, normalizedApiBaseUrl)
  }

  return url.toString()
}

const buildAndroidIntentUrl = (target: StreamXTarget, apiBaseUrl: string | null, fallbackUrl: string): string => {
  const query = apiBaseUrl ? `?${API_QUERY_PARAMETER}=${encodeURIComponent(apiBaseUrl)}` : ''
  return `intent://${target.kind}/${encodeURIComponent(target.id)}${query}#Intent;scheme=${APP_SCHEME};package=${APP_PACKAGE};S.browser_fallback_url=${encodeURIComponent(fallbackUrl)};end;`
}

export const canOpenStreamXApp = (): boolean => !platform.isDesktop && !platform.isTelegram

export const openStreamXTarget = (
  target: StreamXTarget,
  options?: {
    apiBaseUrl?: string | null
    fallbackUrl?: string
  },
): void => {
  if (typeof window === 'undefined') return

  const normalizedApiBaseUrl = normalizeApiBaseUrl(options?.apiBaseUrl)
  const fallbackUrl = stripAutoOpenFromUrl(options?.fallbackUrl ?? window.location.href)

  if (platform.isAndroid) {
    window.location.replace(buildAndroidIntentUrl(target, normalizedApiBaseUrl, fallbackUrl))
    return
  }

  const customSchemeUrl = buildCustomSchemeUrl(target, normalizedApiBaseUrl)

  if (platform.isIOS) {
    let timeoutId: number | null = null
    const onVisibilityChange = () => {
      if (document.hidden && timeoutId != null) {
        window.clearTimeout(timeoutId)
        document.removeEventListener('visibilitychange', onVisibilityChange)
      }
    }

    document.addEventListener('visibilitychange', onVisibilityChange)

    timeoutId = window.setTimeout(() => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      if (!document.hidden && fallbackUrl !== window.location.href) {
        window.location.replace(fallbackUrl)
      }
    }, 1500)

    window.location.assign(customSchemeUrl)
    return
  }

  window.location.assign(customSchemeUrl)
}

export const parseJamInviteInput = (raw: string): { jamId: string; apiBaseUrl?: string } | null => {
  const trimmed = raw.trim()
  if (!trimmed) return null

  let parsedUrl: URL | null = null
  try {
    parsedUrl = new URL(trimmed)
  } catch {
    try {
      if (/^[A-Za-z0-9.-]+\.[A-Za-z]{2,}/.test(trimmed)) {
        parsedUrl = new URL(`https://${trimmed}`)
      }
    } catch {
      parsedUrl = null
    }
  }

  if (parsedUrl) {
    const apiBaseUrl = normalizeApiBaseUrl(parsedUrl.searchParams.get(API_QUERY_PARAMETER)) ?? undefined
    if (
      parsedUrl.protocol === `${APP_SCHEME}:` &&
      (parsedUrl.hostname === 'jam' || parsedUrl.hostname === 'join-jam')
    ) {
      const jamId = parsedUrl.pathname.split('/').filter(Boolean)[0]?.trim()
      if (jamId) return { jamId, apiBaseUrl }
    }

    const jamIdFromPath =
      parsedUrl.pathname.match(/\/share\/jam\/([^/?#]+)/i)?.[1]?.trim() ||
      parsedUrl.pathname.match(/\/jam\/([^/?#]+)/i)?.[1]?.trim()
    if (jamIdFromPath) {
      return {
        jamId: jamIdFromPath,
        apiBaseUrl: apiBaseUrl ?? normalizeApiBaseUrl(parsedUrl.origin) ?? undefined,
      }
    }
  }

  const jamId =
    trimmed.match(/\/jam\/([^/?#]+)/i)?.[1]?.trim() ||
    trimmed.match(/\b(jam_[a-f0-9]{16,})\b/i)?.[1]?.trim() ||
    trimmed

  if (!jamId || jamId === 'undefined' || jamId === 'null') return null
  return { jamId }
}
