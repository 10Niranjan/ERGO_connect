import { api } from '@/services/baseApi'
import type { AuditEntry } from '@/features/rfq/rfqApi'
import type { NpdView } from '@/features/npd/services/npdApi'

/** SUBMITTED waits for the KAM, RETURNED is back with Customer OFP for correction, ACCEPTED has an OFP project. */
export type ProductionPoStatus = 'SUBMITTED' | 'RETURNED' | 'ACCEPTED'

/** Buttons the backend allows now: RAISE on an eligible project, the rest on a production order. */
export type ProductionPoAction = 'RAISE' | 'EDIT' | 'RETURN' | 'CREATE_PROJECT'

/** One view for ERGO and the customer: a production order holds no supplier data. `revision`/`description` are ERGO-only. */
export interface ProductionPoView {
  id: number
  productionPoNumber: string
  sourceProjectId: number
  sourceNpdNumber: string | null
  customerName: string | null
  partNumber: string
  revision: string | null
  description: string | null
  customerPoNumber: string
  quantity: number
  requiredDate: string
  remarks: string | null
  status: ProductionPoStatus
  /** What the KAM last asked the customer to fix. */
  returnComment: string | null
  projectId: number | null
  projectNumber: string | null
  createdAt: string
  updatedAt: string
  actions: ProductionPoAction[]
}

/** An approved sample project of the customer that has no open production order. */
export interface EligibleProject {
  projectId: number
  npdNumber: string
  partNumber: string
  revision: string | null
  actions: ProductionPoAction[]
}

export interface ProductionPoForm {
  sourceProjectId: number
  customerPoNumber: string
  quantity: number
  requiredDate: string
  remarks: string | null
}

/** The customer's correction of a returned production order; the part never changes. */
export type ProductionPoEditForm = Omit<ProductionPoForm, 'sourceProjectId'>

export interface CreateOfpProjectForm {
  ergoOfpUserId: number
  ergoQualityUserId: number
  comment: string | null
}

export interface OfpAssigneeUser {
  id: number
  name: string
  companyId: number
}

/** The active ERGO OFP and ERGO Quality users an OFP project can be assigned to. */
export interface OfpAssignees {
  ofp: OfpAssigneeUser[]
  quality: OfpAssigneeUser[]
}

// Accepting a production order creates an OFP project, so it invalidates both tags.
const productionPoApi = api.enhanceEndpoints({ addTagTypes: ['ProductionPo', 'Npd'] }).injectEndpoints({
  endpoints: (build) => ({
    productionPos: build.query<ProductionPoView[], void>({ query: () => '/ofp/production-pos', providesTags: ['ProductionPo'] }),
    eligibleProjects: build.query<EligibleProject[], void>({ query: () => '/ofp/production-pos/eligible-projects', providesTags: ['ProductionPo'] }),
    ofpAssignees: build.query<OfpAssignees, void>({ query: () => '/ofp/production-pos/assignees' }),
    productionPo: build.query<ProductionPoView, number>({
      query: (id) => `/ofp/production-pos/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'ProductionPo', id }, 'ProductionPo'],
    }),
    productionPoHistory: build.query<AuditEntry[], number>({
      query: (id) => `/ofp/production-pos/${id}/history`,
      providesTags: (_r, _e, id) => [{ type: 'ProductionPo', id }],
    }),
    raiseProductionPo: build.mutation<ProductionPoView, ProductionPoForm>({
      query: (form) => ({ url: '/ofp/production-pos', method: 'POST', body: form }),
      invalidatesTags: ['ProductionPo'],
    }),
    resubmitProductionPo: build.mutation<ProductionPoView, { id: number; form: ProductionPoEditForm }>({
      query: ({ id, form }) => ({ url: `/ofp/production-pos/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['ProductionPo'],
    }),
    returnProductionPo: build.mutation<ProductionPoView, { id: number; comment: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/production-pos/${id}/return`, method: 'POST', body: { comment } }),
      invalidatesTags: ['ProductionPo'],
    }),
    createOfpProject: build.mutation<NpdView, { id: number; form: CreateOfpProjectForm }>({
      query: ({ id, form }) => ({ url: `/ofp/production-pos/${id}/create-project`, method: 'POST', body: form }),
      invalidatesTags: ['ProductionPo', 'Npd'],
    }),
  }),
})

export const {
  useProductionPosQuery,
  useEligibleProjectsQuery,
  useOfpAssigneesQuery,
  useProductionPoQuery,
  useProductionPoHistoryQuery,
  useRaiseProductionPoMutation,
  useResubmitProductionPoMutation,
  useReturnProductionPoMutation,
  useCreateOfpProjectMutation,
} = productionPoApi
