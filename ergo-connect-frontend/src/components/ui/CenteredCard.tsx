import { Box, Typography, keyframes } from '@mui/material'
import type { PointerEvent, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { BRAND_BLUE, BRAND_RED } from '@/styles/theme'
import { BrandLogo } from './BrandLogo'

// The platform's end-to-end flow (CLAUDE.md): shown on the brand panel so the first screen explains what the app is.
const STAGES = ['RFQ', 'Quotation', 'NPD', 'OFP', 'Quality', 'Delivery', 'Closure']

const float = keyframes`
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
`
const rise = keyframes`
  from { opacity: 0; transform: translateY(14px); }
  to { opacity: 1; transform: translateY(0); }
`

/** Left panel: brand colours, an animated process pipeline, and a soft parallax that follows the pointer. */
function BrandPanel() {
  const panel = useRef<HTMLDivElement>(null)
  const [active, setActive] = useState(0)
  const [pinned, setPinned] = useState(false)

  useEffect(() => {
    if (pinned) return
    const timer = window.setInterval(() => setActive((i) => (i + 1) % STAGES.length), 1600)
    return () => window.clearInterval(timer)
  }, [pinned])

  function handleMove(event: PointerEvent<HTMLDivElement>) {
    const el = panel.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    el.style.setProperty('--px', String((event.clientX - rect.left) / rect.width - 0.5))
    el.style.setProperty('--py', String((event.clientY - rect.top) / rect.height - 0.5))
  }

  return (
    <Box
      ref={panel}
      onPointerMove={handleMove}
      sx={{
        '--px': 0,
        '--py': 0,
        position: 'relative',
        overflow: 'hidden',
        display: { xs: 'none', md: 'flex' },
        flexDirection: 'column',
        justifyContent: 'space-between',
        p: 6,
        color: '#fff',
        background: `linear-gradient(145deg, #0a2a7a 0%, ${BRAND_BLUE} 55%, #1f4fc4 100%)`,
      }}
    >
      {/* soft parallax orbs */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          width: 460,
          height: 460,
          borderRadius: '50%',
          top: -140,
          right: -120,
          background: 'radial-gradient(circle, rgba(255,255,255,0.18), transparent 70%)',
          transform: 'translate(calc(var(--px) * -40px), calc(var(--py) * -40px))',
          transition: 'transform 200ms ease-out',
        }}
      />
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          width: 360,
          height: 360,
          borderRadius: '50%',
          bottom: -120,
          left: -100,
          background: `radial-gradient(circle, ${BRAND_RED}55, transparent 70%)`,
          transform: 'translate(calc(var(--px) * 50px), calc(var(--py) * 50px))',
          transition: 'transform 200ms ease-out',
        }}
      />

      <Box sx={{ position: 'relative', animation: `${rise} 600ms ease-out both` }}>
        <Box sx={{ display: 'inline-block', bgcolor: '#fff', borderRadius: 2, p: 1.25, boxShadow: '0 8px 24px rgba(0,0,0,0.2)' }}>
          <BrandLogo height={84} />
        </Box>
      </Box>

      <Box sx={{ position: 'relative' }}>
        <Typography
          variant="h3"
          component="p"
          sx={{ fontWeight: 700, lineHeight: 1.15, mb: 2, animation: `${rise} 700ms 100ms ease-out both` }}
        >
          One place for every
          <br />
          RFQ, order and shipment.
        </Typography>
        <Typography sx={{ color: 'rgba(255,255,255,0.78)', maxWidth: 460, mb: 4, animation: `${rise} 700ms 200ms ease-out both` }}>
          ERGO Connect brings customers, suppliers and ERGO teams together, from first enquiry to closure, with quality
          and compliance built in.
        </Typography>

        {/* interactive pipeline: hover or focus a stage to pin it */}
        <Box
          role="list"
          aria-label="Platform workflow"
          onPointerLeave={() => setPinned(false)}
          sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, animation: `${rise} 700ms 300ms ease-out both` }}
        >
          {STAGES.map((stage, i) => {
            const on = i === active
            return (
              <Box
                key={stage}
                role="listitem"
                tabIndex={0}
                onPointerEnter={() => {
                  setActive(i)
                  setPinned(true)
                }}
                onFocus={() => {
                  setActive(i)
                  setPinned(true)
                }}
                onBlur={() => setPinned(false)}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.75,
                  px: 1.5,
                  py: 0.75,
                  borderRadius: 5,
                  fontSize: '0.8rem',
                  fontWeight: 600,
                  cursor: 'default',
                  outline: 'none',
                  border: '1px solid',
                  borderColor: on ? '#fff' : 'rgba(255,255,255,0.25)',
                  bgcolor: on ? '#fff' : 'rgba(255,255,255,0.08)',
                  color: on ? BRAND_BLUE : '#fff',
                  transform: on ? 'translateY(-3px)' : 'none',
                  boxShadow: on ? '0 8px 20px rgba(0,0,0,0.25)' : 'none',
                  transition: 'all 200ms ease',
                  '&:focus-visible': { boxShadow: '0 0 0 3px rgba(255,255,255,0.5)' },
                }}
              >
                <Box
                  component="span"
                  sx={{
                    width: 8,
                    height: 8,
                    borderRadius: '50%',
                    bgcolor: on ? BRAND_RED : 'rgba(255,255,255,0.5)',
                    transition: 'background-color 200ms',
                  }}
                />
                {stage}
              </Box>
            )
          })}
        </Box>
      </Box>

      <Box
        sx={{
          position: 'relative',
          display: 'flex',
          gap: 1,
          alignItems: 'center',
          color: 'rgba(255,255,255,0.7)',
          fontSize: '0.8rem',
          animation: `${float} 5s ease-in-out infinite`,
        }}
      >
        Europe · Asia · USA
      </Box>
    </Box>
  )
}

/**
 * Layout for the screens outside the app shell: sign-in, one-time code, role selection, password change and reset.
 * A brand panel (desktop only) sits beside the form.
 */
export function CenteredCard({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1.1fr 1fr' }, minHeight: '100vh', bgcolor: '#fff' }}>
      <BrandPanel />
      <Box sx={{ display: 'grid', placeItems: 'center', p: { xs: 3, sm: 6 }, bgcolor: 'background.default' }}>
        <Box sx={{ width: '100%', maxWidth: 400, display: 'grid', gap: 3, animation: `${rise} 500ms ease-out both` }}>
          <Box sx={{ display: { xs: 'block', md: 'none' } }}>
            <BrandLogo height={48} />
          </Box>
          <Box>
            <Typography variant="h5" component="h1" sx={{ fontWeight: 700 }}>
              {title}
            </Typography>
            {subtitle && (
              <Typography color="text.secondary" sx={{ mt: 0.75 }}>
                {subtitle}
              </Typography>
            )}
          </Box>
          <Box sx={{ display: 'grid', gap: 2 }}>{children}</Box>
        </Box>
      </Box>
    </Box>
  )
}
