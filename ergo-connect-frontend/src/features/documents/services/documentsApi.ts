import { api } from '@/services/baseApi'
import type { Visibility } from '@/features/rfq/rfqApi'

// SRS §10.9/§12.11/§14 docs states, Phase 6a/6b. Upload (create) stays on each owning module's own endpoint; this
// file is the cross-module Document Library, detail, edit, replace and lock/approval surface.

export type LockStatus = 'UNLOCKED' | 'LOCKED'
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** Buttons the backend allows for this caller and document right now (DocumentDtos.DocumentAction). The UI renders only these. */
export type DocumentAction = 'EDIT' | 'REPLACE' | 'LOCK' | 'UNLOCK' | 'APPROVE' | 'REJECT'

/** The values the backend stores in documents.linked_module ('comment' = a file attached to a comment, Phase 6c). */
export type DocumentLinkedModule = 'rfq' | 'quotation' | 'OFP_SHIPMENT' | 'QUALITY_CAPA' | 'comment'

export interface CategoryOption {
  id: number
  name: string
}

export interface LibraryRow {
  id: number
  fileName: string
  contentType: string
  fileSize: number
  categoryId: number | null
  category: string | null
  visibility: Visibility
  /** "ERGO", the caller's own company, or null when the other external party uploaded it (never disclosed). */
  uploadedBy: string | null
  createdAt: string
  updatedAt: string
  versionNo: number
  hasLaterVersion: boolean
  lockStatus: LockStatus
  approvalStatus: ApprovalStatus
  linkedModule: DocumentLinkedModule
  linkedRecordId: number
  actions: DocumentAction[]
}

export interface LibraryPage {
  items: LibraryRow[]
  page: number
  size: number
  totalElements: number
}

export interface VersionSummary {
  id: number
  versionNo: number
  fileName: string
  createdAt: string
  current: boolean
}

export interface SecondaryLink {
  linkedModule: string
  linkedRecordId: number
}

export interface DocumentDetail {
  id: number
  fileName: string
  contentType: string
  fileSize: number
  categoryId: number | null
  category: string | null
  visibility: Visibility
  uploadedBy: string | null
  createdAt: string
  updatedAt: string
  versionNo: number
  supersedesDocumentId: number | null
  versionHistory: VersionSummary[]
  lockStatus: LockStatus
  approvalStatus: ApprovalStatus
  linkedModule: DocumentLinkedModule
  linkedRecordId: number
  secondaryLinks: SecondaryLink[]
  actions: DocumentAction[]
}

/** PATCH: only the fields sent change. */
export interface DocumentEditForm {
  documentCategoryId?: number
  visibility?: Visibility
}

export interface LibraryFilters {
  linkedModule?: string
  categoryId?: number
  uploaderCompanyId?: number
  approvalStatus?: ApprovalStatus
  from?: string
  to?: string
  page: number
  size: number
}

const documentsApi = api.enhanceEndpoints({ addTagTypes: ['Document'] }).injectEndpoints({
  endpoints: (build) => ({
    documentCategories: build.query<CategoryOption[], void>({ query: () => '/documents/categories' }),
    documentLibrary: build.query<LibraryPage, LibraryFilters>({
      query: (filters) => ({
        url: '/documents',
        params: Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== undefined && v !== '')),
      }),
      providesTags: ['Document'],
    }),
    document: build.query<DocumentDetail, number>({
      query: (id) => `/documents/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'Document', id }],
    }),
    editDocument: build.mutation<DocumentDetail, { id: number; form: DocumentEditForm }>({
      query: ({ id, form }) => ({ url: `/documents/${id}`, method: 'PATCH', body: form }),
      invalidatesTags: ['Document'],
    }),
    replaceDocument: build.mutation<DocumentDetail, { id: number; file: File; documentCategoryId?: number }>({
      query: ({ id, file, documentCategoryId }) => {
        const body = new FormData()
        body.append('file', file)
        if (documentCategoryId !== undefined) body.append('documentCategoryId', String(documentCategoryId))
        return { url: `/documents/${id}/replace`, method: 'POST', body }
      },
      invalidatesTags: ['Document'],
    }),
    lockDocument: build.mutation<DocumentDetail, number>({
      query: (id) => ({ url: `/documents/${id}/lock`, method: 'POST' }),
      invalidatesTags: ['Document'],
    }),
    /** Unlocking an APPROVED document reopens it and needs a reason; unlocking a merely locked one does not. */
    unlockDocument: build.mutation<DocumentDetail, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/documents/${id}/unlock`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Document'],
    }),
    approveDocument: build.mutation<DocumentDetail, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/documents/${id}/approve`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Document'],
    }),
    rejectDocument: build.mutation<DocumentDetail, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/documents/${id}/reject`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Document'],
    }),
    /** Resolves to an object URL (the caller saves it, then revokes it). */
    downloadDocument: build.mutation<string, number>({
      query: (id) => ({
        url: `/documents/${id}/content`,
        responseHandler: async (response) => (response.ok ? URL.createObjectURL(await response.blob()) : response.json()),
      }),
    }),
  }),
})

export const {
  useDocumentCategoriesQuery,
  useDocumentLibraryQuery,
  useDocumentQuery,
  useEditDocumentMutation,
  useReplaceDocumentMutation,
  useLockDocumentMutation,
  useUnlockDocumentMutation,
  useApproveDocumentMutation,
  useRejectDocumentMutation,
  useDownloadDocumentMutation,
} = documentsApi
