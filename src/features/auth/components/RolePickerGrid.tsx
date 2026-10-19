import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import BusinessIcon from '@mui/icons-material/Business'
import CorporateFareIcon from '@mui/icons-material/CorporateFare'
import FactoryIcon from '@mui/icons-material/Factory'
import {
  Avatar,
  Box,
  Card,
  CardActionArea,
  CardContent,
  Chip,
  Tab,
  Tabs,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import type { CompanyType } from '../store/authSlice'
import { DEMO_ACCOUNTS, ROLE_LABELS, type DemoAccount } from '../utils/demoAccounts'

interface RolePickerGridProps {
  onSelectAccount: (account: DemoAccount) => void | Promise<void>
  disabled?: boolean
}

export function RolePickerGrid({ onSelectAccount, disabled }: RolePickerGridProps) {
  const [activeTab, setActiveTab] = useState<CompanyType>('ERGO')

  const accounts = DEMO_ACCOUNTS.filter((acc) => acc.companyType === activeTab)

  return (
    <Box sx={{ width: '100%' }}>
      {/* Tab selection */}
      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 3 }}>
        <Tabs
          value={activeTab}
          onChange={(_e, val: CompanyType) => setActiveTab(val)}
          variant="fullWidth"
          sx={{
            '& .MuiTab-root': {
              fontWeight: 700,
              fontSize: '0.95rem',
              textTransform: 'none',
              py: 1.5,
            },
          }}
        >
          <Tab
            value="ERGO"
            icon={<CorporateFareIcon />}
            iconPosition="start"
            label="ERGO — Internal Teams (7 roles)"
          />
          <Tab
            value="CUSTOMER"
            icon={<BusinessIcon />}
            iconPosition="start"
            label="Customer — Roles (6 roles)"
          />
          <Tab
            value="SUPPLIER"
            icon={<FactoryIcon />}
            iconPosition="start"
            label="Supplier — Roles (5 roles)"
          />
        </Tabs>
      </Box>

      {/* Group Description Banner */}
      <Box sx={{ mb: 2.5, px: 0.5 }}>
        <Typography variant="body2" color="text.secondary">
          {activeTab === 'ERGO' &&
            'ERGO Platform Team: Full administrative, RFQ estimation, 9-stage NPD management, production OFP, and quality governance.'}
          {activeTab === 'CUSTOMER' &&
            'Customer Portals (e.g. Acme Automotive): Buyer RFQ submission, drawing reviews, quote approval, milestone visibility, and delivery tracking.'}
          {activeTab === 'SUPPLIER' &&
            'Supplier Partner Portals (e.g. Precision Castings): Quotation submission, tooling updates, production readiness, and self-inspection QA.'}
        </Typography>
      </Box>

      {/* Cards Grid */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: {
            xs: '1fr',
            sm: 'repeat(2, 1fr)',
            md: 'repeat(3, 1fr)',
          },
          gap: 2,
        }}
      >
        {accounts.map((acc) => {
          const initials = `${acc.firstName[0] ?? ''}${acc.lastName[0] ?? ''}`
          return (
            <Card
              key={acc.id}
              variant="outlined"
              sx={{
                height: '100%',
                display: 'flex',
                flexDirection: 'column',
                borderRadius: 2,
                transition: 'all 0.2s ease-in-out',
                borderColor: 'divider',
                '&:hover': {
                  borderColor: 'primary.main',
                  boxShadow: (t) =>
                    `0 4px 16px ${t.palette.mode === 'dark' ? 'rgba(0,0,0,0.5)' : 'rgba(25, 118, 210, 0.12)'}`,
                  transform: 'translateY(-2px)',
                },
              }}
            >
              <CardActionArea
                disabled={disabled}
                onClick={() => onSelectAccount(acc)}
                sx={{
                  height: '100%',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'stretch',
                  justifyContent: 'space-between',
                  p: 2,
                }}
              >
                <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
                  {/* User Header */}
                  <Box
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 1.5,
                      mb: 1.5,
                    }}
                  >
                    <Avatar
                      sx={{
                        bgcolor: acc.avatarColor,
                        width: 42,
                        height: 42,
                        fontWeight: 700,
                        fontSize: '0.9rem',
                      }}
                    >
                      {initials}
                    </Avatar>
                    <Box sx={{ minWidth: 0, flex: 1 }}>
                      <Typography variant="subtitle1" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                        {acc.firstName} {acc.lastName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" noWrap sx={{ display: 'block' }}>
                        {acc.jobTitle}
                      </Typography>
                      <Typography variant="caption" sx={{ color: 'primary.main', fontWeight: 600 }}>
                        {acc.companyName}
                      </Typography>
                    </Box>
                  </Box>

                  {/* Role Chip */}
                  <Box sx={{ mb: 1.5 }}>
                    <Chip
                      size="small"
                      label={ROLE_LABELS[acc.role]}
                      color={
                        acc.companyType === 'ERGO'
                          ? 'primary'
                          : acc.companyType === 'CUSTOMER'
                            ? 'info'
                            : 'warning'
                      }
                      sx={{ fontWeight: 600, fontSize: '0.72rem' }}
                    />
                  </Box>

                  {/* Description */}
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{
                      fontSize: '0.8rem',
                      lineHeight: 1.45,
                      display: '-webkit-box',
                      WebkitLineClamp: 3,
                      WebkitBoxOrient: 'vertical',
                      overflow: 'hidden',
                    }}
                  >
                    {acc.description}
                  </Typography>
                </CardContent>

                {/* Sign In CTA footer */}
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'flex-end',
                    gap: 0.5,
                    mt: 2,
                    pt: 1,
                    borderTop: '1px dashed',
                    borderColor: 'divider',
                  }}
                >
                  <Typography variant="caption" sx={{ fontWeight: 700, color: 'primary.main' }}>
                    Login as {acc.firstName}
                  </Typography>
                  <ArrowForwardIcon fontSize="small" sx={{ color: 'primary.main', fontSize: 16 }} />
                </Box>
              </CardActionArea>
            </Card>
          )
        })}
      </Box>
    </Box>
  )
}
