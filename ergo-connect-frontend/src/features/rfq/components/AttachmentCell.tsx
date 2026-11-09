import AttachFileIcon from '@mui/icons-material/AttachFile'
import { Box, Button, Chip, Stack } from '@mui/material'
import { useRef } from 'react'
import type { AttachmentRule, ItemFile } from '../services/rfqApi'

interface Props {
  rule: AttachmentRule | undefined
  /** Files already stored for this slot. */
  existing: ItemFile[]
  /** Files chosen but not yet uploaded (they go up when the RFQ is saved). */
  pending: File[]
  disabled?: boolean
  label: string
  onAdd: (files: File[]) => void
  onRemovePending: (index: number) => void
  onRemoveExisting: (file: ItemFile) => void
  onDownload: (file: ItemFile) => void
  /** Called with a message when a chosen file has a type or size the slot does not accept. */
  onReject: (message: string) => void
}

/** The extension of a file name, lower case, no dot; '' when there is none. */
function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

/**
 * One attachment slot of a part: files only, no text. Several files per slot; each shows its name with remove (and
 * download once stored). The slot's accepted types come from the backend's rules; the backend checks them again.
 */
export function AttachmentCell({ rule, existing, pending, disabled, label, onAdd, onRemovePending, onRemoveExisting, onDownload, onReject }: Props) {
  const input = useRef<HTMLInputElement>(null)

  function choose(list: FileList | null) {
    const chosen = Array.from(list ?? [])
    if (input.current) input.current.value = '' // the same file may be chosen again after removing it
    const accepted: File[] = []
    for (const file of chosen) {
      if (rule && !rule.extensions.includes(extensionOf(file.name))) {
        onReject(`${file.name}: ${label} accepts ${rule.extensions.map((e) => e.toUpperCase()).join(', ')} only`)
      } else if (rule && file.size > rule.maxBytes) {
        onReject(`${file.name} is larger than ${Math.round(rule.maxBytes / (1024 * 1024))} MB`)
      } else if (file.size === 0) {
        onReject(`${file.name} is empty`)
      } else {
        accepted.push(file)
      }
    }
    if (accepted.length > 0) onAdd(accepted)
  }

  return (
    <Stack spacing={0.5} sx={{ minWidth: 150, alignItems: 'flex-start' }}>
      {!disabled && (
        <>
          <input
            ref={input}
            type="file"
            multiple
            hidden
            accept={rule?.extensions.map((e) => `.${e}`).join(',')}
            onChange={(e) => choose(e.target.files)}
            aria-label={`Attach ${label}`}
          />
          <Button size="small" startIcon={<AttachFileIcon />} onClick={() => input.current?.click()} disabled={!rule}>
            Attach
          </Button>
        </>
      )}
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, maxWidth: 220 }}>
        {existing.map((file) => (
          <Chip
            key={file.id}
            size="small"
            label={file.fileName}
            title={file.fileName}
            onClick={() => onDownload(file)}
            onDelete={!disabled && file.canDelete ? () => onRemoveExisting(file) : undefined}
            sx={{ justifyContent: 'space-between', maxWidth: 220 }}
          />
        ))}
        {pending.map((file, index) => (
          <Chip
            key={`${file.name}-${index}`}
            size="small"
            variant="outlined"
            color="primary"
            label={file.name}
            title={`${file.name} (uploads when you save)`}
            onDelete={() => onRemovePending(index)}
            sx={{ justifyContent: 'space-between', maxWidth: 220 }}
          />
        ))}
      </Box>
    </Stack>
  )
}
