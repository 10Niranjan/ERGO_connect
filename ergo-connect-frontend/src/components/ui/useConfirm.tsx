import { useCallback, useRef, useState } from 'react'
import { ConfirmDialog } from './ConfirmDialog'

interface ConfirmOptions {
  title: string
  message: string
  confirmLabel?: string
}

/** `if (!(await confirm({...}))) return` — the in-app, awaitable replacement for window.confirm(). Render `dialog` once. */
export function useConfirm() {
  const [options, setOptions] = useState<ConfirmOptions | null>(null)
  const resolver = useRef<((ok: boolean) => void) | null>(null)

  const confirm = useCallback(
    (next: ConfirmOptions) =>
      new Promise<boolean>((resolve) => {
        resolver.current = resolve
        setOptions(next)
      }),
    [],
  )
  const close = (ok: boolean) => {
    resolver.current?.(ok)
    setOptions(null)
  }

  const dialog = (
    <ConfirmDialog
      open={options != null}
      title={options?.title ?? ''}
      message={options?.message ?? ''}
      confirmLabel={options?.confirmLabel ?? 'Confirm'}
      onConfirm={() => close(true)}
      onCancel={() => close(false)}
    />
  )
  return { confirm, dialog }
}
