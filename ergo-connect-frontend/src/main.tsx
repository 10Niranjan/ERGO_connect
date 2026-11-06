// Self-hosted (8p F4): no Google Fonts request, so the CSP in nginx.conf.template can stay 'self'-only.
import '@fontsource-variable/inter'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from '@/app/App'
import { AppProviders } from '@/app/providers'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProviders>
      <App />
    </AppProviders>
  </StrictMode>,
)
