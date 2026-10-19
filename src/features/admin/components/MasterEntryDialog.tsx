import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Switch,
  TextField,
} from '@mui/material'
import type { FormEvent } from 'react'
import {
  useCreateMasterEntryMutation,
  useUpdateMasterEntryMutation,
  type MasterEntry,
  type MasterList,
} from '@/features/admin/services/adminApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** One form for every master list: its extra fields come from the list definition sent by the backend. */
export function MasterEntryDialog({ list, entry, onClose }: { list: MasterList; entry: MasterEntry | null; onClose: () => void }) {
  const [createEntry, creating] = useCreateMasterEntryMutation()
  const [updateEntry, updating] = useUpdateMasterEntryMutation()
  const error = creating.error ?? updating.error

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const input = {
      list: list.key,
      code: entry?.code ?? String(form.get('code')).trim(),
      name: String(form.get('name')).trim(),
      active: entry ? form.get('active') === 'on' : true,
      fields: Object.fromEntries(list.fields.map((field) => [field.key, String(form.get(field.key) ?? '').trim() || null])),
    }
    try {
      await (entry ? updateEntry({ ...input, id: entry.id }) : createEntry(input)).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <form onSubmit={handleSubmit}>
        <DialogTitle>
          {entry ? 'Edit' : 'New'} entry: {list.title}
        </DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          <TextField
            name="code"
            label="Code"
            defaultValue={entry?.code}
            required
            disabled={Boolean(entry)}
            helperText={entry ? 'Cannot be changed' : 'Stored in capitals, e.g. EN8 or NET30'}
            slotProps={{ htmlInput: { pattern: '[A-Za-z0-9][A-Za-z0-9._\\/\\-]{0,29}' } }}
          />
          <TextField name="name" label="Name" defaultValue={entry?.name} required slotProps={{ htmlInput: { maxLength: 200 } }} />
          {list.fields.map((field) =>
            field.choices.length > 0 ? (
              <TextField key={field.key} name={field.key} label={field.label} select required defaultValue={entry?.fields[field.key] ?? field.choices[0]}>
                {field.choices.map((choice) => (
                  <MenuItem key={choice} value={choice}>
                    {choice.charAt(0) + choice.slice(1).toLowerCase()}
                  </MenuItem>
                ))}
              </TextField>
            ) : (
              <TextField
                key={field.key}
                name={field.key}
                label={field.label}
                defaultValue={entry?.fields[field.key] ?? ''}
                slotProps={{ htmlInput: { maxLength: field.maxLength } }}
              />
            ),
          )}
          {entry && (
            <FormControlLabel
              label="Active (inactive entries disappear from dropdowns but stay on existing records)"
              control={<Switch name="active" defaultChecked={entry.active} />}
            />
          )}
          {error && <ProblemAlert error={error} />}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" loading={creating.isLoading || updating.isLoading}>
            {entry ? 'Save' : 'Add'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
