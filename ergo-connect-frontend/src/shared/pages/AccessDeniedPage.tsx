import { Alert, Stack, Typography } from '@mui/material'

// SRS 12.4.3
export function AccessDeniedPage() {
  return (
    <Stack spacing={2} sx={{ maxWidth: 560 }}>
      <Typography variant="h5" component="h1">
        Access denied
      </Typography>
      <Alert severity="warning">
        Your role does not have access to this page. Contact your ERGO Connect administrator if you need access.
      </Alert>
    </Stack>
  )
}
