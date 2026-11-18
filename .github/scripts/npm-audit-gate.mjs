// Frontend dependency CVE gate (ARCHITECTURE.md §8, Phase 8a): fails on any high/critical npm advisory, except the
// ones listed in ALLOWED until their expiry date. Run from ergo-connect-frontend/. Fails closed if the audit itself
// fails (network, registry, unreadable output).
import { spawnSync } from 'node:child_process'

/** Advisory id -> last day (UTC, inclusive) it is tolerated. Each entry needs a reason; keep the list short. */
const ALLOWED = {
  // picomatch ReDoS through repeated extglobs. Reaches us only through Vite (build tool, never shipped: the image
  // serves static files) and no patched picomatch exists yet. Decided 2026-11-18; drop once a fixed version lands.
  'GHSA-fc5q-39xc-vjpf': '2026-12-31',
}

// AUDIT_GATE_TODAY exists only to test expiry; anything but a YYYY-MM-DD date is ignored.
const override = process.env.AUDIT_GATE_TODAY
const today = /^\d{4}-\d{2}-\d{2}$/.test(override ?? '') ? override : new Date().toISOString().slice(0, 10)

const run = spawnSync('npm', ['audit', '--json'], { encoding: 'utf8', shell: process.platform === 'win32' })
let report
try {
  report = JSON.parse(run.stdout)
} catch {
  console.error(`::error::npm audit gave no readable report (exit ${run.status}): ${run.stderr}`)
  process.exit(1)
}
// v2 is the only report shape this parser knows; any other shape could read as "no advisories".
if (report?.error || !report?.vulnerabilities || report.auditReportVersion !== 2) {
  console.error(`::error::npm audit failed: ${JSON.stringify(report?.error ?? report).slice(0, 500)}`)
  process.exit(1)
}

// Each advisory appears as an object in the `via` list of the package it affects; strings there only point at
// another vulnerable package, whose own entry carries the advisory.
const advisories = new Map()
for (const pkg of Object.values(report.vulnerabilities)) {
  for (const via of pkg.via) {
    if (typeof via === 'object' && (via.severity === 'high' || via.severity === 'critical')) {
      const id = via.url?.split('/').pop() ?? `${via.name}:${via.title}`
      advisories.set(id, `${via.severity} ${via.name} ${via.range}: ${via.title} (${via.url})`)
    }
  }
}

let blocking = 0
for (const [id, text] of advisories) {
  const until = Object.hasOwn(ALLOWED, id) ? ALLOWED[id] : undefined // not inherited keys like "constructor"
  if (until && today <= until) {
    console.log(`::warning::allowed until ${until}: ${text}`)
  } else {
    console.error(`::error::${until ? `allowance expired ${until}: ` : ''}${text}`)
    blocking++
  }
}
console.log(`${advisories.size} high/critical advisories, ${blocking} blocking`)
process.exit(blocking > 0 ? 1 : 0)
