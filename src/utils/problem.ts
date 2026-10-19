/** User-facing text for an RTK Query error: the backend's ProblemDetail message when it sent one. */
export function problemMessage(error: unknown): string {
  const { status, data } = (error ?? {}) as { status?: unknown; data?: { detail?: unknown } }
  if (typeof data?.detail === 'string') return data.detail
  if (status === 403) return 'You do not have permission to do this.'
  if (status === 'FETCH_ERROR') return 'Cannot reach the server. Check your connection and try again.'
  return 'Something went wrong. Please try again.'
}

export const formatProblem = problemMessage
