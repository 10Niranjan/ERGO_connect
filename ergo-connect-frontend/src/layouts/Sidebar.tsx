import AccountBoxIcon from '@mui/icons-material/AccountBox'
import AltRouteIcon from '@mui/icons-material/AltRoute'
import AssignmentIcon from '@mui/icons-material/Assignment'
import BusinessIcon from '@mui/icons-material/Business'
import ChatIcon from '@mui/icons-material/Chat'
import DashboardIcon from '@mui/icons-material/Dashboard'
import EngineeringIcon from '@mui/icons-material/Engineering'
import FactCheckIcon from '@mui/icons-material/FactCheck'
import FolderIcon from '@mui/icons-material/Folder'
import LocalShippingIcon from '@mui/icons-material/LocalShipping'
import PeopleIcon from '@mui/icons-material/People'
import ReportProblemIcon from '@mui/icons-material/ReportProblem'
import RequestQuoteIcon from '@mui/icons-material/RequestQuote'
import SecurityIcon from '@mui/icons-material/Security'
import TuneIcon from '@mui/icons-material/Tune'
import {
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
import { NavLink } from 'react-router'
import type { Session } from '@/features/auth/store/authSlice'

export const DRAWER_WIDTH = 260

// Icon keys the backend menu (nav_items.icon) may name. An unknown key falls back to the dashboard icon.
const ICONS: Record<string, ElementType> = {
  dashboard: DashboardIcon,
  rfq: RequestQuoteIcon,
  assignment: AssignmentIcon,
  engineering: EngineeringIcon,
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
}

interface SidebarProps {
  session: Session
  mobileOpen: boolean
  onCloseMobile: () => void
}

export function Sidebar({ session, mobileOpen, onCloseMobile }: SidebarProps) {
  const initials = `${session.user.firstName[0] ?? ''}${session.user.lastName[0] ?? ''}`

  const drawerContent = (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
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
        <Box
          sx={{
            width: 38,
            height: 38,
            borderRadius: 1.5,
            bgcolor: 'primary.main',
            color: 'primary.contrastText',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: 800,
            fontSize: '1.15rem',
            boxShadow: '0 2px 6px rgba(11, 79, 138, 0.3)',
          }}
        >
          E
        </Box>
        <Box>
          <Typography variant="subtitle1" sx={{ fontWeight: 800, color: 'primary.main', lineHeight: 1.2 }}>
            ERGO Connect
          </Typography>
          <Typography variant="caption" sx={{ color: 'text.secondary', fontWeight: 500 }}>
            Collaboration Platform
          </Typography>
        </Box>
      </Box>

      <Divider />

      {/* Navigation items list */}
      <Box sx={{ flexGrow: 1, overflowY: 'auto', py: 1 }}>
        {/* The menu is built by the server from the role's permissions: what is not sent is not allowed. */}
        {(session.access?.menu ?? []).map((section, sIndex) => {
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
                      color: 'text.secondary',
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
                    component={NavLink}
                    to={item.path}
                    end={item.path === '/'}
                    onClick={onCloseMobile}
                    sx={{
                      mx: 1.5,
                      borderRadius: 1.5,
                      py: 0.8,
                      px: 1.5,
                      color: 'text.secondary',
                      transition: 'all 0.15s ease-in-out',
                      '&:hover': {
                        bgcolor: 'action.hover',
                        color: 'primary.main',
                        '& .MuiListItemIcon-root': { color: 'primary.main' },
                      },
                      '&.active': {
                        bgcolor: 'primary.main',
                        color: 'primary.contrastText',
                        fontWeight: 600,
                        boxShadow: '0 2px 8px rgba(11, 79, 138, 0.25)',
                        '& .MuiListItemIcon-root': { color: 'primary.contrastText' },
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

      <Divider />

      {/* User / Company card at sidebar bottom */}
      <Box sx={{ p: 2, bgcolor: 'rgba(0, 0, 0, 0.02)' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <Avatar sx={{ width: 34, height: 34, fontSize: 13, bgcolor: 'primary.main' }}>{initials}</Avatar>
          <Box sx={{ minWidth: 0, flexGrow: 1 }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }} noWrap>
              {session.user.firstName} {session.user.lastName}
            </Typography>
            <Typography variant="caption" sx={{ color: 'text.secondary' }} noWrap component="div">
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
