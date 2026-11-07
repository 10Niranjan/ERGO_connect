import { Alert, Box, Button, Chip, LinearProgress, Link, Stack, Tab, Tabs, Typography } from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router'
import { useAppSelector } from '@/hooks/useAppSelector'
import { AuditTrail } from '../../../shared/components/AuditTrail'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { CreateOfpProjectDialog, EditProductionPoDialog } from '../components/ProductionPoDialogs'
import { PRODUCTION_PO_STATUS } from '../utils/labels'
import { useProductionPoHistoryQuery, useProductionPoQuery, useReturnProductionPoMutation, type ProductionPoView } from '../services/productionPoApi'

export function ProductionPoDetailPage() {
  const id = Number(useParams().id)
  // currentData, not data: data keeps the previous record while a new id fails to load.
  const { currentData: productionPo, isLoading, isFetching, error } = useProductionPoQuery(id)
  if (isLoading || (isFetching && !productionPo)) return <LinearProgress />
  if (!productionPo) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <ProductionPoDetail productionPo={productionPo} />
}

function ProductionPoDetail({ productionPo }: { productionPo: ProductionPoView }) {
  const ergo = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'
  const [tab, setTab] = useState('overview')
  const [dialog, setDialog] = useState<'edit' | 'return' | 'create' | null>(null)
  const [returnToCustomer, returning] = useReturnProductionPoMutation()
  const status = PRODUCTION_PO_STATUS[productionPo.status]

  async function doReturn(comment: string) {
    try {
      await returnToCustomer({ id: productionPo.id, comment }).unwrap()
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
              {productionPo.productionPoNumber}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
          </Stack>
          <Typography color="text.secondary">
            {ergo && productionPo.customerName ? `${productionPo.customerName} · ` : ''}
            {productionPo.partNumber}
            {productionPo.revision ? ` rev ${productionPo.revision}` : ''} · {productionPo.quantity} pcs · required by {formatDate(productionPo.requiredDate)}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {productionPo.actions.includes('EDIT') && (
            <Button variant="contained" onClick={() => setDialog('edit')}>
              Correct &amp; resubmit
            </Button>
          )}
          {productionPo.actions.includes('RETURN') && <Button onClick={() => setDialog('return')}>Return to customer</Button>}
          {productionPo.actions.includes('CREATE_PROJECT') && (
            <Button variant="contained" onClick={() => setDialog('create')}>
              Create OFP project
            </Button>
          )}
        </Stack>
      </Stack>

      {productionPo.status === 'RETURNED' && productionPo.returnComment && <Alert severity="warning">Returned for correction: {productionPo.returnComment}</Alert>}
      {productionPo.projectId != null && (
        <Alert severity="success">
          Accepted. OFP project{' '}
          <Link component={RouterLink} to={`/npd/${productionPo.projectId}`}>
            {productionPo.projectNumber}
          </Link>{' '}
          was created from this production order.
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
            title="Production PO"
            facts={[
              ['Production PO', productionPo.productionPoNumber],
              ['Customer PO number', productionPo.customerPoNumber],
              ['Quantity', String(productionPo.quantity)],
              ['Required by', formatDate(productionPo.requiredDate)],
              ['Submitted', formatDate(productionPo.createdAt)],
            ]}
          />
          <Facts
            title="Part"
            facts={[
              ['Part number', productionPo.partNumber],
              ['Revision', productionPo.revision ?? '—'],
              [
                'Sample project',
                productionPo.sourceNpdNumber ? (
                  <Link key="sample" component={RouterLink} to={`/npd/${productionPo.sourceProjectId}`}>
                    {productionPo.sourceNpdNumber}
                  </Link>
                ) : (
                  '—'
                ),
              ],
              ['Description', productionPo.description ?? '—'],
            ]}
          />
          <Facts title="Remarks" facts={[['', productionPo.remarks ?? '—']]} />
        </Box>
      )}
      {tab === 'audit' && ergo && <ProductionPoHistory id={productionPo.id} />}

      {dialog === 'edit' && <EditProductionPoDialog productionPo={productionPo} onClose={() => setDialog(null)} />}
      {dialog === 'create' && <CreateOfpProjectDialog productionPo={productionPo} onClose={() => setDialog(null)} />}
      {dialog === 'return' && (
        <CommentDialog
          title={`Return ${productionPo.productionPoNumber} to the customer`}
          label="What must the customer correct?"
          required
          confirmLabel="Return production PO"
          intro="The customer sees this comment and can correct and resubmit the production order."
          busy={returning.isLoading}
          error={returning.error}
          onConfirm={(comment) => void doReturn(comment)}
          onClose={() => setDialog(null)}
        />
      )}
    </Stack>
  )
}

function ProductionPoHistory({ id }: { id: number }) {
  const { data = [], error } = useProductionPoHistoryQuery(id)
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <AuditTrail rows={data} />
}
