import { Box } from '@mui/material'
import logo from '@/assets/ergo-logo.jpeg'

/** The ERGO company logo (Europe · Asia · USA). The artwork has a white background, so it sits on a white tile. */
export function BrandLogo({ height = 40 }: { height?: number }) {
  return (
    <Box
      component="img"
      src={logo}
      alt="ERGO - Europe Asia USA"
      sx={{ height, width: 'auto', display: 'block', borderRadius: 1, objectFit: 'contain' }}
    />
  )
}
