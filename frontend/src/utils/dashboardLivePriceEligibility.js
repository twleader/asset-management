const MARKET_ZONES = {
  台股: 'Asia/Taipei',
  美股: 'America/New_York',
  英股: 'Europe/London'
}

function localDate(now, timeZone) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(now instanceof Date ? now : new Date(now))
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

export function dashboardLiveIdentityMatches(snapshot, liveAssets, selectedId, latestId) {
  return selectedId != null && latestId != null && selectedId === latestId
    && snapshot?.id != null && snapshot.id === selectedId
    && liveAssets?.snapshotId != null && liveAssets.snapshotId === selectedId
    && snapshot.snapshotDate != null && snapshot.snapshotDate === liveAssets.snapshotDate
}

/** Dashboard-only live gate. Keep SnapshotEnricher's editor contract unchanged. */
export function isDashboardLivePriceEligible(snapshot, liveAssets, selectedId, latestId, market, now = new Date()) {
  if (!dashboardLiveIdentityMatches(snapshot, liveAssets, selectedId, latestId)) return false
  const timeZone = MARKET_ZONES[market]
  if (!timeZone) return false
  const snapshotDate = snapshot.snapshotDate
  return snapshotDate === localDate(now, 'Asia/Taipei') || snapshotDate === localDate(now, timeZone)
}

/** A latest same-ID snapshot has rolled to Taipei today and needs real reads. */
export function isDashboardSnapshotRollover(snapshot, liveAssets, selectedId, latestId, now = new Date()) {
  if (selectedId == null || latestId == null || selectedId !== latestId) return false
  if (snapshot?.id == null || snapshot.id !== selectedId) return false
  if (liveAssets?.snapshotId == null || liveAssets.snapshotId !== selectedId) return false
  if (!/^\d{4}-\d{2}-\d{2}$/.test(snapshot.snapshotDate ?? '')
      || !/^\d{4}-\d{2}-\d{2}$/.test(liveAssets.snapshotDate ?? '')
      || snapshot.snapshotDate >= liveAssets.snapshotDate) return false
  return liveAssets.snapshotDate === localDate(now, 'Asia/Taipei')
}
