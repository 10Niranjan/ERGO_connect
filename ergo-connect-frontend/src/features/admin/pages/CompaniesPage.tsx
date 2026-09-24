import {
  Alert,
  Button,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery, type Company } from '../adminApi'
import { CompanyDialog } from '../components/CompanyDialog'

export function CompaniesPage() {
  const { data: companies = [], isLoading, error } = useCompaniesQuery()
  const [editing, setEditing] = useState<Company | 'new' | null>(null)

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Companies
        </Typography>
        <Button variant="contained" onClick={() => setEditing('new')}>
          New company
        </Button>
      </Stack>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Code</TableCell>
              <TableCell>Name</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>City</TableCell>
              <TableCell>Country</TableCell>
              <TableCell>Primary contact</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {companies.map((company) => (
              <TableRow key={company.id} hover>
                <TableCell>{company.code}</TableCell>
                <TableCell>{company.name}</TableCell>
                <TableCell>{company.type.charAt(0) + company.type.slice(1).toLowerCase()}</TableCell>
                <TableCell>{company.city ?? '—'}</TableCell>
                <TableCell>{company.country ?? '—'}</TableCell>
                <TableCell>{company.primaryContactName ?? '—'}</TableCell>
                <TableCell>
                  <Chip
                    size="small"
                    color={company.active ? 'success' : 'default'}
                    label={company.active ? 'Active' : 'Inactive'}
                  />
                </TableCell>
                <TableCell align="right">
                  <Button size="small" onClick={() => setEditing(company)}>
                    Edit
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && <CompanyDialog company={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Stack>
  )
}
