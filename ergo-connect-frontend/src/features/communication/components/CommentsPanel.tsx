import { Alert, LinearProgress, Stack, Typography } from '@mui/material'
import { problemMessage } from '../../../shared/problem'
import { useCommentThreadQuery, type CommentLinkedModule } from '../communicationApi'
import { CommentCard } from './CommentCard'
import { CommentComposer } from './CommentComposer'

interface Props {
  linkedModule: CommentLinkedModule
  recordId: number
}

/**
 * The comment threads of one record (SRS §12.12.1, wireframe "Bottom: Timeline + Comments"), embedded on each record's
 * own screen. The backend returns only the threads the caller may read; a record the caller cannot see answers 404.
 */
export function CommentsPanel({ linkedModule, recordId }: Props) {
  // Other people comment from their own screens, which this one cannot see: always refetch when it is opened.
  // currentData, not data: while another record's threads load, `data` still holds the previous record's, with live buttons.
  const { currentData, isFetching, error } = useCommentThreadQuery({ linkedModule, linkedRecordId: recordId }, { refetchOnMountOrArgChange: true })
  const threads = currentData ?? []
  const isLoading = isFetching && currentData === undefined
  const openQueries = threads.filter((t) => t.query && !t.resolved).length

  return (
    <Stack spacing={2}>
      {isLoading && <LinearProgress />}
      {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
      {openQueries > 0 && <Alert severity="warning">{openQueries === 1 ? '1 open query' : `${openQueries} open queries`} on this record.</Alert>}
      {!isLoading && error == null && threads.length === 0 && <Typography color="text.secondary">No comments yet.</Typography>}
      {threads.map((thread) => (
        <CommentCard key={thread.id} comment={thread} />
      ))}
      {error == null && (
        <Stack spacing={1}>
          <Typography variant="h6" component="h3">
            Add a comment
          </Typography>
          <CommentComposer key={`${linkedModule}-${recordId}`} linkedModule={linkedModule} recordId={recordId} onDone={() => undefined} />
        </Stack>
      )}
    </Stack>
  )
}

/** The panel under a heading, for record screens that are one long page rather than tabs. */
export function CommentsSection(props: Props) {
  return (
    <Stack spacing={1}>
      <Typography variant="h6" component="h2">
        Comments
      </Typography>
      <CommentsPanel {...props} />
    </Stack>
  )
}
