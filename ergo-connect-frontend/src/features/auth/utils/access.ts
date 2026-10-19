import type { Role } from '../store/authSlice'

// Which active roles see which screens: the UI side of the backend's role sheet (ModuleAccess.java, "role access.xlsx",
// client decision 2026-10-19). Convenience only; the backend enforces every rule itself, so keep this table identical
// to ModuleAccess.java.

export type ModuleKey = 'RFQ' | 'QUOTATION' | 'NPD' | 'OFP' | 'QUALITY'

/** `-` no access, `V` view only, `A` acts (create / edit / submit), `O` owns and gives final approval, `CO` the customer's final approval. */
type Level = '-' | 'V' | 'A' | 'O' | 'CO'

type Side = 'ERGO' | 'CUSTOMER' | 'SUPPLIER'

// Columns: RFQ, QUOTATION, NPD, OFP, QUALITY.
const SHEET: Record<Role, [Level, Level, Level, Level, Level]> = {
  ERGO_ADMIN: ['V', 'V', 'V', 'V', 'V'],
  ERGO_MANAGEMENT: ['O', 'O', 'O', 'O', 'O'],
  ERGO_KAM: ['A', 'V', 'V', 'V', 'V'],
  ERGO_RFQ: ['O', 'O', 'V', 'V', 'V'],
  ERGO_NPD: ['V', 'V', 'O', 'V', 'V'],
  ERGO_OFP: ['-', '-', 'V', 'O', 'A'],
  ERGO_QUALITY: ['-', '-', 'A', 'A', 'O'],
  CUSTOMER_RFQ: ['CO', 'CO', 'V', '-', 'V'],
  CUSTOMER_NPD: ['V', '-', 'CO', '-', 'CO'],
  CUSTOMER_OFP: ['-', '-', '-', 'CO', 'V'],
  CUSTOMER_QUALITY: ['V', '-', 'A', 'V', 'CO'],
  CUSTOMER_KAM: ['A', 'A', 'V', 'V', 'V'],
  CUSTOMER_MANAGEMENT: ['A', 'A', 'A', 'A', 'A'],
  SUPPLIER_RFQ: ['A', 'A', '-', '-', '-'],
  SUPPLIER_NPD: ['-', '-', 'A', '-', 'V'],
  SUPPLIER_OFP: ['-', '-', '-', 'A', 'V'],
  SUPPLIER_QUALITY: ['V', '-', 'A', 'A', 'A'],
  SUPPLIER_KAM: ['A', 'A', 'A', 'A', 'A'],
}

const COLUMN: Record<ModuleKey, number> = { RFQ: 0, QUOTATION: 1, NPD: 2, OFP: 3, QUALITY: 4 }

const ALL_ROLES = Object.keys(SHEET) as Role[]

export const levelOf = (role: Role, module: ModuleKey): Level => SHEET[role][COLUMN[module]]

/** Whether the role has any access to the module (the sheet's non-blank cell). */
export const canViewModule = (role: Role | undefined, module: ModuleKey): boolean => role !== undefined && canView(levelOf(role, module))

const sideOf = (role: Role): Side => (role.split('_')[0] as Side)

const canView = (level: Level) => level !== '-'
const canAct = (level: Level) => level === 'A' || level === 'O' || level === 'CO'
const canApprove = (level: Level) => level === 'O' || level === 'CO'

const rolesWhere = (module: ModuleKey, test: (level: Level) => boolean, sides?: Side[]): Role[] =>
  ALL_ROLES.filter((role) => test(levelOf(role, module)) && (!sides || sides.includes(sideOf(role))))

/** Roles with any access to the module (optionally one or more sides only). */
export const viewersOf = (module: ModuleKey, ...sides: Side[]): Role[] => rolesWhere(module, canView, sides.length ? sides : undefined)

/** Roles that create / edit / submit in the module ("A" and up). */
export const actorsOf = (module: ModuleKey, ...sides: Side[]): Role[] => rolesWhere(module, canAct, sides.length ? sides : undefined)

/** Roles that give the module's final approval ("O" / "CO"). */
export const approversOf = (module: ModuleKey, ...sides: Side[]): Role[] => rolesWhere(module, canApprove, sides.length ? sides : undefined)

export const EXTERNAL_ROLES: Role[] = ALL_ROLES.filter((role) => sideOf(role) !== 'ERGO')

/** SRS §10.2/§10.3: ERGO roles that work with the customer and supplier list. */
export const COMPANY_MANAGERS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM']

