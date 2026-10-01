import type { ChipColor } from '../rfq/labels'
import type { DeviationDecision, DeviationStatus } from './deviationApi'
import type { CapaActionStatus, CapaActionType, DefectSeverity, LinkedModule, NcrStatus, ResultStatus } from './qualityApi'

export const RESULT_STATUS: Record<ResultStatus, { label: string; color: ChipColor }> = {
  OPEN: { label: 'Open', color: 'default' },
  IN_INSPECTION: { label: 'In inspection', color: 'primary' },
  PASSED: { label: 'Passed', color: 'success' },
  REJECTED: { label: 'Rejected', color: 'error' },
  RELEASED_WITH_DEVIATION: { label: 'Released with deviation', color: 'warning' },
  CLOSED: { label: 'Closed', color: 'default' },
}

export const LINKED_MODULE: Record<LinkedModule, string> = {
  NPD: 'NPD',
  OFP: 'OFP (order)',
  INCOMING: 'Incoming',
  FINAL: 'Final',
  IN_PROCESS: 'In-process',
}

export const DEFECT_SEVERITY: Record<DefectSeverity, { label: string; color: ChipColor }> = {
  MINOR: { label: 'Minor', color: 'default' },
  MAJOR: { label: 'Major', color: 'warning' },
  CRITICAL: { label: 'Critical', color: 'error' },
}

export const NCR_STATUS: Record<NcrStatus, { label: string; color: ChipColor }> = {
  OPEN: { label: 'Open', color: 'default' },
  CONTAINMENT_IN_PROGRESS: { label: 'Containment in progress', color: 'primary' },
  ROOT_CAUSE_IN_PROGRESS: { label: 'Root cause in progress', color: 'primary' },
  CAPA_IN_PROGRESS: { label: 'CAPA in progress', color: 'warning' },
  PENDING_CLOSURE_APPROVAL: { label: 'Pending closure approval', color: 'warning' },
  CLOSED: { label: 'Closed', color: 'success' },
}

export const CAPA_ACTION_TYPE: Record<CapaActionType, string> = {
  CORRECTIVE: 'Corrective',
  PREVENTIVE: 'Preventive',
}

export const CAPA_ACTION_STATUS: Record<CapaActionStatus, { label: string; color: ChipColor }> = {
  OPEN: { label: 'Open', color: 'default' },
  IN_PROGRESS: { label: 'In progress', color: 'primary' },
  CLOSED: { label: 'Closed', color: 'success' },
}

export const DEVIATION_STATUS: Record<DeviationStatus, { label: string; color: ChipColor }> = {
  REQUESTED: { label: 'Requested', color: 'default' },
  UNDER_REVIEW: { label: 'Under review', color: 'primary' },
  CUSTOMER_APPROVAL_PENDING: { label: 'Pending customer approval', color: 'warning' },
  APPROVED: { label: 'Approved', color: 'success' },
  REJECTED: { label: 'Rejected', color: 'error' },
  EXPIRED: { label: 'Expired', color: 'warning' },
  CLOSED: { label: 'Closed', color: 'default' },
}

export const DEVIATION_DECISION: Record<DeviationDecision, { label: string; color: ChipColor }> = {
  PENDING: { label: 'Pending', color: 'default' },
  APPROVED: { label: 'Approved', color: 'success' },
  REJECTED: { label: 'Rejected', color: 'error' },
  SENT_BACK: { label: 'Sent back', color: 'warning' },
}
