import { Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, TextField, Typography } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useAppSelector } from '../../../app/store'
import type { CompanyType } from '@/features/auth/store/authSlice'
import { VISIBILITY } from '@/features/rfq/utils/labels'
import type { Visibility } from '@/features/rfq/services/rfqApi'
import {
  useDocumentCategoriesQuery,
  useEditDocumentMutation,
  useReplaceDocumentMutation,
  type DocumentDetail,
  type DocumentEditForm,
} from '@/features/documents/services/documentsApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** Mirrors DocumentService.edit (security review H1): ERGO may choose any visibility; a customer or supplier may
 * only set its own party's value. Convenience only, the backend enforces it. */
function visibilityOptions(companyType: CompanyType | undefined): Visibility[] {
  if (companyType === 'ERGO') return ['INTERNAL', 'CUSTOMER', 'SUPPLIER', 'ALL']
  return companyType === 'CUSTOMER' ? ['CUSTOMER'] : ['SUPPLIER']
}

function CategoryField({ value, onChange, allowNone }: { value: number | ''; onChange: (v: number | '') => void; allowNone: boolean }) {
  const { data: categories = [] } = useDocumentCategoriesQuery()
  return (
    <TextField select label="Category" value={value} onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}>
      {allowNone && <MenuItem value="">{value === '' ? 'No change' : 'Keep current'}</MenuItem>}
      {categories.map((c) => (
        <MenuItem key={c.id} value={c.id}>
          {c.name}
        </MenuItem>
      ))}
    </TextField>
  )
}

/** SRS §10.9 edit: category and visibility. Only the fields that actually changed are sent (the PATCH is partial). */
export function EditDocumentDialog({ document, onClose }: { document: DocumentDetail; onClose: () => void }) {
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const [edit, editing] = useEditDocumentMutation()
  const options = visibilityOptions(companyType)
  const [categoryId, setCategoryId] = useState<number | ''>(document.categoryId ?? '')
  const [visibility, setVisibility] = useState<Visibility>(document.visibility)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const form: DocumentEditForm = {}
    if (categoryId !== '' && categoryId !== document.categoryId) form.documentCategoryId = categoryId
    if (visibility !== document.visibility) form.visibility = visibility
    if (Object.keys(form).length === 0) return onClose()
    await edit({ id: document.id, form }).unwrap().then(onClose, () => undefined)
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={submit}>
        <DialogTitle>Edit document</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          <CategoryField value={categoryId} onChange={setCategoryId} allowNone={false} />
          <TextField select label="Visible to" value={visibility} onChange={(e) => setVisibility(e.target.value as Visibility)}>
            {/* An ERGO-set value outside the caller's own options stays selectable only as the current value. */}
            {[...new Set([document.visibility, ...options])].map((v) => (
              <MenuItem key={v} value={v} disabled={!options.includes(v)}>
                {VISIBILITY[v]}
              </MenuItem>
            ))}
          </TextField>
          {companyType !== 'ERGO' && (
            <Typography variant="body2" color="text.secondary">
              Only ERGO can share a document with anyone beyond your own side.
            </Typography>
          )}
          {editing.error != null && <ProblemAlert error={editing.error} />}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" loading={editing.isLoading}>
            Save
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

/** Uploading a new version supersedes this one; the old file is kept in the version history. */
/** onClose receives the new version's id after a successful upload, so the page can move to it. */
export function ReplaceDocumentDialog({ document, isErgo, onClose }: { document: DocumentDetail; isErgo: boolean; onClose: (newVersionId?: number) => void }) {
  const [replace, replacing] = useReplaceDocumentMutation()
  const [file, setFile] = useState<File | null>(null)
  const [categoryId, setCategoryId] = useState<number | ''>('')

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!file) return
    await replace({ id: document.id, file, documentCategoryId: categoryId === '' ? undefined : categoryId })
      .unwrap()
      .then((created) => onClose(created.id), () => undefined)
  }

  return (
    <Dialog open onClose={() => onClose()} fullWidth maxWidth="sm">
      <form onSubmit={submit}>
        <DialogTitle>Upload a new version</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          <Typography color="text.secondary">
            Version {document.versionNo + 1} will replace {document.fileName}; the current file stays in the version history.
            {!isErgo && ' The new version is visible only on your side until ERGO reviews it.'}
          </Typography>
          <Button variant="outlined" component="label">
            {file ? file.name : 'Choose file'}
            <input type="file" hidden required aria-label="New version file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </Button>
          <CategoryField value={categoryId} onChange={setCategoryId} allowNone />
          {replacing.error != null && <ProblemAlert error={replacing.error} />}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => onClose()}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={!file} loading={replacing.isLoading}>
            Upload version
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