export const COMPANY_CREATORS: Role[] = ['ERGO_ADMIN', 'ERGO_RFQ', 'ERGO_KAM']

/** RFQ column: who works with RFQs. Suppliers never see RFQs as such, only the quotes ERGO asks them for. */
export const RFQ_VIEWERS: Role[] = viewersOf('RFQ', 'ERGO', 'CUSTOMER')

export const RFQ_CREATORS: Role[] = actorsOf('RFQ', 'ERGO', 'CUSTOMER')

/** Quotation column: supplier roles that quote. */
export const SUPPLIER_QUOTERS: Role[] = actorsOf('QUOTATION', 'SUPPLIER')

/** RFQ column, supplier side: the "Assigned RFQs" list (a role with RFQ view but no Quotation access sees the RFQ only). */
export const SUPPLIER_RFQ_VIEWERS: Role[] = viewersOf('RFQ', 'SUPPLIER')

/** Who opens a supplier quote page (ERGO's RFQ scope still applies). */
export const QUOTE_VIEWERS: Role[] = [...viewersOf('QUOTATION', 'ERGO'), ...SUPPLIER_RFQ_VIEWERS]

/** ERGO's Quotation viewers: the supplier-quote tab and comparison on an RFQ. */
export const QUOTATION_ERGO_VIEWERS: Role[] = viewersOf('QUOTATION', 'ERGO')

/** Customer roles that see and answer the quotes ERGO sends. */
export const QUOTATION_CUSTOMER_VIEWERS: Role[] = viewersOf('QUOTATION', 'CUSTOMER')

/** SRS §10.12 */
export const MASTER_DATA_VIEWERS: Role[] = ['ERGO_ADMIN', 'ERGO_MANAGEMENT']

/** NPD column: the ERGO project list, conversion queue and full detail view. */
export const NPD_VIEWERS: Role[] = viewersOf('NPD', 'ERGO')

export const NPD_EDITORS: Role[] = actorsOf('NPD', 'ERGO')

/** SRS §12.15's customer "NPD" nav entry — reaches only /npd/mine and /npd/:id, never the ERGO project list. */
export const NPD_CUSTOMER_ROLES: Role[] = viewersOf('NPD', 'CUSTOMER')

/** SRS §12.15's supplier "NPD Tasks" nav entry — same reach as above. */
export const NPD_SUPPLIER_ROLES: Role[] = viewersOf('NPD', 'SUPPLIER')

/** /npd/:id only: every role with any NPD access at all. */
export const NPD_DETAIL_VIEWERS: Role[] = viewersOf('NPD')

/** OFP column, ERGO side: the roles OrderService.list()/get() give a real order scope to. */
export const OFP_VIEWERS: Role[] = viewersOf('OFP', 'ERGO')

/** ERGO's owning roles ("O"): create orders, run the workflow and shipments, hand an NPD project over. */
export const OFP_EDITORS: Role[] = approversOf('OFP', 'ERGO')

/** The supplier roles reaching their own assigned orders (production progress/packaging only). */
export const OFP_SUPPLIER_ROLES: Role[] = viewersOf('OFP', 'SUPPLIER')

/** Customer roles reaching their own company's orders, read-only. */
export const OFP_CUSTOMER_ROLES: Role[] = viewersOf('OFP', 'CUSTOMER')

/** /ofp/:id: everyone with any OFP access. */
export const OFP_DETAIL_VIEWERS: Role[] = viewersOf('OFP')

/** Quality column: every role with any Quality access (the three screens' list/detail endpoints scope by caller). */
export const QUALITY_VIEWERS: Role[] = viewersOf('QUALITY')

/** Create/Edit/Submit of inspections and NCRs: ERGO and supplier roles that act (customers are view-only here).
 * Convenience only, for showing the "New ..." button on the list page (there is no allowed-actions field
 * before a record exists) - the backend enforces the same set on every write. */
export const QUALITY_EDITORS: Role[] = actorsOf('QUALITY', 'ERGO', 'SUPPLIER')

/** Deviations: every acting role may raise one, customers included (unlike inspections and NCRs). */
export const DEVIATION_EDITORS: Role[] = actorsOf('QUALITY')

/** SRS §10.10: every role has view, create and edit rights on communication; the backend scopes which threads. */
export const COMMUNICATION_VIEWERS: Role[] = ALL_ROLES

/** SRS §10.9: every role may open the Document Library; the backend scopes which documents (record scope + visibility). */
export const DOCUMENT_VIEWERS: Role[] = ALL_ROLES
