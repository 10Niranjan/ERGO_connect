import type { FormEvent } from 'react'

// Forms keep numbers as typed text until saved.

/** A value as form text ('' for none). */
export const text = (value: string | number | null | undefined) => (value == null ? '' : String(value))

/** Form text as a number (null when empty). */
export const num = (value: string) => (value.trim() === '' ? null : Number(value))

/** Form text trimmed (null when empty). */
export const str = (value: string) => value.trim() || null

export function formValues<T = Record<string, string>>(event: FormEvent<HTMLFormElement>): T {
  event.preventDefault()
  return Object.fromEntries(new FormData(event.currentTarget).entries()) as T
}
