import type { CompanyType, Role, RoleOption, Session } from '../store/authSlice'

export interface DemoAccount {
  id: number
  firstName: string
  lastName: string
  email: string
  jobTitle: string
  companyId: number
  companyName: string
  companyType: CompanyType
  role: Role
  allRoles?: Role[]
  description: string
  landingPath: string
  avatarColor: string
}

/** The 18 official role titles (ERGO Connect Platform Overview & Demo Guide, section 3); same as roles.role_name in the database. */
export const ROLE_LABELS: Record<Role, string> = {
  ERGO_ADMIN: 'ERGO Admin',
  ERGO_MANAGEMENT: 'ERGO Management',
  ERGO_KAM: 'ERGO Key Account Manager',
  ERGO_RFQ: 'ERGO RFQ',
  ERGO_NPD: 'ERGO NPD',
  ERGO_OFP: 'ERGO OFP',
  ERGO_QUALITY: 'ERGO Quality',
  CUSTOMER_RFQ: 'Customer RFQ',
  CUSTOMER_NPD: 'Customer NPD',
  CUSTOMER_OFP: 'Customer OFP',
  CUSTOMER_QUALITY: 'Customer Quality',
  CUSTOMER_KAM: 'Customer Key Account Manager',
  CUSTOMER_MANAGEMENT: 'Customer Management',
  SUPPLIER_RFQ: 'Supplier RFQ',
  SUPPLIER_NPD: 'Supplier NPD',
  SUPPLIER_OFP: 'Supplier OFP',
  SUPPLIER_QUALITY: 'Supplier Quality',
  SUPPLIER_KAM: 'Supplier Key Account Manager',
}

/** Title for a role code coming from the API; unknown codes are shown as they are. */
export const roleTitle = (code: string): string => ROLE_LABELS[code as Role] ?? code

