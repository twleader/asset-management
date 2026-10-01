const BACKUP_ROOT = 'asset-management-backup'
const CUTOVER_DATE = '2026-09-29'
const FILENAME = /^asset_(manual|daily_tw|daily_us|weekly|auto-pre-restore)_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?:_[A-Za-z0-9-]+)?\.dump$/

export function backupDateFolder(row) {
  const match = FILENAME.exec(row?.filename || '')
  if (!match) return null

  const [, kind, year, month, day, hour, minute, second] = match
  const folder = kind.startsWith('daily_') ? 'daily' : kind === 'auto-pre-restore' ? 'manual' : kind
  if (row.folder !== folder) return null

  const date = `${year}-${month}-${day}`
  const parsed = new Date(`${date}T${hour}:${minute}:${second}Z`)
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 19) !== `${date}T${hour}:${minute}:${second}`) {
    return null
  }
  return date >= CUTOVER_DATE ? date : null
}

export function backupLocation(row) {
  const date = backupDateFolder(row)
  return date ? `${BACKUP_ROOT}/${date}/` : '舊備份（未搬移）'
}
