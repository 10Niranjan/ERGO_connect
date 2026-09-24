import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

export type CompanyType = 'ERGO' | 'CUSTOMER' | 'SUPPLIER'

/** Role codes, mirroring Role.java on the backend. */
export type Role =
  | 'ERGO_ADMIN'
  | 'ERGO_MANAGEMENT'
  | 'ERGO_KAM'
  | 'ERGO_RFQ'
  | 'ERGO_NPD'
  | 'ERGO_OFP'
  | 'ERGO_QUALITY'
  | 'CUSTOMER_RFQ'
  | 'CUSTOMER_NPD'
  | 'CUSTOMER_OFP'
  | 'CUSTOMER_QUALITY'
  | 'CUSTOMER_KAM'
  | 'CUSTOMER_MANAGEMENT'
  | 'SUPPLIER_RFQ'
  | 'SUPPLIER_NPD'
  | 'SUPPLIER_OFP'
  | 'SUPPLIER_QUALITY'
  | 'SUPPLIER_KAM'

export interface RoleOption {
  code: Role
  name: string
  companyType: CompanyType
}

export interface Session {
  /** Kept in memory only. The long-lived session is an HttpOnly cookie that scripts cannot read. */
  accessToken: string
  expiresIn: number
  user: {
    id: number
    firstName: string
    lastName: string
    email: string
    companyId: number
    companyName: string
    companyType: CompanyType
  }
  /** One role at a time; switching issues a new token. */
  activeRole: Role
  roles: RoleOption[]
  mustChangePassword: boolean
}

export interface AuthState {
  session: Session | null
  /** False until the first session check after a page load has finished. */
  checked: boolean
}

const initialState: AuthState = { session: null, checked: false }

export const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    sessionStarted(state, action: PayloadAction<Session>) {
      state.session = action.payload
      state.checked = true
    },
    sessionEnded(state) {
      state.session = null
      state.checked = true
    },
  },
})

export const { sessionStarted, sessionEnded } = authSlice.actions
