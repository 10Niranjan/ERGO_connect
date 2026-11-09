import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, TextField } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useAppSelector } from '../../../app/store'
import type { CompanyType } from '../../../auth/authSlice'
import { problemMessage } from '../../../shared/problem'
import {
  TYPE_LABELS,
  useCreateCompanyMutation,
  useUpdateCompanyMutation,
  type Company,
  type CompanyProfile,
} from '../companiesApi'

// Optional profile fields of SRS 13.2.1, in form order, with the backend's length limits.
const DETAILS: [Exclude<keyof CompanyProfile, 'name' | 'rfqPrefix'>, string, number][] = [
  ['addressLine1', 'Address line 1', 200],
  ['addressLine2', 'Address line 2', 200],
  ['city', 'City', 100],
  ['state', 'State', 100],
  ['country', 'Country', 100],
  ['postalCode', 'Postal code', 20],
  ['taxId', 'Tax ID / GST / VAT', 50],
  ['website', 'Website', 200],
  ['primaryContactName', 'Primary contact name', 200],
  ['primaryContactEmail', 'Primary contact email', 254],
  ['primaryContactPhone', 'Primary contact phone', 30],
]

interface Props {
  /** null = add a new company of one of {@link types} */
  company: Company | null
  types?: CompanyType[]
  onClose: () => void
}

export function CompanyDialog({ company, types = ['CUSTOMER', 'SUPPLIER'], onClose }: Props) {
  const [createCompany, creating] = useCreateCompanyMutation()
  const [updateCompany, updating] = useUpdateCompanyMutation()
  const error = creating.error ?? updating.error
  const ergoUser = useAppSelector((state) => state.auth.session?.user.companyType === 'ERGO')
  const [newType, setNewType] = useState<CompanyType>(types[0])
  // Only ERGO sets the RFQ prefix, and only a customer's RFQs carry it.
  const showPrefix = ergoUser && (company ? company.type === 'CUSTOMER' : newType === 'CUSTOMER')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const text = (name: string) => String(form.get(name) ?? '').trim() || null
    const profile = {
      ...Object.fromEntries(DETAILS.map(([name]) => [name, text(name)])),
      name: String(form.get('name')).trim(),
      rfqPrefix: showPrefix ? text('rfqPrefix')?.toUpperCase() ?? null : (company?.profile.rfqPrefix ?? null),
    } as CompanyProfile
    try {
      if (company) {
        await updateCompany({ id: company.id, profile }).unwrap()
      } else {
        const code = String(form.get('code')).trim().toUpperCase()
        await createCompany({ code, type: form.get('type') as CompanyType, profile }).unwrap()
      }
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={handleSubmit}>
        <DialogTitle>{company ? `Edit ${company.profile.name}` : 'New company'}</DialogTitle>
        <DialogContent sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2, pt: '8px !important' }}>
          {company ? (
            <>
              <TextField label="Code" value={company.code} disabled helperText="Cannot be changed" />
              <TextField label="Type" value={TYPE_LABELS[company.type]} disabled />
            </>
          ) : (
            <>
              <TextField
                name="code"
                label="Code"
                required
                helperText="Capital letters, digits, - and _"
                slotProps={{ htmlInput: { pattern: '[A-Za-z0-9][A-Za-z0-9_\\-]{1,29}' } }}
              />
              <TextField name="type" label="Type" select defaultValue={types[0]} required onChange={(e) => setNewType(e.target.value as CompanyType)}>
                {types.map((type) => (
                  <MenuItem key={type} value={type}>
                    {TYPE_LABELS[type]}
                  </MenuItem>
                ))}
              </TextField>
            </>
          )}
          <TextField
            name="name"
            label="Name"
            defaultValue={company?.profile.name}
            required
            slotProps={{ htmlInput: { maxLength: 200 } }}
            sx={{ gridColumn: '1 / -1' }}
          />
          {DETAILS.map(([name, label, maxLength]) => (
            <TextField
              key={name}
              name={name}
              label={label}
              type={name === 'primaryContactEmail' ? 'email' : 'text'}
              defaultValue={company?.profile[name] ?? ''}
              slotProps={{ htmlInput: { maxLength } }}
            />
          ))}
          {showPrefix && (
            <TextField
              name="rfqPrefix"
              label="RFQ prefix"
              defaultValue={company?.profile.rfqPrefix ?? ''}
              helperText="2-6 letters or digits; RFQ numbers read IC-2026-001. Empty = initials of the name."
              slotProps={{ htmlInput: { maxLength: 6, pattern: '[A-Za-z0-9]{2,6}', style: { textTransform: 'uppercase' } } }}
              sx={{ gridColumn: '1 / -1' }}
            />
          )}
          {!company && (
            <Alert severity="info" sx={{ gridColumn: '1 / -1' }}>
              Every supplier waits for approval by ERGO Management, and a customer added by ERGO RFQ or a Key Account Manager waits for ERGO Management or ERGO Admin. A supplier joins the approved supplier list only after that.
            </Alert>
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
            {company ? 'Save' : 'Add company'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
