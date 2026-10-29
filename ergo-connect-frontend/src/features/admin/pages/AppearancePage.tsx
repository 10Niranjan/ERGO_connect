import DarkModeIcon from '@mui/icons-material/DarkMode'
import LightModeIcon from '@mui/icons-material/LightMode'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import {
  Alert,
  Box,
  Button,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material'
import { getContrastRatio } from '@mui/material/styles'
import { useState } from 'react'
import { useConfirm } from '@/components/ui/useConfirm'
import type { CompanyType } from '@/features/auth/store/authSlice'
import { DEFAULT_THEME, type ColorMode, type ModeColors, type ThemeConfig } from '@/styles/theme'
import { problemMessage } from '../../../shared/problem'
import {
  useAdminThemeQuery,
  usePublishThemeMutation,
  useResetThemeMutation,
  useSaveThemeDraftMutation,
  type ThemeAdminView,
} from '../services/themeApi'

const SIDES: { side: CompanyType; label: string; who: string }[] = [
  { side: 'ERGO', label: 'ERGO', who: 'every ERGO user' },
  { side: 'SUPPLIER', label: 'Supplier', who: 'every supplier user' },
  { side: 'CUSTOMER', label: 'Client', who: 'every client (customer) user' },
]

const FIELDS: { key: keyof ModeColors; label: string; hint: string }[] = [
  { key: 'primary', label: 'Brand / buttons', hint: 'Buttons, links, focus rings' },
  { key: 'headerBg', label: 'Header background', hint: 'Top bar' },
  { key: 'headerText', label: 'Header text', hint: 'Title, icons, role selector' },
  { key: 'navBg', label: 'Navigation background', hint: 'Left menu' },
  { key: 'navText', label: 'Navigation text', hint: 'Menu items and icons' },
  { key: 'navActiveBg', label: 'Active item background', hint: 'The page you are on' },
  { key: 'navActiveText', label: 'Active item text', hint: 'The page you are on' },
]

const light = (primary: string, navBg: string, navText: string, navActiveBg: string, navActiveText: string, headerBg = '#ffffff', headerText = '#101828'): ModeColors => ({
  primary, headerBg, headerText, navBg, navText, navActiveBg, navActiveText,
})

const preset = (group: string, name: string, lightColors: ModeColors, darkColors: ModeColors) => ({
  group, name, config: { light: lightColors, dark: darkColors },
})

/**
 * Ready-made looks, shared by every side; each carries a matching dark mode and passes the contrast checks below
 * (ERGO Classic excepted: it is the built-in default). Picking one only fills the editor, nothing is saved.
 */
const PRESETS: { group: string; name: string; config: ThemeConfig }[] = [
  { group: 'Professional', name: 'ERGO Classic', config: DEFAULT_THEME },
  preset('Professional', 'Midnight',
    light('#4f46e5', '#0f172a', '#cbd5e1', '#1e293b', '#ffffff', '#0f172a', '#f8fafc'),
    light('#4f46e5', '#020617', '#94a3b8', '#1e293b', '#ffffff', '#0f172a', '#f8fafc')),
  preset('Professional', 'Slate',
    light('#344054', '#1d2939', '#d0d5dd', '#344054', '#ffffff', '#ffffff', '#1d2939'),
    light('#475467', '#0c111d', '#98a2b3', '#1d2939', '#ffffff', '#101828', '#f2f4f7')),
  preset('Professional', 'Navy Executive',
    light('#1d4ed8', '#0a1f44', '#bfdbfe', '#1e3a8a', '#ffffff', '#ffffff', '#0a1f44'),
    light('#2563eb', '#050f24', '#93c5fd', '#1e3a8a', '#ffffff', '#0a1f44', '#dbeafe')),
  preset('Professional', 'Steel Blue',
    light('#0369a1', '#f8fafc', '#334155', '#e0f2fe', '#075985', '#0369a1', '#ffffff'),
    light('#0369a1', '#0b1220', '#94a3b8', '#0c4a6e', '#ffffff', '#0f2942', '#e0f2fe')),
  preset('Professional', 'Graphite',
    light('#111827', '#f3f4f6', '#374151', '#e5e7eb', '#111827', '#ffffff', '#111827'),
    light('#4b5563', '#111111', '#a3a3a3', '#262626', '#ffffff', '#171717', '#f5f5f5')),

  preset('Industrial', 'Industrial Amber',
    light('#b45309', '#1c1917', '#d6d3d1', '#b45309', '#ffffff', '#fbbf24', '#1c1917'),
    light('#b45309', '#0c0a09', '#a8a29e', '#422006', '#fde68a', '#1c1917', '#fbbf24')),
  preset('Industrial', 'Carbon',
    light('#b91c1c', '#18181b', '#d4d4d8', '#27272a', '#ffffff', '#ffffff', '#18181b'),
    light('#b91c1c', '#09090b', '#a1a1aa', '#27272a', '#ffffff', '#18181b', '#fafafa')),
  preset('Industrial', 'Blueprint',
    light('#1e40af', '#1e3a8a', '#dbeafe', '#1d4ed8', '#ffffff', '#eff6ff', '#1e3a8a'),
    light('#1d4ed8', '#0b1a3d', '#bfdbfe', '#1e3a8a', '#ffffff', '#102559', '#dbeafe')),
  preset('Industrial', 'Copper Forge',
    light('#9a3412', '#431407', '#fed7aa', '#7c2d12', '#ffffff', '#ffffff', '#431407'),
    light('#c2410c', '#1c0a03', '#fdba74', '#431407', '#ffffff', '#2a1006', '#ffedd5')),

  preset('Nature & calm', 'Forest',
    light('#067647', '#ecfdf3', '#344054', '#d1fadf', '#054f31', '#ffffff', '#054f31'),
    light('#087443', '#052e1f', '#a6f4c5', '#0b4a32', '#ffffff', '#073d29', '#d1fadf')),
  preset('Nature & calm', 'Ocean',
    light('#0e7490', '#083344', '#a5f3fc', '#155e75', '#ffffff', '#ffffff', '#083344'),
    light('#0e7490', '#04202b', '#67e8f9', '#164e63', '#ffffff', '#06303f', '#cffafe')),
  preset('Nature & calm', 'Teal Fresh',
    light('#0f766e', '#f0fdfa', '#134e4a', '#ccfbf1', '#115e59', '#0f766e', '#ffffff'),
    light('#0f766e', '#042f2e', '#99f6e4', '#115e59', '#ffffff', '#134e4a', '#ccfbf1')),
  preset('Nature & calm', 'Mint',
    light('#047857', '#ecfdf5', '#065f46', '#d1fae5', '#065f46', '#ffffff', '#064e3b'),
    light('#047857', '#022c22', '#a7f3d0', '#064e3b', '#ffffff', '#033b2d', '#d1fae5')),
  preset('Nature & calm', 'Olive Grove',
    light('#4d7c0f', '#f7fee7', '#365314', '#d9f99d', '#365314', '#ffffff', '#365314'),
    light('#4d7c0f', '#1a2e05', '#d9f99d', '#365314', '#ffffff', '#1f3a07', '#ecfccb')),
  preset('Nature & calm', 'Lavender Mist',
    light('#6d28d9', '#f5f3ff', '#4c1d95', '#ede9fe', '#5b21b6', '#ffffff', '#4c1d95'),
    light('#6d28d9', '#17112b', '#c4b5fd', '#2e1f5e', '#ffffff', '#1e1638', '#ede9fe')),
  preset('Nature & calm', 'Sand Dune',
    light('#92400e', '#faf5eb', '#57534e', '#f5e6c8', '#78350f', '#ffffff', '#44403c'),
    light('#92400e', '#1c1917', '#d6d3d1', '#3a2a17', '#fde68a', '#292524', '#f5f5f4')),

  preset('Bold & vibrant', 'Crimson',
    light('#c11a20', '#ffffff', '#475467', '#fef3f2', '#b42318', '#c11a20', '#ffffff'),
    light('#d92d20', '#1a0c0c', '#d0d5dd', '#3b1414', '#ffffff', '#2a0f0f', '#fef3f2')),
  preset('Bold & vibrant', 'Sunset',
    light('#c4320a', '#fffaeb', '#7a2e0e', '#fef0c7', '#93370d', '#ffffff', '#7a2e0e'),
    light('#b54708', '#1c1203', '#fedf89', '#41290a', '#ffffff', '#2a1a05', '#fef0c7')),
  preset('Bold & vibrant', 'Royal Purple',
    light('#6d28d9', '#2e1065', '#ddd6fe', '#5b21b6', '#ffffff', '#ffffff', '#2e1065'),
    light('#6d28d9', '#1a0b3b', '#c4b5fd', '#3b1d7a', '#ffffff', '#240f52', '#ede9fe')),
  preset('Bold & vibrant', 'Aurora',
    light('#4338ca', '#1e1b4b', '#c7d2fe', '#0f766e', '#ffffff', '#ffffff', '#1e1b4b'),
    light('#4338ca', '#0f0d2e', '#a5b4fc', '#115e59', '#ffffff', '#1e1b4b', '#e0e7ff')),
  preset('Bold & vibrant', 'Rose',
    light('#be185d', '#fff1f2', '#881337', '#ffe4e6', '#9f1239', '#ffffff', '#881337'),
    light('#be185d', '#2a0a16', '#fbcfe8', '#4c1230', '#ffffff', '#3b0f22', '#ffe4e6')),
  preset('Bold & vibrant', 'Berry',
    light('#a21caf', '#4a044e', '#f5d0fe', '#701a75', '#ffffff', '#ffffff', '#4a044e'),
    light('#a21caf', '#2a0230', '#f0abfc', '#581c5f', '#ffffff', '#3b0a40', '#fae8ff')),

  preset('Accessibility', 'High Contrast',
    light('#0000cc', '#000000', '#ffffff', '#ffff00', '#000000', '#ffffff', '#000000'),
    light('#0000cc', '#000000', '#ffffff', '#ffff00', '#000000', '#000000', '#ffffff')),
  preset('Accessibility', 'Colour-blind Safe', // Okabe-Ito blue/orange: distinct for red-green colour blindness
    light('#0072b2', '#002b49', '#cfe8ff', '#e69f00', '#000000', '#ffffff', '#002b49'),
    light('#0072b2', '#001a2e', '#b3d9f2', '#e69f00', '#000000', '#00243f', '#e6f2fb')),
]
const PRESET_GROUPS = [...new Set(PRESETS.map((p) => p.group))]

const HEX = /^#[0-9a-fA-F]{6}$/
const same = (a: ThemeConfig, b: ThemeConfig) => JSON.stringify(a) === JSON.stringify(b)

function ColorField({ label, hint, value, onChange }: { label: string; hint: string; value: string; onChange: (v: string) => void }) {
  const valid = HEX.test(value)
  return (
    <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
      <Box
        component="input"
        type="color"
        aria-label={`${label} picker`}
        value={valid ? value : '#000000'}
        onChange={(e: React.ChangeEvent<HTMLInputElement>) => onChange(e.target.value)}
        sx={{ width: 40, height: 40, p: 0, border: 1, borderColor: 'divider', borderRadius: 1, bgcolor: 'transparent', cursor: 'pointer' }}
      />
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{label}</Typography>
        <Typography variant="caption" color="text.secondary">{hint}</Typography>
      </Box>
      <TextField
        size="small"
        value={value}
        onChange={(e) => onChange(e.target.value.trim())}
        error={!valid}
        slotProps={{ htmlInput: { 'aria-label': `${label} hex`, maxLength: 7, style: { fontFamily: 'monospace' } } }}
        sx={{ width: 112 }}
      />
    </Stack>
  )
}

/** Mock of the real header + menu, drawn straight from the colours being edited. */
function Preview({ c }: { c: ModeColors }) {
  const items = ['Dashboard', 'RFQs', 'Quotes', 'Documents']
  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 2, overflow: 'hidden', display: 'flex', height: 300 }}>
      <Box sx={{ width: 150, bgcolor: c.navBg, color: c.navText, p: 1.25, flexShrink: 0 }}>
        <Box sx={{ height: 22, width: 70, bgcolor: 'rgba(255,255,255,0.9)', borderRadius: 0.5, mb: 1.5 }} />
        {items.map((item, i) => (
          <Box
            key={item}
            sx={{
              px: 1, py: 0.6, mb: 0.4, borderRadius: 1, fontSize: 12,
              bgcolor: i === 1 ? c.navActiveBg : 'transparent',
              color: i === 1 ? c.navActiveText : c.navText,
              fontWeight: i === 1 ? 600 : 400,
            }}
          >
            {item}
          </Box>
        ))}
      </Box>
      <Box sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <Box sx={{ bgcolor: c.headerBg, color: c.headerText, px: 1.5, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid rgba(128,128,128,0.25)' }}>
          <Typography sx={{ fontSize: 13, fontWeight: 600 }}>ERGO Connect</Typography>
          <Box sx={{ width: 22, height: 22, borderRadius: '50%', bgcolor: c.primary }} />
        </Box>
        <Box sx={{ p: 2, flexGrow: 1, bgcolor: 'background.default' }}>
          <Paper variant="outlined" sx={{ p: 1.5 }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>Open RFQs</Typography>
            <Box sx={{ display: 'inline-block', px: 1.5, py: 0.6, borderRadius: 1, bgcolor: c.primary, color: '#fff', fontSize: 12, fontWeight: 600 }}>
              New RFQ
            </Box>
          </Paper>
        </Box>
      </Box>
    </Box>
  )
}

function contrastWarnings(c: ModeColors): string[] {
  const pairs: [string, string, string][] = [
    ['Header text', c.headerText, c.headerBg],
    ['Navigation text', c.navText, c.navBg],
    ['Active item text', c.navActiveText, c.navActiveBg],
    ['Button text (white)', '#ffffff', c.primary],
  ]
  return pairs.filter(([, fg, bg]) => HEX.test(fg) && HEX.test(bg) && getContrastRatio(fg, bg) < 4.5).map(([name]) => name)
}

function Editor({ view }: { view: ThemeAdminView }) {
  const side = SIDES.find((s) => s.side === view.side)!
  const base = view.draft ?? view.published ?? DEFAULT_THEME
  const [config, setConfig] = useState<ThemeConfig>(base)
  const [mode, setMode] = useState<ColorMode>('light')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [save, saving] = useSaveThemeDraftMutation()
  const [publish, publishing] = usePublishThemeMutation()
  const [reset, resetting] = useResetThemeMutation()
  const [confirm, confirmDialog] = useConfirm()

  const busy = saving.isLoading || publishing.isLoading || resetting.isLoading
  const allValid = (['light', 'dark'] as const).every((m) => Object.values(config[m]).every((v) => HEX.test(v)))
  const dirty = !same(config, base)
  const warnings = contrastWarnings(config[mode])

  const edit = (key: keyof ModeColors, value: string) => setConfig({ ...config, [mode]: { ...config[mode], [key]: value } })

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setNotice(null)
    try {
      await action()
      setNotice(done)
    } catch (e) {
      setError(problemMessage(e))
    }
  }

  const saveDraft = () =>
    run(() => save({ side: view.side, expectedVersion: view.version, config }).unwrap(), 'Draft saved. Only you can see it until you publish.')

  const publishNow = async () => {
    if (!(await confirm(`Publish this look to ${side.who}? They will see it on their next page load.`, 'Publish'))) return
    await run(async () => {
      let version = view.version
      if (dirty) version = (await save({ side: view.side, expectedVersion: version, config }).unwrap()).version
      await publish({ side: view.side, expectedVersion: version }).unwrap()
    }, `Published to ${side.who}.`)
  }

  const resetNow = async () => {
    if (!(await confirm(`Return ${side.label} users to the built-in ERGO look? Your draft and the published theme are removed.`, 'Reset'))) return
    await run(async () => {
      await reset({ side: view.side, expectedVersion: view.version }).unwrap()
      setConfig(DEFAULT_THEME)
    }, 'Back to the built-in look.')
  }

  return (
    <Box>
      {busy && <LinearProgress sx={{ mb: 1 }} />}
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}

      <Stack direction="row" spacing={1} useFlexGap sx={{ mb: 2, alignItems: 'center', flexWrap: 'wrap' }}>
        <Chip size="small" color={view.published ? 'success' : 'default'} label={view.published ? 'Published theme live' : 'Using built-in look'} />
        {view.draft && <Chip size="small" color="warning" label="Unpublished draft" />}
        {dirty && <Chip size="small" color="info" label="Unsaved changes" />}
      </Stack>

      <Typography variant="subtitle2" sx={{ mb: 1 }}>Start from a preset</Typography>
      <Stack spacing={1.5} sx={{ mb: 3 }}>
        {PRESET_GROUPS.map((group) => (
          <Box key={group}>
            <Typography variant="caption" sx={{ display: 'block', mb: 0.5, color: 'text.secondary' }}>{group}</Typography>
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
              {PRESETS.filter((p) => p.group === group).map((p) => (
                <Button
                  key={p.name}
                  size="small"
                  variant={same(config, p.config) ? 'contained' : 'outlined'}
                  onClick={() => setConfig(p.config)}
                  sx={{ gap: 1 }}
                >
                  <Box sx={{ display: 'flex' }}>
                    {[p.config.light.primary, p.config.light.navBg, p.config.light.navActiveBg, p.config.dark.navBg].map((color, i) => (
                      <Box key={i} sx={{ width: 14, height: 14, borderRadius: '50%', bgcolor: color, border: '1px solid rgba(128,128,128,.4)', ml: i ? -0.5 : 0 }} />
                    ))}
                  </Box>
                  {p.name}
                </Button>
              ))}
            </Stack>
          </Box>
        ))}
      </Stack>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' }, gap: 3, alignItems: 'start' }}>
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Stack direction="row" sx={{ mb: 2, alignItems: 'center', justifyContent: 'space-between' }}>
            <Typography variant="subtitle1">Colours</Typography>
            <ToggleButtonGroup size="small" exclusive value={mode} onChange={(_e, v: ColorMode | null) => v && setMode(v)} aria-label="Editing mode">
              <ToggleButton value="light"><LightModeIcon fontSize="small" sx={{ mr: 0.5 }} />Light</ToggleButton>
              <ToggleButton value="dark"><DarkModeIcon fontSize="small" sx={{ mr: 0.5 }} />Dark</ToggleButton>
            </ToggleButtonGroup>
          </Stack>
          <Stack spacing={2}>
            {FIELDS.map((f) => (
              <ColorField key={f.key} label={f.label} hint={f.hint} value={config[mode][f.key]} onChange={(v) => edit(f.key, v)} />
            ))}
          </Stack>
        </Paper>

        <Box>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>Live preview ({mode})</Typography>
          <Preview c={config[mode]} />
          {warnings.length > 0 && (
            <Alert severity="warning" icon={<WarningAmberIcon />} sx={{ mt: 2 }}>
              Low contrast, hard to read: {warnings.join(', ')}. Aim for a clearly lighter or darker colour.
            </Alert>
          )}
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Users on this side can switch between light and dark themselves from the header.
          </Typography>
        </Box>
      </Box>

      <Stack direction="row" spacing={1.5} useFlexGap sx={{ mt: 3, flexWrap: 'wrap' }}>
        <Button variant="outlined" disabled={busy || !allValid || !dirty} onClick={() => void saveDraft()}>Save draft</Button>
        <Button variant="contained" disabled={busy || !allValid || (!dirty && !view.draft)} onClick={() => void publishNow()}>Publish</Button>
        <Button disabled={busy || !dirty} onClick={() => setConfig(base)}>Discard changes</Button>
        <Box sx={{ flexGrow: 1 }} />
        <Button color="error" disabled={busy || (!view.published && !view.draft)} onClick={() => void resetNow()}>Reset to default</Button>
      </Stack>
      {confirmDialog}
    </Box>
  )
}

export function AppearancePage() {
  const [side, setSide] = useState<CompanyType>('ERGO')
  const { data, isFetching, error } = useAdminThemeQuery(side)
  const current = SIDES.find((s) => s.side === side)!

  return (
    <Box sx={{ maxWidth: 1200 }}>
      <Typography variant="h5" sx={{ mb: 0.5 }}>Appearance</Typography>
      <Typography color="text.secondary" sx={{ mb: 2 }}>
        Choose the colours of the header bar and navigation for each side. A change applies to {current.who} once published.
      </Typography>
      <Tabs value={side} onChange={(_e, v: CompanyType) => setSide(v)} sx={{ mb: 3, borderBottom: 1, borderColor: 'divider' }}>
        {SIDES.map((s) => <Tab key={s.side} value={s.side} label={s.label} />)}
      </Tabs>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      {isFetching && !data && <LinearProgress />}
      {/* keyed by side: switching tabs restarts the editor; a save keeps the edits on screen and just refreshes the status chips */}
      {data && data.side === side && <Editor key={data.side} view={data} />}
    </Box>
  )
}
