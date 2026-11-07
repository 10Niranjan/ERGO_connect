import AccountBoxIcon from '@mui/icons-material/AccountBox'
import AltRouteIcon from '@mui/icons-material/AltRoute'
import AssignmentIcon from '@mui/icons-material/Assignment'
import BusinessIcon from '@mui/icons-material/Business'
import ChatIcon from '@mui/icons-material/Chat'
import DashboardIcon from '@mui/icons-material/Dashboard'
import EngineeringIcon from '@mui/icons-material/Engineering'
import FactCheckIcon from '@mui/icons-material/FactCheck'
import DescriptionIcon from '@mui/icons-material/Description'
import FolderIcon from '@mui/icons-material/Folder'
import PaletteIcon from '@mui/icons-material/Palette'
import LocalShippingIcon from '@mui/icons-material/LocalShipping'
import PeopleIcon from '@mui/icons-material/People'
import ReportProblemIcon from '@mui/icons-material/ReportProblem'
import RequestQuoteIcon from '@mui/icons-material/RequestQuote'
import SecurityIcon from '@mui/icons-material/Security'
import TuneIcon from '@mui/icons-material/Tune'
import {
  alpha,
  Avatar,
  Box,
  Divider,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  Typography,
} from '@mui/material'
import type { ElementType } from 'react'
import { Link, useLocation } from 'react-router'
import { BrandLogo } from '@/components/ui/BrandLogo'
import type { Session } from '@/features/auth/store/authSlice'

export const DRAWER_WIDTH = 260

// Icon keys the backend menu (nav_items.icon) may name. An unknown key falls back to the dashboard icon.
const ICONS: Record<string, ElementType> = {
  dashboard: DashboardIcon,
  rfq: RequestQuoteIcon,
  assignment: AssignmentIcon,
  engineering: EngineeringIcon,
  description: DescriptionIcon,
  shipping: LocalShippingIcon,
  inspection: FactCheckIcon,
  ncr: ReportProblemIcon,
  deviation: AltRouteIcon,
  folder: FolderIcon,
  chat: ChatIcon,
  business: BusinessIcon,
  account: AccountBoxIcon,
  tune: TuneIcon,
  people: PeopleIcon,
  security: SecurityIcon,
  palette: PaletteIcon,
}

interface SidebarProps {
  session: Session
  mobileOpen: boolean
  onCloseMobile: () => void
}

// Not a server menu row: the screen is ERGO Admin only (RequireRole on the route), so the sidebar adds it for that role.
const THEME_ITEM = { key: 'theme-branding', label: 'Theme & Branding', path: '/admin/appearance', icon: 'palette' }

