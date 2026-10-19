import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query/react'
import type { AuthState } from '@/features/auth/store/authSlice'

/**
 * Frontend-only demo: replaces the HTTP call with responses recorded from the real backend
 * (scripts/capture-fixtures.mjs), one JSON file per persona + role. Reads are served as recorded;
 * writes succeed but change nothing, so the data stays presentable.
 */
type Fixture = Record<string, unknown>

const loaders = import.meta.glob<Fixture>('./fixtures/*.json', { import: 'default' })
const cache = new Map<string, Promise<Fixture>>()

const sortedQuery = (params: Record<string, unknown>) =>
  Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${k}=${v}`)
    .join('&')

function load(userId: number, role: string): Promise<Fixture> {
  const key = `${userId}-${role}`
  const loader = loaders[`./fixtures/${key}.json`]
  if (!loader) return Promise.resolve({})
  if (!cache.has(key)) cache.set(key, loader())
  return cache.get(key)!
}

// A tiny valid PDF so "download / view document" works for every file in the demo.
const PLACEHOLDER_PDF =
  '%PDF-1.1\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
  '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 120]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n' +
  '4 0 obj<</Length 60>>stream\nBT /F1 14 Tf 20 60 Td (ERGO Connect - demo document) Tj ET\nendstream\nendobj\n' +
  '5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF'

const isDownload = (path: string) => /\/(content|pod)$/.test(path.split('?')[0])

type Item = Record<string, unknown>
interface Page { items: Item[]; page: number; size: number; totalElements: number; [k: string]: unknown }

/** Paged lists were recorded as one full page; apply the screen's filters and paging to it. */
function filterPage(page: Page, q: Record<string, string>): Page {
  const items = page.items.filter((it) =>
    Object.entries(q).every(([k, v]) => {
      if (k === 'unreadOnly') return v !== 'true' || it.read === false
      if (k === 'openQueriesOnly') return v !== 'true' || (it.query === true && it.resolved === false)
      if (k === 'escalatedOnly') return v !== 'true' || it.escalated === true
      if (['page', 'size', 'from', 'to'].includes(k)) return true
      return !(k in it) || String(it[k]) === v
    }),
  )
  const size = Number(q.size) || page.size
  const from = (Number(q.page) || 0) * size
  return { ...page, items: items.slice(from, from + size), totalElements: items.length, size }
}

const normalise = (key: string) => {
  const [p, qs = ''] = key.split('?')
  return qs ? `${p}?${sortedQuery(Object.fromEntries(new URLSearchParams(qs)))}` : p
}

function lookup(fixture: Fixture, path: string, q: Record<string, string>): unknown {
  const want = normalise(Object.keys(q).length ? `${path}?${sortedQuery(q)}` : path)
  const keys = Object.keys(fixture)
  const exact = keys.find((k) => normalise(k) === want)
  if (exact) return fixture[exact]
  // not recorded with these exact params: fall back to the recorded full page for this path
  const full = keys.find((k) => k.split('?')[0] === path && k.includes('?'))
  const page = full ? (fixture[full] as Page) : undefined
  return page && Array.isArray(page.items) ? filterPage(page, q) : fixture[path]
}

export const mockBaseQuery: BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError> = async (args, api) => {
  const req = typeof args === 'string' ? { url: args } : args
  const method = (req.method ?? 'GET').toUpperCase()
  const [path, inline = ''] = req.url.split('?')
  await new Promise((r) => setTimeout(r, 120)) // feels like a network call, not a flash

  if (method !== 'GET') {
    if (path.startsWith('/auth/')) return { data: undefined }
    return { data: {} }
  }
  if (isDownload(path)) return { data: URL.createObjectURL(new Blob([PLACEHOLDER_PDF], { type: 'application/pdf' })) }

  const session = (api.getState() as { auth: AuthState }).auth.session
  if (!session) return { error: { status: 401, data: { message: 'Not signed in' } } }
  const q: Record<string, string> = Object.fromEntries(new URLSearchParams(inline))
  for (const [k, v] of Object.entries(req.params ?? {})) if (v !== undefined && v !== '') q[k] = String(v)

  const found = lookup(await load(session.user.id, session.activeRole), path, q)
  if (found === undefined) return { error: { status: 404, data: { message: 'Not part of the demo data' } } }
  if (found && typeof found === 'object' && '__status' in found) {
    return { error: { status: (found as { __status: number }).__status, data: { message: 'Not permitted for this role' } } }
  }
  return { data: found }
}
