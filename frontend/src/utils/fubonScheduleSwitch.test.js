import test from 'node:test'
import assert from 'node:assert/strict'
import { hasSwitch, toSwitchMap } from './fubonScheduleSwitch.js'

test('toSwitchMap 只收有 scheduleKey 的列，enabled 非 true 一律視為關閉', () => {
  assert.deepEqual(
    toSwitchMap([
      { scheduleKey: 'trade', enabled: true },
      { scheduleKey: 'srpp-order-source', enabled: false },
      { scheduleKey: 'inventory' },
      { enabled: true },
      null
    ]),
    { trade: true, 'srpp-order-source': false, inventory: false }
  )
  assert.deepEqual(toSwitchMap(undefined), {})
})

test('hasSwitch 需要列有 scheduleKey 且後端有回傳該 key', () => {
  const map = { trade: true }
  assert.equal(hasSwitch({ scheduleKey: 'trade' }, map), true)
  assert.equal(hasSwitch({ scheduleKey: 'settlement' }, map), false)
  assert.equal(hasSwitch({ scheduleKey: null }, map), false)
  assert.equal(hasSwitch(null, map), false)
})
