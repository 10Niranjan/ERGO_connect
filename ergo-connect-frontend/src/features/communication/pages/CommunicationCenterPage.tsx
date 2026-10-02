import { Alert, FormControlLabel, LinearProgress, Link, MenuItem, Stack, Switch, TablePagination, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink } from 'react-router'
import { problemMessage } from '../../../shared/problem'
import { CommentCard } from '../components/CommentCard'
import { useCommentInboxQuery, type CommentLinkedModule } from '../communicationApi'
import { MODULE_LABEL, recordPath } from '../labels'

// SRS §12.12.1 Communication Center: module-wise threaded communication across every record the caller can read, with
// internal/external tagging, mentions, attachments and the unresolved-query and escalation markers. New comments are
// written on the record's own screen (its Comments tab or section); here you read, reply, resolve and escalate.
export function CommunicationCenterPage() {
  const [linkedModule, setLinkedModule] = useState('')
  const [openQueriesOnly, setOpenQueriesOnly] = useState(false)
  const [escalatedOnly, setEscalatedOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)

  const { data, isLoading, isFetching, error } = useCommentInboxQuery(
    { linkedModule: (linkedModule || undefined) as CommentLinkedModule | undefined, openQueriesOnly, escalatedOnly, page, size },
    // Others comment from their own screens, which this cache cannot see.
    { refetchOnMountOrArgChange: true },
  )
  const threads = data?.items ?? []

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Communication
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Every conversation you can read, newest first. To start a new one, open the record and use its Comments tab.
      </Typography>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
        <TextField
          select
          size="small"
          label="Module"
          value={linkedModule}
          onChange={(e) => {
            setLinkedModule(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: 200 }}
        >
          <MenuItem value="">All</MenuItem>
          {(Object.keys(MODULE_LABEL) as CommentLinkedModule[]).map((m) => (
            <MenuItem key={m} value={m}>
              {MODULE_LABEL[m]}
            </MenuItem>
          ))}
        </TextField>
        <FormControlLabel
          control={
            <Switch
              checked={openQueriesOnly}
              onChange={(e) => {
                setOpenQueriesOnly(e.target.checked)
                setPage(0)
              }}
            />
          }
          label="Open queries only"
        />
        <FormControlLabel
          control={
            <Switch
              checked={escalatedOnly}
              onChange={(e) => {
                setEscalatedOnly(e.target.checked)
                setPage(0)
              }}
            />
          }
          label="Escalated only"
        />
      </Stack>
      {(isLoading || isFetching) && <LinearProgress />}
      {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
      {!isLoading && error == null && threads.length === 0 && <Typography color="text.secondary">No conversations match.</Typography>}
      {threads.map((thread) => (
        <Stack key={thread.id} spacing={0.5}>
          <Typography variant="subtitle2" color="text.secondary">
            <Link component={RouterLink} to={recordPath(thread.linkedModule, thread.linkedRecordId)}>
              {MODULE_LABEL[thread.linkedModule]} #{thread.linkedRecordId}
            </Link>
          </Typography>
          <CommentCard comment={thread} />
        </Stack>
      ))}
      <TablePagination
        component="div"
        count={data?.totalElements ?? 0}
        page={page}
        rowsPerPage={size}
        rowsPerPageOptions={[10, 25, 50, 100]}
        onPageChange={(_e, p) => setPage(p)}
        onRowsPerPageChange={(e) => {
          setSize(Number(e.target.value))
          setPage(0)
        }}
      />
    </Stack>
  )
}
