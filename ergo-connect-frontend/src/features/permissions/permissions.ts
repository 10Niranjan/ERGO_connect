import { useAppSelector } from '@/hooks/useAppSelector'
import type { AccessLevel, AccessView, CompanyType } from '@/features/auth/store/authSlice'

/** What a screen or button needs: see it, create/edit/submit in it, or give its final approval. */
export type Need = 'view' | 'act' | 'approve'

/** One requirement on a module, optionally only for some company sides. A screen allows a user who meets ANY rule. */
export interface Rule {
  module: string
  need: Need
  sides?: CompanyType[]
}

const LEVEL_RANK: Record<AccessLevel, number> = { NV: 0, V: 1, A: 2, O: 3 }
const NEED_RANK: Record<Need, number> = { view: 1, act: 2, approve: 3 }

/**
 * Convenience only (hides screens and buttons): the backend decides the permissions sent here and enforces every
 * rule itself on every request.
 */
export function allows(access: AccessView | undefined, side: CompanyType | undefined, rules: Rule[]): boolean {
  if (!access || !side) return false
  return rules.some(
    (rule) =>
      (!rule.sides || rule.sides.includes(side)) &&
      LEVEL_RANK[access.permissions[rule.module] ?? 'NV'] >= NEED_RANK[rule.need],
  )
}

/** Whether the signed-in role meets any of the rules, per the permissions the server last sent. */
export function useCan(rules: Rule[]): boolean {
  const session = useAppSelector((state) => state.auth.session)
  return allows(session?.access, session?.user.companyType, rules)
}

const ERGO_CUSTOMER: CompanyType[] = ['ERGO', 'CUSTOMER']
const ERGO: CompanyType[] = ['ERGO']
const CUSTOMER: CompanyType[] = ['CUSTOMER']
const SUPPLIER: CompanyType[] = ['SUPPLIER']
const EXTERNAL: CompanyType[] = ['CUSTOMER', 'SUPPLIER']

// The named requirements the router, sidebar and pages use. They name modules and needs, never roles: who holds which
// level is configured in Permission Management.
export const RFQ_VIEW: Rule[] = [{ module: 'RFQ', need: 'view', sides: ERGO_CUSTOMER }]
export const RFQ_CREATE: Rule[] = [{ module: 'RFQ', need: 'act', sides: ERGO_CUSTOMER }]
/** Suppliers reach RFQs only as the quotes ERGO asks them for (Assigned RFQs). */
export const ASSIGNED_RFQ_VIEW: Rule[] = [{ module: 'RFQ', need: 'view', sides: SUPPLIER }]
/** Who opens a supplier quote page (ERGO's RFQ scope still applies). */
export const QUOTE_VIEW: Rule[] = [{ module: 'QUOTATION', need: 'view', sides: ERGO }, ...ASSIGNED_RFQ_VIEW]
export const QUOTATION_VIEW: Rule[] = [{ module: 'QUOTATION', need: 'view' }]
/** Customer roles that see and answer the quotes ERGO sends. */
export const QUOTATION_CUSTOMER_VIEW: Rule[] = [{ module: 'QUOTATION', need: 'view', sides: CUSTOMER }]
export const NPD_ERGO_VIEW: Rule[] = [{ module: 'NPD', need: 'view', sides: ERGO }]
export const NPD_MINE_VIEW: Rule[] = [{ module: 'NPD', need: 'view', sides: EXTERNAL }]
export const NPD_VIEW: Rule[] = [{ module: 'NPD', need: 'view' }]
export const OFP_VIEW: Rule[] = [{ module: 'OFP', need: 'view' }]
/** ERGO's owning roles: create orders, run the workflow, hand an NPD project over. */
export const OFP_CREATE: Rule[] = [{ module: 'OFP', need: 'approve', sides: ERGO }]
export const QUALITY_VIEW: Rule[] = [{ module: 'QUALITY', need: 'view' }]
/** Inspections and NCRs: ERGO and supplier roles that act (customers are view-only here). */
export const QUALITY_CREATE: Rule[] = [{ module: 'QUALITY', need: 'act', sides: ['ERGO', 'SUPPLIER'] }]
/** Deviations: every acting role may raise one, customers included. */
export const DEVIATION_CREATE: Rule[] = [{ module: 'QUALITY', need: 'act' }]
export const DOCUMENTS_VIEW: Rule[] = [{ module: 'DOCUMENTS', need: 'view' }]
export const COMMUNICATION_VIEW: Rule[] = [{ module: 'COMMUNICATION', need: 'view' }]
export const COMPANIES_VIEW: Rule[] = [{ module: 'COMPANIES', need: 'view' }]
export const SUPPLIERS_VIEW: Rule[] = [{ module: 'SUPPLIERS', need: 'view' }]
export const COMPANY_PROFILE_VIEW: Rule[] = [{ module: 'COMPANY_PROFILE', need: 'view' }]
export const MASTER_DATA_VIEW: Rule[] = [{ module: 'MASTER_DATA', need: 'view' }]
export const USERS_VIEW: Rule[] = [{ module: 'USERS', need: 'view' }]
export const PERMISSIONS_VIEW: Rule[] = [{ module: 'PERMISSIONS', need: 'view' }]
