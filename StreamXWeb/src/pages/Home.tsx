import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { SongList } from '../components/SongList.js'
import { PlaylistList, UpdatedPlaylists } from '../components/PlaylistList.js'
import { JamSession } from '../components/JamSession.js'
import { usePlayerLibrary } from '../context/PlayerContext.js'
import { api, CACHE_TTL_MS, getAuthUserInfo, getCacheEnabled } from '../services/api.js'
import type { Playlist, Song, BrowseResponse } from '../types/index.js'
import { platform } from '../platform.js'
import './Home.css'

const RANDOM_MIX_CACHE_KEY = 'streamw:randomMix'
const RANDOM_MIX_TTL = 1000 * 60 * 60 * 12 // 12 hours

interface RandomMixCache {
  tracks: Song[]
  created_at: number
}

const getRandomMixCache = (): Song[] | null => {
  if (!getCacheEnabled()) return null
  
  try {
    const cached = localStorage.getItem(RANDOM_MIX_CACHE_KEY)
    if (!cached) return null
    
    const data = JSON.parse(cached) as RandomMixCache
    const now = Date.now()
    
    // Check if expired
    if (now - data.created_at > RANDOM_MIX_TTL) {
      localStorage.removeItem(RANDOM_MIX_CACHE_KEY)
      return null
    }
    
    return Array.isArray(data.tracks) ? data.tracks : null
  } catch {
    return null
  }
}

const setRandomMixCache = (tracks: Song[]): void => {
  if (!getCacheEnabled()) return
  
  try {
    const data: RandomMixCache = {
      tracks,
      created_at: Date.now()
    }
    localStorage.setItem(RANDOM_MIX_CACHE_KEY, JSON.stringify(data))
  } catch {
    // Ignore storage errors
  }
}

