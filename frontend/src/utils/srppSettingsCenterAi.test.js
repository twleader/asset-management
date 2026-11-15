import test from 'node:test'
import assert from 'node:assert/strict'

import {
  createEmptySrppAiSelection,
  hasSrppAiSelection,
  isSrppAiToolSelectable,
  safeSrppAiCodeMessage,
  selectSrppAiModel,
  selectSrppAiTool,
  srppAiActualModelLabel,
  srppAiBillingModeLabel,
  srppAiExecutionStatusLabel,
  srppAiModelSourceLabel,
  srppAiSelectionPayload,
  validateSrppAiSelection
} from './srppSettingsCenterAi.js'

const tools = [
  {
    id: 'future_registered_tool',
    label: '新增工具',
    adapter: 'future_adapter',
    billingMode: 'SUBSCRIPTION',
    available: true,
    modelSource: 'RUNTIME',
    models: [
      { id: 'model-with-effort', label: '可選程度模型', efforts: ['low', 'high'], defaultEffort: 'high' },
      { id: 'model-without-effort', label: '無程度模型', efforts: [], defaultEffort: null }
    ]
  },
  {
    id: 'disabled_registered_tool',
    label: '暫不可用工具',
    adapter: 'local_adapter',
    billingMode: 'LOCAL',
    available: false,
    code: 'HOST_UNAVAILABLE',
    modelSource: 'CONFIGURED',
    models: [{ id: 'disabled-model', label: '不應可選', efforts: [], defaultEffort: null }]
  },
  {
    id: 'empty_registered_tool',
    label: '沒有模型的工具',
    adapter: 'local_adapter',
    billingMode: 'LOCAL',
    available: true,
    modelSource: 'CONFIGURED',
    models: []
  }
]

test('AI 工具與模型由 catalog 動態提供，僅可用且有模型的工具可選', () => {
  assert.equal(selectSrppAiTool(tools, 'future_registered_tool', 'catalog-1').toolId, 'future_registered_tool')
  assert.equal(isSrppAiToolSelectable(tools[1]), false)
  assert.equal(isSrppAiToolSelectable(tools[2]), false)
  assert.equal(selectSrppAiTool([{ ...tools[0], id: 'newly_added_later' }], 'newly_added_later', 'catalog-1').toolId, 'newly_added_later')
})

test('工具變更清除舊模型與 effort，模型變更只採 catalog 的合法預設 effort', () => {
  const first = selectSrppAiTool(tools, 'future_registered_tool', 'catalog-1')
  assert.deepEqual(first, {
    toolId: 'future_registered_tool', model: '', effort: null, catalogRevision: 'catalog-1'
  })

  const withEffort = selectSrppAiModel(tools, first, 'model-with-effort', 'catalog-1')
  assert.equal(withEffort.effort, 'high')
  assert.equal(validateSrppAiSelection(tools, 'catalog-1', withEffort).valid, true)

  const withoutEffort = selectSrppAiModel(tools, withEffort, 'model-without-effort', 'catalog-1')
  assert.equal(withoutEffort.effort, null)
  assert.equal(validateSrppAiSelection(tools, 'catalog-1', withoutEffort).valid, true)
  assert.deepEqual(srppAiSelectionPayload(withoutEffort), {
    toolId: 'future_registered_tool', model: 'model-without-effort', effort: null, catalogRevision: 'catalog-1'
  })
})

test('選擇驗證拒絕不可用工具、未選模型、無效 effort 與過期 catalog', () => {
  const base = selectSrppAiModel(tools, selectSrppAiTool(tools, 'future_registered_tool', 'catalog-1'), 'model-with-effort', 'catalog-1')
  assert.deepEqual(validateSrppAiSelection(tools, 'catalog-2', base), { valid: false, reason: 'catalog-stale' })
  assert.deepEqual(validateSrppAiSelection(tools, 'catalog-1', { ...base, effort: null }), { valid: false, reason: 'effort-required' })
  assert.deepEqual(validateSrppAiSelection(tools, 'catalog-1', { ...base, effort: 'medium' }), { valid: false, reason: 'effort-required' })
  assert.deepEqual(validateSrppAiSelection(tools, 'catalog-1', { ...base, toolId: 'disabled_registered_tool' }), { valid: false, reason: 'tool-unavailable' })
  assert.deepEqual(validateSrppAiSelection(tools, 'catalog-1', { ...base, model: '' }), { valid: false, reason: 'model-required' })
})

