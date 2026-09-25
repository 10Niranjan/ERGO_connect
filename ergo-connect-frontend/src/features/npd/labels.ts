import type { ChipColor } from '../rfq/labels'
import type { ActivityStatus, NpdStage, RiskLevel } from './npdApi'

export const NPD_STAGE: Record<NpdStage, { label: string; color: ChipColor }> = {
  PENDING_CONVERSION: { label: 'Pending Conversion', color: 'default' },
  PLANNING: { label: 'Planning', color: 'info' },
  COMMERCIAL_SOURCING_READINESS: { label: 'Commercial & Sourcing Readiness', color: 'info' },
  TOOLING_MATERIAL_RELEASE: { label: 'Tooling & Material Release', color: 'primary' },
  TRIAL_PREPARATION: { label: 'Trial Preparation', color: 'primary' },
  TRIAL_EXECUTION: { label: 'Trial Execution', color: 'primary' },
  FINAL_SAMPLE_READINESS: { label: 'Final Sample Readiness', color: 'secondary' },
  CUSTOMER_FEEDBACK: { label: 'Customer Feedback', color: 'secondary' },
  APPROVED_FOR_PRODUCTION_HANDOVER: { label: 'Approved for Production Handover', color: 'success' },
  CLOSED: { label: 'Closed', color: 'default' },
}

export const ACTIVITY_STATUS: Record<ActivityStatus, { label: string; color: ChipColor }> = {
  NOT_STARTED: { label: 'Not started', color: 'default' },
  IN_PROGRESS: { label: 'In progress', color: 'info' },
  DONE: { label: 'Done', color: 'success' },
  BLOCKED: { label: 'Blocked', color: 'error' },
}

export const RISK_LEVEL: Record<RiskLevel, { label: string; color: ChipColor }> = {
  LOW: { label: 'Low', color: 'success' },
  MEDIUM: { label: 'Medium', color: 'warning' },
  HIGH: { label: 'High', color: 'error' },
}

/** The 9 real workflow stages in order. PENDING_CONVERSION precedes conversion and is never a project's own stage. */
export const NPD_STAGE_ORDER: NpdStage[] = [
  'PLANNING',
  'COMMERCIAL_SOURCING_READINESS',
  'TOOLING_MATERIAL_RELEASE',
  'TRIAL_PREPARATION',
  'TRIAL_EXECUTION',
  'FINAL_SAMPLE_READINESS',
  'CUSTOMER_FEEDBACK',
  'APPROVED_FOR_PRODUCTION_HANDOVER',
  'CLOSED',
]

/** Position in the fixed 9-stage sequence — "how far along", not the SRS's weighted progress. */
export function stageProgress(stage: NpdStage): { label: string; percent: number } {
  const position = NPD_STAGE_ORDER.indexOf(stage) + 1
  return { label: `${position} of ${NPD_STAGE_ORDER.length}`, percent: Math.round((position / NPD_STAGE_ORDER.length) * 100) }
}
