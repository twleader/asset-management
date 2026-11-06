import test from 'node:test'
import assert from 'node:assert/strict'
import { createClassificationDraft } from './transactionClassificationDraft.js'

test('unchanged category is omitted; auto clears only on explicit change; cancel discards draft', async () => {
  const state = {}
  const draft = createClassificationDraft(state, async () => ({ assetClassOverride: 'BOND', effectiveAssetClass: 'BOND', assetClassAssignable: true }))
  await draft.load({ assetCode: '00881' })
  assert.equal(draft.payload(), undefined)
  draft.change('')
  assert.deepEqual(draft.payload(), { assetClass: null })
  draft.invalidate()
  assert.equal(draft.payload(), undefined)
})
test('identity change clears unsaved selection and delayed old response cannot overwrite current identity', async () => {
  const state = {}
  const pending = []
  const draft = createClassificationDraft(state, () => new Promise(resolve => pending.push(resolve)))
  const first = draft.load({ assetCode: '00881' })
  draft.change('STOCK')
  const second = draft.load({ assetType: '基金', assetName: '收益基金' })
  assert.equal(state.selected, '')
  pending[1]({ assetClassOverride: 'BOND', effectiveAssetClass: 'BOND', assetClassAssignable: true })
  await second
  pending[0]({ assetClassOverride: 'STOCK', effectiveAssetClass: 'STOCK', assetClassAssignable: true })
  await first
  assert.equal(state.selected, 'BOND')
  assert.equal(draft.payload(), undefined)
})
test('unknown target and lookup error never emit an override or silently drop explicit changes', async () => {
  const state = {}
  const draft = createClassificationDraft(state, async () => ({ assetClassOverride: null, effectiveAssetClass: 'STOCK', assetClassAssignable: false }))
  await draft.load({})
  assert.equal(draft.payload(), undefined)
  draft.change('BOND')
  assert.throws(() => draft.payload())
  const failing = createClassificationDraft(state, async () => { throw new Error('offline') })
  await failing.load({})
  assert.ok(state.error)
  assert.equal(failing.payload(), undefined)
  failing.change('BOND')
  assert.throws(() => failing.payload())
})

test('a late name lookup preserves the user selection for an already assignable identity', async () => {
  const state = {}
  let calls = 0
  const draft = createClassificationDraft(state, async () => {
    calls++
    return { assetClassOverride: null, effectiveAssetClass: 'STOCK', assetClassAssignable: true }
  })
  await draft.load({ assetCode: '00881' })
  draft.change('BOND')
  await draft.refreshAfterMasterLookup({ assetCode: '00881' })
  assert.equal(calls, 1)
  assert.equal(state.selected, 'BOND')
  assert.deepEqual(draft.payload(), { assetClass: 'BOND' })
})
test('a newly resolved master can refresh an unavailable target without discarding a dirty choice', async () => {
  const state = {}
  let ready = false
  const draft = createClassificationDraft(state, async () => ({
    assetClassOverride: null, effectiveAssetClass: 'STOCK', assetClassAssignable: ready
  }))
  await draft.load({ assetCode: '00881' })
  assert.equal(state.assignable, false)
  ready = true
  await draft.refreshAfterMasterLookup({ assetCode: '00881' })
  assert.equal(state.assignable, true)
  draft.change('BOND')
  await draft.refreshAfterMasterLookup({ assetCode: '00881' })
  assert.deepEqual(draft.payload(), { assetClass: 'BOND' })
})
