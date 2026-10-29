import { CssBaseline, ThemeProvider } from '@mui/material'
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { Provider } from 'react-redux'
import { store } from '@/app/store'
import { useMyThemeQuery } from '@/features/admin/services/themeApi'
import { useAppSelector } from '@/hooks/useAppSelector'
import { buildTheme, DEFAULT_THEME, type ColorMode } from '@/styles/theme'

const MODE_KEY = 'ergo-color-mode'

function storedMode(): ColorMode {
  try {
    return localStorage.getItem(MODE_KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light' // storage blocked (private window): fall back, never fail
  }
}

interface ColorModeValue {
  mode: ColorMode
  toggle: () => void
}

const ColorModeContext = createContext<ColorModeValue>({ mode: 'light', toggle: () => {} })

export const useColorMode = () => useContext(ColorModeContext)

/** Builds the MUI theme from the signed-in user's side (published theme, else the default) and their light/dark choice. */
function ThemeRoot({ children }: { children: ReactNode }) {
  const signedIn = useAppSelector((state) => Boolean(state.auth.session))
  const { data } = useMyThemeQuery(undefined, { skip: !signedIn })
  const [mode, setMode] = useState<ColorMode>(storedMode)

  const toggle = useCallback(() => {
    setMode((current) => {
      const next = current === 'dark' ? 'light' : 'dark'
      try {
        localStorage.setItem(MODE_KEY, next)
      } catch {
        // not persisted; still applies for this visit
      }
      return next
    })
  }, [])

  const config = (signedIn && data?.config) || DEFAULT_THEME
  const theme = useMemo(() => {
    try {
      return buildTheme(mode, config[mode])
    } catch {
      return buildTheme(mode, DEFAULT_THEME[mode]) // a malformed stored colour must never blank the app
    }
  }, [mode, config])
  const value = useMemo(() => ({ mode, toggle }), [mode, toggle])

  return (
    <ColorModeContext.Provider value={value}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </ColorModeContext.Provider>
  )
}

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <Provider store={store}>
      <ThemeRoot>{children}</ThemeRoot>
    </Provider>
  )
}
