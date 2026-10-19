import { CssBaseline, ThemeProvider } from '@mui/material'
import type { ReactNode } from 'react'
import { Provider } from 'react-redux'
import { store } from '@/app/store'
import { theme } from '@/styles/theme'

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </Provider>
  )
}
