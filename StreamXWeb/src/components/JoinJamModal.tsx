import { memo, useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { joinJam } from '../services/jamApi.js'
import './StartJamModal.css'
import './JoinJamModal.css'

interface JoinJamModalProps {
  onClose: () => void
}

const AUDIO_UNLOCK_KEY = '__streamw_audio_unlocked__'

const unlockAudioOnce = async () => {
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

export const JoinJamModal = memo(({ onClose }: JoinJamModalProps) => {
  const navigate = useNavigate()
  const [jamId, setJamId] = useState('')
  const [loading, setLoading] = useState(false)

  const handleJoin = useCallback(async () => {
    const raw = jamId.trim()
    if (!raw) return

    const extracted =
      raw.match(/\/jam\/([^/?#]+)/i)?.[1] ||
      raw.match(/\b(jam_[a-f0-9]{16,})\b/i)?.[1] ||
      raw

    const normalized = extracted.trim()
    if (!normalized || normalized === 'undefined' || normalized === 'null') return

    await unlockAudioOnce()

    setLoading(true)
    try {
      const result = await joinJam(normalized)
      try {
        window.localStorage.setItem('streamw:jam:activeId', normalized)
      } catch {
        void 0
      }
      navigate(`/jam/${normalized}`, { state: { initialJam: result?.jam ?? null } })
    } catch (err) {
      console.error('Failed to join jam:', err)
      alert('Failed to join jam session. Please check the ID and try again.')
      setLoading(false)
    }
  }, [jamId, navigate])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && jamId.trim() && !loading) {
        handleJoin()
      }
    },
    [jamId, loading, handleJoin]
  )

  return (
    <div className="jam-modal-backdrop" onClick={onClose}>
      <div className="jam-modal jam-modal--join" onClick={(e) => e.stopPropagation()}>
        <div className="jam-modal-header">
          <h2 className="jam-modal-title">Join Jam Session</h2>
          <button className="jam-modal-close" onClick={onClose} aria-label="Close">
            <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path
                fill="currentColor"
                d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
              />
            </svg>
          </button>
        </div>

        <div className="jam-modal-content">
          <div className="jam-join-icon">
            <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path
                fill="currentColor"
                d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"
              />
            </svg>
          </div>

          <p className="jam-join-description">
            Enter the Jam Session ID or paste the invite link to join
          </p>

          <input
            type="text"
            className="jam-join-input"
            placeholder="Jam ID or link"
            value={jamId}
            onChange={(e) => setJamId(e.target.value)}
            onKeyDown={handleKeyDown}
            autoFocus
          />
        </div>

        <div className="jam-modal-footer">
          <button className="jam-modal-btn jam-modal-btn--cancel" onClick={onClose} type="button">
            Cancel
          </button>
          <button
            className="jam-modal-btn jam-modal-btn--start"
            onClick={handleJoin}
            disabled={!jamId.trim() || loading}
            type="button"
          >
            {loading ? 'Joining...' : 'Join Jam'}
          </button>
        </div>
      </div>
    </div>
  )
})

JoinJamModal.displayName = 'JoinJamModal'
