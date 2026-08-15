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

/**
 * source_urls 不保證每一項都是可導覽的網址。SEC_DERIVED（Requirement 74）的每一列都會多帶一個
 * derived://... 標記，用來標明「這是由官方季報推導出來的值、不是觀測到的公告值」。
 * 把它一併 render 成 <a href> 會做出一個看起來像官方來源、點下去卻是死連結的「來源 N」。
 * 故在這裡就分成兩堆：可導覽的走超連結，其餘一律以純文字標籤呈現（不得直接丟掉——那等於把推導性質
 * 的揭露也一起丟掉）。
 */
function isNavigable(url) {
  return typeof url === 'string' && /^https?:\/\//i.test(url.trim())
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
    sourceLinks: [],
    sourceNotes: [],
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
    const sourceUrls = Array.isArray(evidence?.sourceUrls) ? [...evidence.sourceUrls] : []

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
      sourceUrls,
      sourceLinks: sourceUrls.filter(isNavigable),
      sourceNotes: sourceUrls.filter(url => !isNavigable(url)).map(url => String(url)),
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
