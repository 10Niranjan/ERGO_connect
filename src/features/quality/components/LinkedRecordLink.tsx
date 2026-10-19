import { Link } from '@mui/material'
import { Link as RouterLink } from 'react-router'
import { LINKED_MODULE } from '@/features/quality/utils/labels'
import type { LinkedModule } from '@/features/quality/services/qualityApi'

/** "NPD #3" as a link to the record itself; INCOMING/FINAL/IN_PROCESS checks point at no record page. */
export function LinkedRecordLink({ module, id }: { module: LinkedModule; id: number }) {
  const label = `${LINKED_MODULE[module]} #${id}`
  if (module !== 'NPD' && module !== 'OFP') return <>{label}</>
  return (
    <Link component={RouterLink} to={`/${module === 'NPD' ? 'npd' : 'ofp'}/${id}`}>
      {label}
    </Link>
  )
}
