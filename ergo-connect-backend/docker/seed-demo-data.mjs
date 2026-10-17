// Demo dataset for client presentations: master data, companies, RFQs at every status, quotes and comparisons,
// NPD projects, orders and shipments, inspections / NCRs / deviations, documents, comments and notifications.
// Dev profile only. Everything is created through the real REST API as the real demo personas.
//
//   node docker/seed-demo-data.mjs              run every phase (safe to re-run: phases skip what already exists)
//   node docker/seed-demo-data.mjs --only=1,2   run just those phases
//
// Clean slate first (see demoChanges.md): recreate the database container, start the backend (DevDataSeeder
// creates the 20 personas as user ids 1..20), then run this script.
import { failures, log, stat } from './seed/lib.mjs'
import * as p1 from './seed/01-master-and-companies.mjs'
import * as p2 from './seed/02-rfq-and-quotes.mjs'
import * as p3 from './seed/03-npd.mjs'
import * as p4 from './seed/04-orders.mjs'
import * as p5 from './seed/05-quality.mjs'
import * as p6 from './seed/06-comments-documents-notifications.mjs'

const PHASES = [
  [1, 'Master data, companies and users', p1],
  [2, 'RFQs and quotations', p2],
  [3, 'NPD projects', p3],
  [4, 'Orders and shipments', p4],
  [5, 'Quality: inspections, NCRs, deviations', p5],
  [6, 'Comments, documents and notifications', p6],
]

const only = process.argv.find((a) => a.startsWith('--only='))?.slice(7).split(',').map(Number)
const ctx = {}
const t0 = Date.now()
for (const [n, title, mod] of PHASES) {
  if (only && !only.includes(n) && n !== 1) continue // phase 1 is cheap, idempotent, and feeds the others
  log(`Phase ${n}: ${title}`)
  Object.assign(ctx, (await mod.run(ctx)) ?? {})
}
const s = stat()
log(`\nDone in ${Math.round((Date.now() - t0) / 1000)}s: ${s.calls} API calls, ${s.failures} skipped steps.`)
if (failures.length) {
  log('\nSkipped steps (a rule blocked them; the rest of the data is unaffected):')
  for (const f of failures) log('  - ' + f)
}
