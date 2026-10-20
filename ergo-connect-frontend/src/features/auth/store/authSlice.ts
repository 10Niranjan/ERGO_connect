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

/** Stored access level codes: NV no access, V view, A create/edit/submit, O final approval. */
export type AccessLevel = 'NV' | 'V' | 'A' | 'O'

export interface MenuItem {
  key: string
  label: string
  path: string
  /** Icon key; the sidebar maps it to a component. */
  icon: string
}

export interface MenuSection {
  title: string | null
  items: MenuItem[]
}

/** What the backend says the signed-in role may do and see. Never computed in the browser. */
export interface AccessView {
  /** Changes whenever ERGO Admin edits permissions; compared with the X-Permissions-Version response header. */
  version: number
  permissions: Record<string, AccessLevel>
  menu: MenuSection[]
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
    twoFactorEnabled: boolean
  }
  /** One role at a time; switching issues a new token. */
  activeRole: Role
  roles: RoleOption[]
  mustChangePassword: boolean
  /** Absent only on a stored demo session; the app shell then fetches it. */
  access?: AccessView
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
    accessUpdated(state, action: PayloadAction<AccessView>) {
      if (state.session) state.session.access = action.payload
    },
  },
})

export const { sessionStarted, sessionEnded, accessUpdated } = authSlice.actions
