import { api } from '../../app/store'

// SRS §12.12.2, Phase 6e backend. Same access for all 18 roles — no record scope here beyond "mine".

export type NotificationType =
  | 'ACTION_REQUIRED'
  | 'OVERDUE_REMINDER'
  | 'APPROVAL_PENDING'
  | 'RESPONSE_RECEIVED'
  | 'DOCUMENT_UPLOADED'
  | 'RECORD_UPDATED'

export interface NotificationView {
  id: number
  type: NotificationType
  title: string
  message: string
  /** Already a full frontend route (e.g. "/rfqs/123") — use directly with navigate(). */
  actionUrl: string
  linkedModule: string
  linkedRecordId: number
  read: boolean
  sentAt: string
  readAt: string | null
}

export interface NotificationPage {
  items: NotificationView[]
  page: number
  size: number
  totalElements: number
  unreadCount: number
}

export interface UnreadCount {
  count: number
}

export interface MarkedRead {
  marked: number
}

export interface NotificationFilters {
  unreadOnly?: boolean
  type?: NotificationType
  page?: number
  size?: number
}

const notificationsApi = api.enhanceEndpoints({ addTagTypes: ['Notification', 'UnreadCount'] }).injectEndpoints({
  endpoints: (build) => ({
    notifications: build.query<NotificationPage, NotificationFilters | void>({
      query: (filters) => ({ url: '/notifications', params: filters ?? {} }),
      providesTags: ['Notification'],
    }),
    unreadCount: build.query<UnreadCount, void>({
      query: () => ({ url: '/notifications/unread-count' }),
      providesTags: ['UnreadCount'],
    }),
    markNotificationRead: build.mutation<NotificationView, number>({
      query: (id) => ({ url: `/notifications/${id}/read`, method: 'POST' }),
      invalidatesTags: ['Notification', 'UnreadCount'],
    }),
    markAllNotificationsRead: build.mutation<MarkedRead, void>({
      query: () => ({ url: '/notifications/read-all', method: 'POST' }),
      invalidatesTags: ['Notification', 'UnreadCount'],
    }),
  }),
})

export const {
  useNotificationsQuery,
  useUnreadCountQuery,
  useMarkNotificationReadMutation,
  useMarkAllNotificationsReadMutation,
} = notificationsApi
