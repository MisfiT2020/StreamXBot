import { memo, useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { usePlayerLibrary } from '../context/PlayerContext.js'
import { StartJamModal } from './StartJamModal.js'
import { JoinJamModal } from './JoinJamModal.js'
import { jamLeave } from '../services/jamApi.js'
import './JamSession.css'

const JAM_ACTIVE_KEY = 'streamw:jam:activeId'

export const JamSession = memo(() => {
  const { songs } = usePlayerLibrary()
  const navigate = useNavigate()
  const [showStartModal, setShowStartModal] = useState(false)
  const [showJoinModal, setShowJoinModal] = useState(false)
  const [activeJamId, setActiveJamId] = useState<string | null>(() => {
    try {
      return window.localStorage.getItem(JAM_ACTIVE_KEY)
    } catch {
      return null
    }
  })

  // Listen for storage changes and navigation
  useEffect(() => {
    const checkActiveJam = () => {
      try {
        const id = window.localStorage.getItem(JAM_ACTIVE_KEY)
        setActiveJamId(id)
      } catch {
        setActiveJamId(null)
      }
    }

    // Check on mount and when window gains focus
    checkActiveJam()
    window.addEventListener('focus', checkActiveJam)
    window.addEventListener('storage', checkActiveJam)

    return () => {
      window.removeEventListener('focus', checkActiveJam)
      window.removeEventListener('storage', checkActiveJam)
    }
  }, [])

  const handleStartJam = useCallback(() => {
    setShowStartModal(true)
  }, [])

  const handleJoinJam = useCallback(() => {
    setShowJoinModal(true)
  }, [])

  const handleOpenActiveJam = useCallback(() => {
    const id = (activeJamId || '').trim()
    if (!id || id === 'undefined' || id === 'null') return
    navigate(`/jam/${id}`)
  }, [activeJamId, navigate])

  const handleLeaveActiveJam = useCallback(async () => {
    const id = (activeJamId || '').trim()
    if (!id || id === 'undefined' || id === 'null') return
    
    try {
      // Call the leave API
      await jamLeave(id)
      console.log('Successfully left jam:', id)
    } catch (err) {
      console.error('Failed to leave jam:', err)
      // Continue to remove from localStorage even if API call fails
    }
    
    // Remove from localStorage
    try {
      const current = window.localStorage.getItem(JAM_ACTIVE_KEY)
      if (current === id) window.localStorage.removeItem(JAM_ACTIVE_KEY)
    } catch {
      void 0
    }
    
    setActiveJamId(null)
  }, [activeJamId])

  return (
    <>
      <section className="jam-session-section">
        <h2 className="jam-session-title">
          <span className="jam-session-title-text">Jam Session</span>
        </h2>
        
        <div className="jam-session-buttons">
          {activeJamId ? (
            <div className="jam-session-playing-card">
              <button
                className="jam-session-playing-main"
                type="button"
                onClick={handleOpenActiveJam}
              >
                <div className="jam-session-btn-icon">
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path
                      fill="currentColor"
                      d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"
                    />
                  </svg>
                </div>
                <span className="jam-session-btn-text">Playing</span>
              </button>

              <button
                className="jam-session-playing-leave"
                type="button"
                onClick={handleLeaveActiveJam}
              >
                <div className="jam-session-btn-icon">
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path
                      fill="currentColor"
                      d="M10.09 15.59L11.5 17l5-5-5-5-1.41 1.41L12.67 11H3v2h9.67l-2.58 2.59zM19 3H5c-1.11 0-2 .9-2 2v4h2V5h14v14H5v-4H3v4c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z"
                    />
                  </svg>
                </div>
                <span className="jam-session-btn-text">Leave</span>
              </button>
            </div>
          ) : (
            <>
              <button 
                className="jam-session-btn jam-session-btn--start" 
                type="button"
                onClick={handleStartJam}
              >
                <div className="jam-session-btn-icon">
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path
                      fill="currentColor"
                      d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"
                    />
                  </svg>
                </div>
                <span className="jam-session-btn-text">Start Jam</span>
              </button>

              <button 
                className="jam-session-btn jam-session-btn--join" 
                type="button"
                onClick={handleJoinJam}
              >
                <div className="jam-session-btn-icon">
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path
                      fill="currentColor"
                      d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"
                    />
                  </svg>
                </div>
                <span className="jam-session-btn-text">Join Jam</span>
              </button>
            </>
          )}
        </div>
      </section>

      {showStartModal && <StartJamModal songs={songs} onClose={() => setShowStartModal(false)} />}
      {showJoinModal && <JoinJamModal onClose={() => setShowJoinModal(false)} />}
    </>
  )
})

JamSession.displayName = 'JamSession'
