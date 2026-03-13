import { memo, useCallback, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Song } from '../types/index.js'
import { createJam } from '../services/jamApi.js'
import { getAuthUserInfo, setAuthUserInfo } from '../services/api.js'
import './StartJamModal.css'

interface StartJamModalProps {
  songs: Song[]
  onClose: () => void
}

export const StartJamModal = memo(({ songs, onClose }: StartJamModalProps) => {
  const navigate = useNavigate()
  const [selectedTrack, setSelectedTrack] = useState<Song | null>(songs[0] || null)
  const [searchQuery, setSearchQuery] = useState('')
  const [allowSeek, setAllowSeek] = useState(false)
  const [allowQueueEdit, setAllowQueueEdit] = useState(false)
  const [loading, setLoading] = useState(false)

  const visibleSongs = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) return songs
    return songs.filter((song) => {
      const title = (song.title || '').toLowerCase()
      const artist = (song.artist || '').toLowerCase()
      return title.includes(q) || artist.includes(q)
    })
  }, [searchQuery, songs])

  const handleStart = useCallback(async () => {
    if (!selectedTrack) return

    setLoading(true)
    try {
      const result = await createJam({
        track_id: selectedTrack._id,
        position_sec: 0,
        is_playing: true,
        queue: [],
        settings: {
          allow_seek: allowSeek,
          allow_queue_edit: allowQueueEdit,
        },
      })

      const createdJamId = (result?.jam?._id || '').trim()
      if (!createdJamId || createdJamId === 'undefined' || createdJamId === 'null') {
        throw new Error('Invalid jam id from server')
      }

      const hostUserId = result?.jam?.host_user_id
      if (typeof hostUserId === 'number' && Number.isFinite(hostUserId)) {
        const existing = getAuthUserInfo()
        if (existing?.user_id !== hostUserId) {
          setAuthUserInfo({
            first_name: existing?.first_name,
            user_id: hostUserId,
          })
        }
      }

      try {
        window.localStorage.setItem('streamw:jam:activeId', createdJamId)
      } catch {
        void 0
      }

      navigate(`/jam/${createdJamId}`, { state: { initialJam: result?.jam ?? null } })
    } catch (err) {
      console.error('Failed to create jam:', err)
      alert('Failed to create jam session')
      setLoading(false)
    }
  }, [selectedTrack, allowSeek, allowQueueEdit, navigate])

  return (
    <div className="jam-modal-backdrop" onClick={onClose}>
      <div className="jam-modal" onClick={(e) => e.stopPropagation()}>
        <div className="jam-modal-header">
          <h2 className="jam-modal-title">Start Jam Session</h2>
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
          <div className="jam-modal-section">
            <label className="jam-modal-label">Select Track</label>
            <input
              className="jam-track-search"
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search tracks"
              aria-label="Search tracks"
              autoComplete="off"
              spellCheck={false}
            />
            <div className="jam-track-list">
              {visibleSongs.slice(0, 30).map((song) => (
                <button
                  key={song._id}
                  className={`jam-track-item${selectedTrack?._id === song._id ? ' jam-track-item--selected' : ''}`}
                  onClick={() => setSelectedTrack(song)}
                  type="button"
                >
                  <img src={song.cover_url || 'https://via.placeholder.com/48'} alt="" className="jam-track-cover" />
                  <div className="jam-track-info">
                    <div className="jam-track-title">{song.title}</div>
                    <div className="jam-track-artist">{song.artist}</div>
                  </div>
                  {selectedTrack?._id === song._id && (
                    <svg className="jam-track-check" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                      <path fill="currentColor" d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
                    </svg>
                  )}
                </button>
              ))}
            </div>
          </div>

          <div className="jam-modal-section">
            <label className="jam-modal-label">Settings</label>
            <div className="jam-settings">
              <label className="jam-setting-item">
                <input
                  type="checkbox"
                  checked={allowSeek}
                  onChange={(e) => setAllowSeek(e.target.checked)}
                  className="jam-checkbox"
                />
                <span className="jam-setting-text">Allow listeners to seek</span>
              </label>
              <label className="jam-setting-item">
                <input
                  type="checkbox"
                  checked={allowQueueEdit}
                  onChange={(e) => setAllowQueueEdit(e.target.checked)}
                  className="jam-checkbox"
                />
                <span className="jam-setting-text">Allow listeners to edit queue</span>
              </label>
            </div>
          </div>
        </div>

        <div className="jam-modal-footer">
          <button className="jam-modal-btn jam-modal-btn--cancel" onClick={onClose} type="button">
            Cancel
          </button>
          <button
            className="jam-modal-btn jam-modal-btn--start"
            onClick={handleStart}
            disabled={!selectedTrack || loading}
            type="button"
          >
            {loading ? 'Starting...' : 'Start Jam'}
          </button>
        </div>
      </div>
    </div>
  )
})

StartJamModal.displayName = 'StartJamModal'
