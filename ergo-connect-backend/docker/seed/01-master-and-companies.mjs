// Phase 1: master data, exchange rates, full company profiles, extra companies and users, phone numbers.
import { PERSONA, attempt, call, get, log, monthsAgo } from './lib.mjs'

const MATERIALS = [
  ['EN19', 'EN19 alloy steel (42CrMo4)', 'BS 970 709M40', 'EN19'],
  ['C45', 'C45 medium carbon steel', 'IS 1570 Part 2', 'C45'],
  ['20MNCR5', '20MnCr5 case-hardening steel', 'DIN EN 10084', '20MnCr5'],
  ['SAE8620', 'SAE 8620 case-hardening steel', 'SAE J404', '8620'],
  ['SAE4140', 'SAE 4140 chromoly steel', 'ASTM A29', '4140'],
  ['EN24', 'EN24 nickel-chrome-moly steel', 'BS 970 817M40', 'EN24'],
  ['AISI316', 'Stainless steel 316L', 'ASTM A240', '316L'],
  ['ADC12', 'Aluminium die-casting alloy ADC12', 'JIS H5302', 'ADC12'],
  ['SG500', 'Ductile iron SG 500/7', 'IS 1865', 'SG 500/7'],
  ['FG260', 'Grey cast iron FG 260', 'IS 210', 'FG 260'],
]
const PROCESSES = [
  ['CNC-MILL', 'CNC milling', 'INHOUSE'], ['VMC-DRILL', 'VMC drilling and tapping', 'INHOUSE'], ['GRIND', 'Cylindrical grinding', 'INHOUSE'],
  ['SHOT-BLAST', 'Shot blasting', 'INHOUSE'], ['MAGNAFLUX', 'Magnetic particle inspection (NDT)', 'OUTSOURCED'], ['DEBURR', 'Deburring and cleaning', 'INHOUSE'],
  ['WELD-FAB', 'Weld fabrication', 'MIXED'], ['DIE-CAST', 'Pressure die casting', 'INHOUSE'], ['SAND-CAST', 'Sand casting', 'INHOUSE'],
  ['FETTLING', 'Fettling and finishing', 'INHOUSE'], ['BROACH', 'Broaching', 'INHOUSE'], ['PHOSPHATE', 'Phosphating', 'OUTSOURCED'],
]
const COATINGS = [
  ['ZN-NI', 'Zinc-nickel plating', 'ISO 19598, 720 h salt spray'], ['E-COAT', 'Cathodic electrocoat', '20-25 micron'], ['PHOS-OIL', 'Phosphate and oil', 'IS 3618'],
  ['BLACK-OX', 'Black oxide', 'MIL-DTL-13924'], ['HDG', 'Hot-dip galvanising', 'IS 4759'], ['CHROME', 'Hard chrome plating', '25 micron'], ['ANODIZE', 'Anodising type II', 'MIL-A-8625'],
]
const PAYMENT_TERMS = [
  ['NET45', 'Net 45 days', 'Payment 45 days from invoice'], ['NET60', 'Net 60 days', 'Payment 60 days from invoice'],
  ['ADV30', '30% advance, 70% on delivery', '30% with order, balance within 7 days of delivery'], ['LC-SIGHT', 'Letter of credit at sight', 'Irrevocable LC payable at sight'],
]
const SHIPMENT_MODES = [['ROAD-FTL', 'Road - full truck load'], ['ROAD-LTL', 'Road - part load'], ['AIR', 'Air freight'], ['SEA-FCL', 'Sea - full container'], ['SEA-LCL', 'Sea - consolidated'], ['RAIL', 'Rail'], ['COURIER', 'Express courier']]

