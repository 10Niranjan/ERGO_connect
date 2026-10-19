import { MenuItem, TextField } from '@mui/material'
import { useAppSelector } from '@/app/store'
import { useMyNpdProjectsQuery, useNpdListQuery } from '@/features/npd/services/npdApi'
import { useOfpOrdersQuery } from '@/features/ofp/services/ofpApi'
import type { LinkedModule } from '@/features/quality/services/qualityApi'

interface Props {
  module: LinkedModule
  value: string
  /** `partNumber` is the picked record's, so the form can prefill its own part number field. */
  onChange: (id: string, partNumber?: string) => void
  helperText: string
}

/**
 * Picks the NPD project or order a quality record hangs off by its number, so nobody has to know an internal id.
 * INCOMING/FINAL/IN_PROCESS checks have no record to pick from, so they keep a plain id field.
 */
export function LinkedRecordPicker({ module, value, onChange, helperText }: Props) {
  const isErgo = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'
  const ergoNpd = useNpdListQuery(undefined, { skip: module !== 'NPD' || !isErgo })
  const ownNpd = useMyNpdProjectsQuery(undefined, { skip: module !== 'NPD' || isErgo })
  const orders = useOfpOrdersQuery(undefined, { skip: module !== 'OFP' })

  const rows: { id: number; number: string; partNumber: string }[] =
    module === 'NPD'
      ? (ergoNpd.data ?? ownNpd.data ?? []).map((r) => ({ id: r.id, number: r.npdNumber, partNumber: r.partNumber }))
      : module === 'OFP'
        ? (orders.data ?? []).map((r) => ({ id: r.id, number: r.orderNumber, partNumber: r.partNumber }))
        : []

  if (module !== 'NPD' && module !== 'OFP') {
    return (
      <TextField required label="Linked record id" type="number" value={value} onChange={(e) => onChange(e.target.value)} helperText={helperText} slotProps={{ htmlInput: { min: 1 } }} />
    )
  }
  return (
    <TextField
      select
      required
      label={module === 'NPD' ? 'NPD project' : 'Order'}
      value={rows.some((r) => String(r.id) === value) ? value : ''}
      onChange={(e) => onChange(e.target.value, rows.find((r) => String(r.id) === e.target.value)?.partNumber)}
      helperText={rows.length ? helperText : `No ${module === 'NPD' ? 'NPD projects' : 'orders'} available to link yet`}
    >
      {rows.map((r) => (
        <MenuItem key={r.id} value={String(r.id)}>
          {r.number} · {r.partNumber}
        </MenuItem>
      ))}
    </TextField>
  )
}
