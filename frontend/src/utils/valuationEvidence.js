const LEGACY_MESSAGE = '舊快照未含逐分量證據'

const STATUS = Object.freeze({
  AVAILABLE: { label: '可用', tagType: 'success' },
  MISSING: { label: '缺漏', tagType: 'warning' },
  STALE: { label: '已過期', tagType: 'danger' },
  NOT_APPLICABLE: { label: '不適用', tagType: 'info' }
})

const COMPONENTS = Object.freeze([
  Object.freeze({ key: 'pe', label: 'PE', evidenceKey: 'peEvidence', format: 'MULTIPLE' }),
  Object.freeze({ key: 'pb', label: 'PB', evidenceKey: 'pbEvidence', format: 'MULTIPLE' }),
  Object.freeze({ key: 'dividend_yield', label: '殖利率', evidenceKey: 'dividendYieldEvidence', format: 'PERCENT' })
])

function own(object, key) {
  return object != null && Object.prototype.hasOwnProperty.call(object, key)
}

function emptyProjection(definition) {
  return {
    key: definition.key,
    label: definition.label,
    format: definition.format,
    value: null,
    percentile: null,
    status: 'LEGACY',
    statusLabel: LEGACY_MESSAGE,
    tagType: 'info',
    provider: null,
    sourceUrls: [],
    availableAt: null,
    asOf: null,
    missingReason: null,
    loss: null,
    legacy: true,
    legacyMessage: LEGACY_MESSAGE
  }
}

/**
 * PE／PB／殖利率的唯一畫面 projection。
 *
 * 數值與 provenance 只取各自 *Evidence；適用狀態與原因只取
 * VALUATION evidence group 的同名 component。不讀 generic valuation provenance，
 * 也不重算 freshness／percentile／coverage。
 */
export function projectValuationEvidence(fundamental, radarEvidence) {
  const groupComponents = radarEvidence?.evidenceGroups?.VALUATION?.components
  const components = Array.isArray(groupComponents) ? groupComponents : null

  return COMPONENTS.map(definition => {
    const component = components?.find(item => item?.name === definition.key)
    if (!component || !own(fundamental, definition.evidenceKey)) {
      return emptyProjection(definition)
    }

    const rawEvidence = fundamental[definition.evidenceKey]
    const evidence = rawEvidence && typeof rawEvidence === 'object' ? rawEvidence : null
    const status = component.applicability ?? null
    const statusPresentation = STATUS[status] || { label: status || '狀態未標示', tagType: 'info' }

    return {
      key: definition.key,
      label: definition.label,
      format: definition.format,
      value: evidence?.value ?? null,
      percentile: evidence?.percentile ?? null,
      status,
      statusLabel: statusPresentation.label,
      tagType: statusPresentation.tagType,
      provider: evidence?.provider ?? null,
      sourceUrls: Array.isArray(evidence?.sourceUrls) ? [...evidence.sourceUrls] : [],
      availableAt: evidence?.availableAt ?? null,
      asOf: evidence?.asOf ?? null,
      missingReason: component.missingReason ?? null,
      loss: evidence == null ? null : Boolean(evidence.loss),
      legacy: false,
      legacyMessage: null
    }
  })
}

export { LEGACY_MESSAGE }
