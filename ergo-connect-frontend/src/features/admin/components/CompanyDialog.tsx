import {
  Alert,
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
import type { CompanyType } from '../../../auth/authSlice'
import { problemMessage } from '../../../shared/problem'
import { useCreateCompanyMutation, useUpdateCompanyMutation, type Company, type CompanyInput } from '../adminApi'

// Optional fields of SRS 13.2.1, in form order.
const DETAILS: [keyof CompanyInput, string][] = [
  ['addressLine1', 'Address line 1'],
  ['addressLine2', 'Address line 2'],
  ['city', 'City'],
  ['state', 'State'],
  ['country', 'Country'],
  ['postalCode', 'Postal code'],
  ['taxId', 'Tax ID / GST / VAT'],
  ['website', 'Website'],
  ['primaryContactName', 'Primary contact name'],
  ['primaryContactEmail', 'Primary contact email'],
  ['primaryContactPhone', 'Primary contact phone'],
]

/** null = create a new company */
export function CompanyDialog({ company, onClose }: { company: Company | null; onClose: () => void }) {
  const [createCompany, creating] = useCreateCompanyMutation()
  const [updateCompany, updating] = useUpdateCompanyMutation()
  const error = creating.error ?? updating.error

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const text = (name: string) => String(form.get(name) ?? '').trim() || null
    const input = {
      ...Object.fromEntries(DETAILS.map(([name]) => [name, text(name)])),
      code: company?.code ?? String(form.get('code')).trim().toUpperCase(),
      name: String(form.get('name')).trim(),
      type: company?.type ?? (String(form.get('type')) as CompanyType),
      active: company ? form.get('active') === 'on' : true,
    } as CompanyInput
    try {
      await (company ? updateCompany({ ...input, id: company.id }) : createCompany(input)).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={handleSubmit}>
        <DialogTitle>{company ? 'Edit company' : 'New company'}</DialogTitle>
        <DialogContent sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2, pt: '8px !important' }}>
          <TextField
            name="code"
            label="Code"
            defaultValue={company?.code}
            required
            disabled={Boolean(company)}
            helperText={company ? 'Cannot be changed' : 'Capital letters, digits, - and _'}
            slotProps={{ htmlInput: { pattern: '[A-Za-z0-9][A-Za-z0-9_\\-]{1,29}' } }}
          />
          <TextField
            name="type"
            label="Type"
            select
            defaultValue={company?.type ?? 'CUSTOMER'}
            required
            disabled={Boolean(company)}
          >
            {company?.type === 'ERGO' && <MenuItem value="ERGO">ERGO</MenuItem>}
            <MenuItem value="CUSTOMER">Customer</MenuItem>
            <MenuItem value="SUPPLIER">Supplier</MenuItem>
          </TextField>
          <TextField name="name" label="Name" defaultValue={company?.name} required sx={{ gridColumn: '1 / -1' }} />
          {DETAILS.map(([name, label]) => (
            <TextField
              key={name}
              name={name}
              label={label}
              type={name === 'primaryContactEmail' ? 'email' : 'text'}
              defaultValue={company?.[name] ?? ''}
            />
          ))}
          {company && (
            <FormControlLabel
              label="Active"
              control={<Switch name="active" defaultChecked={company.active} disabled={company.type === 'ERGO'} />}
            />
          )}
          {error && (
            <Alert severity="error" sx={{ gridColumn: '1 / -1' }}>
              {problemMessage(error)}
            </Alert>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" loading={creating.isLoading || updating.isLoading}>
            {company ? 'Save' : 'Create company'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
