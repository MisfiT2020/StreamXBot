import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { BrowserRouter as Router, Routes, Route, useLocation } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { PlayerProvider } from './context/PlayerContext.js'
import { Header } from './components/Header.js'
import { Player } from './components/Player.js'
import { Home } from './pages/Home.js'
import { AvailablePlaylistPage, PlaylistPage } from './pages/Playlist.js'
import { AudioPage } from './pages/Audio.js'
import { SettingsPage } from './pages/Settings.js'
import { LatestSongsPage } from './pages/LatestSongs.js'
import { RandomMixPage } from './pages/RandomMix.js'
import { FavoritesPage } from './pages/Favorites.js'
import { LoginPage } from './pages/Login.js'
import { JamPage } from './pages/JamPage.js'
import { ProfilePage } from './pages/ProfilePage.js'
import { SearchPage } from './pages/Search.js'
import { platform } from './platform.js'
import { API_BASE_URL, ensureAuthCookieFromToken, getAuthToken, getRedSelectorEnabled, getThemeMode, getFloatingNavTopPad, setAuthToken } from './services/api.js'
import './App.css'

const RouteMotion = ({ children }: { children: ReactNode }) => {
  return (
    <motion.div
      className="route-motion"
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -10 }}
      transition={{ duration: 0.32, ease: [0.16, 1, 0.3, 1] }}
    >
      {children}
    </motion.div>
  )
}

const AnimatedRoutes = () => {
  const location = useLocation()

  useEffect(() => {
    if (typeof window === 'undefined') return

    try {
      window.scrollTo(0, 0)
      document.documentElement.scrollTop = 0
      document.body.scrollTop = 0
      document.querySelectorAll<HTMLElement>('.content').forEach((el) => {
        el.scrollTop = 0
      })
    } catch {
      void 0
    }
  }, [location.pathname])

  return (
    <AnimatePresence mode="wait" initial={false}>
      <Routes location={location} key={location.pathname}>
        <Route path="/" element={<RouteMotion><Home /></RouteMotion>} />
        <Route path="/playlists/:playlistId" element={<RouteMotion><PlaylistPage /></RouteMotion>} />
        <Route path="/daily-playlist/:slug" element={<RouteMotion><AvailablePlaylistPage /></RouteMotion>} />
        <Route path="/me/:slug" element={<RouteMotion><AvailablePlaylistPage /></RouteMotion>} />
        <Route path="/audio" element={<RouteMotion><AudioPage /></RouteMotion>} />
        <Route path="/settings" element={<RouteMotion><SettingsPage /></RouteMotion>} />
        <Route path="/latest-songs" element={<RouteMotion><LatestSongsPage /></RouteMotion>} />
        <Route path="/random-mix" element={<RouteMotion><RandomMixPage /></RouteMotion>} />
        <Route path="/favorites" element={<RouteMotion><FavoritesPage /></RouteMotion>} />
        <Route path="/login" element={<RouteMotion><LoginPage /></RouteMotion>} />
        <Route path="/jam/:jamId" element={<RouteMotion><JamPage /></RouteMotion>} />
        <Route path="/profile" element={<RouteMotion><ProfilePage /></RouteMotion>} />
        <Route path="/search" element={<RouteMotion><SearchPage /></RouteMotion>} />
      </Routes>
    </AnimatePresence>
  )
}

