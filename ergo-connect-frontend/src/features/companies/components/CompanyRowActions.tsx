import { Button } from '@mui/material'
import { useState } from 'react'
import { TemporaryPasswordDialog } from '../../admin/components/TemporaryPasswordDialog'
import { UserDialog } from '../../admin/components/UserDialog'
import { useResubmitCompanyMutation, useSetCompanyActiveMutation, type Company } from '../companiesApi'
import { CompanyDialog } from './CompanyDialog'
import { ReviewDialog } from './ReviewDialog'

interface Props {
  company: Company
  onError: (error: unknown) => void
}

/**
 * The buttons of one company row, and the dialogs they open. Which buttons appear is decided by the backend
 * ({@code company.actions}), the same checks that enforce them. Shared by the Customers & suppliers page and the
 * Suppliers page, so both offer exactly the same actions.
 */
export function CompanyRowActions({ company, onError }: Props) {
  const [resubmit] = useResubmitCompanyMutation()
  const [setActive] = useSetCompanyActiveMutation()
  const [editing, setEditing] = useState(false)
  const [addingUser, setAddingUser] = useState(false)
  const [reviewing, setReviewing] = useState<'approve' | 'reject' | null>(null)
  const [issued, setIssued] = useState<{ email: string; password: string } | null>(null)
  const can = (action: Company['actions'][number]) => company.actions.includes(action)

  async function run(action: () => Promise<unknown>, question?: string) {
    // ponytail: native confirm(); switch to an MUI dialog if these need more than a yes/no
    if (question && !window.confirm(question)) return
    onError(null)
    await action().catch(onError)
  }

  return (
    <>
      {can('APPROVE') && (
        <Button size="small" onClick={() => setReviewing('approve')}>
          Approve
        </Button>
      )}
      {can('REJECT') && (
        <Button size="small" color="error" onClick={() => setReviewing('reject')}>
          Reject
        </Button>
      )}
      {can('EDIT') && (
        <Button size="small" onClick={() => setEditing(true)}>
          Edit
        </Button>
      )}
      {can('ADD_USER') && (
        <Button size="small" onClick={() => setAddingUser(true)}>
          Add user
        </Button>
      )}
      {can('RESUBMIT') && (
        <Button size="small" onClick={() => void run(() => resubmit(company.id).unwrap(), `Send ${company.profile.name} for approval again?`)}>
          Resubmit
        </Button>
      )}
      {can('DEACTIVATE') && (
        <Button
          size="small"
          color="error"
          onClick={() =>
            void run(
              () => setActive({ id: company.id, active: false }).unwrap(),
              `Deactivate ${company.profile.name}? Its users will no longer be able to sign in.`,
            )
          }
        >
          Deactivate
        </Button>
      )}
      {can('ACTIVATE') && (
        <Button size="small" onClick={() => void run(() => setActive({ id: company.id, active: true }).unwrap())}>
          Activate
        </Button>
      )}
      {editing && <CompanyDialog company={company} onClose={() => setEditing(false)} />}
      {reviewing && <ReviewDialog company={company} decision={reviewing} onClose={() => setReviewing(null)} />}
      {addingUser && (
        <UserDialog
          user={null}
          fixedCompanyId={company.id}
          onClose={() => setAddingUser(false)}
          onCreated={(email, password) => {
            setAddingUser(false)
            setIssued({ email, password })
          }}
        />
      )}
      {issued && <TemporaryPasswordDialog {...issued} onClose={() => setIssued(null)} />}
    </>
  )
}
