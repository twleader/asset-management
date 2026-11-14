import test from 'node:test'
import assert from 'node:assert/strict'

import {
  canPublishSrppRun,
  hasSrppDraftChanges,
  isCurrentSrppRun
} from './srppSettingsCenterState.js'

const savedValues = {
  retirementDate: '2040-01-01',
  targetWeights: { TW_STOCK: 0.4, CASH: 0.6 }
}

const draft = {
  id: 'draft-1',
  revision: 3,
  configSha256: 'config-3'
}

const run = {
  id: 'run-3',
  draftId: 'draft-1',
  draftRevision: 3,
  configSha256: 'config-3',
  status: 'SUCCEEDED',
  publishEligible: true
}

test('草稿 dirty 比對忽略物件鍵順序，並偵測 nested weights 修改', () => {
  assert.equal(hasSrppDraftChanges({ targetWeights: { CASH: 0.6, TW_STOCK: 0.4 }, retirementDate: '2040-01-01' }, savedValues), false)
  assert.equal(hasSrppDraftChanges({ ...savedValues, targetWeights: { TW_STOCK: 0.5, CASH: 0.5 } }, savedValues), true)
})

test('只有同一草稿 revision/config 的成功 run 可供發布', () => {
  assert.equal(isCurrentSrppRun(run, draft), true)
  assert.equal(isCurrentSrppRun({ ...run, draftRevision: 2 }, draft), false)
  assert.equal(isCurrentSrppRun({ ...run, configSha256: 'old-config' }, draft), false)
  assert.equal(isCurrentSrppRun({ ...run, status: 'PROCESSING' }, draft), false)
})

test('發布按鈕需同時滿足管理者、BFF 權限、乾淨草稿及 server publishEligible', () => {
  assert.equal(canPublishSrppRun({ isAdmin: true, canPublish: true, draft, run, dirty: false }), true)
  assert.equal(canPublishSrppRun({ isAdmin: false, canPublish: true, draft, run, dirty: false }), false)
  assert.equal(canPublishSrppRun({ isAdmin: true, canPublish: false, draft, run, dirty: false }), false)
  assert.equal(canPublishSrppRun({ isAdmin: true, canPublish: true, draft, run, dirty: true }), false)
  assert.equal(canPublishSrppRun({ isAdmin: true, canPublish: true, draft, run: { ...run, publishEligible: false }, dirty: false }), false)
  assert.equal(canPublishSrppRun({ isAdmin: true, canPublish: true, draft, run, dirty: false, publishing: true }), false)
})
