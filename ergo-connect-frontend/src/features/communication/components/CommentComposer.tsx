import { Alert, Button, Checkbox, Chip, FormControlLabel, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useRef, useState, type FormEvent } from 'react'
import { useAppSelector } from '../../../app/store'
import { problemMessage } from '../../../shared/problem'
import {
  useAttachToCommentMutation,
  useCreateCommentMutation,
  type CommentLinkedModule,
  type CommentType,
  type CommentView,
  type MentionOption,
} from '../communicationApi'
import { MAX_ATTACHMENTS, MAX_COMMENT_LENGTH, SUPPORTED_TYPES, TYPE_LABEL } from '../labels'
import { MentionPicker } from './MentionPicker'

interface Props {
  linkedModule: CommentLinkedModule
  recordId: number
  /** Set when replying: the reply takes the parent's audience and cannot itself be a query. */
  parent?: CommentView
  onDone: () => void
  onCancel?: () => void
}

/** New comment or reply. ERGO picks the audience (defaulting to the narrowest, internal); an external user always writes to its own side. */
export function CommentComposer({ linkedModule, recordId, parent, onDone, onCancel }: Props) {
  const isErgo = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'
  const audiences = SUPPORTED_TYPES[linkedModule]
  const [audience, setAudience] = useState<CommentType>('INTERNAL')
  const [text, setText] = useState('')
  const [query, setQuery] = useState(false)
  const [mentions, setMentions] = useState<MentionOption[]>([])
  const [files, setFiles] = useState<File[]>([])
  const [warning, setWarning] = useState<string | null>(null)
  const [fileNote, setFileNote] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [create, creating] = useCreateCommentMutation()
  const [attach] = useAttachToCommentMutation()
  const [uploading, setUploading] = useState(false)

  // The audience the comment will actually have, which decides who can be mentioned.
  const effectiveType: CommentType | undefined = parent ? parent.commentType : isErgo ? audience : undefined
  const tooLong = text.length > MAX_COMMENT_LENGTH
  const busy = creating.isLoading || uploading

  function addFiles(picked: FileList | null) {
    if (!picked) return
    const all = [...files, ...Array.from(picked)]
    setFileNote(all.length > MAX_ATTACHMENTS ? `Only ${MAX_ATTACHMENTS} files fit on one comment; the rest were not added.` : null)
    setFiles(all.slice(0, MAX_ATTACHMENTS))
    if (fileInput.current) fileInput.current.value = ''
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!text.trim() || tooLong || busy) return
    setWarning(null)
    let created: CommentView
    try {
      created = await create({
        linkedModule,
        linkedRecordId: recordId,
        commentType: isErgo && !parent ? audience : undefined,
        text: text.trim(),
        parentCommentId: parent?.id,
        query: !parent && query,
        mentionedUserIds: mentions.map((m) => m.userId),
      }).unwrap()
    } catch {
      return // creating.error is shown below; nothing was posted, so the draft is kept
    }
    // The comment exists now: clear the draft, then add the files one by one.
    const pending = files
    setText('')
    setQuery(false)
    setMentions([])
    setFiles([])
    // The next comment starts from the narrowest audience again: a leftover "shared" choice must never carry an
    // internal note out to the customer or supplier.
    setAudience('INTERNAL')
    const failed: string[] = []
    setUploading(true)
    for (const file of pending) {
      try {
        await attach({ id: created.id, file }).unwrap()
      } catch {
        failed.push(file.name)
      }
    }
    setUploading(false)
    if (failed.length > 0) {
      setWarning(`Your comment was posted, but these files could not be attached: ${failed.join(', ')}. You can add them to the comment until someone replies.`)
      return
    }
    onDone()
  }

  return (
    <form onSubmit={submit}>
      <Stack spacing={1.5}>
        {parent ? (
          <Typography variant="body2" color="text.secondary">
            Your reply is visible to the same people as the comment above.
          </Typography>
        ) : isErgo ? (
          <TextField select size="small" label="Who can read this" value={audience} onChange={(e) => { setAudience(e.target.value as CommentType); setMentions([]) }} sx={{ maxWidth: 320 }}>
            {audiences.map((type) => (
              <MenuItem key={type} value={type}>
                {TYPE_LABEL[type].label}
              </MenuItem>
            ))}
          </TextField>
        ) : (
          <Typography variant="body2" color="text.secondary">
            Visible to ERGO and your company only.
          </Typography>
        )}
        <TextField
          label={parent ? 'Your reply' : 'Write a comment'}
          value={text}
          onChange={(e) => setText(e.target.value)}
          multiline
          minRows={3}
          required
          error={tooLong}
          helperText={`${text.length}/${MAX_COMMENT_LENGTH}${tooLong ? ' - too long' : ''}`}
        />
        {!parent && (
          <FormControlLabel
            control={<Checkbox checked={query} onChange={(e) => setQuery(e.target.checked)} />}
            label="This is a question that needs an answer (track it as an open query)"
          />
        )}
        <MentionPicker linkedModule={linkedModule} recordId={recordId} commentType={effectiveType} value={mentions} onChange={setMentions} disabled={busy} />
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
          <Button size="small" variant="outlined" disabled={busy || files.length >= MAX_ATTACHMENTS} onClick={() => fileInput.current?.click()}>
            Attach files
          </Button>
          <input ref={fileInput} type="file" multiple hidden aria-label="Files to attach" onChange={(e) => addFiles(e.target.files)} />
          {files.map((file, index) => (
            <Chip key={`${file.name}-${index}`} size="small" label={file.name} onDelete={() => setFiles((current) => current.filter((_f, i) => i !== index))} />
          ))}
          <Typography variant="caption" color="text.secondary">
            Up to {MAX_ATTACHMENTS} files, readable by the same people as the comment.
          </Typography>
        </Stack>
        {fileNote && <Alert severity="info">{fileNote}</Alert>}
        {creating.error != null && <Alert severity="error">{problemMessage(creating.error)}</Alert>}
        {warning && (
          <Alert severity="warning" action={<Button color="inherit" size="small" onClick={() => { setWarning(null); onDone() }}>Close</Button>}>
            {warning}
          </Alert>
        )}
        <Stack direction="row" spacing={1}>
          <Button type="submit" variant="contained" loading={busy} disabled={!text.trim() || tooLong}>
            {parent ? 'Post reply' : 'Post comment'}
          </Button>
          {onCancel && (
            <Button onClick={onCancel} disabled={busy}>
              Cancel
            </Button>
          )}
        </Stack>
      </Stack>
    </form>
  )
}
