import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { platform } from '../platform.js'
import { api, getAuthUserInfo } from '../services/api.js'
import './ProfilePage.css'

export const ProfilePage = () => {
  const navigate = useNavigate()
  const isTelegram = platform.isTelegram
  const [authUserInfo, setAuthUserInfoState] = useState(() => getAuthUserInfo())
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [telegramPhotoUrl, setTelegramPhotoUrl] = useState<string | null>(null)

  const normalizePhotoUrl = useCallback((raw: unknown) => {
    if (typeof raw !== 'string') return null
    const normalized = raw.trim().replace(/^`+|`+$/g, '').trim()
    return normalized || null
  }, [])

  // Get Telegram photo URL
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

  const authPhotoUrl = useMemo(() => {
    return normalizePhotoUrl(authUserInfo?.photo_url ?? authUserInfo?.profile_url)
  }, [authUserInfo?.photo_url, authUserInfo?.profile_url, normalizePhotoUrl])

  const profilePhotoUrl = useMemo(() => telegramPhotoUrl ?? authPhotoUrl, [authPhotoUrl, telegramPhotoUrl])

  const handleManualLogin = useCallback(async (e: React.FormEvent) => {
    e.preventDefault()
    if (!username || !password) {
      setError('Please enter both username and password')
      return
    }

    setLoading(true)
    setError(null)

    try {
      await api.setCredentials(username, password)

      // Clear form
      setUsername('')
      setPassword('')
      
      // Navigate to home
      navigate('/', { replace: true })
    } catch (err) {
      console.error('Login error:', err)
      setError(err instanceof Error ? err.message : 'Login failed')
    } finally {
      setLoading(false)
    }
  }, [username, password, navigate])

  useEffect(() => {
    const refresh = () => {
      setAuthUserInfoState(getAuthUserInfo())
    }

    window.addEventListener('streamw:authUserInfoChanged', refresh)
    return () => {
      window.removeEventListener('streamw:authUserInfoChanged', refresh)
    }
  }, [])

  return (
    <div className="profile-page">
      <div className="profile-page-content">
        <div className="profile-page-header">
          {isTelegram && (
            <button className="profile-page-back" onClick={() => navigate(-1)} aria-label="Back">
              <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
              </svg>
            </button>
          )}
          <h1 className="profile-page-title">Profile</h1>
        </div>

        <div className="profile-page-card">
          <div className="profile-page-avatar">
            {profilePhotoUrl ? (
              <img src={profilePhotoUrl} alt={authUserInfo?.first_name || 'Profile'} />
            ) : (
              <div className="profile-page-avatar-placeholder">
                <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <path fill="currentColor" d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" />
                </svg>
              </div>
            )}
          </div>

          <div className="profile-page-info">
            <div className="profile-page-name">
              {authUserInfo?.first_name || 'Guest'}
            </div>
            <div className="profile-page-userid">
              User ID: {authUserInfo?.user_id || 'Not logged in'}
            </div>
          </div>
        </div>

        {isTelegram && (
          <div className="profile-page-login">
            <h2 className="profile-page-section-title">Manual Login</h2>
            <form onSubmit={handleManualLogin} className="profile-page-form">
              <div className="profile-page-field">
                <label htmlFor="username" className="profile-page-label">
                  Username
                </label>
                <input
                  id="username"
                  type="text"
                  className="profile-page-input"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="Enter username"
                  disabled={loading}
                  autoComplete="username"
                />
              </div>

              <div className="profile-page-field">
                <label htmlFor="password" className="profile-page-label">
                  Password
                </label>
                <input
                  id="password"
                  type="password"
                  className="profile-page-input"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter password"
                  disabled={loading}
                  autoComplete="current-password"
                />
              </div>

              {error && (
                <div className="profile-page-error">
                  {error}
                </div>
              )}

              <button
                type="submit"
                className="profile-page-submit"
                disabled={loading || !username || !password}
              >
                {loading ? 'Logging in...' : 'Save'}
              </button>
            </form>
          </div>
        )}
      </div>
    </div>
  )
}
