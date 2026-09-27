import test from 'node:test'
import assert from 'node:assert/strict'
import { LOCAL_HYBRID_TEMPLATE_NOTICE, portfolioAdviceEngineNotice } from './portfolioAdviceEngineNotice.js'

test('管理者在 local 或 hybrid 且 llm 可用時使用設定提供的名稱', () => {
  for (const engine of ['local', 'hybrid']) {
    assert.equal(portfolioAdviceEngineNotice({
      isAdmin: true,
      engine,
      availableEngines: [{ id: 'llm', label: '完整 AI 分析' }]
    }), '可在上方「分析引擎」選單切換為完整 AI 分析。')
  }
})

test('管理者在 llm 不可用時明確提示檢查設定', () => {
  assert.equal(portfolioAdviceEngineNotice({
    isAdmin: true,
    engine: 'local',
    availableEngines: [{ id: 'hybrid', label: '混合模式' }]
  }), '完整 AI 引擎目前不可用，請檢查引擎設定。')
})

test('一般使用者只被引導洽系統管理者', () => {
  const notice = portfolioAdviceEngineNotice({
    isAdmin: false,
    engine: 'hybrid',
    availableEngines: [{ id: 'llm', label: '完整 AI 分析' }]
  })
  assert.equal(notice, '如需完整 AI 引擎，請洽系統管理者設定。')
  assert.doesNotMatch(notice, /選單|切換/)
})

test('已選 llm 時不顯示切換至 llm 的 CTA', () => {
  assert.equal(portfolioAdviceEngineNotice({
    isAdmin: true,
    engine: 'llm',
    availableEngines: [{ id: 'llm', label: '完整 AI 分析' }]
  }), null)
})

test('固定說明保留模板免責、分攤規則與分析限制', () => {
  for (const phrase of [
    '常見經驗法則', '未經回測或個人情境驗證', '不構成個人化投資建議',
    '股票與信託基金', '（assetClass, subClass）', '目前市值比例',
    '存款（現金）增碼按存款市值比例分攤', '減碼依提領優先序逐筆抽取',
    '不推薦尚未持有的新標的', '基本面、損益、交易成本或稅務'
  ]) assert.match(LOCAL_HYBRID_TEMPLATE_NOTICE, new RegExp(phrase.replace(/[()[\].?+*^$|\\]/g, '\\$&')))
})
