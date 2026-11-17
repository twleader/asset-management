export const MAX_RAW_MIME_BYTES = 512 * 1024
export const MAX_SOURCE_MESSAGE_ID_LENGTH = 200

const FILE_VALIDATION_MESSAGES = Object.freeze({
  missing: '請選擇 .eml 檔案',
  extension: '只接受 .eml 檔案',
  empty: '檔案內容不可為空',
  tooLarge: '原始郵件不得超過 512 KiB',
  unreadable: '無法讀取此檔案，請重新選擇'
})

export function validateEmlFile(file) {
  if (!file) return { valid: false, reason: 'missing', message: FILE_VALIDATION_MESSAGES.missing }
  if (!/\.eml$/i.test(String(file.name || ''))) {
    return { valid: false, reason: 'extension', message: FILE_VALIDATION_MESSAGES.extension }
  }
  if (!Number.isFinite(file.size) || file.size <= 0) {
    return { valid: false, reason: 'empty', message: FILE_VALIDATION_MESSAGES.empty }
  }
  if (file.size > MAX_RAW_MIME_BYTES) {
    return { valid: false, reason: 'tooLarge', message: FILE_VALIDATION_MESSAGES.tooLarge }
  }
  return { valid: true, reason: null, message: '' }
}

function asBytes(input) {
  if (input instanceof Uint8Array) return input
  if (input instanceof ArrayBuffer) return new Uint8Array(input)
  if (ArrayBuffer.isView(input)) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength)
  }
  throw new TypeError('Expected raw MIME bytes')
}

function bytesToLatin1(bytes) {
  const chunkSize = 0x2000
  let value = ''
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    value += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize))
  }
  return value
}

/**
 * Reads only the RFC-style header block. This is a convenience prefill, not a
 * source-authentication check; the user must still make both attestations.
 */
export function extractMessageIdHeader(input) {
  let raw
  try {
    raw = bytesToLatin1(asBytes(input))
  } catch {
    return ''
  }

  const separator = /\r?\n\r?\n|\n\n|\r\r/.exec(raw)
  if (!separator) return ''
  const headers = raw.slice(0, separator.index).replace(/\r?\n[\t ]+/g, ' ')
  const messageIdLines = headers.match(/^Message-ID\s*:\s*.*$/gim) || []
  if (messageIdLines.length !== 1) return ''

  const value = messageIdLines[0].replace(/^Message-ID\s*:\s*/i, '').trim()
  if (!value || value.length > MAX_SOURCE_MESSAGE_ID_LENGTH || /[\u0000\r\n]/.test(value)) return ''
  return value
}

export function bytesToBase64(input) {
  const bytes = asBytes(input)
  if (typeof globalThis.btoa !== 'function') throw new Error('此瀏覽器不支援安全的 Base64 編碼')
  return globalThis.btoa(bytesToLatin1(bytes))
}

export async function readEmlFile(file) {
  const validation = validateEmlFile(file)
  if (!validation.valid) throw new Error(validation.message)
  if (typeof file.arrayBuffer !== 'function') throw new Error(FILE_VALIDATION_MESSAGES.unreadable)

  let buffer
  try {
    buffer = await file.arrayBuffer()
  } catch {
    throw new Error(FILE_VALIDATION_MESSAGES.unreadable)
  }

  const bytes = new Uint8Array(buffer)
  if (bytes.length === 0) throw new Error(FILE_VALIDATION_MESSAGES.empty)
  // Recheck the bytes actually read rather than relying only on the input's size metadata.
  if (bytes.length > MAX_RAW_MIME_BYTES) throw new Error(FILE_VALIDATION_MESSAGES.tooLarge)
  return {
    rawMimeBase64: bytesToBase64(bytes),
    suggestedMessageId: extractMessageIdHeader(bytes),
    byteLength: bytes.length
  }
}

