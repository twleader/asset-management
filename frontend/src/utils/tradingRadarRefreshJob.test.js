import test from 'node:test'
import assert from 'node:assert/strict'
import { createTradingRadarRefreshJob } from './tradingRadarRefreshJob.js'

const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }
function fakeTime() {
  let time = 0, id = 0
  const timers = new Map()
  return {
    now: () => time, timers,
    setTimer(callback, delay) { timers.set(++id, { callback, at: time + delay }); return id },
    clearTimer(id) { timers.delete(id) },
    async advance(ms) {
      const target = time + ms
      while (true) {
        const next = [...timers].sort((a, b) => a[1].at - b[1].at)[0]
        if (!next || next[1].at > target) break
        time = next[1].at; timers.delete(next[0]); next[1].callback(); await flush()
      }
      time = target; await flush()
    }
  }
}

test('手動更新 single-flight，POST 一次，完成後才讀取 Panel', async () => {
  let starts = 0, statuses = 0, completed = 0
  const status = deferred()
  const job = createTradingRadarRefreshJob({
    start: async () => { starts += 1; return { jobId: 'id' } }, status: async () => { statuses += 1; return status.promise },
    onCompleted: async () => { completed += 1; return { completed: true } }
  })
  const first = job.run(), second = job.run()
  assert.equal(first, second)
  await flush(); assert.equal(starts, 1); assert.equal(statuses, 1)
  status.resolve({ status: 'COMPLETED' })
  assert.deepEqual(await first, { completed: true })
  assert.equal(completed, 1)
})

for (const phase of ['POST', 'GET', '等待']) {
  test(`dispose 立即結束${phase}，即使 adapter 忽略 abort，無 late callback 或殘留 timer`, async () => {
    const time = fakeTime(), pending = deferred(); let receivedSignal, completed = 0
    const job = createTradingRadarRefreshJob({ ...time,
      start: ({ signal }) => { if (phase === 'POST') { receivedSignal = signal; return pending.promise } return Promise.resolve({ jobId: 'id' }) },
      status: (_id, { signal }) => { receivedSignal = signal; return phase === 'GET' ? pending.promise : Promise.resolve({ status: 'RUNNING' }) },
      onCompleted: () => { completed++; }
    })
    const running = job.run(); await flush(); job.dispose()
    assert.equal(await running, null)
    if (phase !== '等待') assert.equal(receivedSignal.aborted, true)
    pending.resolve({ jobId: 'late', status: 'COMPLETED' }); await flush()
    assert.equal(completed, 0)
    assert.equal(time.timers.size, 0)
    assert.equal(await job.run(), null)
  })
}

for (const phase of ['POST', 'GET']) {
  test(`60 秒硬期限包含卡住的 ${phase}，取消後晚到 COMPLETED 也不重算`, async () => {
    const time = fakeTime(), pending = deferred(); let signal, completed = 0, statuses = 0
    const job = createTradingRadarRefreshJob({ ...time,
      start: options => { signal = options.signal; return phase === 'POST' ? pending.promise : Promise.resolve({ jobId: 'id' }) },
      status: (_id, options) => { statuses++; signal = options.signal; return pending.promise },
      onCompleted: () => { completed++; }
    })
    const running = job.run(); await flush(); await time.advance(60000)
    assert.deepEqual(await running, { timeout: true, completed: false })
    assert.equal(signal.aborted, true)
    pending.resolve({ jobId: 'late', status: 'COMPLETED' }); await flush()
    assert.equal(completed, 0)
    assert.equal(statuses, phase === 'POST' ? 0 : 1)
    assert.equal(time.timers.size, 0)
  })
}

test('FAILED、terminal error 立即停止；POST 失敗不自動重送', async () => {
  for (const statusCode of [400, 401, 403, 404]) {
    let starts = 0, statuses = 0
    const error = Object.assign(Error('denied'), { response: { status: statusCode } })
    const job = createTradingRadarRefreshJob({ start: async () => { starts++; return { jobId: 'id' } }, status: async () => { statuses++; throw error } })
    await assert.rejects(job.run(), error)
    assert.equal(starts, 1); assert.equal(statuses, 1)
  }
  const failed = createTradingRadarRefreshJob({ start: async () => ({ jobId: 'id' }), status: async () => ({ status: 'FAILED' }) })
  assert.deepEqual(await failed.run(), { job: { status: 'FAILED' }, completed: false })
  let starts = 0
  const postFailure = createTradingRadarRefreshJob({ start: async () => { starts++; throw Error('network') }, status: async () => { assert.fail('no poll') } })
  await assert.rejects(postFailure.run(), /network/); assert.equal(starts, 1)
})

test('暫時錯誤僅以 1、2 秒有界退避輪詢，最多一個 GET，總期限也包含 POST', async () => {
  const time = fakeTime(); let starts = 0, statuses = 0, inFlight = 0, maxFlight = 0
  const start = deferred()
  const job = createTradingRadarRefreshJob({ ...time,
    start: () => { starts++; return start.promise },
    status: async () => { statuses++; inFlight++; maxFlight = Math.max(maxFlight, inFlight); await Promise.resolve(); inFlight--; throw Error('temporary') }
  })
  const running = job.run(); await flush(); await time.advance(58000)
  start.resolve({ jobId: 'id' }); await flush()
  assert.equal(statuses, 1)
  await time.advance(1000); assert.equal(statuses, 2)
  await time.advance(1000)
  assert.deepEqual(await running, { timeout: true, completed: false })
  assert.equal(statuses, 2); assert.equal(starts, 1); assert.equal(maxFlight, 1); assert.equal(time.timers.size, 0)
})
