import { alpha, createTheme, darken, lighten } from '@mui/material'

// Untitled UI-style design language (see .claude/skills/untitled-ui/SKILL.md) on ERGO's brand colours.
// Brand blue and red are taken from the company logo.
export const BRAND_BLUE = '#0a369d'
export const BRAND_RED = '#e31e24'

/** The colours the Super Admin may change per side and per light/dark mode (Appearance screen). */
export interface ModeColors {
  primary: string
  headerBg: string
  headerText: string
  navBg: string
  navText: string
  navActiveBg: string
  navActiveText: string
}

export interface ThemeConfig {
  light: ModeColors
  dark: ModeColors
}

export type ColorMode = 'light' | 'dark'

/** What ships when a side has no published theme: the original ERGO look, plus a matching dark mode. */
export const DEFAULT_THEME: ThemeConfig = {
  light: {
    primary: BRAND_BLUE,
    headerBg: '#ffffff',
    headerText: '#101828',
    navBg: '#ffffff',
    navText: '#475467',
    navActiveBg: '#e8eefb',
    navActiveText: BRAND_BLUE,
  },
  dark: {
    primary: '#6690f5',
    headerBg: '#101828',
    headerText: '#f2f4f7',
    navBg: '#0c111d',
    navText: '#98a2b3',
    navActiveBg: '#1d2939',
    navActiveText: '#ffffff',
  },
}

declare module '@mui/material/styles' {
  interface Palette {
    /** Header bar and navigation (sidebar) colours, set per side by the Super Admin. */
    chrome: Omit<ModeColors, 'primary'>
  }
  interface PaletteOptions {
    chrome?: Omit<ModeColors, 'primary'>
  }
}

const GRAY_LIGHT = {
  25: '#fcfcfd',
  50: '#f9fafb',
  100: '#f2f4f7',
  200: '#eaecf0',
  300: '#d0d5dd',
  400: '#98a2b3',
  500: '#667085',
  600: '#475467',
  700: '#344054',
  800: '#1d2939',
  900: '#101828',
}

// The same scale reversed, so every `GRAY[n]` below reads correctly in dark mode without a second set of overrides.
const GRAY_DARK = {
  25: '#0c111d',
  50: '#101828',
  100: '#1d2939',
  200: '#344054',
  300: '#475467',
  400: '#667085',
  500: '#98a2b3',
  600: '#d0d5dd',
  700: '#eaecf0',
  800: '#f2f4f7',
  900: '#f9fafb',
}

const SHADOW_XS = '0 1px 2px rgba(16, 24, 40, 0.05)'
const SHADOW_SM = '0 1px 3px rgba(16, 24, 40, 0.1), 0 1px 2px rgba(16, 24, 40, 0.06)'

