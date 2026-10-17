import { num, str, text } from '@/utils/form'
import type { ProcessStep, SourcingType } from '../services/rfqApi'

/** A process flow row as typed in the form (numbers still text). */
export interface StepRow {
  /** Stable React key: rows are added and removed mid-list, so an index would hand one row's inputs to another. */
  key: string
  processId: string
  stepName: string
  cycleTimeSeconds: string
  sourcingType: '' | SourcingType
  outsourcedSupplierName: string
  remarks: string
}

export const newStep = (): StepRow => ({
  key: crypto.randomUUID(),
  processId: '',
  stepName: '',
  cycleTimeSeconds: '',
  sourcingType: '',
  outsourcedSupplierName: '',
  remarks: '',
})

export function toStepRows(steps: ProcessStep[]): StepRow[] {
  return steps.map((s) => ({
    key: crypto.randomUUID(),
    processId: text(s.processId),
    stepName: s.stepName,
    cycleTimeSeconds: text(s.cycleTimeSeconds),
    sourcingType: s.sourcingType ?? '',
    outsourcedSupplierName: text(s.outsourcedSupplierName),
    remarks: text(s.remarks),
  }))
}

/** Empty rows are ignored. */
export function toProcessSteps(rows: StepRow[]): ProcessStep[] {
  return rows
    .filter((s) => s.stepName.trim() || s.processId)
    .map((s) => ({
      processId: num(s.processId),
      stepName: s.stepName.trim(),
      cycleTimeSeconds: num(s.cycleTimeSeconds),
      sourcingType: s.sourcingType || null,
      outsourcedSupplierName: str(s.outsourcedSupplierName),
      remarks: str(s.remarks),
    }))
}
