import { api } from '@/services/baseApi'

// Supplier audit reports and customer assessments (client questions 28, 29). ERGO only; the backend decides who may
// see and add them (canAdd), so the UI shows exactly that.
export type AssessmentKind = 'SUPPLIER_AUDIT' | 'CUSTOMER_ASSESSMENT'
export type AuditType = 'ON_SITE' | 'VIRTUAL' | 'DESK_REVIEW'
export type Recommendation = 'APPROVE' | 'REJECT'
export type RiskRating = 'LOW' | 'MEDIUM' | 'HIGH'

export const AUDIT_TYPE_LABELS: Record<AuditType, string> = { ON_SITE: 'On-site', VIRTUAL: 'Virtual', DESK_REVIEW: 'Desk review' }
export const RECOMMENDATION_LABELS: Record<Recommendation, string> = { APPROVE: 'Approve', REJECT: 'Reject' }
export const RISK_LABELS: Record<RiskRating, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' }

export interface Assessment {
  id: number
  kind: AssessmentKind
  assessmentDate: string
  auditType: AuditType | null
  auditor: string | null
  findings: string
  recommendation: Recommendation | null
  riskRating: RiskRating | null
  file: { id: number; fileName: string; size: number } | null
  createdBy: string | null
  createdAt: string
}

export interface AssessmentList {
  kind: AssessmentKind
  canAdd: boolean
  items: Assessment[]
}

export interface AssessmentForm {
  assessmentDate: string
  auditType?: AuditType
  auditor?: string
  findings: string
  recommendation?: Recommendation
  riskRating?: RiskRating
}

const assessmentsApi = api.enhanceEndpoints({ addTagTypes: ['Assessment', 'Company'] }).injectEndpoints({
  endpoints: (build) => ({
    assessments: build.query<AssessmentList, number>({
      query: (companyId) => `/companies/${companyId}/assessments`,
      providesTags: (_result, _error, companyId) => [{ type: 'Assessment', id: companyId }],
    }),
    addAssessment: build.mutation<Assessment, { companyId: number; form: AssessmentForm; file: File | null }>({
      query: ({ companyId, form, file }) => {
        const body = new FormData()
        body.append('assessment', new Blob([JSON.stringify(form)], { type: 'application/json' }))
        if (file) body.append('file', file)
        return { url: `/companies/${companyId}/assessments`, method: 'POST', body }
      },
      // the company's "still needed" list changes once a supplier has its first audit report
      invalidatesTags: (_result, _error, { companyId }) => [{ type: 'Assessment', id: companyId }, 'Company'],
    }),
    /** Resolves to an object URL plus the file name from Content-Disposition (same pattern as NCR evidence). */
    downloadAssessmentFile: build.mutation<{ url: string; fileName: string }, { companyId: number; assessmentId: number }>({
      query: ({ companyId, assessmentId }) => ({
        url: `/companies/${companyId}/assessments/${assessmentId}/file`,
        responseHandler: async (response) => {
          if (!response.ok) return response.json()
          const disposition = response.headers.get('content-disposition') ?? ''
          const match = /filename\*?=(?:UTF-8'')?"?([^;"]+)"?/i.exec(disposition)
          const fileName = match ? decodeURIComponent(match[1]) : 'report'
          return { url: URL.createObjectURL(await response.blob()), fileName }
        },
      }),
    }),
  }),
})

export const { useAssessmentsQuery, useAddAssessmentMutation, useDownloadAssessmentFileMutation } = assessmentsApi
