import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { platform } from '../platform.js'
import { getAuthUserInfo, signOut } from '../services/api.js'
import './Header.css'

export const Header = memo(({ userFirstName }: { userFirstName?: string | null }) => {
  const navigate = useNavigate()
  const isTelegram = useMemo(() => platform.isTelegram, [])

  const normalizePhotoUrl = useCallback((raw: unknown) => {
    if (typeof raw !== 'string') return null
    const normalized = raw.trim().replace(/^`+|`+$/g, '').trim()
    return normalized || null
  }, [])

  const [authUserInfo, setAuthUserInfo] = useState(() => getAuthUserInfo())

  useEffect(() => {
    if (typeof window === 'undefined') return

    const refresh = () => {
      setAuthUserInfo(getAuthUserInfo())
    }

    refresh()
    window.addEventListener('streamw:authUserInfoChanged', refresh)
    window.addEventListener('storage', refresh)
    return () => {
      window.removeEventListener('streamw:authUserInfoChanged', refresh)
      window.removeEventListener('storage', refresh)
    }
  }, [])

  const authPhotoUrl = useMemo(() => {
    return normalizePhotoUrl(authUserInfo?.photo_url ?? authUserInfo?.profile_url)
  }, [authUserInfo?.photo_url, authUserInfo?.profile_url, normalizePhotoUrl])

  const effectiveUserName = useMemo(() => {
    if (userFirstName) return userFirstName
    if (authUserInfo?.first_name) return authUserInfo.first_name
    return null
  }, [userFirstName, authUserInfo])
  
  const displayName = useMemo(() => 
    effectiveUserName ? (effectiveUserName.length > 7 ? `${effectiveUserName.slice(0, 7)}…` : effectiveUserName) : '...',
    [effectiveUserName]
  )
  const initial = useMemo(() => effectiveUserName?.trim()?.slice(0, 1)?.toUpperCase() || '?', [effectiveUserName])
  const [telegramPhotoUrl, setTelegramPhotoUrl] = useState<string | null>(null)

  useEffect(() => {
    if (typeof window === 'undefined') return
    if (!isTelegram) return

    const readOnce = () => {
      const w = window as unknown as { Telegram?: { WebApp?: { initDataUnsafe?: unknown } } }
      const tg = w.Telegram?.WebApp as unknown as { initDataUnsafe?: { user?: { photo_url?: unknown } } } | undefined
      const raw = tg?.initDataUnsafe?.user?.photo_url ?? null
      const next = normalizePhotoUrl(raw)
      setTelegramPhotoUrl((prev) => (prev === next ? prev : next))
      return Boolean(next)
    }

    if (readOnce()) return

    let attempts = 0
    const tick = () => {
      attempts += 1
      if (readOnce()) return
      if (attempts >= 24) return
      window.setTimeout(tick, 250)
    }

    window.setTimeout(tick, 250)
  }, [isTelegram, normalizePhotoUrl])

  const profilePhotoUrl = useMemo(() => telegramPhotoUrl ?? authPhotoUrl, [authPhotoUrl, telegramPhotoUrl])
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const [isSearchOpen, setIsSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const floatingRef = useRef<HTMLElement | null>(null)
  const menuRef = useRef<HTMLDivElement | null>(null)
  const searchInputRef = useRef<HTMLInputElement | null>(null)

  const closeMenu = useCallback(() => setIsMenuOpen(false), [])
  const closeSearch = useCallback(() => setIsSearchOpen(false), [])
  const toggleMenu = useCallback(() => {
    setIsSearchOpen(false)
    setIsMenuOpen((prev) => !prev)
  }, [])
  const toggleSearch = useCallback(() => {
    setIsMenuOpen(false)
    setIsSearchOpen((prev) => !prev)
  }, [])

  const submitSearch = useCallback(() => {
    const q = searchQuery.trim()
    if (!q) return
    navigate(`/search?q=${encodeURIComponent(q)}`)
    setIsMenuOpen(false)
    setIsSearchOpen(false)
  }, [navigate, searchQuery])

  const handleAudioClick = useCallback(() => {
    navigate('/audio')
    setIsMenuOpen(false)
  }, [navigate])

  const handleProfileClick = useCallback(() => {
    navigate('/profile')
    setIsMenuOpen(false)
  }, [navigate])

  const handleSettingsClick = useCallback(() => {
    navigate('/settings')
    setIsMenuOpen(false)
  }, [navigate])

  const handleSignInClick = useCallback(() => {
    navigate('/login')
    setIsMenuOpen(false)
  }, [navigate])

  const handleSignOutClick = useCallback(() => {
    signOut()
    setIsMenuOpen(false)
    navigate('/', { replace: true })
    window.location.reload()
  }, [navigate])

  const hasAuthToken = useMemo(() => {
    if (typeof window === 'undefined') return false
    try {
      return Boolean(window.localStorage.getItem('streamw:auth:token'))
    } catch {
      return false
    }
  }, [])

  useEffect(() => {
    if (!isMenuOpen && !isSearchOpen) return

    const onPointerDown = (e: PointerEvent) => {
      const target = e.target
      if (!(target instanceof Node)) return
      if (isMenuOpen) {
        if (isTelegram) {
          if (floatingRef.current && floatingRef.current.contains(target)) return
        } else if (menuRef.current && menuRef.current.contains(target)) return
        closeMenu()
      }

      if (isSearchOpen) {
        if (floatingRef.current && floatingRef.current.contains(target)) return
        closeSearch()
      }
    }

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        closeMenu()
        closeSearch()
      }
    }

    document.addEventListener('pointerdown', onPointerDown, { passive: true })
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [isMenuOpen, isSearchOpen, isTelegram, closeMenu, closeSearch])

  useEffect(() => {
    if (!isSearchOpen) return
    const input = searchInputRef.current
    if (!input) return
    requestAnimationFrame(() => input.focus())
  }, [isSearchOpen])

  if (isTelegram) {
    return (
      <header
        className="header header--floating"
        data-mode={isSearchOpen ? 'search' : 'default'}
        ref={floatingRef}
      >
        <div className="floating-main">
          <div className="logo logo--floating">Music</div>
          <div className="floating-search-wrap">
            <input
              ref={searchInputRef}
              className="floating-search-input"
              type="search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key !== 'Enter') return
                e.preventDefault()
                submitSearch()
              }}
              placeholder="Search"
              aria-label="Search"
              autoComplete="off"
              inputMode="search"
            />
          </div>
        </div>
        <div className="floating-actions header-right" ref={menuRef}>
          <button
            className="floating-search-btn"
            type="button"
            aria-label={isSearchOpen ? 'Close search' : 'Open search'}
            aria-expanded={isSearchOpen ? 'true' : 'false'}
            onClick={toggleSearch}
          >
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path
                fill="currentColor"
                d="M10.5 3a7.5 7.5 0 1 0 4.52 13.49l4.5 4.5a1 1 0 0 0 1.42-1.42l-4.5-4.5A7.5 7.5 0 0 0 10.5 3Zm-5.5 7.5a5.5 5.5 0 1 1 11 0a5.5 5.5 0 0 1-11 0Z"
              />
            </svg>
          </button>
          {!isSearchOpen ? (
            <button
              className="profile-btn profile-btn--icon"
              type="button"
              aria-label={isMenuOpen ? 'Close profile menu' : 'Open profile menu'}
              aria-expanded={isMenuOpen ? 'true' : 'false'}
              onClick={toggleMenu}
            >
              <span className="profile-avatar" data-open={isMenuOpen ? 'true' : 'false'} aria-hidden="true">
                {profilePhotoUrl ? <img src={profilePhotoUrl} alt="" /> : <span className="profile-avatar-initial">{initial}</span>}
                <span className="profile-avatar-x" aria-hidden="true">
                  <span className="profile-avatar-x-line" />
                  <span className="profile-avatar-x-line" />
                </span>
              </span>
            </button>
          ) : null}
        </div>

        {isMenuOpen ? (
          <div className="profile-menu profile-menu--floating" role="menu" aria-label="Profile menu">
            <div className="profile-menu-header profile-menu-header--floating">
              <div className="profile-avatar profile-avatar-lg" aria-hidden="true">
                {profilePhotoUrl ? <img src={profilePhotoUrl} alt="" /> : initial}
              </div>
              <div className="profile-menu-name">{effectiveUserName || '...'}</div>
            </div>

            <div className="profile-menu-items">
              <button className="profile-menu-item profile-menu-item--floating" type="button" role="menuitem" onClick={handleProfileClick}>
                <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M12 12a4 4 0 1 0-4-4a4 4 0 0 0 4 4Zm0 2c-4.41 0-8 2.01-8 4.5a1.5 1.5 0 0 0 3 0c0-1.02 2.33-2.5 5-2.5s5 1.48 5 2.5a1.5 1.5 0 0 0 3 0C20 16.01 16.41 14 12 14Z" />
                </svg>
                <span>Profile</span>
              </button>
              <button className="profile-menu-item profile-menu-item--floating" type="button" role="menuitem" onClick={handleAudioClick}>
                <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M12 3a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3Zm-1 3a1 1 0 0 1 2 0v7a1 1 0 0 1-2 0V6Zm-4 6a1 1 0 0 1 1 1a4 4 0 0 0 8 0a1 1 0 1 1 2 0a6 6 0 0 1-5 5.91V21a1 1 0 1 1-2 0v-2.09A6 6 0 0 1 6 13a1 1 0 0 1 1-1Z" />
                </svg>
                <span>Audio</span>
              </button>
              <button className="profile-menu-item profile-menu-item--floating" type="button" role="menuitem" onClick={handleSettingsClick}>
                <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M19.14 12.94a7.98 7.98 0 0 0 .06-.94a7.98 7.98 0 0 0-.06-.94l2.03-1.58a.75.75 0 0 0 .18-.96l-1.92-3.32a.75.75 0 0 0-.9-.33l-2.39.96a7.4 7.4 0 0 0-1.63-.94l-.36-2.54A.75.75 0 0 0 12.4 1h-3.8a.75.75 0 0 0-.74.64l-.36 2.54c-.58.23-1.12.54-1.63.94l-2.39-.96a.75.75 0 0 0-.9.33L.66 7.81a.75.75 0 0 0 .18.96l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94L.84 14.52a.75.75 0 0 0-.18.96l1.92 3.32c.2.35.62.49.99.33l2.38-.96c.5.4 1.05.71 1.63.94l.36 2.54c.06.37.37.64.74.64h3.8c.37 0 .69-.27.74-.64l.36-2.54c.58-.23 1.12-.54 1.63-.94l2.39.96c.37.16.79.02.99-.33l1.92-3.32a.75.75 0 0 0-.18-.96l-2.03-1.58ZM10.5 15A3 3 0 1 1 13.5 12a3 3 0 0 1-3 3Z" />
                </svg>
                <span>Settings</span>
              </button>
              {!hasAuthToken && (
                <button className="profile-menu-item profile-menu-item--floating" type="button" role="menuitem" onClick={handleSignInClick}>
                  <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                    <path fill="currentColor" d="M12 12a4 4 0 1 0-4-4a4 4 0 0 0 4 4Zm0 2c-4.41 0-8 2.01-8 4.5a1.5 1.5 0 0 0 3 0c0-1.02 2.33-2.5 5-2.5s5 1.48 5 2.5a1.5 1.5 0 0 0 3 0C20 16.01 16.41 14 12 14Z" />
                  </svg>
                  <span>Sign In</span>
                </button>
              )}
              {hasAuthToken && !isTelegram && (
                <button className="profile-menu-item profile-menu-item--floating" type="button" role="menuitem" onClick={handleSignOutClick}>
                  <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
                    <path fill="currentColor" d="M5 21q-.825 0-1.413-.587Q3 19.825 3 19V5q0-.825.587-1.413Q4.175 3 5 3h7v2H5v14h7v2Zm11-4l-1.375-1.45l2.55-2.55H9v-2h8.175l-2.55-2.55L16 7l5 5Z"/>
                  </svg>
                  <span>Sign Out</span>
                </button>
              )}
            </div>
          </div>
        ) : null}
      </header>
    )
  }

  return (
    <header className="header">
      <div className="header-left" />
      <div className="logo">Music</div>
      <div className="header-right" ref={menuRef}>
        <button
          className="profile-btn"
          type="button"
          aria-label={isMenuOpen ? 'Close profile menu' : 'Open profile menu'}
          aria-expanded={isMenuOpen ? 'true' : 'false'}
          onClick={toggleMenu}
        >
          <span className="profile-avatar" data-open={isMenuOpen ? 'true' : 'false'} aria-hidden="true">
            {profilePhotoUrl ? <img src={profilePhotoUrl} alt="" /> : <span className="profile-avatar-initial">{initial}</span>}
            <span className="profile-avatar-x" aria-hidden="true">
              <span className="profile-avatar-x-line" />
              <span className="profile-avatar-x-line" />
            </span>
          </span>
          <span className="profile-name" title={userFirstName || undefined}>
            {displayName}
          </span>
        </button>

        {isMenuOpen ? (
          <div className="profile-menu" role="menu" aria-label="Profile menu">
            <div className="profile-menu-header">
              <div className="profile-avatar profile-avatar-lg" aria-hidden="true">
                {profilePhotoUrl ? <img src={profilePhotoUrl} alt="" /> : initial}
              </div>
              <div className="profile-menu-name">{effectiveUserName || '...'}</div>
            </div>
            <button className="profile-menu-item" type="button" role="menuitem" onClick={handleProfileClick}>
              Profile
            </button>
            <button className="profile-menu-item" type="button" role="menuitem" onClick={handleAudioClick}>
              Audio
            </button>
            <button className="profile-menu-item" type="button" role="menuitem" onClick={handleSettingsClick}>
              Settings
            </button>
            {!hasAuthToken && (
              <button className="profile-menu-item" type="button" role="menuitem" onClick={handleSignInClick}>
                Sign In
              </button>
            )}
            {hasAuthToken && (
              <button className="profile-menu-item" type="button" role="menuitem" onClick={handleSignOutClick}>
                Sign Out
              </button>
            )}
          </div>
        ) : null}
      </div>
    </header>
  )
})

Header.displayName = 'Header'
