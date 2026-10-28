// One place for how dates read on screen: dd/mm/yyyy, matching the browser's date inputs, whatever the OS locale.
const LOCALE = 'en-GB'

/** Date-only values (due dates, validity) are calendar days, not instants: they are read as local midnight. */
export function formatDate(value: string | null | undefined): string {
  return value ? new Date(value.length === 10 ? `${value}T00:00` : value).toLocaleDateString(LOCALE) : '—'
}

export function formatDateTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString(LOCALE) : '—'
}
