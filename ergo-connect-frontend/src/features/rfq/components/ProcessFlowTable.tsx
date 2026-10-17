import {
  Button,
  IconButton,
  MenuItem,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
} from '@mui/material'
import type { MasterOption } from '@/utils/masterData'
import { newStep, type StepRow } from '@/features/rfq/utils/processFlow'

interface Props {
  steps: StepRow[]
  processes: MasterOption[]
  onChange: (steps: StepRow[]) => void
}

// SRS §5.5: expandable rows starting with 2, "add row" at the bottom left.
export function ProcessFlowTable({ steps, processes, onChange }: Props) {
  const update = (index: number, patch: Partial<StepRow>) =>
    onChange(steps.map((step, i) => (i === index ? { ...step, ...patch } : step)))

  return (
    <>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell sx={{ width: 40 }}>#</TableCell>
              <TableCell sx={{ minWidth: 180 }}>Process</TableCell>
              <TableCell sx={{ minWidth: 200 }}>Process step</TableCell>
              <TableCell sx={{ width: 130 }}>Cycle time (s)</TableCell>
              <TableCell sx={{ width: 160 }}>In-house / outsourced</TableCell>
              <TableCell sx={{ minWidth: 170 }}>Sub-supplier (if outsourced)</TableCell>
              <TableCell sx={{ minWidth: 160 }}>Remarks</TableCell>
              <TableCell sx={{ width: 48 }} />
            </TableRow>
          </TableHead>
          <TableBody>
            {steps.map((step, index) => (
              <TableRow key={step.key}>
                <TableCell>{index + 1}</TableCell>
                <TableCell>
                  <TextField
                    select
                    size="small"
                    fullWidth
                    value={step.processId}
                    slotProps={{ htmlInput: { 'aria-label': `Process of step ${index + 1}` } }}
                    onChange={(event) => {
                      const process = processes.find((p) => String(p.id) === event.target.value)
                      update(index, { processId: event.target.value, stepName: step.stepName || process?.name || '' })
                    }}
                  >
                    <MenuItem value="">—</MenuItem>
                    {processes.map((p) => (
                      <MenuItem key={p.id} value={String(p.id)}>
                        {p.name}
                      </MenuItem>
                    ))}
                  </TextField>
                </TableCell>
                <TableCell>
                  <TextField
                    size="small"
                    fullWidth
                    value={step.stepName}
                    onChange={(event) => update(index, { stepName: event.target.value })}
                    slotProps={{ htmlInput: { maxLength: 200, 'aria-label': `Step ${index + 1} name` } }}
                  />
                </TableCell>
                <TableCell>
                  <TextField
                    size="small"
                    type="number"
                    value={step.cycleTimeSeconds}
                    onChange={(event) => update(index, { cycleTimeSeconds: event.target.value })}
                    slotProps={{ htmlInput: { min: 0, step: 'any', 'aria-label': `Step ${index + 1} cycle time` } }}
                  />
                </TableCell>
                <TableCell>
                  <TextField
                    select
                    size="small"
                    fullWidth
                    value={step.sourcingType}
                    onChange={(event) => update(index, { sourcingType: event.target.value as StepRow['sourcingType'] })}
                    slotProps={{ htmlInput: { 'aria-label': `Step ${index + 1} sourcing` } }}
                  >
                    <MenuItem value="">—</MenuItem>
                    <MenuItem value="INHOUSE">In-house</MenuItem>
                    <MenuItem value="OUTSOURCED">Outsourced</MenuItem>
                  </TextField>
                </TableCell>
                <TableCell>
                  <TextField
                    size="small"
                    fullWidth
                    disabled={step.sourcingType !== 'OUTSOURCED'}
                    value={step.outsourcedSupplierName}
                    onChange={(event) => update(index, { outsourcedSupplierName: event.target.value })}
                    slotProps={{ htmlInput: { maxLength: 200, 'aria-label': `Step ${index + 1} sub-supplier` } }}
                  />
                </TableCell>
                <TableCell>
                  <TextField
                    size="small"
                    fullWidth
                    value={step.remarks}
                    onChange={(event) => update(index, { remarks: event.target.value })}
                    slotProps={{ htmlInput: { maxLength: 500, 'aria-label': `Step ${index + 1} remarks` } }}
                  />
                </TableCell>
                <TableCell>
                  <Tooltip title="Remove step">
                    <IconButton aria-label={`Remove step ${index + 1}`} onClick={() => onChange(steps.filter((_, i) => i !== index))}>
                      ×
                    </IconButton>
                  </Tooltip>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <Button sx={{ alignSelf: 'flex-start' }} onClick={() => onChange([...steps, newStep()])}>
        + Add step
      </Button>
    </>
  )
}
