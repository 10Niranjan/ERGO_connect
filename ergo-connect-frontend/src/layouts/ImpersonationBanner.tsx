import { Box, Button, Typography } from '@mui/material'
import { useNavigate } from 'react-router'
import { useEndImpersonationMutation } from '@/features/auth/services/authApi'
import type { Session } from '@/features/auth/store/authSlice'
import { formatDateTime } from '@/utils/date'

/** Shown on every page while a Super Admin is logged in as another user ("Login as"). Exit returns to their own login. */
export function ImpersonationBanner({ session }: { session: Session }) {
  const [endImpersonation, { isLoading }] = useEndImpersonationMutation()
  const navigate = useNavigate()
  const view = session.impersonation
  if (!view) return null

  async function exit() {
    await endImpersonation().unwrap().catch(() => undefined)
    void navigate('/admin/users')
  }

  return (
    <Box
      role="status"
      sx={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 1.5,
        px: 2,
        py: 0.75,
        bgcolor: 'warning.main',
        color: 'warning.contrastText',
        position: 'sticky',
        top: 0,
        zIndex: (theme) => theme.zIndex.drawer + 2,
      }}
    >
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        Logged in as {session.user.firstName} {session.user.lastName} · {session.user.companyName} · acting as this user, every change is recorded under your name · ends{' '}
        {formatDateTime(view.endsAt)}
      </Typography>
      <Button size="small" variant="contained" color="inherit" sx={{ color: 'text.primary' }} loading={isLoading} onClick={() => void exit()}>
        Exit
      </Button>
    </Box>
  )
}
