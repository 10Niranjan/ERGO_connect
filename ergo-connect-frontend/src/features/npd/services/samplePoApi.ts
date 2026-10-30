import { api } from '@/services/baseApi'
import type { AuditEntry } from '@/features/rfq/rfqApi'
import type { NpdView } from './npdApi'

/** SUBMITTED waits for the KAM, RETURNED is back with Customer NPD for correction, ACCEPTED has a project. */
export type SamplePoStatus = 'SUBMITTED' | 'RETURNED' | 'ACCEPTED'

/** Buttons the backend allows now: RAISE on an eligible part, the rest on a Sample PO. */
export type SamplePoAction = 'RAISE' | 'EDIT' | 'RETURN' | 'CREATE_PROJECT'

/** One view for ERGO and the customer: a Sample PO holds no supplier data. `revision`/`description` are ERGO-only. */
export interface SamplePoView {
  id: number
  samplePoNumber: string
  rfqId: number
  rfqNumber: string | null
  rfqItemId: number
  customerName: string | null
  partNumber: string
  revision: string | null
  description: string | null
  customerPoNumber: string
  quantity: number
  requiredDate: string
  remarks: string | null
  status: SamplePoStatus
  /** What the KAM last asked the customer to fix. */
  returnComment: string | null
  npdProjectId: number | null
  npdNumber: string | null
  createdAt: string
  updatedAt: string
  actions: SamplePoAction[]
}

/** An approved part of the customer that has no Sample PO yet. */
export interface EligiblePart {
  rfqId: number
  rfqNumber: string | null
  rfqItemId: number
  partNumber: string
  approvedAt: string
  actions: SamplePoAction[]
}

export interface SamplePoForm {
  rfqItemId: number
  customerPoNumber: string
  quantity: number
  requiredDate: string
  remarks: string | null
}

/** The customer's correction of a returned Sample PO; the part never changes. */
export type SamplePoEditForm = Omit<SamplePoForm, 'rfqItemId'>

export interface CreateProjectForm {
  ergoNpdUserId: number
  ergoQualityUserId: number
  comment: string | null
}

export interface AssigneeUser {
  id: number
  name: string
  companyId: number
}

/** The active ERGO NPD and ERGO Quality users a project can be assigned to. */
export interface Assignees {
  npd: AssigneeUser[]
  quality: AssigneeUser[]
}

/** One page of the list; `total` counts every match, not just this page. */
export interface SamplePoPage {
  items: SamplePoView[]
  total: number
  page: number
  size: number
}

export interface SamplePoListArgs {
  page: number
  size: number
  status?: string
  q?: string
}

// Accepting a Sample PO creates an NPD project, so it invalidates both tags.
const samplePoApi = api.enhanceEndpoints({ addTagTypes: ['SamplePo', 'Npd'] }).injectEndpoints({
  endpoints: (build) => ({
    // One page, newest first; filtered and searched by the server.
    samplePos: build.query<SamplePoPage, SamplePoListArgs>({
      query: ({ page, size, status, q }) => ({
        url: '/npd/sample-pos',
        params: { page, size, ...(status ? { status } : {}), ...(q ? { q } : {}) },
      }),
      providesTags: ['SamplePo'],
    }),
    eligibleParts: build.query<EligiblePart[], void>({ query: () => '/npd/sample-pos/eligible-parts', providesTags: ['SamplePo'] }),
    assignees: build.query<Assignees, void>({ query: () => '/npd/sample-pos/assignees' }),
    samplePo: build.query<SamplePoView, number>({
      query: (id) => `/npd/sample-pos/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'SamplePo', id }, 'SamplePo'],
    }),
    samplePoHistory: build.query<AuditEntry[], number>({
      query: (id) => `/npd/sample-pos/${id}/history`,
      providesTags: (_r, _e, id) => [{ type: 'SamplePo', id }],
    }),
    raiseSamplePo: build.mutation<SamplePoView, SamplePoForm>({
      query: (form) => ({ url: '/npd/sample-pos', method: 'POST', body: form }),
      invalidatesTags: ['SamplePo'],
    }),
    resubmitSamplePo: build.mutation<SamplePoView, { id: number; form: SamplePoEditForm }>({
      query: ({ id, form }) => ({ url: `/npd/sample-pos/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['SamplePo'],
    }),
    returnSamplePo: build.mutation<SamplePoView, { id: number; comment: string }>({
      query: ({ id, comment }) => ({ url: `/npd/sample-pos/${id}/return`, method: 'POST', body: { comment } }),
      invalidatesTags: ['SamplePo'],
    }),
    createProjectFromSamplePo: build.mutation<NpdView, { id: number; form: CreateProjectForm }>({
      query: ({ id, form }) => ({ url: `/npd/sample-pos/${id}/create-project`, method: 'POST', body: form }),
      invalidatesTags: ['SamplePo', 'Npd'],
    }),
  }),
})

export const {
  useSamplePosQuery,
  useEligiblePartsQuery,
  useAssigneesQuery,
  useSamplePoQuery,
  useSamplePoHistoryQuery,
  useRaiseSamplePoMutation,
  useResubmitSamplePoMutation,
  useReturnSamplePoMutation,
  useCreateProjectFromSamplePoMutation,
} = samplePoApi
