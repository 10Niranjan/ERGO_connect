import { Alert, Box, Button, Checkbox, Chip, Dialog as MuiDialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material'
import { useRef, useState, type FormEvent } from 'react'
import { useAppSelector } from '../../../app/store'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { saveObjectUrl } from '../../../shared/download'
import { formatSize } from '../../documents/labels'
import { problemMessage } from '../../../shared/problem'
import {
  useAttachToCommentMutation,
  useDeescalateCommentMutation,
  useDownloadCommentAttachmentMutation,
  useEditCommentMutation,
  useEscalateCommentMutation,
  useReopenCommentMutation,
  useRemoveCommentAttachmentMutation,
  useResolveCommentMutation,
  type CommentAction,
  type CommentView,
  type MentionOption,
} from '../communicationApi'
import { EXTERNAL_TYPE_LABEL, MAX_ATTACHMENTS, MAX_COMMENT_LENGTH, TYPE_LABEL, formatDateTime } from '../labels'
import { CommentComposer } from './CommentComposer'
import { MentionPicker } from './MentionPicker'

type Dialog = 'resolve' | 'reopen' | 'escalate' | 'deescalate'

/** Reason dialogs: resolve and escalate take an optional comment, reopening a query and lowering an escalation need a reason (CLAUDE.md non-negotiable). */
const DIALOGS: Record<Dialog, { title: string; label: string; required: boolean; confirm: string; intro?: string }> = {
  resolve: { title: 'Resolve query', label: 'Comment (optional)', required: false, confirm: 'Resolve' },
  reopen: { title: 'Reopen query', label: 'Reason', required: true, confirm: 'Reopen' },
  escalate: { title: 'Escalate thread', label: 'Comment (optional)', required: false, confirm: 'Escalate', intro: 'Marks this thread for attention. Everyone who can read it will see that it is escalated.' },
  deescalate: { title: 'Lower escalation', label: 'Reason', required: true, confirm: 'Lower escalation' },
}

interface Props {
  comment: CommentView
  /** A reply has no replies of its own and cannot be replied to. */
  isReply?: boolean
}

/**
 * One comment with its replies (SRS §12.12.1). Every button comes from the backend's `actions` list, computed by the
 * same checks that enforce them; the card never decides what the caller may do.
 */
export function CommentCard({ comment, isReply }: Props) {
  const isErgo = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'
  const can = (action: CommentAction) => comment.actions.includes(action)
  const [editing, setEditing] = useState(false)
  const [replying, setReplying] = useState(false)
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const [removing, setRemoving] = useState<{ id: number; fileName: string } | null>(null)
  const [download] = useDownloadCommentAttachmentMutation()
  const [removeAttachment] = useRemoveCommentAttachmentMutation()
  const [attach, attaching] = useAttachToCommentMutation()
  const fileInput = useRef<HTMLInputElement>(null)

  const runners = {
    resolve: useResolveCommentMutation(),
    reopen: useReopenCommentMutation(),
    escalate: useEscalateCommentMutation(),
    deescalate: useDeescalateCommentMutation(),
  }
  const running = dialog ? runners[dialog][1] : null

  async function confirm(reason: string) {
    if (!dialog) return
    const id = comment.id
    try {
      if (dialog === 'resolve') await runners.resolve[0]({ id, reason: reason || undefined }).unwrap()
      else if (dialog === 'escalate') await runners.escalate[0]({ id, reason: reason || undefined }).unwrap()
      else if (dialog === 'reopen') await runners.reopen[0]({ id, reason }).unwrap()
      else await runners.deescalate[0]({ id, reason }).unwrap()
      setDialog(null)
    } catch {
      // the dialog shows the error from the mutation state and stays open
    }
  }

  async function save(attachmentId: number, fileName: string) {
    setActionError(null)
    try {
      saveObjectUrl(await download({ id: comment.id, documentId: attachmentId }).unwrap(), fileName)
    } catch (e) {
      setActionError(e)
    }
  }

  async function onFile(file: File | undefined) {
    if (!file) return
    setActionError(null)
    await attach({ id: comment.id, file }).unwrap().catch(setActionError)
    if (fileInput.current) fileInput.current.value = ''
  }

  const audience = isErgo ? TYPE_LABEL[comment.commentType] : { label: EXTERNAL_TYPE_LABEL, color: 'default' as const }
  const mentions = comment.mentions.map((m) => m.name)

  return (
    <Box>
      <Paper variant="outlined" sx={{ p: 2, borderColor: comment.escalated ? 'error.main' : undefined }}>
        <Stack spacing={1}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
            <Typography sx={{ fontWeight: 600 }}>
              {comment.author ?? 'Unknown'}
              {comment.mine && ' (you)'}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {formatDateTime(comment.createdAt)}
            </Typography>
            {!isReply && <Chip size="small" label={audience.label} color={audience.color} variant="outlined" />}
            {comment.query &&
              (comment.resolved ? (
                <Tooltip title={`Resolved${comment.resolvedBy ? ` by ${comment.resolvedBy}` : ''}${comment.resolvedAt ? ` on ${formatDateTime(comment.resolvedAt)}` : ''}`}>
                  <Chip size="small" label="Resolved" color="success" />
                </Tooltip>
              ) : (
                <Chip size="small" label="Open query" color="warning" />
              ))}
            {comment.escalated && (
              <Tooltip title={`Escalated${comment.escalatedBy ? ` by ${comment.escalatedBy}` : ''}${comment.escalatedAt ? ` on ${formatDateTime(comment.escalatedAt)}` : ''}`}>
                <Chip size="small" label="Escalated" color="error" />
              </Tooltip>
            )}
          </Stack>

          {editing ? (
            <EditForm comment={comment} onDone={() => setEditing(false)} />
          ) : (
            <Typography sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{comment.text}</Typography>
          )}

          {!editing && mentions.length > 0 && (
            <Typography variant="body2" color="text.secondary">
              Mentioned: {mentions.join(', ')}
            </Typography>
          )}

          {(comment.attachments.length > 0 || can('ATTACH')) && (
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
              {comment.attachments.map((a) => (
                <Chip
                  key={a.id}
                  size="small"
                  variant="outlined"
                  label={`${a.fileName} (${formatSize(a.size)})`}
                  onClick={() => void save(a.id, a.fileName)}
                  onDelete={can('ATTACH') ? () => setRemoving({ id: a.id, fileName: a.fileName }) : undefined}
                />
              ))}
              {can('ATTACH') && comment.attachments.length < MAX_ATTACHMENTS && (
                <>
                  <Button size="small" loading={attaching.isLoading} onClick={() => fileInput.current?.click()}>
                    Add file
                  </Button>
                  <input ref={fileInput} type="file" hidden aria-label="File to attach" onChange={(e) => void onFile(e.target.files?.[0])} />
                </>
              )}
            </Stack>
          )}

          {actionError != null && <Alert severity="error">{problemMessage(actionError)}</Alert>}

          {!editing && (
            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }} useFlexGap>
              {can('REPLY') && !replying && (
                <Button size="small" onClick={() => setReplying(true)}>
                  Reply
                </Button>
              )}
              {can('EDIT') && (
                <Button size="small" onClick={() => setEditing(true)}>
                  Edit
                </Button>
              )}
              {can('RESOLVE') && (
                <Button size="small" onClick={() => setDialog('resolve')}>
                  Resolve
                </Button>
              )}
              {can('REOPEN') && (
                <Button size="small" onClick={() => setDialog('reopen')}>
                  Reopen
                </Button>
              )}
              {can('ESCALATE') && (
                <Button size="small" color="error" onClick={() => setDialog('escalate')}>
                  Escalate
                </Button>
              )}
              {can('DEESCALATE') && (
                <Button size="small" onClick={() => setDialog('deescalate')}>
                  Lower escalation
                </Button>
              )}
            </Stack>
          )}
        </Stack>
      </Paper>

      {(comment.replies.length > 0 || replying) && (
        <Stack spacing={1.5} sx={{ mt: 1.5, ml: { xs: 1.5, md: 4 }, pl: 2, borderLeft: 2, borderColor: 'divider' }}>
          {comment.replies.map((reply) => (
            <CommentCard key={reply.id} comment={reply} isReply />
          ))}
          {replying && (
            <CommentComposer
              linkedModule={comment.linkedModule}
              recordId={comment.linkedRecordId}
              parent={comment}
              onDone={() => setReplying(false)}
              onCancel={() => setReplying(false)}
            />
          )}
        </Stack>
      )}

      {removing && (
        <MuiDialog open onClose={() => setRemoving(null)} fullWidth maxWidth="xs">
          <DialogTitle>Remove attachment</DialogTitle>
          <DialogContent>
            <Typography>Remove {removing.fileName} from this comment?</Typography>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setRemoving(null)}>Cancel</Button>
            <Button
              color="error"
              variant="contained"
              onClick={() => {
                setActionError(null)
                void removeAttachment({ id: comment.id, documentId: removing.id })
                  .unwrap()
                  .catch(setActionError)
                  .finally(() => setRemoving(null))
              }}
            >
              Remove
            </Button>
          </DialogActions>
        </MuiDialog>
      )}

      {dialog && running && (
        <CommentDialog
          title={DIALOGS[dialog].title}
          label={DIALOGS[dialog].label}
          required={DIALOGS[dialog].required}
          confirmLabel={DIALOGS[dialog].confirm}
          intro={DIALOGS[dialog].intro}
          danger={dialog === 'escalate'}
          busy={running.isLoading}
          error={running.error}
          onConfirm={(reason) => void confirm(reason)}
          onClose={() => setDialog(null)}
        />
      )}
    </Box>
  )
}

