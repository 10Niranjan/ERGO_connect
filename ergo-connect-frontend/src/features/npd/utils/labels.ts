import type { ChipColor } from '@/types'
import type {
  ActivityStatus,
  ApprovalStatus,
  ApprovalType,
  IssueRiskStatus,
  IssueRiskType,
  NpdStage,
  PlanEvent,
  PlanStatus,
  ProjectKind,
  PromptResponseType,
  RiskLevel,
  ValidationParty,
  ValidationStatus,
} from '../services/npdApi'
import type { SamplePoStatus } from '../services/samplePoApi'

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

/** The 9 real workflow stages in order. PENDING_CONVERSION is never a project's own stage. */
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

/** A production (OFP) project skips these two (NpdStage.skippedBy on the server: no tool development, no sample approval). */
const PRODUCTION_SKIPPED: NpdStage[] = ['TOOLING_MATERIAL_RELEASE', 'TRIAL_PREPARATION']

/** Position in the project's own stage sequence — "how far along", not the SRS's weighted progress. */
export function stageProgress(stage: NpdStage, kind: ProjectKind = 'SAMPLE'): { label: string; percent: number } {
  const order = kind === 'PRODUCTION' ? NPD_STAGE_ORDER.filter((s) => !PRODUCTION_SKIPPED.includes(s)) : NPD_STAGE_ORDER
  const position = order.indexOf(stage) + 1
  return { label: `${position} of ${order.length}`, percent: Math.round((position / order.length) * 100) }
}

export const PROMPT_RESPONSE_TYPE: Record<PromptResponseType, { label: string }> = {
  TEXT: { label: 'Text' },
  YES_NO: { label: 'Yes/No' },
  FILE: { label: 'File' },
}

export const ISSUE_RISK_TYPE: Record<IssueRiskType, { label: string; color: ChipColor }> = {
  ISSUE: { label: 'Issue', color: 'error' },
  RISK: { label: 'Risk', color: 'warning' },
}

export const ISSUE_RISK_STATUS: Record<IssueRiskStatus, { label: string; color: ChipColor }> = {
  OPEN: { label: 'Open', color: 'error' },
  IN_PROGRESS: { label: 'In progress', color: 'info' },
  CLOSED: { label: 'Closed', color: 'success' },
}

export const APPROVAL_TYPE: Record<ApprovalType, { label: string }> = {
  STAGE_GATE: { label: 'Stage gate' },
  QUALITY_GATE: { label: 'Quality gate' },
  CUSTOMER_SAMPLE_APPROVAL: { label: 'Customer sample approval' },
  CUSTOMER_MILESTONE_ACKNOWLEDGMENT: { label: 'Customer milestone acknowledgment' },
  CUSTOMER_QUALITY_ACCEPTANCE: { label: 'Customer quality acceptance' },
}

export const APPROVAL_STATUS: Record<ApprovalStatus, { label: string; color: ChipColor }> = {
  PENDING: { label: 'Pending', color: 'warning' },
  APPROVED: { label: 'Approved', color: 'success' },
  REJECTED: { label: 'Rejected', color: 'error' },
}

export const PLAN_STATUS: Record<PlanStatus, { label: string; color: ChipColor }> = {
  AWAITING_PLAN: { label: 'Awaiting plan', color: 'warning' },
  ASSIGNED_TO_SUPPLIER: { label: 'Assigned to supplier', color: 'info' },
  SUBMITTED: { label: 'Submitted, awaiting ERGO', color: 'warning' },
  CHANGES_REQUESTED: { label: 'Sent back to supplier', color: 'error' },
  AGREED: { label: 'Agreed', color: 'success' },
}

export const PLAN_EVENT: Record<PlanEvent, string> = {
  RELEASED: 'Plan assigned to supplier',
  SUBMITTED: 'Plan submitted by supplier',
  SENT_BACK: 'Plan sent back to supplier',
  AGREED: 'Plan agreed by ERGO',
}

export const VALIDATION_STATUS: Record<ValidationStatus, { label: string; color: ChipColor }> = {
  NOT_STARTED: { label: 'Not started', color: 'default' },
  IN_VALIDATION: { label: 'In quality validation', color: 'warning' },
  VALIDATED: { label: 'Quality validated', color: 'info' },
  IN_CUSTOMER_REVIEW: { label: 'In customer review', color: 'warning' },
  CUSTOMER_APPROVED: { label: 'Customer approved', color: 'success' },
}

export const VALIDATION_PARTY: Record<ValidationParty, string> = {
  SUPPLIER_INTERNAL: 'Supplier Quality (internal)',
  SUPPLIER_QUALITY: 'Supplier Quality',
  ERGO_QUALITY: 'ERGO Quality',
  CUSTOMER_NPD: 'Customer NPD',
  CUSTOMER_QUALITY: 'Customer Quality',
  CUSTOMER: 'Customer',
}

export const SAMPLE_PO_STATUS: Record<SamplePoStatus, { label: string; color: ChipColor }> = {
  SUBMITTED: { label: 'Submitted to KAM', color: 'info' },
  RETURNED: { label: 'Returned for correction', color: 'warning' },
  ACCEPTED: { label: 'Accepted', color: 'success' },
}
