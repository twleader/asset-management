const SAFE_AI_CODE_MESSAGES = Object.freeze({
  AI_UNAUTHORIZED: '本機工具連線驗證失敗，請重新載入狀態後再試。',
  AI_INVALID_REQUEST: '這次 AI 請求未能處理，請重新載入工具清單後再試。',
  AI_INVALID_SELECTION: '目前選擇已失效，請重新選擇工具、模型與思考程度。',
  AI_CATALOG_STALE: 'AI 模型清單已更新，請重新載入後選擇。',
  AI_TOOL_UNAVAILABLE: '此工具目前無法使用，請稍後重新載入工具清單。',
  AI_SUBSCRIPTION_REQUIRED: '尚未確認可用的既有訂閱，系統未呼叫模型。',
  AI_BILLING_UNVERIFIED: '無法確認這次使用不會產生額外費用，因此系統未呼叫模型。',
  AI_PAID_CREDITS_ENABLED: '帳戶啟用了可能計費的點數，為避免額外費用，系統未呼叫模型。',
  AI_EXTRA_USAGE_ENABLED: '帳戶啟用了額外用量，為避免額外費用，系統未呼叫模型。',
  AI_MODEL_UNAVAILABLE: '此模型目前無法使用，請重新載入工具清單。',
  AI_REMOTE_MODEL_FORBIDDEN: '已阻止連接遠端或雲端模型；請改用本機已安裝模型。',
  AI_BUSY: 'AI 工具目前正在處理其他工作，請稍後再試。',
  AI_TIMEOUT: 'AI 解讀等待逾時；情境數值仍已保留。',
  AI_EMPTY_RESPONSE: 'AI 未提供文字解讀；情境數值仍已保留。',
  AI_EXECUTION_FAILED: 'AI 解讀未成功；情境數值仍已保留。',
  AI_AUTH_REQUIRED: '無法取得此工具的安全登入資訊；若此為 Codex 工具，請先在一般 Codex CLI 登入。',
  AI_AUTH_EXPIRED: '此工具的登入已失效或需要更新；若此為 Codex 工具，請在一般 Codex CLI 重新登入。',
  AI_TOOL_USE_FORBIDDEN: 'AI 嘗試使用非解讀工具，系統已拒絕；情境數值仍已保留。',
  TOOL_NOT_FOUND: '找不到此工具，請重新載入工具清單。',
  TOOL_UNAVAILABLE: '此工具目前無法使用，請稍後重新載入工具清單。',
  TOOL_NOT_INSTALLED: '此工具尚未安裝或無法啟動。',
  MODEL_NOT_FOUND: '找不到此模型，請重新載入工具清單。',
  MODEL_UNAVAILABLE: '此模型目前無法使用，請重新載入工具清單。',
  MODEL_NOT_INSTALLED: '本機目前沒有此模型。',
  CATALOG_STALE: '模型清單已更新，請重新選擇工具與模型。',
  CATALOG_UNAVAILABLE: '目前無法讀取工具清單，請稍後重試。',
  HOST_UNAVAILABLE: '本機 AI 連線目前無法使用。',
  AUTH_REQUIRED: '工具尚未完成登入。',
  SUBSCRIPTION_REQUIRED: '此工具需要可用的訂閱。',
  BILLING_UNAVAILABLE: '目前無法確認此工具的使用方式，已停止 AI 解讀。',
  UNSUPPORTED_SELECTION: '此工具與模型組合目前無法使用，請重新選擇。',
  BUSY: 'AI 工具目前正在處理其他工作，請稍後再試。',
  TIMEOUT: 'AI 解讀等待逾時；情境數值仍已保留。',
  EXECUTION_FAILED: 'AI 解讀未成功；情境數值仍已保留。'
})

function nonBlankString(value) {
  return typeof value === 'string' && value.trim() ? value : ''
}

export function createEmptySrppAiSelection(catalogRevision = '') {
  return { toolId: '', model: '', effort: null, catalogRevision: nonBlankString(catalogRevision) }
}

export function srppAiTools(aiTools) {
  return Array.isArray(aiTools)
    ? aiTools.filter(tool => tool && typeof tool === 'object' && nonBlankString(tool.id))
    : []
}

export function srppAiModels(tool) {
  return Array.isArray(tool?.models)
    ? tool.models.filter(model => model && typeof model === 'object' && nonBlankString(model.id))
    : []
}

