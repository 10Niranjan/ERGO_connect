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
import {
  COMMUNICATION_VIEWERS,
  COMPANY_MANAGERS,
  DOCUMENT_VIEWERS,
  EXTERNAL_ROLES,
  MASTER_DATA_VIEWERS,
  NPD_CUSTOMER_ROLES,
  NPD_SUPPLIER_ROLES,
  NPD_VIEWERS,
  OFP_CUSTOMER_ROLES,
  OFP_SUPPLIER_ROLES,
  OFP_VIEWERS,
  QUALITY_VIEWERS,
  RFQ_VIEWERS,
  SUPPLIER_QUOTERS,
} from '@/features/auth/utils/access'
import type { Role, Session } from '@/features/auth/store/authSlice'

export const DRAWER_WIDTH = 260

interface NavItem {
  label: string
  to: string
  icon: ElementType
  roles?: Role[]
}

interface NavSection {
  title?: string
  items: NavItem[]
}

// Grouped navigation menu entries per active role (SRS 12.15).
const NAV_SECTIONS: NavSection[] = [
  {
    items: [{ label: 'Dashboard', to: '/', icon: DashboardIcon }],
  },
  {
    title: 'Sourcing & Workflow',
    items: [
      { label: 'RFQs', to: '/rfqs', icon: RequestQuoteIcon, roles: RFQ_VIEWERS },
      { label: 'Assigned RFQs', to: '/quotes', icon: AssignmentIcon, roles: SUPPLIER_QUOTERS },
      { label: 'NPD', to: '/npd', icon: EngineeringIcon, roles: NPD_VIEWERS },
      { label: 'NPD', to: '/npd/mine', icon: EngineeringIcon, roles: NPD_CUSTOMER_ROLES },
      { label: 'NPD Tasks', to: '/npd/mine', icon: EngineeringIcon, roles: NPD_SUPPLIER_ROLES },
      {
        label: 'Orders',
        to: '/ofp',
        icon: LocalShippingIcon,
        roles: [...OFP_VIEWERS, ...OFP_SUPPLIER_ROLES, ...OFP_CUSTOMER_ROLES],
      },
    ],
  },
  {
    title: 'Quality',
    items: [
      { label: 'Inspections', to: '/quality', icon: FactCheckIcon, roles: QUALITY_VIEWERS },
      { label: 'NCR / CAPA', to: '/quality/ncrs', icon: ReportProblemIcon, roles: QUALITY_VIEWERS },
      { label: 'Deviation Approvals', to: '/quality/deviations', icon: AltRouteIcon, roles: QUALITY_VIEWERS },
    ],
  },
  {
    title: 'Workspace',
    items: [
      { label: 'Documents', to: '/documents', icon: FolderIcon, roles: DOCUMENT_VIEWERS },
      { label: 'Communication', to: '/communication', icon: ChatIcon, roles: COMMUNICATION_VIEWERS },
    ],
  },
  {
    title: 'Administration',
    items: [
      { label: 'Customers & suppliers', to: '/companies', icon: BusinessIcon, roles: COMPANY_MANAGERS },
      { label: 'Company profile', to: '/company', icon: AccountBoxIcon, roles: EXTERNAL_ROLES },
      { label: 'Users', to: '/admin/users', icon: PeopleIcon, roles: ['ERGO_ADMIN'] },
      { label: 'Permissions', to: '/admin/permissions', icon: SecurityIcon, roles: ['ERGO_ADMIN'] },
      { label: 'Master data', to: '/admin/master-data', icon: TuneIcon, roles: MASTER_DATA_VIEWERS },
    ],
  },
]

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
        {NAV_SECTIONS.map((section, sIndex) => {
          const visibleItems = section.items.filter(
            (item) => !item.roles || item.roles.includes(session.activeRole),
          )
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
              {visibleItems.map((item) => (
                <ListItem key={item.to + item.label} disablePadding sx={{ display: 'block', mb: 0.3 }}>
                  <ListItemButton
                    component={NavLink}
                    to={item.to}
                    end={item.to === '/'}
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
                      <item.icon sx={{ fontSize: 20 }} />
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
              ))}
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