export const Home = () => {
  const navigate = useNavigate()
  const { songs, setSongs } = usePlayerLibrary()
  const [userId, setUserId] = useState<number | null>(() => getAuthUserInfo()?.user_id ?? null)
  const [latestLoading, setLatestLoading] = useState(false)
  const [randomMix, setRandomMix] = useState<Song[]>([])
  const [randomMixLoading, setRandomMixLoading] = useState(false)

  useEffect(() => {
    const sync = () => setUserId(getAuthUserInfo()?.user_id ?? null)
    window.addEventListener('streamw:authUserInfoChanged', sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener('streamw:authUserInfoChanged', sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  // Fetch random mix from cache or API on mount
  useEffect(() => {
    let cancelled = false
    
    const loadRandomMix = async () => {
      setRandomMixLoading(true)
      
      try {
        // Check cache first
        const cachedTracks = getRandomMixCache()
        
        if (cachedTracks && cachedTracks.length > 0) {
          // Load from cache - no need to fetch
          if (!cancelled) {
            setRandomMix(cachedTracks)
            setRandomMixLoading(false)
          }
          return
        }
        
        // Fetch new shuffle
        const data = await api.shuffleTracks(100)
        if (cancelled) return
        
        const items = Array.isArray(data.items) ? data.items : []
        
        // Store full tracks in cache
        setRandomMixCache(items)
        
        setRandomMix(items)
        setRandomMixLoading(false)
      } catch (err) {
        void err
        if (!cancelled) {
          setRandomMixLoading(false)
        }
      }
    }
    
    loadRandomMix()

    return () => {
      cancelled = true
    }
  }, [])

  const handleRefreshShuffle = useCallback(async () => {
    setRandomMixLoading(true)
    
    try {
      // Force refresh - fetch new shuffle
      const data = await api.shuffleTracks(100)
      const items = Array.isArray(data.items) ? data.items : []
      
      // Store full tracks in cache
      setRandomMixCache(items)
      
      setRandomMix(items)
      setRandomMixLoading(false)
    } catch (err) {
      void err
      setRandomMixLoading(false)
    }
  }, [])

  useEffect(() => {
    let cancelled = false

    const cacheEnabled = getCacheEnabled()

    if (!cacheEnabled) {
      queueMicrotask(() => {
        if (cancelled) return
        setLatestLoading(true)
      })
      
      // Fetch pages 1-4 for horizontal scrolling
      Promise.all([
        api.browseTracks(1),
        api.browseTracks(2),
        api.browseTracks(3),
        api.browseTracks(4)
      ])
        .then((results) => {
          if (cancelled) return
          const allItems = results.flatMap((data: BrowseResponse) => Array.isArray(data.items) ? data.items : [])
          setSongs(allItems)
          setLatestLoading(false)
        })
        .catch((err) => {
          void err
          if (cancelled) return
          setLatestLoading(false)
        })

      return () => {
        cancelled = true
      }
    }

    const cacheKey = `streamw:latestSongs:${String(userId)}:pages:1-4`
    const cacheTtlMs = CACHE_TTL_MS
    const now = Date.now()
    let cachedItems: Song[] | null = null
    let cacheFresh = false
    let hasCachedList = false

    try {
      const cachedRaw = localStorage.getItem(cacheKey)
      if (cachedRaw) {
        const cached = JSON.parse(cachedRaw) as { ts?: number; items?: Song[] }
        cachedItems = Array.isArray(cached.items) ? cached.items : null
        hasCachedList = Boolean(cachedItems && cachedItems.length > 0)
        cacheFresh = Boolean(cachedItems) && typeof cached.ts === 'number' && now - cached.ts < cacheTtlMs
      }
    } catch (err) {
      void err
    }

    if (cachedItems) {
      queueMicrotask(() => {
        if (cancelled) return
        setSongs(cachedItems)
        setLatestLoading(false)
      })
    }

    if (cacheFresh) {
      queueMicrotask(() => {
        if (cancelled) return
        setLatestLoading(false)
      })
      return () => {
        cancelled = true
      }
    }

    queueMicrotask(() => {
      if (cancelled) return
      setLatestLoading(!hasCachedList)
    })

    // Fetch pages 1-4 for horizontal scrolling
    Promise.all([
      api.browseTracks(1),
      api.browseTracks(2),
      api.browseTracks(3),
      api.browseTracks(4)
    ])
      .then((results) => {
        if (cancelled) return
        const allItems = results.flatMap((data: BrowseResponse) => Array.isArray(data.items) ? data.items : [])
        setSongs(allItems)
        setLatestLoading(false)
        try {
          localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), items: allItems }))
        } catch (err) {
          void err
        }
      })
      .catch((err) => {
        void err
        if (cancelled) return
        setLatestLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [setSongs, userId])

  const openPlaylist = useCallback((playlist: Playlist) => {
    navigate(`/playlists/${playlist.playlist_id}`, {
      state: { playlistName: playlist.name },
    })
  }, [navigate])

  return (
    <div className="home">
      <main className="content">
        {!platform.isTelegram && (
          <div className="home-search-container">
            <button 
              className="home-search-bar" 
              type="button"
              onClick={() => navigate('/search')}
              aria-label="Search music"
            >
              <svg className="home-search-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
                />
              </svg>
              <span className="home-search-placeholder">Search songs, artists, albums...</span>
            </button>
          </div>
        )}
        <UpdatedPlaylists />
        <SongList
          songs={songs}
          title="Latest Songs"
          layout="two-column"
          loading={latestLoading}
          onTitleClick={() => navigate('/latest-songs')}
        />
        <SongList
          songs={randomMix}
          title="Random Mix"
          layout="two-column"
          loading={randomMixLoading}
          onTitleClick={() => navigate('/random-mix')}
          titleAction={
            <button className="random-mix-refresh-btn" type="button" onClick={handleRefreshShuffle} aria-label="Refresh shuffle">
              <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path
                  fill="currentColor"
                  d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
                />
              </svg>
            </button>
          }
        />
        <PlaylistList userId={userId} title="Playlists" onSelectPlaylist={openPlaylist} />
        <JamSession />
      </main>
    </div>
  )
}
