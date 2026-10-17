import { Alert, Button, Stack, Typography } from '@mui/material'
import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <Stack spacing={2} sx={{ maxWidth: 560 }}>
      <Typography variant="h5" component="h1">
        Page not found
      </Typography>
      <Alert severity="info">This address does not exist. Use the menu, or go back to your dashboard.</Alert>
      <Button component={Link} to="/" sx={{ alignSelf: 'flex-start' }}>
        Dashboard
      </Button>
    </Stack>
  )
}
