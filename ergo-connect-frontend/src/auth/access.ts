import type { Role } from './authSlice'

// Which active roles see which screens: the UI side of the backend's @PreAuthorize rules (SRS §10).
// Convenience only; the backend enforces every rule itself.

export const EXTERNAL_ROLES: Role[] = [
  'CUSTOMER_RFQ',
  'CUSTOMER_NPD',
  'CUSTOMER_OFP',
  'CUSTOMER_QUALITY',
  'CUSTOMER_KAM',
  'CUSTOMER_MANAGEMENT',
  'SUPPLIER_RFQ',
  'SUPPLIER_NPD',
  'SUPPLIER_OFP',
  'SUPPLIER_QUALITY',
  'SUPPLIER_KAM',
]

/** SRS §10.2/§10.3: ERGO roles that work with the customer and supplier list. */
export const COMPANY_MANAGERS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM']

export const COMPANY_CREATORS: Role[] = ['ERGO_ADMIN', 'ERGO_RFQ', 'ERGO_KAM']

/** SRS §10.12 */
export const MASTER_DATA_VIEWERS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT']
