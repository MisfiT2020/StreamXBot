import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type PointerEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { CACHE_TTL_MS, clearAppCache, getCacheEnabled, getRedSelectorEnabled, getWebSongListEnabled, getAutoHidePlayerEnabled, getThemeMode, getFloatingNavTopPad, setCacheEnabled, setRedSelectorEnabled, setWebSongListEnabled, setAutoHidePlayerEnabled, setThemeMode, setFloatingNavTopPad } from '../services/api.js'
import { platform } from '../platform.js'
import './Home.css'

const HOLD_TO_CLEAR_MS = 2000
const CLEAR_FEEDBACK_MS = 900

export const SettingsPage = () => {
  const navigate = useNavigate()
  const isTelegram = platform.isTelegram
  const [cacheEnabledState, setCacheEnabledState] = useState(getCacheEnabled())
  const [webSongListEnabledState, setWebSongListEnabledState] = useState(getWebSongListEnabled())
  const [redSelectorEnabledState, setRedSelectorEnabledState] = useState(getRedSelectorEnabled())
  const [autoHidePlayerEnabledState, setAutoHidePlayerEnabledState] = useState(getAutoHidePlayerEnabled())
  const [themeModeState, setThemeModeState] = useState(getThemeMode())
  const [floatingNavTopPadState, setFloatingNavTopPadState] = useState<number | ''>(() => {
    const initial = getFloatingNavTopPad()
    return initial == null ? '' : initial
  })
  const [justCleared, setJustCleared] = useState(false)

  const holdTimerRef = useRef<number | null>(null)
  const holdStartedAtRef = useRef<number | null>(null)
  const clearFeedbackTimerRef = useRef<number | null>(null)

  const cacheTtlLabel = useMemo(() => {
    const hours = CACHE_TTL_MS / (60 * 60 * 1000)
    return Number.isFinite(hours) ? `${hours}h` : '3h'
  }, [])

  useEffect(() => {
    return () => {
      if (holdTimerRef.current !== null) {
        window.clearInterval(holdTimerRef.current)
        holdTimerRef.current = null
      }
      if (clearFeedbackTimerRef.current !== null) {
        window.clearTimeout(clearFeedbackTimerRef.current)
        clearFeedbackTimerRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    const sync = () => setThemeModeState(getThemeMode())
    window.addEventListener('streamw:themeChanged', sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener('streamw:themeChanged', sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  const goBack = useCallback(() => {
    if (window.history.length > 1) {
      navigate(-1)
    } else {
      navigate('/', { replace: true })
    }
  }, [navigate])

  const onCacheToggle = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.checked
    setCacheEnabledState(next)
    setCacheEnabled(next)
  }, [])

  const onWebSongListToggle = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.checked
    setWebSongListEnabledState(next)
    setWebSongListEnabled(next)
  }, [])

  const onRedSelectorToggle = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.checked
    setRedSelectorEnabledState(next)
    setRedSelectorEnabled(next)
  }, [])

  const onAutoHidePlayerToggle = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.checked
    setAutoHidePlayerEnabledState(next)
    setAutoHidePlayerEnabled(next)
  }, [])

  const onThemeModeChange = useCallback((next: 'dark' | 'light') => {
    setThemeModeState(next)
    setThemeMode(next)
  }, [])

  const onFloatingNavTopPadChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value
    if (raw === '') {
      setFloatingNavTopPadState('')
      setFloatingNavTopPad(null)
      return
    }
    const parsed = Number.parseInt(raw, 10)
    if (!Number.isFinite(parsed)) return
    const clamped = Math.max(-40, Math.min(80, parsed))
    setFloatingNavTopPadState(clamped)
    setFloatingNavTopPad(clamped)
  }, [])

  const stopHold = useCallback(() => {
    if (holdTimerRef.current !== null) {
      window.clearInterval(holdTimerRef.current)
      holdTimerRef.current = null
    }
    holdStartedAtRef.current = null
  }, [])

  const startHold = useCallback(
    (e: PointerEvent<HTMLButtonElement>) => {
      if (holdTimerRef.current !== null) return
      setJustCleared(false)

      try {
        e.currentTarget.setPointerCapture(e.pointerId)
      } catch {
        void 0
      }

      const startAt = performance.now()
      holdStartedAtRef.current = startAt

      holdTimerRef.current = window.setInterval(() => {
        const startedAt = holdStartedAtRef.current
        if (startedAt === null) return
        const elapsed = Math.max(0, performance.now() - startedAt)
        if (elapsed >= HOLD_TO_CLEAR_MS) {
          stopHold()
          clearAppCache()
          setJustCleared(true)
          if (clearFeedbackTimerRef.current !== null) window.clearTimeout(clearFeedbackTimerRef.current)
          clearFeedbackTimerRef.current = window.setTimeout(() => {
            clearFeedbackTimerRef.current = null
            setJustCleared(false)
          }, CLEAR_FEEDBACK_MS)
          return
        }
      }, 40)
    },
    [stopHold],
  )

  const clearButtonLabel = !cacheEnabledState ? 'Disabled' : 'Hold 2s'

  return (
    <div className="prefs-page prefs-page--settings">
      <header className="prefs-header">
        {isTelegram && (
          <button className="prefs-back" type="button" onClick={goBack} aria-label="Back">
            <svg className="prefs-back-icon" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
            </svg>
          </button>
        )}
        <div className="prefs-header-text">
          <div className="prefs-title">Settings</div>
          <div className="prefs-subtitle">App and interface preferences</div>
        </div>
      </header>

      <main className="content">
        <div className="prefs-container">
          <section className="prefs-section" aria-label="Storage">
            <div className="prefs-section-title">Storage</div>
            <div className="prefs-card">
              <div className="prefs-row">
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Save cache</div>
                  <div className="prefs-row-desc">Store latest tracks and playlists (refreshes every {cacheTtlLabel}).</div>
                </div>
                <label className="material-switch" aria-label="Save cache">
                  <input type="checkbox" checked={cacheEnabledState} onChange={onCacheToggle} />
                  <span className="material-switch-track" aria-hidden="true">
                    <span className="material-switch-thumb" />
                  </span>
                </label>
              </div>
              <div className="prefs-divider" aria-hidden="true" />
              <div className="prefs-row" data-disabled={cacheEnabledState ? 'false' : 'true'}>
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Clear cache</div>
                  <div className="prefs-row-desc">
                    {cacheEnabledState ? 'Hold for 2 seconds to delete cached playlists and latest tracks.' : 'Enable Save cache to clear stored playlists and tracks.'}
                  </div>
                </div>
                <div className="prefs-segment" role="group" aria-label="Clear cache">
                  <button
                    className={`prefs-segment-btn prefs-segment-btn--hold${justCleared ? ' is-active' : ''}`}
                    type="button"
                    disabled={!cacheEnabledState}
                    onPointerDown={cacheEnabledState ? startHold : undefined}
                    onPointerUp={stopHold}
                    onPointerCancel={stopHold}
                    onPointerLeave={stopHold}
                  >
                    {clearButtonLabel}
                  </button>
                </div>
              </div>
            </div>
          </section>

          <section className="prefs-section" aria-label="Interface">
            <div className="prefs-section-title">Interface</div>
            <div className="prefs-card">
              <div className="prefs-row">
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Theme</div>
                  <div className="prefs-row-desc">Choose a light or dark look.</div>
                </div>
                <div className="prefs-segment" role="group" aria-label="Theme">
                  <button
                    className={`prefs-segment-btn${themeModeState === 'dark' ? ' is-active' : ''}`}
                    type="button"
                    onClick={() => onThemeModeChange('dark')}
                  >
                    Dark
                  </button>
                  <button
                    className={`prefs-segment-btn${themeModeState === 'light' ? ' is-active' : ''}`}
                    type="button"
                    onClick={() => onThemeModeChange('light')}
                  >
                    Light
                  </button>
                </div>
              </div>
              <div className="prefs-divider" aria-hidden="true" />
              <div className="prefs-row">
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Web Songlist</div>
                  <div className="prefs-row-desc">Switch Latest Songs to the web-style list.</div>
                </div>
                <label className="material-switch" aria-label="Web Songlist toggle">
                  <input type="checkbox" checked={webSongListEnabledState} onChange={onWebSongListToggle} />
                  <span className="material-switch-track" aria-hidden="true">
                    <span className="material-switch-thumb" />
                  </span>
                </label>
              </div>
              <div className="prefs-divider" aria-hidden="true" />
              <div className="prefs-row">
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Red Selector</div>
                  <div className="prefs-row-desc">Highlight the current Latest Songs track in red.</div>
                </div>
                <label className="material-switch" aria-label="Red Selector toggle">
                  <input type="checkbox" checked={redSelectorEnabledState} onChange={onRedSelectorToggle} />
                  <span className="material-switch-track" aria-hidden="true">
                    <span className="material-switch-thumb" />
                  </span>
                </label>
              </div>
              <div className="prefs-divider" aria-hidden="true" />
              <div className="prefs-row">
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Auto Hide Player</div>
                  <div className="prefs-row-desc">Hide the player when nothing is playing.</div>
                </div>
                <label className="material-switch" aria-label="Auto Hide Player toggle">
                  <input type="checkbox" checked={autoHidePlayerEnabledState} onChange={onAutoHidePlayerToggle} />
                  <span className="material-switch-track" aria-hidden="true">
                    <span className="material-switch-thumb" />
                  </span>
                </label>
              </div>
              <div className="prefs-divider" aria-hidden="true" />
              <div className="prefs-row">
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Floating navbar offset</div>
                  <div className="prefs-row-desc">
                    Adjust Telegram floating navbar position in pixels. Positive moves it down, negative up. Empty uses default.
                  </div>
                </div>
                <input
                  type="number"
                  className="profile-page-input"
                  value={floatingNavTopPadState}
                  onChange={onFloatingNavTopPadChange}
                  min={-40}
                  max={80}
                  step={1}
                  placeholder="15"
                  aria-label="Floating navbar offset in pixels"
                />
              </div>
            </div>
          </section>
        </div>
      </main>
      <div className={`prefs-toast${justCleared ? ' is-visible' : ''}`} role="status" aria-live="polite">
        Cleared
      </div>
    </div>
  )
}