export const DEMO_ACCOUNTS: DemoAccount[] = [
  // ==========================================
  // 1. ERGO INTERNAL ROLES (7 Roles)
  // ==========================================
  {
    id: 1,
    firstName: 'Asha',
    lastName: 'Admin',
    email: 'admin@ergo.dev',
    jobTitle: 'Head of Systems & Compliance',
    companyId: 1,
    companyName: 'ERGO',
    companyType: 'ERGO',
    role: 'ERGO_ADMIN',
    allRoles: ['ERGO_ADMIN'],
    description: 'Full administrative access: User management, master data, permission matrix, system audit logs, and company profiles.',
    landingPath: '/',
    avatarColor: '#1976d2',
  },
  {
    id: 2,
    firstName: 'Mohan',
    lastName: 'Management',
    email: 'management@ergo.dev',
    jobTitle: 'Managing Director / Executive',
    companyId: 1,
    companyName: 'ERGO',
    companyType: 'ERGO',
    role: 'ERGO_MANAGEMENT',
    allRoles: ['ERGO_MANAGEMENT'],
    description: 'Executive KPI dashboard, cross-module visibility, milestone approvals, and supplier performance oversight.',
    landingPath: '/',
    avatarColor: '#0288d1',
  },
  {
    id: 5,
    firstName: 'Kavya',
    lastName: 'Menon',
    email: 'kam@ergo.dev',
    jobTitle: 'Key Account Manager',
    companyId: 1,
    companyName: 'ERGO',
    companyType: 'ERGO',
    role: 'ERGO_KAM',
    allRoles: ['ERGO_KAM'],
    description: 'Customer coordination for assigned accounts (Acme, Bharat), quotations review, and customer relationship monitoring.',
    landingPath: '/',
    avatarColor: '#00796b',
  },
  {
    id: 3,
    firstName: 'Ravi',
    lastName: 'Rao',
    email: 'rfq@ergo.dev',
    jobTitle: 'Senior RFQ Engineer',
    companyId: 1,
    companyName: 'ERGO',
    companyType: 'ERGO',
    role: 'ERGO_RFQ',
    allRoles: ['ERGO_RFQ', 'ERGO_NPD'],
    description: 'Customer RFQ review, validation, BOM process flow, supplier quotation float, price comparison, and margin adjustment.',
    landingPath: '/rfqs',
    avatarColor: '#2e7d32',
  },
  {
    id: 11,
    firstName: 'Kavya',
    lastName: 'Reddy',
    email: 'npd@ergo.dev',
    jobTitle: 'NPD Programme Manager',
    companyId: 1,
    companyName: 'ERGO',
    companyType: 'ERGO',
    role: 'ERGO_NPD',
    allRoles: ['ERGO_NPD', 'ERGO_OFP'],
    description: 'RFQ-to-NPD conversion queue, 9-stage development lifecycle, activity checklist gating, and sample readiness.',
    landingPath: '/npd',
    avatarColor: '#ed6c02',
  },
  {
    id: 10,
    firstName: 'Vikram',
    lastName: 'Singh',
    email: 'ofp@ergo.dev',
    jobTitle: 'Production & OFP Lead',
    companyId: 1,
    companyName: 'ERGO',
    companyType: 'ERGO',
    role: 'ERGO_OFP',
    allRoles: ['ERGO_OFP'],
    description: 'Purchase orders, production milestone tracking, live dispatch readiness, and delivery compliance.',
    landingPath: '/ofp',
    avatarColor: '#d32f2f',
  },
  {
    id: 4,
    firstName: 'Kiran',
    lastName: 'Kumar',
    email: 'quality@ergo.dev',
    jobTitle: 'Quality Assurance Manager',
    companyId: 1,
    companyName: 'ERGO',
    companyType: 'ERGO',
    role: 'ERGO_QUALITY',
    allRoles: ['ERGO_QUALITY'],
    description: 'Incoming and final inspections, Non-Conformance Reports (NCR), concessions/deviations workflow, and CAPA.',
    landingPath: '/quality',
    avatarColor: '#7b1fa2',
  },

  // ==========================================
  // 2. CUSTOMER ROLES (6 Roles)
  // ==========================================
  {
    id: 6,
    firstName: 'Alice',
    lastName: 'Iyer',
    email: 'buyer@acme.dev',
    jobTitle: 'Strategic Sourcing Lead',
    companyId: 2,
    companyName: 'Acme Automotive',
    companyType: 'CUSTOMER',
    role: 'CUSTOMER_RFQ',
    allRoles: ['CUSTOMER_RFQ', 'CUSTOMER_QUALITY'],
    description: 'Create & submit new part RFQs, review ERGO-selected quotations, negotiate terms, and approve quotes.',
    landingPath: '/rfqs',
    avatarColor: '#00838f',
  },
  {
    id: 12,
    firstName: 'Rohit',
    lastName: 'Desai',
    email: 'npd@acme.dev',
    jobTitle: 'NPD Project Coordinator',
    companyId: 2,
    companyName: 'Acme Automotive',
    companyType: 'CUSTOMER',
    role: 'CUSTOMER_NPD',
    allRoles: ['CUSTOMER_NPD'],
    description: 'Review development milestones, track tooling & sample submission dates, and approve customer sample gates.',
    landingPath: '/npd/mine',
    avatarColor: '#00695c',
  },
  {
    id: 13,
    firstName: 'Neha',
    lastName: 'Kulkarni',
    email: 'logistics@acme.dev',
    jobTitle: 'Supply Chain & Orders Planner',
    companyId: 2,
    companyName: 'Acme Automotive',
    companyType: 'CUSTOMER',
    role: 'CUSTOMER_OFP',
    allRoles: ['CUSTOMER_OFP'],
    description: 'Monitor live order delivery progress, milestone schedules, shipment tracking, and delivery notes.',
    landingPath: '/ofp',
    avatarColor: '#ef6c00',
  },
  {
    id: 14,
    firstName: 'Amit',
    lastName: 'Verma',
    email: 'quality@acme.dev',
    jobTitle: 'Supplier Quality Assurance Lead',
    companyId: 2,
    companyName: 'Acme Automotive',
    companyType: 'CUSTOMER',
    role: 'CUSTOMER_QUALITY',
    allRoles: ['CUSTOMER_QUALITY'],
    description: 'Review inspection certificates, raise quality complaints, and review/approve deviation requests.',
    landingPath: '/quality',
    avatarColor: '#c2185b',
  },
  {
    id: 15,
    firstName: 'Sunita',
    lastName: 'Deshpande',
    email: 'account@acme.dev',
    jobTitle: 'Strategic Account Manager',
    companyId: 2,
    companyName: 'Acme Automotive',
    companyType: 'CUSTOMER',
    role: 'CUSTOMER_KAM',
    allRoles: ['CUSTOMER_KAM', 'CUSTOMER_RFQ'],
    description: 'Account-wide oversight across Acme Automotive: RFQ float, active production orders, and commercial terms.',
    landingPath: '/',
    avatarColor: '#303f9f',
  },
  {
    id: 20,
    firstName: 'Raman',
    lastName: 'Athreya',
    email: 'management@acme.dev',
    jobTitle: 'Plant Head / Vice President',
    companyId: 2,
    companyName: 'Acme Automotive',
    companyType: 'CUSTOMER',
    role: 'CUSTOMER_MANAGEMENT',
    allRoles: ['CUSTOMER_MANAGEMENT'],
    description: 'Executive oversight of customer-side order volume, spend, project statuses, and supplier performance.',
    landingPath: '/',
    avatarColor: '#455a64',
  },

  // ==========================================
  // 3. SUPPLIER ROLES (5 Roles)
  // ==========================================
  {
    id: 8,
    firstName: 'Prakash',
    lastName: 'Shah',
    email: 'sales@precision.dev',
    jobTitle: 'Commercial & Sales Manager',
    companyId: 4,
    companyName: 'Precision Castings',
    companyType: 'SUPPLIER',
    role: 'SUPPLIER_RFQ',
    allRoles: ['SUPPLIER_RFQ'],
    description: 'Review invited RFQs, submit cost breakdown and tooling pricing, ask technical clarifications, and submit quotes.',
    landingPath: '/quotes',
    avatarColor: '#4e342e',
  },
  {
    id: 16,
    firstName: 'Harish',
    lastName: 'Gupta',
    email: 'tooling@precision.dev',
    jobTitle: 'Tooling & NPD Lead',
    companyId: 4,
    companyName: 'Precision Castings',
    companyType: 'SUPPLIER',
    role: 'SUPPLIER_NPD',
    allRoles: ['SUPPLIER_NPD'],
    description: 'Update tooling progress, sample manufacturing status, DFM attachments, and trial reports.',
    landingPath: '/npd/mine',
    avatarColor: '#e65100',
  },
  {
    id: 17,
    firstName: 'Prakash',
    lastName: 'Jadhav',
    email: 'planner@precision.dev',
    jobTitle: 'Production & Dispatch Planner',
    companyId: 4,
    companyName: 'Precision Castings',
    companyType: 'SUPPLIER',
    role: 'SUPPLIER_OFP',
    allRoles: ['SUPPLIER_OFP'],
    description: 'Update order progress, committed dispatch dates, production batch quantities, and shipment documents.',
    landingPath: '/ofp',
    avatarColor: '#f57f17',
  },
  {
    id: 18,
    firstName: 'Lakshmi',
    lastName: 'Menon',
    email: 'qa@precision.dev',
    jobTitle: 'Quality Assurance Lead',
    companyId: 4,
    companyName: 'Precision Castings',
    companyType: 'SUPPLIER',
    role: 'SUPPLIER_QUALITY',
    allRoles: ['SUPPLIER_QUALITY'],
    description: 'Submit self-inspection results, respond to customer NCRs with 8D/CAPA, and submit concession requests.',
    landingPath: '/quality',
    avatarColor: '#6a1b9a',
  },
  {
    id: 19,
    firstName: 'Farhan',
    lastName: 'Sheikh',
    email: 'lead@precision.dev',
    jobTitle: 'Key Account Partner',
    companyId: 4,
    companyName: 'Precision Castings',
    companyType: 'SUPPLIER',
    role: 'SUPPLIER_KAM',
    allRoles: ['SUPPLIER_KAM', 'SUPPLIER_RFQ'],
    description: 'Commercial and relationship lead on the supplier side: Quote reviews, active project delivery, and capacity planning.',
    landingPath: '/',
    avatarColor: '#37474f',
  },
]

