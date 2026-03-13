import { useCallback, useEffect, useRef, useState } from 'react'
import { usePlayerPlayback } from '../context/PlayerContext.js'
import playerIconUrl from '../assets/playerIcon.svg'
import { ExpandedPlayer } from './ExpandedPlayer.js'
import { api, getAutoHidePlayerEnabled } from '../services/api.js'
import './Player.css'

export const Player = () => {
  const { currentSong, isPlaying, audioRef, togglePlay, playNext } = usePlayerPlayback()
  const SHEET_ANIM_MS = 560
  const [expandedState, setExpandedState] = useState<'closed' | 'open' | 'closing'>('closed')
  const [sheetOffsetY, setSheetOffsetY] = useState(0)
  const [isSheetDragging, setIsSheetDragging] = useState(false)
  const [sheetDragMode, setSheetDragMode] = useState<'open' | 'close' | null>(null)
  const [sheetViewportHeight, setSheetViewportHeight] = useState(0)
  const [suppressSheetClicks, setSuppressSheetClicks] = useState(false)
  const [miniReveal, setMiniReveal] = useState(false)
  const [autoHideEnabled, setAutoHideEnabled] = useState(getAutoHidePlayerEnabled())
  const sheetOffsetYRef = useRef(0)
  const collapseTimeoutRef = useRef<number | null>(null)
  const clickSuppressTimeoutRef = useRef<number | null>(null)
  const miniRevealTimeoutRef = useRef<number | null>(null)
  const isSheetDraggingRef = useRef(false)
  const [coverOverride, setCoverOverride] = useState<string | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const playerRef = useRef<HTMLDivElement>(null)

  const getViewportHeight = useCallback(() => {
    return Math.max(1, window.visualViewport?.height ?? window.innerHeight)
  }, [])

  const setSheetDragging = useCallback((next: boolean) => {
    isSheetDraggingRef.current = next
    setIsSheetDragging(next)
  }, [])

  const setSheetOffsetYValue = useCallback((next: number) => {
    sheetOffsetYRef.current = next
    setSheetOffsetY(next)
  }, [])

  const setSheetTransform = useCallback(
    (nextOffsetY: number) => {
      setSheetOffsetYValue(nextOffsetY)
    },
    [setSheetOffsetYValue],
  )

  const cancelPendingCollapse = useCallback(() => {
    if (collapseTimeoutRef.current !== null) {
      window.clearTimeout(collapseTimeoutRef.current)
      collapseTimeoutRef.current = null
    }
    if (miniRevealTimeoutRef.current !== null) {
      window.clearTimeout(miniRevealTimeoutRef.current)
      miniRevealTimeoutRef.current = null
    }
    setMiniReveal(false)
  }, [])

  const setSheetCloseTransform = useCallback(
    (nextOffsetY: number) => {
      setSheetOffsetYValue(nextOffsetY)
    },
    [setSheetOffsetYValue],
  )

  const setSheetClickSuppressed = useCallback(
    (next: boolean) => {
      setSuppressSheetClicks(next)
      if (clickSuppressTimeoutRef.current !== null) {
        window.clearTimeout(clickSuppressTimeoutRef.current)
        clickSuppressTimeoutRef.current = null
      }
      if (!next) return
      clickSuppressTimeoutRef.current = window.setTimeout(() => {
        clickSuppressTimeoutRef.current = null
        setSuppressSheetClicks(false)
      }, 280)
    },
    [],
  )

  const openExpanded = useCallback(() => {
    if (!currentSong) return
    cancelPendingCollapse()
    setExpandedState('open')
    const viewportHeight = getViewportHeight()
    setSheetViewportHeight(viewportHeight)
    setSheetTransform(viewportHeight)
    setSheetDragging(false)
    setSheetDragMode(null)
    window.requestAnimationFrame(() => setSheetTransform(0))
  }, [cancelPendingCollapse, currentSong, getViewportHeight, setSheetDragging, setSheetTransform])

  const closeExpanded = useCallback((viewportHeightOverride?: number) => {
    if (collapseTimeoutRef.current !== null) return
    const viewportHeight = Math.max(viewportHeightOverride ?? 0, getViewportHeight(), sheetOffsetYRef.current, 1)
    setSheetViewportHeight(viewportHeight)
    setSheetDragging(false)
    setSheetClickSuppressed(true)
    setSheetDragMode(null)
    setSheetCloseTransform(viewportHeight)
    setExpandedState('closing')
    collapseTimeoutRef.current = window.setTimeout(() => {
      collapseTimeoutRef.current = null
      setExpandedState('closed')
      setSheetCloseTransform(0)
      setMiniReveal(true)
      if (miniRevealTimeoutRef.current !== null) {
        window.clearTimeout(miniRevealTimeoutRef.current)
      }
      miniRevealTimeoutRef.current = window.setTimeout(() => {
        miniRevealTimeoutRef.current = null
        setMiniReveal(false)
      }, SHEET_ANIM_MS)
    }, SHEET_ANIM_MS)
  }, [SHEET_ANIM_MS, getViewportHeight, setSheetClickSuppressed, setSheetCloseTransform, setSheetDragging])

  const isExpanded = expandedState !== 'closed'

  const backdropOpacity = (() => {
    if (!isExpanded) return 0
    if (isSheetDragging) {
      if (sheetDragMode === 'close') return 1
      const viewportHeight = sheetViewportHeight || getViewportHeight()
      const progress = viewportHeight ? 1 - sheetOffsetY / viewportHeight : 1
      return Math.min(Math.max(progress, 0), 1)
    }
    return expandedState === 'closing' ? 0 : 1
  })()

  useEffect(() => {
    const sync = () => setAutoHideEnabled(getAutoHidePlayerEnabled())
    window.addEventListener('streamw:autoHidePlayerChanged', sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener('streamw:autoHidePlayerChanged', sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  useEffect(() => {
    if (!isSheetDragging) return
    const onTouchMove = (e: TouchEvent) => {
      e.preventDefault()
    }
    document.addEventListener('touchmove', onTouchMove, { passive: false })
    return () => document.removeEventListener('touchmove', onTouchMove)
  }, [isSheetDragging])

  useEffect(() => {
    return () => {
      if (clickSuppressTimeoutRef.current !== null) {
        window.clearTimeout(clickSuppressTimeoutRef.current)
      }
      if (collapseTimeoutRef.current !== null) {
        window.clearTimeout(collapseTimeoutRef.current)
      }
      if (miniRevealTimeoutRef.current !== null) {
        window.clearTimeout(miniRevealTimeoutRef.current)
      }
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    queueMicrotask(() => {
      if (cancelled) return
      setCoverOverride(null)
    })

    if (!currentSong?._id) return () => {
      cancelled = true
    }

    api
      .getSpotifyCoverUrl(currentSong._id, { title: currentSong.title, artist: currentSong.artist })
      .then((cover) => {
        if (cancelled) return
        if (!cover) return
        setCoverOverride(cover)
      })
      .catch(() => {})

    return () => {
      cancelled = true
    }
  }, [currentSong?._id, currentSong?.artist, currentSong?.title])

  useEffect(() => {
    const canvas = canvasRef.current
    const player = playerRef.current
    if (!canvas || !player) return

    const updateBlur = () => {
      if (!currentSong) return
      const rect = player.getBoundingClientRect()
      canvas.width = rect.width
      canvas.height = rect.height
      
      const ctx = canvas.getContext('2d')
      if (!ctx) return

      const appEl = document.querySelector('.app')
      const isTelegramApp = appEl instanceof HTMLElement && appEl.classList.contains('app--telegram')
      const rootStyle = getComputedStyle(document.documentElement)
      const theme = document.documentElement.dataset.theme
      const isLightTheme = theme === 'light'
      const themeFill = rootStyle.getPropertyValue('--header-bg').trim() || (isLightTheme ? '#f6f6f8' : 'rgba(44, 44, 44, 0.55)')
      const fillStyle = isTelegramApp && !isLightTheme ? 'rgba(40, 40, 40, 0.8)' : themeFill

      try {
        ctx.filter = 'blur(20px)'
        ctx.fillStyle = fillStyle
        ctx.fillRect(0, 0, canvas.width, canvas.height)
      } catch {
        ctx.fillStyle = fillStyle
        ctx.fillRect(0, 0, canvas.width, canvas.height)
      }
    }

    updateBlur()
    window.addEventListener('resize', updateBlur)
    window.addEventListener('streamw:themeChanged', updateBlur)
    return () => {
      window.removeEventListener('resize', updateBlur)
      window.removeEventListener('streamw:themeChanged', updateBlur)
    }
  }, [currentSong])

  const shouldHidePlayer = autoHideEnabled && !currentSong

  return (
    <>
      <audio 
        ref={audioRef} 
        preload="auto"
        playsInline
      />
      
      <svg style={{ position: 'absolute', width: 0, height: 0 }}>
        <defs>
          <filter id="player-blur">
            <feGaussianBlur in="SourceGraphic" stdDeviation="30" />
          </filter>
        </defs>
      </svg>
      
      {!shouldHidePlayer && (
        <div
          className="player-container"
          data-expanded={isExpanded ? 'true' : 'false'}
          data-mini-reveal={miniReveal ? 'true' : 'false'}
        >
          <div
            ref={playerRef}
            className="player"
            data-has-song={currentSong ? 'true' : 'false'}
            aria-hidden={isExpanded ? 'true' : 'false'}
          >
            <canvas ref={canvasRef} className="player-blur-canvas" />
            <div className="player-content">
              <button className="player-info-btn" type="button" onClick={openExpanded} disabled={!currentSong}>
                {currentSong ? (
                  <>
                    <img 
                      src={coverOverride || currentSong.cover_url || playerIconUrl} 
                      alt={currentSong.title}
                      className="player-cover"
                    />
                    <div className="player-text">
                      <div className="player-title">{currentSong.title}</div>
                    </div>
                  </>
                ) : (
                  <>
                    <div className="player-cover-placeholder">
                      <img src={playerIconUrl} alt="" className="player-icon" />
                    </div>
                  </>
                )}
              </button>

              <div className="player-controls">
                <button className="control-btn" onClick={togglePlay} disabled={!currentSong}>
                  {isPlaying ? (
                    <svg viewBox="0 0 32 28" xmlns="http://www.w3.org/2000/svg">
                      <path d="M13.293 22.772c.955 0 1.436-.481 1.436-1.436V6.677c0-.98-.481-1.427-1.436-1.427h-2.457c-.954 0-1.436.473-1.436 1.427v14.66c-.008.954.473 1.435 1.436 1.435h2.457zm7.87 0c.954 0 1.427-.481 1.427-1.436V6.677c0-.98-.473-1.427-1.428-1.427h-2.465c-.955 0-1.428.473-1.428 1.427v14.66c0 .954.473 1.435 1.428 1.435h2.465z" fill="currentColor" fillRule="nonzero"/>
                    </svg>
                  ) : (
                    <svg viewBox="0 0 32 28" xmlns="http://www.w3.org/2000/svg">
                      <path d="M10.345 23.287c.415 0 .763-.15 1.22-.407l12.742-7.404c.838-.481 1.178-.855 1.178-1.46 0-.599-.34-.972-1.178-1.462L11.565 5.158c-.457-.265-.805-.407-1.22-.407-.789 0-1.345.606-1.345 1.57V21.71c0 .971.556 1.577 1.345 1.577z" fill="currentColor" fillRule="nonzero"/>
                    </svg>
                  )}
                </button>
                <button className="control-btn" onClick={playNext} disabled={!currentSong}>
                  <svg width="32" height="28" viewBox="0 0 32 28" xmlns="http://www.w3.org/2000/svg">
                    <path d="M18.14 20.68c.365 0 .672-.107 1.038-.323l8.508-4.997c.623-.365.938-.814.938-1.37 0-.564-.307-.988-.938-1.361l-8.508-4.997c-.366-.216-.68-.324-1.046-.324-.73 0-1.337.556-1.337 1.569v4.773c-.108-.399-.406-.73-.904-1.021L7.382 7.632c-.357-.216-.672-.324-1.037-.324-.73 0-1.345.556-1.345 1.569v10.235c0 1.013.614 1.569 1.345 1.569.365 0 .68-.108 1.037-.324l8.509-4.997c.49-.29.796-.631.904-1.038v4.79c0 1.013.615 1.569 1.345 1.569z" fill="currentColor" fillRule="nonzero"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      <ExpandedPlayer
        isOpen={isExpanded}
        isClosing={expandedState === 'closing'}
        isDragging={false}
        backdropOpacity={backdropOpacity}
        sheetOffsetY={0}
        sheetTiltDeg={0}
        suppressSheetClicks={suppressSheetClicks}
        onClose={closeExpanded}
        onCollapseClick={closeExpanded}
      />
    </>
  )
}