export function createTradeReportImportDraft() {
  return {
    fileName: '',
    rawMimeBase64: '',
    sourceMessageId: '',
    attestedAuthenticReport: false,
    attestedOwnerAccount: false,
    reading: false,
    previewing: false,
    confirming: false,
    applying: false,
    preview: null,
    fileError: '',
    previewError: '',
    applyError: '',
    revision: 0
  }
}

/** Clears every fact derived from a previous input set and advances its revision. */
export function invalidateTradeReportPreview(draft) {
  draft.preview = null
  draft.previewing = false
  draft.previewError = ''
  draft.applyError = ''
  draft.revision = Number(draft.revision || 0) + 1
}

export function canPreviewTradeReport(draft) {
  return Boolean(
    draft?.rawMimeBase64
    && String(draft.sourceMessageId || '').trim()
    && String(draft.sourceMessageId || '').trim().length <= MAX_SOURCE_MESSAGE_ID_LENGTH
    && draft.attestedAuthenticReport
    && draft.attestedOwnerAccount
    && !draft.reading
    && !draft.previewing
    && !draft.confirming
    && !draft.applying
  )
}

/** Display projection is owned by the BFF; do not re-derive business conflicts here. */
export function getTradeReportConflictRows(preview) {
  return Array.isArray(preview?.conflictRows) ? preview.conflictRows : []
}

/** The BFF decides eligibility; the dialog adds only local busy/confirmation state. */
export function canApplyTradeReportPreview(preview) {
  return preview?.canApply === true
}

export function importStatusLabel(status) {
  return ({
    READY: '可匯入',
    NEW: '待匯入',
    ALREADY_IMPORTED: '已匯入，待確認來源紀錄',
    IMPORTED: '已匯入',
    CONFLICT: '衝突，未寫入'
  })[status] || status || '—'
}

/** Text-only projection of backend fee provenance; never infers a fee or affects import eligibility. */
export function feeProvenanceLabel(provenance) {
  if (!provenance || typeof provenance !== 'object') return ''
  if (provenance.status === 'DERIVED') {
    const asOf = typeof provenance.asOf === 'string' && provenance.asOf.trim()
      ? provenance.asOf
      : '時間未知'
    return `依官方交割與訂單資料推導（截至 ${asOf}），非原始郵件提供`
  }
  if (provenance.status === 'UNAVAILABLE') {
    const reason = ({
      ORDER_IDENTITY_UNVERIFIED: '無法唯一核對官方訂單',
      SETTLEMENT_UNAVAILABLE: '最新官方交割資料不可用',
      SETTLEMENT_AMBIGUOUS: '官方交割資料有歧義',
      FEE_COMPONENTS_UNVERIFIED: '官方費用組成未能驗證'
    })[provenance.reason] || '官方交割／訂單證據不足或無法驗證'
    return `未能安全推導費用：${reason}`
  }
  if (provenance.status === 'RECORDED') return '既有匯入記錄，無官方推導證據'
  return '費用來源狀態未提供'
}

export function conflictReasonLabel(reason) {
  return ({
    POSSIBLE_DUPLICATE: '帳本已有同日、同標的、同方向的其他交易，請先核對既有紀錄。',
    LEDGER_CHANGED: '先前匯入的交易已被修改或刪除；本次未覆寫或重新新增。請檢查既有交易紀錄。',
    SOURCE_MESSAGE_ID_REUSED: '相同郵件識別碼已對應不同原始內容，請核對郵件，不可覆蓋。',
    MESSAGE_CONTENT_CHANGED: '相同郵件識別碼已對應不同原始內容，請核對郵件，不可覆蓋。',
    DUPLICATE_REPORT_ROW: '回報中有重複的成交列，請先核對原始資料。',
    SOURCE_FACTS_CHANGED: '此成交的原始事實與既有匯入紀錄不同，請先核對交易帳本。'
  })[reason] || (reason ? `衝突原因：${reason}` : '請先核對既有交易紀錄。')
}
