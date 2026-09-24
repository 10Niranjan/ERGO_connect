import { Alert, Button, Card, CardContent, LinearProgress, Stack, Typography } from '@mui/material'
import { useState } from 'react'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery, type CompanyProfile } from '../companiesApi'
import { CompanyDialog } from '../components/CompanyDialog'

const FIELDS: [keyof CompanyProfile, string][] = [
  ['addressLine1', 'Address'],
  ['addressLine2', ''],
  ['city', 'City'],
  ['state', 'State'],
  ['country', 'Country'],
  ['postalCode', 'Postal code'],
  ['taxId', 'Tax ID / GST / VAT'],
  ['website', 'Website'],
  ['primaryContactName', 'Primary contact'],
  ['primaryContactEmail', 'Contact email'],
  ['primaryContactPhone', 'Contact phone'],
]

// Customers and suppliers: their own company only (SRS §10.2/§10.3 RV). Edit only for roles with E rights.
export function MyCompanyPage() {
  const { data: companies = [], isLoading, error } = useCompaniesQuery()
  const [editing, setEditing] = useState(false)
  const company = companies[0]

  return (
    <Stack spacing={2} sx={{ maxWidth: 720 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Company profile
        </Typography>
        {company?.actions.includes('EDIT') && (
          <Button variant="contained" onClick={() => setEditing(true)}>
            Edit
          </Button>
        )}
      </Stack>
      {isLoading && <LinearProgress />}
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      {company && (
        <Card variant="outlined">
          <CardContent sx={{ display: 'grid', gridTemplateColumns: '180px 1fr', rowGap: 1.5, columnGap: 2 }}>
            <Typography color="text.secondary">Name</Typography>
            <Typography>{company.profile.name}</Typography>
            <Typography color="text.secondary">Code</Typography>
            <Typography>{company.code}</Typography>
            {FIELDS.filter(([key]) => company.profile[key]).map(([key, label]) => (
              <Stack key={key} direction="row" sx={{ display: 'contents' }}>
                <Typography color="text.secondary">{label}</Typography>
                <Typography>{company.profile[key]}</Typography>
              </Stack>
            ))}
          </CardContent>
        </Card>
      )}
      {editing && company && <CompanyDialog company={company} onClose={() => setEditing(false)} />}
    </Stack>
  )
}
