import type { Role } from '../store/authSlice'

// What each role may open or do is no longer decided here: the backend sends the signed-in role's permissions and
// sidebar (Permission Management edits them) and @/features/permissions reads them. Only the fixed system areas
// whose backend checks are still role lists in code keep a role list on this side.

/** SRS §10.2/§10.3: ERGO roles that add a customer or supplier (CompanyController's CREATORS). */
export const COMPANY_CREATORS: Role[] = ['ERGO_ADMIN', 'ERGO_RFQ', 'ERGO_KAM']