export const DEMO_STORAGE_KEY = 'ergo_connect_demo_session'
export const DEV_JWT_SECRET = 'dev-only-jwt-secret-never-use-outside-local-dev'

function base64UrlEncode(str: string): string {
  const bytes = new TextEncoder().encode(str)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

function bufferToBase64Url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

/**
 * Creates a valid JWT signed with the backend's local HMAC-SHA256 secret.
 * This guarantees the Spring Boot backend accepts the token with full authorization (200 OK)
 * and no 401s or OTP prompts.
 */
export async function createSignedDemoJwt(account: DemoAccount): Promise<string> {
  const header = { alg: 'HS256', typ: 'JWT' }
  const now = Math.floor(Date.now() / 1000)
  const payload = {
    iss: 'ergo-connect',
    sub: String(account.id),
    cid: account.companyId,
    ctype: account.companyType,
    role: account.role,
    sid: 1,
    pwc: false,
    iat: now,
    exp: now + 86400 * 7, // 7 days
  }

  const encodedHeader = base64UrlEncode(JSON.stringify(header))
  const encodedPayload = base64UrlEncode(JSON.stringify(payload))
  const unsignedToken = `${encodedHeader}.${encodedPayload}`

  try {
    const encoder = new TextEncoder()
    const cryptoObj = typeof window !== 'undefined' ? window.crypto : globalThis.crypto
    if (cryptoObj?.subtle) {
      const key = await cryptoObj.subtle.importKey(
        'raw',
        encoder.encode(DEV_JWT_SECRET),
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign']
      )
      const sigBuffer = await cryptoObj.subtle.sign(
        'HMAC',
        key,
        encoder.encode(unsignedToken)
      )
      return `${unsignedToken}.${bufferToBase64Url(sigBuffer)}`
    }
  } catch (err) {
    console.error('Failed to sign demo JWT:', err)
  }

  return unsignedToken
}

export function buildDemoSession(account: DemoAccount, token?: string): Session {
  const roleCodes: Role[] = account.allRoles && account.allRoles.length > 0 ? account.allRoles : [account.role]
  const roles: RoleOption[] = roleCodes.map((r) => ({
    code: r,
    name: ROLE_LABELS[r] ?? r,
    companyType: account.companyType,
  }))

  return {
    accessToken: token ?? `demo-token-${account.role.toLowerCase()}-${Date.now()}`,
    expiresIn: 604800, // 7 days
    user: {
      id: account.id,
      firstName: account.firstName,
      lastName: account.lastName,
      email: account.email,
      companyId: account.companyId,
      companyName: account.companyName,
      companyType: account.companyType,
      twoFactorEnabled: false,
    },
    activeRole: account.role,
    roles,
    mustChangePassword: false,
  }
}
