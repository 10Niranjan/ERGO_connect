import type { FetchBaseQueryError } from '@reduxjs/toolkit/query'
import { API_BASE_URL } from '@/constants/config'

/** What the upload resolves to: the same shapes RTK Query's own base query returns, so callers and `problemMessage` need nothing new. */
export type UploadResult<T> = { data: T } | { error: FetchBaseQueryError }

/**
 * POSTs multipart form data and reports how many bytes have gone out. `fetch` cannot do that, so this uses
 * XMLHttpRequest. Everything else matches the app's normal requests: same origin (the session cookie travels), the
 * bearer token, and the `X-Ergo-Client` audit channel header.
 *
 * `onProgress` counts bytes handed to the network, not bytes the server has processed: the caller should not show
 * 100% until the response arrives (the server still scans and stores the file after the last byte).
 */
export function uploadWithProgress<T>(
  path: string,
  body: FormData,
  token: string | undefined,
  onProgress?: (loaded: number, total: number) => void,
): Promise<UploadResult<T>> {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${API_BASE_URL}${path}`)
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.setRequestHeader('X-Ergo-Client', 'web')
    xhr.setRequestHeader('Accept', 'application/json')
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(event.loaded, event.total)
    }
    xhr.onerror = () => resolve({ error: { status: 'FETCH_ERROR', error: 'network error' } })
    xhr.ontimeout = () => resolve({ error: { status: 'FETCH_ERROR', error: 'timeout' } })
    xhr.onabort = () => resolve({ error: { status: 'FETCH_ERROR', error: 'aborted' } })
    xhr.onload = () => {
      let parsed: unknown = null
      try {
        parsed = xhr.responseText ? JSON.parse(xhr.responseText) : null
      } catch {
        parsed = xhr.responseText // a proxy's own error page: kept as text, never shown to the user
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve({ data: parsed as T })
      else resolve({ error: { status: xhr.status, data: parsed } as FetchBaseQueryError })
    }
    xhr.send(body)
  })
}
