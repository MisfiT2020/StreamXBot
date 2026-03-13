import { memo, useState } from 'react'
import './Banner.css'

export const Banner = memo(() => {
  const [isVisible, setIsVisible] = useState(true)

  if (!isVisible) return null

  return (
    <div className="banner">
      <button className="close-btn" onClick={() => setIsVisible(false)} aria-label="Close banner">✕</button>
      <div className="banner-content">
        <h2>Get over 100 million songs free for 1 month.</h2>
        <p>Plus your entire music library on all your devices. 1 month free, then $10.99/month.</p>
      </div>
    </div>
  )
})

Banner.displayName = 'Banner'