export function buildTheme(mode: ColorMode, colors: ModeColors) {
  const dark = mode === 'dark'
  const GRAY = dark ? GRAY_DARK : GRAY_LIGHT
  const PAPER = dark ? '#161b2c' : '#ffffff'
  const primary = colors.primary
  const primaryLight = dark ? alpha(primary, 0.18) : lighten(primary, 0.9)
  const primaryDark = darken(primary, 0.25)
  const focusRing = `0 0 0 4px ${alpha(primary, 0.14)}`

  return createTheme({
    palette: {
      mode,
      primary: { main: primary, light: primaryLight, dark: primaryDark, contrastText: '#fff' },
      secondary: { main: '#4472c4' },
      error: { main: '#d92d20', light: '#fef3f2', dark: '#b42318' },
      warning: { main: '#dc6803', light: '#fffaeb', dark: '#b54708' },
      success: { main: '#079455', light: '#ecfdf3', dark: '#067647' },
      info: { main: '#1570ef', light: '#eff8ff', dark: '#175cd3' },
      grey: GRAY,
      text: { primary: GRAY[900], secondary: GRAY[600], disabled: GRAY[400] },
      divider: GRAY[200],
      background: { default: dark ? GRAY[25] : GRAY[50], paper: PAPER },
      chrome: {
        headerBg: colors.headerBg,
        headerText: colors.headerText,
        navBg: colors.navBg,
        navText: colors.navText,
        navActiveBg: colors.navActiveBg,
        navActiveText: colors.navActiveText,
      },
    },
    shape: { borderRadius: 8 },
    typography: {
      fontFamily: '"Inter", -apple-system, "Segoe UI", Roboto, Arial, sans-serif',
      fontSize: 14,
      h1: { fontWeight: 600, letterSpacing: '-0.02em' },
      h2: { fontWeight: 600, letterSpacing: '-0.02em' },
      h3: { fontWeight: 600, letterSpacing: '-0.02em' },
      h4: { fontWeight: 600, letterSpacing: '-0.02em' },
      h5: { fontWeight: 600, letterSpacing: '-0.01em' },
      h6: { fontWeight: 600 },
      subtitle1: { fontWeight: 600 },
      subtitle2: { fontWeight: 600 },
      button: { fontWeight: 600, textTransform: 'none' },
      overline: { fontWeight: 600, letterSpacing: '0.08em' },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: { WebkitFontSmoothing: 'antialiased' },
          '@media (prefers-reduced-motion: reduce)': {
            '*, *::before, *::after': { animationDuration: '0.001ms !important', transitionDuration: '0.001ms !important' },
          },
        },
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: {
            borderRadius: 8,
            transition: 'all 100ms linear',
            '&.Mui-disabled': { opacity: 0.5 },
            '&.MuiButton-containedPrimary': { boxShadow: SHADOW_XS, '&:hover': { backgroundColor: primaryDark } },
          },
          sizeMedium: { padding: '8px 14px' },
          sizeLarge: { padding: '10px 18px', fontSize: '1rem' },
          outlined: { borderColor: GRAY[300], color: GRAY[700], boxShadow: SHADOW_XS, '&:hover': { backgroundColor: GRAY[50], borderColor: GRAY[300] } },
          text: { '&:hover': { backgroundColor: GRAY[50] } },
        },
      },
      MuiTextField: { defaultProps: { variant: 'outlined' } },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            borderRadius: 8,
            backgroundColor: PAPER,
            boxShadow: SHADOW_XS,
            '& .MuiOutlinedInput-notchedOutline': { borderColor: GRAY[300] },
            '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: GRAY[400] },
            '&.Mui-focused': { boxShadow: focusRing },
            '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderColor: primary, borderWidth: 1 },
            '&.Mui-error.Mui-focused': { boxShadow: `0 0 0 4px ${alpha('#d92d20', 0.14)}` },
          },
        },
      },
      MuiInputLabel: { styleOverrides: { root: { color: GRAY[500] } } },
      MuiFormHelperText: { styleOverrides: { root: { marginLeft: 2 } } },
      MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' }, rounded: { borderRadius: 12 } } },
      MuiCard: {
        defaultProps: { elevation: 0 },
        styleOverrides: { root: { border: `1px solid ${GRAY[200]}`, borderRadius: 12, boxShadow: SHADOW_SM } },
      },
      MuiAppBar: {
        defaultProps: { elevation: 0, color: 'inherit' },
        styleOverrides: {
          root: { backgroundColor: colors.headerBg, color: colors.headerText, borderBottom: `1px solid ${GRAY[200]}` },
        },
      },
      MuiDrawer: { styleOverrides: { paper: { backgroundColor: colors.navBg, color: colors.navText } } },
      MuiTableContainer: { styleOverrides: { root: { border: `1px solid ${GRAY[200]}`, borderRadius: 12, boxShadow: SHADOW_XS } } },
      MuiTableHead: {
        styleOverrides: {
          root: { backgroundColor: GRAY[50], '& .MuiTableCell-root': { color: GRAY[600], fontWeight: 500, fontSize: '0.75rem' } },
        },
      },
      MuiTableCell: { styleOverrides: { root: { borderBottomColor: GRAY[200] } } },
      MuiTableRow: { styleOverrides: { root: { '&.MuiTableRow-hover:hover': { backgroundColor: GRAY[50] } } } },
      MuiChip: {
        styleOverrides: {
          root: { fontWeight: 500, borderRadius: 16 },
          outlined: { borderColor: GRAY[300] },
        },
      },
      MuiAlert: {
        styleOverrides: {
          root: {
            borderRadius: 8,
            '&.MuiAlert-standardError': { backgroundColor: '#fef3f2', color: '#b42318' },
            '&.MuiAlert-standardWarning': { backgroundColor: '#fffaeb', color: '#b54708' },
            '&.MuiAlert-standardSuccess': { backgroundColor: '#ecfdf3', color: '#067647' },
            '&.MuiAlert-standardInfo': { backgroundColor: '#eff8ff', color: '#175cd3' },
          },
        },
      },
      MuiDialog: { styleOverrides: { paper: { borderRadius: 12, boxShadow: '0 20px 24px -4px rgba(16, 24, 40, 0.08)' } } },
      MuiTabs: { styleOverrides: { indicator: { height: 2 } } },
      MuiTab: { styleOverrides: { root: { textTransform: 'none', fontWeight: 600 } } },
      MuiTooltip: { styleOverrides: { tooltip: { backgroundColor: GRAY_LIGHT[900], fontSize: '0.75rem', borderRadius: 8 } } },
      MuiListItemButton: {
        styleOverrides: { root: { borderRadius: 8, '&.Mui-selected': { backgroundColor: primaryLight, color: primary } } },
      },
    },
  })
}

/** The original look; kept for code that needs a theme outside the provider. */
export const theme = buildTheme('light', DEFAULT_THEME.light)
