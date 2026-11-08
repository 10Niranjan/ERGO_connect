import InboxOutlinedIcon from '@mui/icons-material/InboxOutlined'
import { Box, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import type { ReactNode } from 'react'

export interface Column<T> {
  header: string
  align?: 'left' | 'right' | 'center'
  render: (row: T) => ReactNode
  cellSx?: (row: T) => Record<string, unknown>
}

/** A read-only summary table shared by every dashboard section (SRS §12.5/§10.11 - dashboards have no row actions). */
export function ItemsTable<T>({
  rows,
  columns,
  getKey,
  emptyMessage = 'No items.',
}: {
  rows: T[]
  columns: Column<T>[]
  getKey: (row: T) => number
  emptyMessage?: string
}) {
  if (rows.length === 0)
    return (
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 2, color: 'text.secondary' }}>
        <InboxOutlinedIcon fontSize="small" sx={{ opacity: 0.7 }} />
        <Typography variant="body2">{emptyMessage}</Typography>
      </Box>
    )

  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            {columns.map((c) => (
              <TableCell key={c.header} align={c.align}>
                {c.header}
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={getKey(r)}>
              {columns.map((c) => (
                <TableCell key={c.header} align={c.align} sx={c.cellSx?.(r)}>
                  {c.render(r)}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
