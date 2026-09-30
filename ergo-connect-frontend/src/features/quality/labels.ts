import type { ChipColor } from '../rfq/labels'
import type { DefectSeverity, LinkedModule, ResultStatus } from './qualityApi'

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
