import type { SourcingType } from './rfqApi'

/** A process flow row as typed in the form (numbers still text). */
export interface StepRow {
  processId: string
  stepName: string
  cycleTimeSeconds: string
  sourcingType: '' | SourcingType
  outsourcedSupplierName: string
  remarks: string
}

export const EMPTY_STEP: StepRow = {
  processId: '',
  stepName: '',
  cycleTimeSeconds: '',
  sourcingType: '',
  outsourcedSupplierName: '',
  remarks: '',
}