test('LOCAL 狀態可明確清空 AI 選擇，未知 safe code 不會原樣顯示', () => {
  assert.deepEqual(createEmptySrppAiSelection('catalog-2'), {
    toolId: '', model: '', effort: null, catalogRevision: 'catalog-2'
  })
  assert.equal(hasSrppAiSelection(createEmptySrppAiSelection('catalog-2')), false)
  assert.equal(safeSrppAiCodeMessage('HOST_UNAVAILABLE'), '本機 AI 連線目前無法使用。')
  assert.equal(safeSrppAiCodeMessage('AI_BILLING_UNVERIFIED'), '無法確認這次使用不會產生額外費用，因此系統未呼叫模型。')
  assert.equal(safeSrppAiCodeMessage('AI_PAID_CREDITS_ENABLED'), '帳戶啟用了可能計費的點數，為避免額外費用，系統未呼叫模型。')
  assert.equal(safeSrppAiCodeMessage('AI_EXTRA_USAGE_ENABLED'), '帳戶啟用了額外用量，為避免額外費用，系統未呼叫模型。')
  assert.equal(safeSrppAiCodeMessage('AI_UNAUTHORIZED'), '本機工具連線驗證失敗，請重新載入狀態後再試。')
  assert.equal(safeSrppAiCodeMessage('AI_INVALID_REQUEST'), '這次 AI 請求未能處理，請重新載入工具清單後再試。')
  assert.equal(safeSrppAiCodeMessage('AI_INVALID_SELECTION'), '目前選擇已失效，請重新選擇工具、模型與思考程度。')
  assert.equal(safeSrppAiCodeMessage('AI_CATALOG_STALE'), 'AI 模型清單已更新，請重新載入後選擇。')
  assert.equal(safeSrppAiCodeMessage('AI_EMPTY_RESPONSE'), 'AI 未提供文字解讀；情境數值仍已保留。')
  assert.equal(safeSrppAiCodeMessage('AI_EXECUTION_FAILED'), 'AI 解讀未成功；情境數值仍已保留。')
  assert.equal(safeSrppAiCodeMessage('AI_REMOTE_MODEL_FORBIDDEN'), '已阻止連接遠端或雲端模型；請改用本機已安裝模型。')
  assert.equal(safeSrppAiCodeMessage('AI_TOOL_USE_FORBIDDEN'), 'AI 嘗試使用非解讀工具，系統已拒絕；情境數值仍已保留。')
  assert.equal(safeSrppAiCodeMessage('private raw error text'), 'AI 工具回報暫時無法處理；情境數值仍已保留。')
  assert.equal(safeSrppAiCodeMessage('__proto__'), 'AI 工具回報暫時無法處理；情境數值仍已保留。')
})

test('收據只在有供應者回報證據時顯示實際模型，並翻譯有限狀態與來源', () => {
  assert.equal(srppAiActualModelLabel({ modelEvidence: 'REQUESTED_ONLY', actualModel: null }), '未回報實際模型（僅記錄要求模型）')
  assert.equal(srppAiActualModelLabel({ modelEvidence: 'PROVIDER_REPORTED', actualModel: 'model-live-2' }), 'model-live-2')
  assert.equal(srppAiActualModelLabel({ modelEvidence: 'PROVIDER_REPORTED', actualModel: null }), '未回報實際模型（僅記錄要求模型）')
  assert.equal(srppAiBillingModeLabel('SUBSCRIPTION'), '沿用既有訂閱')
  assert.equal(srppAiBillingModeLabel('LOCAL'), '本機模型')
  assert.equal(srppAiModelSourceLabel('CACHE'), '本機已知項目')
  assert.equal(srppAiExecutionStatusLabel('FAILED'), '文字解讀未成功')
})