const PROFILES = {
  1: { name: 'ERGO', addressLine1: 'Plot 24, Hinjewadi Phase 2', addressLine2: 'Rajiv Gandhi Infotech Park', city: 'Pune', state: 'Maharashtra', country: 'India', postalCode: '411057', taxId: '27AAECE1234F1Z5', website: 'https://www.ergo-connect.example', primaryContactName: 'Asha Admin', primaryContactEmail: 'admin@ergo.dev', primaryContactPhone: '+91 20 4100 2000' },
  2: { name: 'Acme Automotive', addressLine1: 'Gat No. 412, Chakan MIDC', addressLine2: 'Phase 3', city: 'Pune', state: 'Maharashtra', country: 'India', postalCode: '410501', taxId: '27AABCA4455K1ZV', website: 'https://www.acme-auto.example', primaryContactName: 'Alice Iyer', primaryContactEmail: 'buyer@acme.dev', primaryContactPhone: '+91 21 3567 8800' },
  3: { name: 'Bharat Motors', addressLine1: '17, Industrial Area Sector 4', addressLine2: 'Bawal', city: 'Rewari', state: 'Haryana', country: 'India', postalCode: '123501', taxId: '06AAACB7788M1Z2', website: 'https://www.bharat-motors.example', primaryContactName: 'Bala Nair', primaryContactEmail: 'buyer@bharat.dev', primaryContactPhone: '+91 1284 266 100' },
  4: { name: 'Precision Castings', addressLine1: 'Survey 88, GIDC Estate', addressLine2: 'Metoda', city: 'Rajkot', state: 'Gujarat', country: 'India', postalCode: '360021', taxId: '24AAFCP3321R1ZK', website: 'https://www.precision-castings.example', primaryContactName: 'Prakash Shah', primaryContactEmail: 'sales@precision.dev', primaryContactPhone: '+91 281 278 4400' },
  5: { name: 'Shakti Forgings', addressLine1: 'B-52, Focal Point Phase 8', addressLine2: 'Industrial Area', city: 'Ludhiana', state: 'Punjab', country: 'India', postalCode: '141010', taxId: '03AAHCS9012P1ZQ', website: 'https://www.shakti-forgings.example', primaryContactName: 'Sunil Patel', primaryContactEmail: 'sales@shakti.dev', primaryContactPhone: '+91 161 267 5500' },
}
const NEW_COMPANIES = [
  { code: 'SAHYADRI-MOTORS', type: 'CUSTOMER', approve: true, profile: { name: 'Sahyadri Motors', addressLine1: '3rd Floor, Auto Cluster Building', addressLine2: 'Pimpri', city: 'Pune', state: 'Maharashtra', country: 'India', postalCode: '411018', taxId: '27AACCS5566L1Z8', website: 'https://www.sahyadri-motors.example', primaryContactName: 'Meera Joshi', primaryContactEmail: 'buyer@sahyadri.dev', primaryContactPhone: '+91 20 2745 6600' } },
  { code: 'DECCAN-MACHINING', type: 'SUPPLIER', approve: true, profile: { name: 'Deccan Machining', addressLine1: 'Plot 9, Bommasandra Industrial Area', addressLine2: 'Anekal Taluk', city: 'Bengaluru', state: 'Karnataka', country: 'India', postalCode: '560099', taxId: '29AAGCD7788H1ZP', website: 'https://www.deccan-machining.example', primaryContactName: 'Ramesh Rao', primaryContactEmail: 'sales@deccan.dev', primaryContactPhone: '+91 80 2783 9900' } },
  { code: 'VINDHYA-TOOLS', type: 'SUPPLIER', approve: false, profile: { name: 'Vindhya Tools', addressLine1: 'Unit 14, Mandideep Industrial Area', addressLine2: 'Obedullaganj Road', city: 'Bhopal', state: 'Madhya Pradesh', country: 'India', postalCode: '462046', taxId: '23AAKCV1190B1ZS', website: 'https://www.vindhya-tools.example', primaryContactName: 'Anil Tiwari', primaryContactEmail: 'info@vindhya-tools.example', primaryContactPhone: '+91 755 267 1200' } },
]
const NEW_USERS = [
  { company: 'SAHYADRI-MOTORS', persona: 'sahyadriBuyer', firstName: 'Meera', lastName: 'Joshi', email: 'buyer@sahyadri.dev', mobile: '+91 98220 11201', roles: ['CUSTOMER_RFQ'] },
  { company: 'SAHYADRI-MOTORS', persona: 'sahyadriNpd', firstName: 'Nikhil', lastName: 'Kale', email: 'npd@sahyadri.dev', mobile: '+91 98220 11203', roles: ['CUSTOMER_NPD'] },
  { company: 'SAHYADRI-MOTORS', persona: 'sahyadriOfp', firstName: 'Pooja', lastName: 'Bhosale', email: 'logistics@sahyadri.dev', mobile: '+91 98220 11204', roles: ['CUSTOMER_OFP'] },
  { company: 'DECCAN-MACHINING', persona: 'deccan', firstName: 'Ramesh', lastName: 'Rao', email: 'sales@deccan.dev', mobile: '+91 98450 11202', roles: ['SUPPLIER_RFQ'] },
  { company: 'BHARAT-MOTORS', persona: 'bharatNpd', firstName: 'Sanjay', lastName: 'Malhotra', email: 'npd@bharat.dev', mobile: '+91 98110 21205', roles: ['CUSTOMER_NPD'] },
  { company: 'BHARAT-MOTORS', persona: 'bharatOfp', firstName: 'Rekha', lastName: 'Chauhan', email: 'logistics@bharat.dev', mobile: '+91 98110 21206', roles: ['CUSTOMER_OFP'] },
  { company: 'BHARAT-MOTORS', persona: 'bharatQc', firstName: 'Vivek', lastName: 'Sethi', email: 'quality@bharat.dev', mobile: '+91 98110 21207', roles: ['CUSTOMER_QUALITY'] },
  { company: 'SHAKTI-FORGE', persona: 'shaktiNpd', firstName: 'Gurpreet', lastName: 'Singh', email: 'tooling@shakti.dev', mobile: '+91 98150 31208', roles: ['SUPPLIER_NPD'] },
  { company: 'SHAKTI-FORGE', persona: 'shaktiOfp', firstName: 'Harjit', lastName: 'Kaur', email: 'planner@shakti.dev', mobile: '+91 98150 31209', roles: ['SUPPLIER_OFP'] },
  { company: 'SHAKTI-FORGE', persona: 'shaktiQa', firstName: 'Manpreet', lastName: 'Gill', email: 'qa@shakti.dev', mobile: '+91 98150 31210', roles: ['SUPPLIER_QUALITY'] },
]
const MOBILES = {
  'admin@ergo.dev': '+91 98900 10001', 'management@ergo.dev': '+91 98900 10002', 'rfq@ergo.dev': '+91 98900 10003', 'quality@ergo.dev': '+91 98900 10004',
  'kam@ergo.dev': '+91 98900 10005', 'buyer@acme.dev': '+91 98220 20006', 'buyer@bharat.dev': '+91 98110 20007', 'sales@precision.dev': '+91 98250 30008',
  'sales@shakti.dev': '+91 98150 30009', 'ofp@ergo.dev': '+91 98900 10010', 'npd@ergo.dev': '+91 98900 10011', 'npd@acme.dev': '+91 98220 20012',
  'logistics@acme.dev': '+91 98220 20013', 'quality@acme.dev': '+91 98220 20014', 'account@acme.dev': '+91 98220 20015', 'tooling@precision.dev': '+91 98250 30016',
  'planner@precision.dev': '+91 98250 30017', 'qa@precision.dev': '+91 98250 30018', 'lead@precision.dev': '+91 98250 30019', 'management@acme.dev': '+91 98220 20020',
}

