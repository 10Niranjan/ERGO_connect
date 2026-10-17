import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import LockOpenIcon from '@mui/icons-material/LockOpen'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Container,
  Paper,
  TextField,
  Typography,
} from '@mui/material'
import type { FormEvent } from 'react'
import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { useAppDispatch } from '@/hooks/useAppDispatch'
import { problemMessage } from '@/utils/problem'
import { RolePickerGrid } from '../components/RolePickerGrid'
import { useLoginMutation } from '../services/authApi'
import { sessionStarted } from '../store/authSlice'
import {
  buildDemoSession,
  createSignedDemoJwt,
  DEMO_STORAGE_KEY,
  type DemoAccount,
} from '../utils/demoAccounts'

export function LoginPage() {
  const dispatch = useAppDispatch()
  const [login, { isLoading, error }] = useLoginMutation()
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/'
  const [manualOpen, setManualOpen] = useState(false)

  // Direct 1-Click Role Login
  const handleSelectAccount = async (account: DemoAccount) => {
    const token = await createSignedDemoJwt(account)
    const session = buildDemoSession(account, token)
    sessionStorage.setItem(DEMO_STORAGE_KEY, JSON.stringify(session))
    dispatch(sessionStarted(session))
    navigate(account.landingPath)
  }

  // Fallback Manual Credentials Login
  async function handleManualSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const email = String(form.get('email'))
    try {
      const result = await login({ email, password: String(form.get('password')) }).unwrap()
      if ('otpRequired' in result) {
        navigate('/verify-otp', { replace: true, state: { email, from } })
        return
      }
      const next = result.mustChangePassword ? '/change-password' : result.roles.length > 1 ? '/select-role' : from
      navigate(next, { replace: true })
    } catch {
      // shown below
    }
  }

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default', py: { xs: 3, md: 5 } }}>
      <Container maxWidth="lg">
        {/* Header / Hero */}
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, textAlign: 'center', mb: 4 }}>
          <Box sx={{ display: 'flex', flexDirection: 'row', gap: 1, alignItems: 'center', justifyContent: 'center' }}>
            <Typography variant="h4" component="h1" sx={{ fontWeight: 900, letterSpacing: '-0.02em', color: 'primary.main' }}>
              ERGO Connect
            </Typography>
          </Box>
          <Typography variant="h6" color="text.primary" sx={{ fontWeight: 600 }}>
            Client Presentation & Role Testing Portal
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 700, mx: 'auto' }}>
            Click on any employee card below to instantly sign in and explore the platform through that role's exact
            permissions, dashboards, and company data scopes.
          </Typography>
        </Box>

        {/* Main Role Selection Area */}
        <Paper elevation={1} sx={{ p: { xs: 2, sm: 3, md: 4 }, borderRadius: 3, mb: 4 }}>
          <RolePickerGrid onSelectAccount={handleSelectAccount} />
        </Paper>

        {/* Optional Manual Login Fallback */}
        <Box sx={{ maxWidth: 500, mx: 'auto' }}>
          <Accordion
            expanded={manualOpen}
            onChange={(_e, expanded) => setManualOpen(expanded)}
            variant="outlined"
            sx={{ borderRadius: 2, '&:before': { display: 'none' } }}
          >
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Box sx={{ display: 'flex', flexDirection: 'row', gap: 1, alignItems: 'center' }}>
                <LockOpenIcon fontSize="small" color="action" />
                <Typography variant="body2" sx={{ fontWeight: 600, color: 'text.secondary' }}>
                  Traditional Email & Password Form (Manual Login)
                </Typography>
              </Box>
            </AccordionSummary>
            <AccordionDetails>
              <Box component="form" onSubmit={handleManualSubmit} sx={{ display: 'grid', gap: 2, pt: 1 }}>
                <TextField name="email" type="email" label="Email" autoComplete="username" size="small" required />
                <TextField name="password" type="password" label="Password" autoComplete="current-password" size="small" required />
                {error && <Alert severity="error">{problemMessage(error)}</Alert>}
                <Button type="submit" variant="contained" loading={isLoading}>
                  Sign in
                </Button>
                <Button component={Link} to="/forgot-password" size="small">
                  Forgot password?
                </Button>
              </Box>
            </AccordionDetails>
          </Accordion>
        </Box>
      </Container>
    </Box>
  )
}