function App() {
  const [isTelegram, setIsTelegram] = useState(platform.isTelegram)
  const isWindows = platform.isWindows
  const [tgUserFirstName, setTgUserFirstName] = useState<string | null>(null)
  const [redSelectorEnabled, setRedSelectorEnabledState] = useState(getRedSelectorEnabled())
  const [themeMode, setThemeModeState] = useState(getThemeMode())
  const [floatingNavTopPad, setFloatingNavTopPadState] = useState<number | null>(() => getFloatingNavTopPad())

  useEffect(() => {
    const token = getAuthToken()
    if (!token) return
    ensureAuthCookieFromToken(token).catch(() => {})
  }, [])

  useEffect(() => {
    type TelegramWebApp = {
      initData?: string
      initDataUnsafe?: { user?: { first_name?: string } } | unknown
      ready?: () => void
    }

    const getTg = (): TelegramWebApp | null => {
      const w = window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }
      return w.Telegram?.WebApp ?? null
    }

    let cancelled = false
    let tries = 0
    const maxTries = 15

    const tick = async () => {
      if (cancelled) return
      const tg = getTg()

      if (!tg) {
        tries += 1
        if (tries >= maxTries) {
          console.error('Telegram WebApp not available')
          return
        }
        window.setTimeout(tick, 100)
        return
      }

      if (!cancelled) setIsTelegram(platform.isTelegram)
      tg.ready?.()

      const initData = tg.initData

      if (!initData) {
        const unsafeFirstName =
          typeof tg.initDataUnsafe === 'object' && tg.initDataUnsafe && 'user' in tg.initDataUnsafe
            ? (tg.initDataUnsafe as { user?: { first_name?: string } }).user?.first_name ?? null
            : null
        if (!cancelled) setTgUserFirstName(unsafeFirstName)
        console.warn('Telegram WebApp detected, but initData is empty. Open via Telegram Mini App.')
        return
      }

      try {
        const controller = new AbortController()
        const timeoutId = window.setTimeout(() => controller.abort(), 8000)

        const params = new URLSearchParams()
        params.set('init_data', initData)

        const res = await fetch(`${API_BASE_URL}/webapp/verify`, {
          method: 'POST',
          headers: {
            accept: 'application/json',
            'Content-Type': 'application/x-www-form-urlencoded',
          },
          body: params.toString(),
          signal: controller.signal,
        })

        window.clearTimeout(timeoutId)

        if (!res.ok) throw new Error(`verify failed: ${res.status}`)
        const json = (await res.json()) as { first_name?: string; token?: string }
        if (!cancelled) setTgUserFirstName(json.first_name ?? null)
        
        // Store the auth token if present
        if (json.token) {
          setAuthToken(json.token)
          await ensureAuthCookieFromToken(json.token)
        }
      } catch {
        const unsafeFirstName =
          typeof tg.initDataUnsafe === 'object' && tg.initDataUnsafe && 'user' in tg.initDataUnsafe
            ? (tg.initDataUnsafe as { user?: { first_name?: string } }).user?.first_name ?? null
            : null
        if (!cancelled) setTgUserFirstName(unsafeFirstName)
      }
    }

    tick()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const sync = () => setRedSelectorEnabledState(getRedSelectorEnabled())
    window.addEventListener('streamw:redSelectorChanged', sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener('streamw:redSelectorChanged', sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  useEffect(() => {
    const sync = () => {
      setFloatingNavTopPadState(getFloatingNavTopPad())
    }
    window.addEventListener('streamw:floatingNavTopPadChanged', sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener('streamw:floatingNavTopPadChanged', sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  useEffect(() => {
    const apply = () => {
      const next = getThemeMode()
      setThemeModeState(next)
      try {
        document.documentElement.dataset.theme = next
        document.body.dataset.theme = next
      } catch {
        void 0
      }

      try {
        const meta = document.querySelector('meta[name="theme-color"]')
        if (meta instanceof HTMLMetaElement) {
          const appBg = getComputedStyle(document.documentElement).getPropertyValue('--app-bg').trim()
          meta.content = appBg || (next === 'dark' ? '#212121' : '#f6f6f8')
        }
      } catch {
        void 0
      }

      try {
        const w = window as unknown as { Telegram?: { WebApp?: unknown } }
        const tg = w.Telegram?.WebApp as
          | { setHeaderColor?: (color: string) => void; setBackgroundColor?: (color: string) => void; setBottomBarColor?: (color: string) => void }
          | undefined

        if (tg) {
          const appBg = getComputedStyle(document.documentElement).getPropertyValue('--app-bg').trim()
          const hex = appBg || (next === 'dark' ? '#212121' : '#f6f6f8')
          tg.setHeaderColor?.(hex)
          tg.setBackgroundColor?.(hex)
          tg.setBottomBarColor?.(hex)
        }
      } catch {
        void 0
      }
    }

    apply()
    window.addEventListener('streamw:themeChanged', apply)
    window.addEventListener('storage', apply)

    const mq = window.matchMedia?.('(prefers-color-scheme: light)')
    const onMqChange = () => apply()
    try {
      mq?.addEventListener('change', onMqChange)
    } catch {
      try {
        mq?.addListener(onMqChange)
      } catch {
        void 0
      }
    }

    return () => {
      window.removeEventListener('streamw:themeChanged', apply)
      window.removeEventListener('storage', apply)
      try {
        mq?.removeEventListener('change', onMqChange)
      } catch {
        try {
          mq?.removeListener(onMqChange)
        } catch {
          void 0
        }
      }
    }
  }, [])

  return (
    <PlayerProvider>
      <Router>
        <div
          className={`${isTelegram ? 'app app--telegram' : 'app'}${isWindows ? ' app--windows' : ''}${redSelectorEnabled ? ' app--red-selector' : ''}`}
          data-theme={themeMode}
          style={
            isTelegram && floatingNavTopPad != null
              ? ({ '--tg-floating-nav-top-pad': `${floatingNavTopPad}px` } as CSSProperties)
              : undefined
          }
        >
          <Header userFirstName={tgUserFirstName} />
          <AnimatedRoutes />
          <Player />
        </div>
      </Router>
    </PlayerProvider>
  )
}

export default App