export function Sidebar({ session, mobileOpen, onCloseMobile }: SidebarProps) {
  const initials = `${session.user.firstName[0] ?? ''}${session.user.lastName[0] ?? ''}`
  const { pathname } = useLocation()
  const serverMenu = session.access?.menu ?? []
  const menu =
    session.activeRole === 'ERGO_ADMIN'
      ? serverMenu.some((section) => section.title?.toUpperCase() === 'ERGO ADMIN')
        ? serverMenu.map((section) =>
            section.title?.toUpperCase() === 'ERGO ADMIN' ? { ...section, items: [...section.items, THEME_ITEM] } : section,
          )
        : [...serverMenu, { title: 'ERGO ADMIN', items: [THEME_ITEM] }]
      : serverMenu
  // The longest item path the page sits under is the active one: /quality/ncrs is NCR / CAPA, not Inspections (/quality) too.
  const activePath = menu
    .flatMap((section) => section.items.map((item) => item.path))
    .filter((path) => (path === '/' ? pathname === '/' : pathname === path || pathname.startsWith(`${path}/`)))
    .sort((a, b) => b.length - a.length)[0]

  const drawerContent = (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', bgcolor: 'chrome.navBg', color: 'chrome.navText' }}>
      {/* Brand header */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1.5,
          px: 2.5,
          py: 2,
          minHeight: 64,
        }}
      >
        <BrandLogo height={40} />
      </Box>

      <Divider sx={{ borderColor: 'currentColor', opacity: 0.15 }} />

      {/* Navigation items list */}
      <Box sx={{ flexGrow: 1, overflowY: 'auto', py: 1 }}>
        {/* The menu is built by the server from the role's permissions: what is not sent is not allowed. */}
        {menu.map((section, sIndex) => {
          const visibleItems = section.items
          if (!visibleItems.length) return null

          return (
            <List
              key={section.title ?? `section-${sIndex}`}
              disablePadding
              subheader={
                section.title ? (
                  <ListSubheader
                    component="div"
                    disableSticky
                    sx={{
                      px: 2.5,
                      pt: 1.5,
                      pb: 0.5,
                      fontSize: '0.7rem',
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      color: 'inherit',
                      opacity: 0.7,
                      bgcolor: 'transparent',
                      lineHeight: 'initial',
                    }}
                  >
                    {section.title}
                  </ListSubheader>
                ) : undefined
              }
            >
              {visibleItems.map((item) => {
                const Icon = ICONS[item.icon] ?? DashboardIcon
                return (
                <ListItem key={item.key} disablePadding sx={{ display: 'block', mb: 0.3 }}>
                  <ListItemButton
                    component={Link}
                    to={item.path}
                    className={item.path === activePath ? 'active' : undefined}
                    aria-current={item.path === activePath ? 'page' : undefined}
                    onClick={onCloseMobile}
                    sx={{
                      mx: 1.5,
                      borderRadius: 1.5,
                      py: 0.8,
                      px: 1.5,
                      color: 'chrome.navText',
                      transition: 'all 100ms linear',
                      '&:hover': { bgcolor: (t) => alpha(t.palette.chrome.navText, 0.1) },
                      '&.active': {
                        bgcolor: 'chrome.navActiveBg',
                        color: 'chrome.navActiveText',
                        '& .MuiListItemIcon-root': { color: 'chrome.navActiveText' },
                        '& .MuiTypography-root': { fontWeight: 600 },
                      },
                    }}
                  >
                    <ListItemIcon sx={{ minWidth: 34, color: 'inherit' }}>
                      <Icon sx={{ fontSize: 20 }} />
                    </ListItemIcon>
                    <ListItemText
                      primary={
                        <Typography variant="body2" sx={{ fontSize: '0.84rem' }}>
                          {item.label}
                        </Typography>
                      }
                    />
                  </ListItemButton>
                </ListItem>
                )
              })}
            </List>
          )
        })}
      </Box>

      <Divider sx={{ borderColor: 'currentColor', opacity: 0.15 }} />

      {/* User / Company card at sidebar bottom */}
      <Box sx={{ p: 2, bgcolor: (t) => alpha(t.palette.chrome.navText, 0.08) }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <Avatar sx={{ width: 34, height: 34, fontSize: 13, fontWeight: 600, bgcolor: 'primary.light', color: 'primary.main' }}>{initials}</Avatar>
          <Box sx={{ minWidth: 0, flexGrow: 1 }}>
            <Typography variant="body2" sx={{ fontWeight: 600, color: 'inherit' }} noWrap>
              {session.user.firstName} {session.user.lastName}
            </Typography>
            <Typography variant="caption" sx={{ color: 'inherit', opacity: 0.75 }} noWrap component="div">
              {session.user.companyName}
            </Typography>
          </Box>
        </Box>
      </Box>
    </Box>
  )

  return (
    <Box component="nav" sx={{ width: { md: DRAWER_WIDTH }, flexShrink: { md: 0 } }}>
      {/* Mobile temporary drawer */}
      <Drawer
        variant="temporary"
        open={mobileOpen}
        onClose={onCloseMobile}
        ModalProps={{ keepMounted: true }}
        sx={{
          display: { xs: 'block', md: 'none' },
          '& .MuiDrawer-paper': { boxSizing: 'border-box', width: DRAWER_WIDTH },
        }}
      >
        {drawerContent}
      </Drawer>

      {/* Desktop permanent drawer */}
      <Drawer
        variant="permanent"
        open
        sx={{
          display: { xs: 'none', md: 'block' },
          '& .MuiDrawer-paper': {
            boxSizing: 'border-box',
            width: DRAWER_WIDTH,
            borderRight: '1px solid',
            borderColor: 'divider',
          },
        }}
      >
        {drawerContent}
      </Drawer>
    </Box>
  )
}