async function masterEntries(list) {
  return new Set((await get('admin', `/master-data/${list}`)).map((e) => e.code))
}

export async function run() {
  // master data
  const add = async (list, rows) => {
    const have = await masterEntries(list)
    let n = 0
    for (const [code, name, ...rest] of rows) {
      if (have.has(code)) continue
      const fields = list === 'materials' ? { specification: rest[0], grade: rest[1] } : list === 'processes' ? { processType: rest[0] }
        : list === 'coatings' ? { specification: rest[0] } : list === 'payment-terms' ? { description: rest[0] } : {}
      if (await attempt(`master ${list} ${code}`, 'admin', 'POST', `/master-data/${list}`, { code, name, active: true, fields })) n++
    }
    return n
  }
  const counts = [
    await add('materials', MATERIALS), await add('processes', PROCESSES), await add('coatings', COATINGS),
    await add('payment-terms', PAYMENT_TERMS), await add('shipment-modes', SHIPMENT_MODES),
  ]
  log(`  master data: +${counts[0]} materials, +${counts[1]} processes, +${counts[2]} coatings, +${counts[3]} payment terms, +${counts[4]} shipment modes`)

  // exchange rates: month-end style rates for the last 6 months (to INR)
  const currencies = await get('admin', '/master-data/currencies')
  const id = Object.fromEntries(currencies.map((c) => [c.code, c.id]))
  const existing = await get('admin', '/master-data/exchange-rates')
  const base = { USD: 83.1, EUR: 90.4, RMB: 11.5 }
  let rates = 0
  for (let m = 5; m >= 0; m--) {
    for (const [code, rate] of Object.entries(base)) {
      const date = monthsAgo(m)
      if (existing.some((r) => r.from.code === code && r.to.code === 'INR' && r.rateDate === date)) continue
      const drift = 1 + (Math.sin(m * 1.7 + code.length) * 0.012)
      if (await attempt('rate', 'admin', 'POST', '/master-data/exchange-rates', { fromCurrencyId: id[code], toCurrencyId: id.INR, rateDate: date, rate: Math.round(rate * drift * 100) / 100, source: 'RBI reference rate' })) rates++
    }
  }
  log(`  exchange rates: +${rates}`)

  // companies
  let companies = await get('admin', '/companies')
  for (const c of companies) {
    const profile = PROFILES[c.id]
    if (profile) await attempt(`profile ${c.code}`, 'admin', 'PUT', `/companies/${c.id}`, profile)
  }
  for (const nc of NEW_COMPANIES) {
    if (companies.some((c) => c.code === nc.code)) continue
    const actor = nc.approve ? 'admin' : 'rfq'
    const created = await attempt(`company ${nc.code}`, actor, 'POST', '/companies', { code: nc.code, type: nc.type, profile: nc.profile })
    if (created && nc.approve && created.status === 'PENDING_APPROVAL') await attempt(`approve ${nc.code}`, 'mgmt', 'POST', `/companies/${created.id}/approve`, { comment: 'Vendor audit completed, approved.' })
  }
  companies = await get('admin', '/companies')
  const cid = Object.fromEntries(companies.map((c) => [c.code, c.id]))
  log(`  companies: ${companies.length} (${companies.filter((c) => c.status === 'PENDING_APPROVAL').length} pending approval)`)

  // users: phone numbers for everyone, plus one buyer / one supplier for the two new companies
  const users = await get('admin', '/users')
  for (const u of users) {
    if (MOBILES[u.email] && !u.mobile) await attempt(`mobile ${u.email}`, 'admin', 'PUT', `/users/${u.id}`, { firstName: u.firstName, lastName: u.lastName, email: u.email, mobile: MOBILES[u.email] })
  }
  for (const nu of NEW_USERS) {
    let user = users.find((u) => u.email === nu.email)
    if (!user) {
      const created = await attempt(`user ${nu.email}`, 'admin', 'POST', '/users', { firstName: nu.firstName, lastName: nu.lastName, email: nu.email, mobile: nu.mobile, companyId: cid[nu.company], roles: nu.roles, defaultRole: nu.roles[0] })
      user = created?.user
    }
    if (user) PERSONA[nu.persona] = { id: user.id, cid: cid[nu.company], ctype: companies.find((c) => c.code === nu.company).type, role: nu.roles[0] }
  }
  // the key account manager looks after three customers
  await attempt('kam accounts', 'admin', 'PUT', '/users/5/accounts', { customerIds: [cid['ACME-AUTO'], cid['BHARAT-MOTORS'], cid['SAHYADRI-MOTORS']].filter(Boolean) })
  log(`  users: ${(await get('admin', '/users')).length} (phones added; ${NEW_USERS.length} added for the newer and smaller companies)`)
  return { companyIds: { ergo: cid.ERGO, acme: cid['ACME-AUTO'], bharat: cid['BHARAT-MOTORS'], sahyadri: cid['SAHYADRI-MOTORS'], precision: cid['PRECISION-CAST'], shakti: cid['SHAKTI-FORGE'], deccan: cid['DECCAN-MACHINING'] } }
}
