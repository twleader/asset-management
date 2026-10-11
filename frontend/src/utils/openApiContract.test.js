import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { OpenApiContractParseError, parseOpenApiContract } from './openApiContract.js'
import { createOpenApiContractLoader } from './openApiContractLoader.js'

test('解析最小合法契約並保留 operation 原始片段', () => {
  const contract = parseOpenApiContract(`openapi: 3.1.0
info:
  title: 測試 API
  version: 1.0.0
servers:
  - url: http://127.0.0.1:9090
paths:
  /api/example:
    get:
      operationId: getExample
      summary: 讀取範例
      responses:
        '200':
          description: 成功
    post:
      operationId: createExample
      summary: 建立範例
      responses:
        '201':
          description: 已建立
components:
  schemas: {}
`)

  assert.equal(contract.title, '測試 API')
  assert.equal(contract.version, '1.0.0')
  assert.deepEqual(contract.serverUrls, ['http://127.0.0.1:9090'])
  assert.deepEqual(contract.operations[0].responseCodes, ['200'])
  assert.match(contract.operations[0].fragment, /\/api\/example:\n    get:/)
  assert.equal(contract.operations.length, 2)
  assert.match(contract.operations[1].fragment, /\/api\/example:\n    post:/)
  assert.doesNotMatch(contract.operations[0].fragment, /createExample|    post:/)
  assert.doesNotMatch(contract.operations[1].fragment, /getExample|    get:/)
})

test('缺少必要結構時 fail closed', () => {
  assert.throws(
    () => parseOpenApiContract('openapi: 3.1.0\ninfo:\n  title: 壞資料\n'),
    OpenApiContractParseError
  )
})

test('金融 24 路與觀測 1 路分開解析，union 精確為 25 個 method/path', () => {
  // 每份契約各自解析自己的 components；不把獨立 OpenAPI component namespace 合併。
  const financialYaml = readFileSync(new URL('../../../docs/openapi/docker-external-api.yaml', import.meta.url), 'utf8')
  const observationYaml = readFileSync(new URL('../../../docs/openapi/ai-flow-observation-api.yaml', import.meta.url), 'utf8')
  const financial = parseOpenApiContract(financialYaml)
  const observation = parseOpenApiContract(observationYaml)
  const entries = financial.operations
    .map(operation => operation.method.toUpperCase() + ' ' + operation.path)
    .sort()
  const observationEntries = observation.operations
    .map(operation => operation.method.toUpperCase() + ' ' + operation.path)
    .sort()
  const financialPaths = new Set(financial.operations.map(operation => operation.path))
  const observationPaths = new Set(observation.operations.map(operation => operation.path))
  const operationIds = [...financial.operations, ...observation.operations].map(operation => operation.operationId)

  assert.equal(entries.length, 24)
  assert.deepEqual(entries, [
    'GET /api/assets/latest',
    'GET /api/public/commodity-prices',
    'GET /api/public/exchange-rate/usd-twd',
    'GET /api/public/market-analysis/today',
    'GET /api/public/market-index',
    'GET /api/public/portfolio-advice/latest',
    'GET /api/public/srpp/calculation-context',
    'GET /api/public/srpp/calculations',
    'GET /api/public/srpp/completed-technicals',
    'GET /api/public/srpp/daily-context',
    'GET /api/public/srpp/market-facts',
    'GET /api/public/srpp/report-calculations',
    'GET /api/public/srpp/technical-series',
    'GET /api/public/trading-calendar',
    'GET /api/public/trading-radar/stock',
    'GET /api/public/trading-radar/today',
    'GET /api/public/transactions',
    'GET /api/quotes',
    'GET /api/quotes/one',
    'GET /api/srpp/daily-report-mail/{idempotencyKey}',
    'POST /api/public/crawler-data/rescan',
    'POST /api/public/srpp/daily-decision/evaluate',
    'POST /api/public/srpp/event-evidence/capture',
    'POST /api/srpp/daily-report-mail'
  ])
  assert.deepEqual(observationEntries, ['POST /api/srpp/ai-flow/events'])
  assert.equal([...financialPaths].filter(path => observationPaths.has(path)).length, 0)
  assert.equal(entries.length + observationEntries.length, 25)
  assert.equal(new Set(operationIds).size, 25)
})

test('切換 OpenAPI 文件會先清除舊契約並忽略較晚返回的舊請求', async () => {
  const pending = {}
  const requests = {
    financial: signal => {
      pending.financial = { signal }
      return new Promise(resolve => { pending.financial.resolve = resolve })
    },
    observation: signal => {
      pending.observation = { signal }
      return new Promise(resolve => { pending.observation.resolve = resolve })
    }
  }
  const loader = createOpenApiContractLoader({
    requests,
    parse: source => 'parsed:' + source,
    errorMessage: error => error.message
  })
  const state = { loading: false, error: '', contract: { kind: 'previous' }, activeOperation: 'old' }

  const financialLoad = loader.load('financial', state)
  const financialSignal = pending.financial.signal
  const observationLoad = loader.load('observation', state)
  assert.equal(financialSignal.aborted, true)
  assert.equal(state.contract, null)
  assert.equal(state.activeOperation, '')
  assert.equal(state.loading, true)

  pending.financial.resolve('old-financial-contract')
  await financialLoad
  assert.equal(state.contract, null)
  assert.equal(state.loading, true)

  pending.observation.resolve('new-observation-contract')
  await observationLoad
  assert.equal(state.contract, 'parsed:new-observation-contract')
  assert.equal(state.loading, false)
  loader.clear(state)
  assert.equal(state.contract, null)
})

test('文件選擇器使用既有金融契約與唯讀觀測契約的獨立 BFF 路由', () => {
  const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
  const view = readFileSync(new URL('../views/OpenApiView.vue', import.meta.url), 'utf8')

  assert.match(api, /contract: \(\{ signal \} = \{\}\) => api\.get\('\/bff\/open-api\/contract'/)
  assert.match(api, /observationContract: \(\{ signal \} = \{\}\) => api\.get\('\/bff\/open-api\/observation-contract'/)
  assert.match(view, /<el-option label="金融 API 契約" value="financial"/)
  assert.match(view, /<el-option label="AI 流程觀測契約" value="observation"/)
  assert.match(view, /financial: signal => bffApi\.openApi\.contract\(\{ signal \}\)/)
  assert.match(view, /observation: signal => bffApi\.openApi\.observationContract\(\{ signal \}\)/)
  assert.doesNotMatch(view, /bffApi\.(?!openApi\.)/)
})
