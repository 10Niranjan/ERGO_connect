// One date style everywhere (dd/mm/yyyy): the date inputs show day-first, and the browser default (en-US here)
// would print 1 March as 3/1.
const DATE: Intl.DateTimeFormatOptions = { day: '2-digit', month: '2-digit', year: 'numeric' }
const TIME: Intl.DateTimeFormatOptions = { ...DATE, hour: '2-digit', minute: '2-digit', hour12: false }

/** A date-only value ("2026-12-01") is read as a local day, never shifted by the time zone. */
const parse = (value: string | Date) =>
  typeof value === 'string' && value.length === 10 ? new Date(`${value}T00:00`) : new Date(value)

export const formatDate = (value: string | Date) => parse(value).toLocaleDateString('en-GB', DATE)

export const formatDateTime = (value: string | Date) => parse(value).toLocaleString('en-GB', TIME)
