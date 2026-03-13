import { useCallback, type ChangeEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import { platform } from '../platform.js'
import './Home.css'

export const AudioPage = () => {
  const navigate = useNavigate()
  const { streamMode, setStreamMode, isSyncLyricsOn, setIsSyncLyricsOn } = usePlayerPlayback()
  const isTelegram = platform.isTelegram

  const aggressiveEnabled = streamMode === 'aggressive'

  const goBack = useCallback(() => {
    if (window.history.length > 1) {
      navigate(-1)
    } else {
      navigate('/', { replace: true })
    }
  }, [navigate])

  const onAggressiveToggle = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      const enabled = e.target.checked
      setStreamMode(enabled ? 'aggressive' : 'balanced')
    },
    [setStreamMode],
  )

  const onSyncLyricsToggle = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setIsSyncLyricsOn(e.target.checked)
    },
    [setIsSyncLyricsOn],
  )

  return (
    <div className="prefs-page prefs-page--audio">
      <header className="prefs-header">
        {isTelegram && (
          <button className="prefs-back" type="button" onClick={goBack} aria-label="Back">
            <svg className="prefs-back-icon" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
            </svg>
          </button>
        )}
        <div className="prefs-header-text">
          <div className="prefs-title">Audio</div>
          <div className="prefs-subtitle">Playback, streaming, and lyrics</div>
        </div>
      </header>

      <main className="content">
        <div className="prefs-container">
          <section className="prefs-section" aria-label="Streaming">
            <div className="prefs-section-title">Streaming</div>
            <div className="prefs-card">
              <div className="prefs-row">
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Aggressive Streaming</div>
                  <div className="prefs-row-desc">Fetch the full track while playing to enable faster seeking.</div>
                </div>
                <label className="material-switch" aria-label="Aggressive Streaming">
                  <input type="checkbox" checked={aggressiveEnabled} onChange={onAggressiveToggle} />
                  <span className="material-switch-track" aria-hidden="true">
                    <span className="material-switch-thumb" />
                  </span>
                </label>
              </div>
              <div className="prefs-divider" aria-hidden="true" />
              <div className="prefs-row" data-disabled={aggressiveEnabled ? 'true' : 'false'}>
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Streaming Mode</div>
                  <div className="prefs-row-desc">{aggressiveEnabled ? 'Aggressive overrides mode.' : 'Balanced is recommended for most users.'}</div>
                </div>
                <div className="prefs-segment" role="group" aria-label="Streaming Mode">
                  <button
                    className={`prefs-segment-btn${streamMode === 'balanced' ? ' is-active' : ''}`}
                    type="button"
                    disabled={aggressiveEnabled}
                    onClick={() => setStreamMode('balanced')}
                  >
                    Balanced
                  </button>
                  <button
                    className={`prefs-segment-btn${streamMode === 'saver' ? ' is-active' : ''}`}
                    type="button"
                    disabled={aggressiveEnabled}
                    onClick={() => setStreamMode('saver')}
                  >
                    Saver
                  </button>
                </div>
              </div>
            </div>
          </section>

          <section className="prefs-section" aria-label="Lyrics">
            <div className="prefs-section-title">Lyrics</div>
            <div className="prefs-card">
              <div className="prefs-row">
                <div className="prefs-row-text">
                  <div className="prefs-row-name">Sync Lyrics</div>
                  <div className="prefs-row-desc">Highlight lyrics as they play. Disable for reading mode.</div>
                </div>
                <label className="material-switch" aria-label="Sync Lyrics">
                  <input type="checkbox" checked={isSyncLyricsOn} onChange={onSyncLyricsToggle} />
                  <span className="material-switch-track" aria-hidden="true">
                    <span className="material-switch-thumb" />
                  </span>
                </label>
              </div>
            </div>
          </section>
        </div>
      </main>
    </div>
  )
}
