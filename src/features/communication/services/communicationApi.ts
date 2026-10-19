import { api } from '@/services/baseApi'

// SRS §10.10/§12.12.1/§13.7.3, Phase 6c backend. Comments hang off one record of one module; the backend decides
// which comments the caller may read (record scope + audience), who may do what to each (the `actions` list) and
// how people are labelled. Nothing in the UI is a protection.

/** INTERNAL = ERGO only; EXTERNAL_CUSTOMER = ERGO + the record's customer; EXTERNAL_SUPPLIER = ERGO + its supplier. */
export type CommentType = 'INTERNAL' | 'EXTERNAL_CUSTOMER' | 'EXTERNAL_SUPPLIER'

/** The record kinds comments can hang off (V21's CHECK list). */
export type CommentLinkedModule = 'rfq' | 'quotation' | 'npd' | 'ofp' | 'inspection' | 'ncr' | 'deviation'

/** Buttons the backend allows for this caller and comment right now (CommentDtos.CommentAction). */
export type CommentAction = 'REPLY' | 'EDIT' | 'ATTACH' | 'RESOLVE' | 'REOPEN' | 'ESCALATE' | 'DEESCALATE'

/** `userId` is null when an external viewer is looking at an ERGO user: internal ids are never disclosed. */
export interface MentionView {
  userId: number | null
  name: string
}

export interface MentionOption {
  userId: number
  name: string
}

export interface AttachmentView {
  id: number
  fileName: string
  size: number
  uploadedByMe: boolean
}

export interface CommentView {
  id: number
  linkedModule: CommentLinkedModule
  linkedRecordId: number
  commentType: CommentType
  parentCommentId: number | null
  text: string
  /** "ERGO" to an external viewer for any ERGO staff member; the person and company to ERGO; null never occurs for the author. */
  author: string | null
  mine: boolean
  createdAt: string
  updatedAt: string
  query: boolean
  resolved: boolean
  resolvedAt: string | null
  resolvedBy: string | null
  escalated: boolean
  escalatedAt: string | null
  escalatedBy: string | null
  mentions: MentionView[]
  attachments: AttachmentView[]
  replies: CommentView[]
  actions: CommentAction[]
}

export interface InboxPage {
  items: CommentView[]
  page: number
  size: number
  totalElements: number
}

/** `commentType` is optional: an external caller always posts to its own side, ERGO defaults to INTERNAL. A reply always takes its parent's audience. */
export interface CommentForm {
  linkedModule: CommentLinkedModule
  linkedRecordId: number
  commentType?: CommentType
  text: string
  parentCommentId?: number
  query: boolean
  mentionedUserIds: number[]
}

/** Full replace of the editable parts. */
export interface CommentEditForm {
  text: string
  query: boolean
  mentionedUserIds: number[]
}

export interface InboxFilters {
  linkedModule?: CommentLinkedModule
  openQueriesOnly: boolean
  escalatedOnly: boolean
  page: number
  size: number
}

export interface ThreadArgs {
  linkedModule: CommentLinkedModule
  linkedRecordId: number
}

const communicationApi = api.enhanceEndpoints({ addTagTypes: ['Comment'] }).injectEndpoints({
  endpoints: (build) => ({
    commentThread: build.query<CommentView[], ThreadArgs>({
      query: (args) => ({ url: '/comments', params: args }),
      providesTags: ['Comment'],
    }),
    commentInbox: build.query<InboxPage, InboxFilters>({
      query: (filters) => ({
        url: '/comments/inbox',
        params: Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== undefined && v !== '')),
      }),
      providesTags: ['Comment'],
    }),
    /** The people the caller may mention on a comment of this audience (the backend checks each can read the record). */
    mentionable: build.query<MentionOption[], ThreadArgs & { commentType?: CommentType }>({
      query: (args) => ({ url: '/comments/mentionable', params: args }),
      providesTags: ['Comment'],
    }),
    createComment: build.mutation<CommentView, CommentForm>({
      query: (body) => ({ url: '/comments', method: 'POST', body }),
      invalidatesTags: ['Comment'],
    }),
    editComment: build.mutation<CommentView, { id: number; form: CommentEditForm }>({
      query: ({ id, form }) => ({ url: `/comments/${id}`, method: 'PATCH', body: form }),
      invalidatesTags: ['Comment'],
    }),
    resolveComment: build.mutation<CommentView, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/comments/${id}/resolve`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Comment'],
    }),
    reopenComment: build.mutation<CommentView, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/comments/${id}/reopen`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Comment'],
    }),
    escalateComment: build.mutation<CommentView, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/comments/${id}/escalate`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Comment'],
    }),
    deescalateComment: build.mutation<CommentView, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/comments/${id}/deescalate`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Comment'],
    }),
    attachToComment: build.mutation<CommentView, { id: number; file: File }>({
      query: ({ id, file }) => {
        const body = new FormData()
        body.append('file', file)
        return { url: `/comments/${id}/attachments`, method: 'POST', body }
      },
      invalidatesTags: ['Comment'],
    }),
    removeCommentAttachment: build.mutation<CommentView, { id: number; documentId: number }>({
      query: ({ id, documentId }) => ({ url: `/comments/${id}/attachments/${documentId}`, method: 'DELETE' }),
      invalidatesTags: ['Comment'],
    }),
    /** Resolves to an object URL (the caller saves it, then revokes it). */
    downloadCommentAttachment: build.mutation<string, { id: number; documentId: number }>({
      query: ({ id, documentId }) => ({
        url: `/comments/${id}/attachments/${documentId}/content`,
        responseHandler: async (response) => (response.ok ? URL.createObjectURL(await response.blob()) : response.json()),
      }),
    }),
  }),
})

export const {
  useCommentThreadQuery,
  useCommentInboxQuery,
  useMentionableQuery,
  useCreateCommentMutation,
  useEditCommentMutation,
  useResolveCommentMutation,
  useReopenCommentMutation,
  useEscalateCommentMutation,
  useDeescalateCommentMutation,
  useAttachToCommentMutation,
  useRemoveCommentAttachmentMutation,
  useDownloadCommentAttachmentMutation,
} = communicationApi
