type TelegramWebApp = {
  initData?: string
  platform?: string
}

const getTg = (): TelegramWebApp | undefined => {
  if (typeof window === 'undefined') return undefined
  return (window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }).Telegram?.WebApp ?? undefined
}

const getUserAgent = () => (typeof navigator !== 'undefined' ? navigator.userAgent : '')
const getNavPlatform = () => (typeof navigator !== 'undefined' ? navigator.platform : '')

const uaIsIOS = () => /iP(hone|ad|od)/i.test(getUserAgent()) && !/Android/i.test(getUserAgent())
const uaIsAndroid = () => /Android/i.test(getUserAgent())
const uaIsMac = () => /Macintosh/i.test(getUserAgent()) && !uaIsIOS()
const uaIsWindows = () => /Windows/i.test(getUserAgent())

export const platform = {
  get isTelegram() {
    const tg = getTg()
    const userAgent = getUserAgent()
    const uaTelegram = /Telegram/i.test(userAgent)
    const tgHasInitData = typeof tg?.initData === 'string' && tg.initData.trim().length > 0
    return Boolean(tg) && (uaTelegram || tgHasInitData)
  },
  get isIOS() {
    const tgPlatform = getTg()?.platform
    return tgPlatform === 'ios' || uaIsIOS()
  },
  get isAndroid() {
    const tgPlatform = getTg()?.platform
    return tgPlatform === 'android' || uaIsAndroid()
  },
  get isMac() {
    const tgPlatform = getTg()?.platform
    const platformIsMac = /Mac/i.test(getNavPlatform()) || uaIsMac()
    return tgPlatform === 'macos' || platformIsMac
  },
  get isWindows() {
    const platformIsWindows = /Win/i.test(getNavPlatform()) || uaIsWindows()
    return platformIsWindows
  },
  get isDesktop() {
    const tgPlatform = getTg()?.platform
    const userAgent = getUserAgent()
    const navPlatform = getNavPlatform()
    const platformIsMac = /Mac/i.test(navPlatform) || uaIsMac()
    const platformIsWindows = /Win/i.test(navPlatform) || uaIsWindows()
    return (
      ['web', 'tdesktop', 'macos'].includes(tgPlatform ?? '') ||
      (!uaIsIOS() &&
        !uaIsAndroid() &&
        (platformIsMac || platformIsWindows || /Linux/i.test(navPlatform) || /Linux/i.test(userAgent)))
    )
  },
} as const
