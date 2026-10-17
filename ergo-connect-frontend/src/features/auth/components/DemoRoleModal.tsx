import CloseIcon from '@mui/icons-material/Close'
import {
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Typography,
} from '@mui/material'
import { useNavigate } from 'react-router'
import { useAppDispatch } from '@/hooks/useAppDispatch'
import { sessionStarted } from '../store/authSlice'
import {
  buildDemoSession,
  createSignedDemoJwt,
  DEMO_STORAGE_KEY,
  type DemoAccount,
} from '../utils/demoAccounts'
import { RolePickerGrid } from './RolePickerGrid'

interface DemoRoleModalProps {
  open: boolean
  onClose: () => void
}

export function DemoRoleModal({ open, onClose }: DemoRoleModalProps) {
  const dispatch = useAppDispatch()
  const navigate = useNavigate()

  const handleSelectAccount = async (account: DemoAccount) => {
    const token = await createSignedDemoJwt(account)
    const session = buildDemoSession(account, token)
    sessionStorage.setItem(DEMO_STORAGE_KEY, JSON.stringify(session))
    dispatch(sessionStarted(session))
    onClose()
    navigate(account.landingPath)
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="lg" fullWidth scroll="paper">
      <DialogTitle sx={{ m: 0, p: 2, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <Typography variant="h6" component="div" sx={{ fontWeight: 800 }}>
            🎭 Quick Role Switcher (Client Demo Mode)
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Switch your active role and company scope instantly to demonstrate different perspectives.
          </Typography>
        </div>
        <IconButton aria-label="close" onClick={onClose} sx={{ color: (theme) => theme.palette.grey[500] }}>
          <CloseIcon />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ p: { xs: 2, md: 3 } }}>
        <RolePickerGrid onSelectAccount={handleSelectAccount} />
      </DialogContent>
    </Dialog>
  )
}
