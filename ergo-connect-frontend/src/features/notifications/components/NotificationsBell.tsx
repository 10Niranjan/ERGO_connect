import NotificationsIcon from '@mui/icons-material/Notifications'
import { Badge, Box, Button, IconButton, ListItemButton, ListItemText, Menu, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import {
  useMarkAllNotificationsReadMutation,
  useMarkNotificationReadMutation,
  useNotificationsQuery,
  useUnreadCountQuery,
  type NotificationView,
} from '../services/notificationsApi'

/** Header bell + dropdown drawer (SRS §12.12.2). Polls the unread count so the badge stays fresh. */
export function NotificationsBell() {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const open = Boolean(anchor)
  const { data: unread } = useUnreadCountQuery(undefined, { pollingInterval: 45_000 })
  const { data: page } = useNotificationsQuery({ size: 15 }, { skip: !open, refetchOnMountOrArgChange: true })
  const [markRead] = useMarkNotificationReadMutation()
  const [markAllRead] = useMarkAllNotificationsReadMutation()
  const navigate = useNavigate()

  const handleRowClick = (notification: NotificationView) => {
    if (!notification.read) void markRead(notification.id)
    setAnchor(null)
    navigate(notification.actionUrl)
  }

  return (
    <>
      <IconButton aria-label="Notifications" onClick={(event) => setAnchor(event.currentTarget)}>
        <Badge badgeContent={unread?.count ?? 0} color="error">
          <NotificationsIcon sx={{ color: 'inherit' }} />
        </Badge>
      </IconButton>
      <Menu
        anchorEl={anchor}
        open={open}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { width: 420, maxHeight: 480 } } }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', px: 2, py: 1 }}>
          <Typography variant="subtitle1">Notifications</Typography>
          <Button size="small" disabled={!unread?.count} onClick={() => void markAllRead()}>
            Mark all read
          </Button>
        </Box>
        {!page?.items.length ? (
          <Typography variant="body2" sx={{ px: 2, py: 3, textAlign: 'center', opacity: 0.7 }}>
            No notifications yet.
          </Typography>
        ) : (
          page.items.map((notification) => (
            <ListItemButton key={notification.id} onClick={() => handleRowClick(notification)} sx={{ alignItems: 'flex-start' }}>
              <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'primary.main', mt: '7px', mr: 1.5, flexShrink: 0, visibility: notification.read ? 'hidden' : 'visible' }} />
              <ListItemText
                primary={
                  <Typography variant="body2" sx={{ fontWeight: notification.read ? 400 : 700 }} noWrap>
                    {notification.title}
                  </Typography>
                }
                secondary={
                  <>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }} noWrap>
                      {notification.message}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {new Date(notification.sentAt).toLocaleString()}
                    </Typography>
                  </>
                }
              />
            </ListItemButton>
          ))
        )}
      </Menu>
    </>
  )
}
