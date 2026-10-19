import type { Role } from '../store/authSlice'

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

/** SRS §10.4: who works with RFQs. Suppliers never see RFQs as such, only the quotes ERGO asks them for. */
export const RFQ_VIEWERS: Role[] = [
  'ERGO_ADMIN',
  'ERGO_MANAGEMENT',
  'ERGO_RFQ',
  'ERGO_KAM',
  'ERGO_NPD',
  'CUSTOMER_RFQ',
  'CUSTOMER_KAM',
  'CUSTOMER_MANAGEMENT',
]

export const RFQ_CREATORS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', 'CUSTOMER_RFQ', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT']

/** SRS §10.5: supplier roles that quote. */
export const SUPPLIER_QUOTERS: Role[] = ['SUPPLIER_RFQ', 'SUPPLIER_KAM']

/** SRS §10.5: who opens a supplier quote (ERGO's RFQ scope still applies). */
export const QUOTE_VIEWERS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', 'ERGO_NPD', ...SUPPLIER_QUOTERS]

/** SRS §10.12 */
export const MASTER_DATA_VIEWERS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT']

/** SRS §10.6: the ERGO project list, conversion queue and full detail view. */
export const NPD_VIEWERS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', 'ERGO_NPD']

export const NPD_EDITORS: Role[] = ['ERGO_ADMIN', 'ERGO_NPD']

/** SRS §12.15's customer "NPD" nav entry — reaches only /npd/mine and /npd/:id, never the ERGO project list. */
export const NPD_CUSTOMER_ROLES: Role[] = ['CUSTOMER_NPD', 'CUSTOMER_QUALITY']

/** SRS §12.15's supplier "NPD Tasks" nav entry — same reach as above. */
export const NPD_SUPPLIER_ROLES: Role[] = ['SUPPLIER_NPD', 'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM']

/** /npd/:id only: NPD_VIEWERS (the ERGO roles) plus every customer/supplier role with any NPD access at all. */
export const NPD_DETAIL_VIEWERS: Role[] = [...NPD_VIEWERS, ...NPD_CUSTOMER_ROLES, ...NPD_SUPPLIER_ROLES]

/** SRS §10.7: the ERGO roles OrderService.list()/get() actually give a real (non-empty) order scope to. */
export const OFP_VIEWERS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_OFP', 'ERGO_KAM', 'ERGO_NPD']

/** SRS §10.7 "E**": create/edit/workflow rights, same grant OrderController's EDITORS gives. */
export const OFP_EDITORS: Role[] = ['ERGO_ADMIN', 'ERGO_OFP']

/** SRS §10.7 "E**": the four supplier roles reaching their own assigned orders (production progress/packaging only;
 * OrderService itself confines the actual write rights further). */
export const OFP_SUPPLIER_ROLES: Role[] = ['SUPPLIER_NPD', 'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM']

/** SRS §10.7 "RV": Customer OFP's own company's orders, pure read-only. */
export const OFP_CUSTOMER_ROLES: Role[] = ['CUSTOMER_OFP']

/** /ofp/:id: everyone /ofp's own single list endpoint gives a real scope to. */
export const OFP_DETAIL_VIEWERS: Role[] = [...OFP_VIEWERS, ...OFP_SUPPLIER_ROLES, ...OFP_CUSTOMER_ROLES]

/** SRS §10.8 View: every role except the three *_RFQ roles (InspectionController's VIEWERS). */
export const QUALITY_VIEWERS: Role[] = [
  'ERGO_ADMIN',
  'ERGO_MANAGEMENT',
  'ERGO_KAM',
  'ERGO_NPD',
  'ERGO_OFP',
  'ERGO_QUALITY',
  'CUSTOMER_NPD',
  'CUSTOMER_OFP',
  'CUSTOMER_QUALITY',
  'CUSTOMER_KAM',
  'CUSTOMER_MANAGEMENT',
  'SUPPLIER_NPD',
  'SUPPLIER_OFP',
  'SUPPLIER_QUALITY',
  'SUPPLIER_KAM',
]

/** SRS §10.8 Create/Edit/Submit: ERGO Admin/Quality and Supplier Quality only (InspectionController's EDITORS).
 * Convenience only, for showing the "New inspection" button on the list page (there is no allowed-actions field
 * before a record exists) - the backend enforces the same set on every write. */
export const QUALITY_EDITORS: Role[] = ['ERGO_ADMIN', 'ERGO_QUALITY', 'SUPPLIER_QUALITY']

/** SRS §10.8 Deviations Create/Edit/Submit: ERGO Admin/Quality, Customer Quality and Supplier Quality
 * (DeviationController's EDITORS) - unlike QUALITY_EDITORS above, Customer Quality may also raise a deviation. */
export const DEVIATION_EDITORS: Role[] = ['ERGO_ADMIN', 'ERGO_QUALITY', 'CUSTOMER_QUALITY', 'SUPPLIER_QUALITY']

/** SRS §10.10: every role has view, create and edit rights on communication; the backend scopes which threads. */
export const COMMUNICATION_VIEWERS: Role[] = [
  'ERGO_ADMIN',
  'ERGO_MANAGEMENT',
  'ERGO_KAM',
  'ERGO_RFQ',
  'ERGO_NPD',
  'ERGO_OFP',
  'ERGO_QUALITY',
  ...EXTERNAL_ROLES,
]

/** SRS §10.9: every role may open the Document Library; the backend scopes which documents (record scope + visibility). */
export const DOCUMENT_VIEWERS: Role[] = [
  'ERGO_ADMIN',
  'ERGO_MANAGEMENT',
  'ERGO_KAM',
  'ERGO_RFQ',
  'ERGO_NPD',
  'ERGO_OFP',
  'ERGO_QUALITY',
  ...EXTERNAL_ROLES,
]
