import { Autocomplete, TextField } from '@mui/material'
import { useState } from 'react'
import { MAX_MENTIONS } from '@/features/communication/utils/labels'
import { useMentionableQuery, type CommentLinkedModule, type CommentType, type MentionOption } from '@/features/communication/services/communicationApi'

interface Props {
  linkedModule: CommentLinkedModule
  recordId: number
  /** The audience the comment will have; the backend lists only people who can read it. Omitted for external callers. */
  commentType?: CommentType
  value: MentionOption[]
  onChange: (value: MentionOption[]) => void
  disabled?: boolean
}

/**
 * Mention picker (SRS §12.12.1). The candidate list is only fetched once the field is opened: the backend checks every
 * candidate against the record's scope, which is more work than a page load should pay for. The list already holds only
 * people who can read the record and this comment, and the backend rejects anyone else on save.
 */
export function MentionPicker({ linkedModule, recordId, commentType, value, onChange, disabled }: Props) {
  const [wanted, setWanted] = useState(false)
  const { data: options = [], isFetching } = useMentionableQuery(
    { linkedModule, linkedRecordId: recordId, commentType },
    { skip: !wanted },
  )
  return (
    <Autocomplete
      multiple
      size="small"
      disabled={disabled}
      options={options}
      value={value}
      loading={isFetching}
      onOpen={() => setWanted(true)}
      onChange={(_e, next) => onChange(next.slice(0, MAX_MENTIONS))}
      getOptionLabel={(option) => option.name}
      isOptionEqualToValue={(a, b) => a.userId === b.userId}
      noOptionsText="Nobody else can read this comment"
      renderInput={(params) => (
        <TextField {...params} label="Mention people" helperText={`Up to ${MAX_MENTIONS}. Only people who can read this comment are listed.`} />
      )}
    />
  )
}
