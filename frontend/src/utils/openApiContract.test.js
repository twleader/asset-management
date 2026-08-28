import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { OpenApiContractParseError, parseOpenApiContract } from './openApiContract.js'

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

test('現行 9090 契約的十三個精確 method/path 全數可辨識', () => {
  const yaml = readFileSync(new URL('../../../docs/openapi/docker-external-api.yaml', import.meta.url), 'utf8')
  const entries = parseOpenApiContract(yaml).operations
    .map(operation => `${operation.method.toUpperCase()} ${operation.path}`)
    .sort()

  assert.equal(entries.length, 13)
  assert.deepEqual(entries, [
    'GET /api/assets/latest',
    'GET /api/public/commodity-prices',
    'GET /api/public/exchange-rate/usd-twd',
    'GET /api/public/market-analysis/today',
    'GET /api/public/market-index',
    'GET /api/public/portfolio-advice/latest',
    'GET /api/public/trading-calendar',
    'GET /api/public/trading-radar/stock',
    'GET /api/public/trading-radar/today',
    'GET /api/public/transactions',
    'GET /api/quotes',
    'GET /api/quotes/one',
    'POST /api/public/crawler-data/rescan'
  ])
})