/** Author-only inline edit (backend: until someone replies or the query is resolved). Text, query flag and mentions are replaced together. */
function EditForm({ comment, onDone }: { comment: CommentView; onDone: () => void }) {
  const [edit, editing] = useEditCommentMutation()
  const [text, setText] = useState(comment.text)
  const [query, setQuery] = useState(comment.query)
  // Mentions the viewer can see an id for; an ERGO mention hidden from an external viewer cannot occur on a comment they wrote.
  const [mentions, setMentions] = useState<MentionOption[]>(
    comment.mentions.flatMap((m) => (m.userId == null ? [] : [{ userId: m.userId, name: m.name }])),
  )
  const tooLong = text.length > MAX_COMMENT_LENGTH

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!text.trim() || tooLong) return
    await edit({ id: comment.id, form: { text: text.trim(), query, mentionedUserIds: mentions.map((m) => m.userId) } })
      .unwrap()
      .then(onDone, () => undefined)
  }

  return (
    <form onSubmit={submit}>
      <Stack spacing={1.5}>
        <TextField
          label="Comment"
          value={text}
          onChange={(e) => setText(e.target.value)}
          multiline
          minRows={3}
          required
          autoFocus
          error={tooLong}
          helperText={`${text.length}/${MAX_COMMENT_LENGTH}${tooLong ? ' - too long' : ''}`}
        />
        {comment.parentCommentId == null && (
          <FormControlLabel control={<Checkbox checked={query} onChange={(e) => setQuery(e.target.checked)} />} label="Track as an open query" />
        )}
        <MentionPicker linkedModule={comment.linkedModule} recordId={comment.linkedRecordId} commentType={comment.commentType} value={mentions} onChange={setMentions} disabled={editing.isLoading} />
        {editing.error != null && (
          <Alert severity="error">
            {problemMessage(editing.error)}
            {mentions.length > 0 && ' If someone you mentioned can no longer read this comment, remove them from the list and save again.'}
          </Alert>
        )}
        <Stack direction="row" spacing={1}>
          <Button type="submit" variant="contained" loading={editing.isLoading} disabled={!text.trim() || tooLong}>
            Save
          </Button>
          <Button onClick={onDone} disabled={editing.isLoading}>
            Cancel
          </Button>
        </Stack>
      </Stack>
    </form>
  )
}