export function srppAiEfforts(model) {
  if (!Array.isArray(model?.efforts)) return []
  return [...new Set(model.efforts.filter(effort => nonBlankString(effort)))]
}

export function isSrppAiToolSelectable(tool) {
  return tool?.available === true && srppAiModels(tool).length > 0
}

export function findSrppAiTool(aiTools, toolId) {
  if (!nonBlankString(toolId)) return null
  return srppAiTools(aiTools).find(tool => tool.id === toolId) || null
}

export function findSrppAiModel(tool, modelId) {
  if (!nonBlankString(modelId)) return null
  return srppAiModels(tool).find(model => model.id === modelId) || null
}

export function selectSrppAiTool(aiTools, toolId, catalogRevision) {
  const tool = findSrppAiTool(aiTools, toolId)
  return {
    ...createEmptySrppAiSelection(catalogRevision),
    toolId: isSrppAiToolSelectable(tool) ? tool.id : ''
  }
}

export function selectSrppAiModel(aiTools, selection, modelId, catalogRevision) {
  const current = selection && typeof selection === 'object' ? selection : createEmptySrppAiSelection()
  const tool = findSrppAiTool(aiTools, current.toolId)
  const model = isSrppAiToolSelectable(tool) ? findSrppAiModel(tool, modelId) : null
  const efforts = srppAiEfforts(model)
  const defaultEffort = nonBlankString(model?.defaultEffort)
  return {
    ...createEmptySrppAiSelection(catalogRevision),
    toolId: tool?.id || '',
    model: model?.id || '',
    effort: efforts.length && efforts.includes(defaultEffort) ? defaultEffort : null
  }
}

export function validateSrppAiSelection(aiTools, catalogRevision, selection) {
  const revision = nonBlankString(catalogRevision)
  const current = selection && typeof selection === 'object' ? selection : {}
  if (!revision) return { valid: false, reason: 'catalog-missing' }
  if (current.catalogRevision !== revision) return { valid: false, reason: 'catalog-stale' }

  const tool = findSrppAiTool(aiTools, current.toolId)
  if (!isSrppAiToolSelectable(tool)) return { valid: false, reason: 'tool-unavailable' }

  const model = findSrppAiModel(tool, current.model)
  if (!model) return { valid: false, reason: 'model-required' }

  const efforts = srppAiEfforts(model)
  if (efforts.length && !efforts.includes(current.effort)) return { valid: false, reason: 'effort-required' }
  if (!efforts.length && current.effort !== null) return { valid: false, reason: 'effort-not-supported' }

  return { valid: true, reason: null }
}

export function srppAiSelectionPayload(selection) {
  return {
    toolId: selection.toolId,
    model: selection.model,
    effort: selection.effort,
    catalogRevision: selection.catalogRevision
  }
}

export function srppAiBillingModeLabel(billingMode) {
  return ({ SUBSCRIPTION: '沿用既有訂閱', LOCAL: '本機模型' })[billingMode] || '使用方式未提供'
}

export function srppAiModelSourceLabel(modelSource) {
  return ({
    RUNTIME: '由工具提供',
    CACHE: '本機已知項目',
    CONFIGURED: '管理者設定項目'
  })[modelSource] || '來源未提供'
}

export function safeSrppAiCodeMessage(code) {
  if (typeof code !== 'string') return 'AI 解讀未成功；情境數值仍已保留。'
  return Object.hasOwn(SAFE_AI_CODE_MESSAGES, code)
    ? SAFE_AI_CODE_MESSAGES[code]
    : 'AI 工具回報暫時無法處理；情境數值仍已保留。'
}

export function srppAiActualModelLabel(execution) {
  if (execution?.modelEvidence === 'PROVIDER_REPORTED' && nonBlankString(execution.actualModel)) {
    return execution.actualModel
  }
  return '未回報實際模型（僅記錄要求模型）'
}

export function srppAiExecutionStatusLabel(status) {
  return ({ SUCCEEDED: '文字解讀完成', FAILED: '文字解讀未成功' })[status] || '狀態未提供'
}

export function hasSrppAiSelection(selection) {
  return !!selection && typeof selection === 'object' &&
    !!(selection.toolId || selection.model || selection.effort != null)
}
