import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { SongList } from '../components/SongList.js'
import { WebSongList } from '../components/WebSongList.js'
import { api, getWebSongListEnabled } from '../services/api.js'
import { platform } from '../platform.js'
import type { Song } from '../types/index.js'
import './Home.css'

export const SearchPage = () => {
  const navigate = useNavigate()
  const location = useLocation()
  const useWebSongList = getWebSongListEnabled()
  const isTelegram = platform.isTelegram

  const searchParams = useMemo(() => new URLSearchParams(location.search), [location.search])
  const query = useMemo(() => (searchParams.get('q') ?? '').trim(), [searchParams])
  const page = useMemo(() => {
    const raw = (searchParams.get('page') ?? '').trim()
    const parsed = Number.parseInt(raw || '1', 10)
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 1
  }, [searchParams])

  const [songs, setSongs] = useState<Song[]>([])
  const [loading, setLoading] = useState(false)
  const [searchInput, setSearchInput] = useState(query)

  useEffect(() => {
    setSearchInput(query)
  }, [query])

  useEffect(() => {
    if (!query) {
      queueMicrotask(() => {
        setSongs([])
        setLoading(false)
      })
      return
    }

    let cancelled = false
    queueMicrotask(() => {
      if (cancelled) return
      setLoading(true)
    })

    api
      .searchTracks({ q: query, page, limit: 20 })
      .then((data) => {
        if (cancelled) return
        const items = Array.isArray(data.items) ? data.items : []
        setSongs(items)
        setLoading(false)
      })
      .catch((err) => {
        void err
        if (cancelled) return
        setSongs([])
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [page, query])

  const goBack = useCallback(() => {
    if (window.history.length > 1) {
      navigate(-1)
    } else {
      navigate('/', { replace: true })
    }
  }, [navigate])

  const handleSearch = useCallback((e: React.FormEvent) => {
    e.preventDefault()
    const trimmed = searchInput.trim()
    if (!trimmed) return
    navigate(`/search?q=${encodeURIComponent(trimmed)}`)
  }, [searchInput, navigate])

  const handleClear = useCallback(() => {
    setSearchInput('')
    navigate('/search')
  }, [navigate])

  const title = query ? `Results: ${query}` : 'Search'

  return (
    <div className="audio-page latest-songs-page search-page">
      <div className={`audio-topbar${useWebSongList ? ' audio-topbar--web' : ''}`}>
        {isTelegram ? (
          <button className="audio-back-pill" type="button" onClick={goBack} aria-label="Back">
            <svg className="audio-back-icon" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
              <path fill="currentColor" d="M15.7 4.3a1 1 0 0 1 0 1.4L9.4 12l6.3 6.3a1 1 0 1 1-1.4 1.4l-7-7a1 1 0 0 1 0-1.4l7-7a1 1 0 0 1 1.4 0Z" />
            </svg>
          </button>
        ) : null}
        <div className="audio-topbar-title">{title}</div>
      </div>

      <main className={`content${useWebSongList ? ' latest-songs-content--web' : ''}`}>
        <form className="search-input-container" onSubmit={handleSearch}>
          <div className="search-input-wrapper">
            <svg className="search-input-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
              <path
                fill="currentColor"
                d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
              />
            </svg>
            <input
              className="search-input"
              type="text"
              placeholder="Search songs, artists, albums..."
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              autoFocus
            />
            {searchInput && (
              <button
                className="search-input-clear"
                type="button"
                onClick={handleClear}
                aria-label="Clear search"
              >
                <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
                  />
                </svg>
              </button>
            )}
          </div>
        </form>

        {useWebSongList ? (
          <WebSongList songs={songs} title={title} loading={loading} showTitle={false} />
        ) : (
          <SongList songs={songs} title={title} layout="single" loading={loading} showHeader={false} density="compact" />
        )}
      </main>
    </div>
  )
}
