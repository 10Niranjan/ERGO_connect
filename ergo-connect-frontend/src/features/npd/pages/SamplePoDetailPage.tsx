import { Alert, Box, Button, Chip, LinearProgress, Link, Stack, Tab, Tabs, Typography } from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router'
import { useAppSelector } from '@/hooks/useAppSelector'
import { AuditTrail } from '../../../shared/components/AuditTrail'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { CreateProjectDialog, EditSamplePoDialog } from '../components/SamplePoDialogs'
import { SAMPLE_PO_STATUS } from '../labels'
import { useReturnSamplePoMutation, useSamplePoHistoryQuery, useSamplePoQuery, type SamplePoView } from '../services/samplePoApi'

export function SamplePoDetailPage() {
  const id = Number(useParams().id)
  // currentData, not data: data keeps the previous record while a new id fails to load.
  const { currentData: samplePo, isLoading, isFetching, error } = useSamplePoQuery(id)
  if (isLoading || (isFetching && !samplePo)) return <LinearProgress />
  if (!samplePo) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <SamplePoDetail samplePo={samplePo} />
}

function SamplePoDetail({ samplePo }: { samplePo: SamplePoView }) {
  const ergo = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'
  const [tab, setTab] = useState('overview')
  const [dialog, setDialog] = useState<'edit' | 'return' | 'create' | null>(null)
  const [returnToCustomer, returning] = useReturnSamplePoMutation()
  const status = SAMPLE_PO_STATUS[samplePo.status]

  async function doReturn(comment: string) {
    try {
      await returnToCustomer({ id: samplePo.id, comment }).unwrap()
      setDialog(null)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ justifyContent: 'space-between', gap: 2 }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {samplePo.samplePoNumber}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
          </Stack>
          <Typography color="text.secondary">
            {ergo && samplePo.customerName ? `${samplePo.customerName} · ` : ''}
            {samplePo.partNumber}
            {samplePo.revision ? ` rev ${samplePo.revision}` : ''} · {samplePo.quantity} pcs · required by {formatDate(samplePo.requiredDate)}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {samplePo.actions.includes('EDIT') && (
            <Button variant="contained" onClick={() => setDialog('edit')}>
              Correct &amp; resubmit
            </Button>
          )}
          {samplePo.actions.includes('RETURN') && (
            <Button onClick={() => setDialog('return')}>Return to customer</Button>
          )}
          {samplePo.actions.includes('CREATE_PROJECT') && (
            <Button variant="contained" onClick={() => setDialog('create')}>
              Create project
            </Button>
          )}
        </Stack>
      </Stack>

      {samplePo.status === 'RETURNED' && samplePo.returnComment && (
        <Alert severity="warning">Returned for correction: {samplePo.returnComment}</Alert>
      )}
      {samplePo.npdProjectId != null && (
        <Alert severity="success">
          Accepted. Sample project{' '}
          <Link component={RouterLink} to={`/npd/${samplePo.npdProjectId}`}>
            {samplePo.npdNumber}
          </Link>{' '}
          was created from this Sample PO.
        </Alert>
      )}

      {ergo && (
        <Tabs value={tab} onChange={(_e, value: string) => setTab(value)}>
          <Tab value="overview" label="Overview" />
          <Tab value="audit" label="Audit trail" />
        </Tabs>
      )}

      {tab === 'overview' && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          <Facts
            title="Sample PO"
            facts={[
              ['Sample PO', samplePo.samplePoNumber],
              ['Customer PO number', samplePo.customerPoNumber],
              ['Quantity', String(samplePo.quantity)],
              ['Required by', formatDate(samplePo.requiredDate)],
              ['Submitted', formatDate(samplePo.createdAt)],
            ]}
          />
          <Facts
            title="Part"
            facts={[
              ['Part number', samplePo.partNumber],
              ['Revision', samplePo.revision ?? '—'],
              [
                'Quotation',
                samplePo.rfqNumber ? (
                  <Link key="rfq" component={RouterLink} to={`/rfqs/${samplePo.rfqId}`}>
                    {samplePo.rfqNumber}
                  </Link>
                ) : (
                  '—'
                ),
              ],
              ['Description', samplePo.description ?? '—'],
            ]}
          />
          <Facts title="Remarks" facts={[['', samplePo.remarks ?? '—']]} />
        </Box>
      )}
      {tab === 'audit' && ergo && <SamplePoHistory id={samplePo.id} />}

      {dialog === 'edit' && <EditSamplePoDialog samplePo={samplePo} onClose={() => setDialog(null)} />}
      {dialog === 'create' && <CreateProjectDialog samplePo={samplePo} onClose={() => setDialog(null)} />}
      {dialog === 'return' && (
        <CommentDialog
          title={`Return ${samplePo.samplePoNumber} to the customer`}
          label="What must the customer correct?"
          required
          confirmLabel="Return Sample PO"
          intro="The customer sees this comment and can correct and resubmit the Sample PO."
          busy={returning.isLoading}
          error={returning.error}
          onConfirm={(comment) => void doReturn(comment)}
          onClose={() => setDialog(null)}
        />
      )}
    </Stack>
  )
}

function SamplePoHistory({ id }: { id: number }) {
  const { data = [], error } = useSamplePoHistoryQuery(id)
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <AuditTrail rows={data} />
}
