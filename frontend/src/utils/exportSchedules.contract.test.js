import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
const views = [
  ['AssetHistoryView.vue', 'assetHistory', '/bff/asset-history/export-schedules', false],
  ['RealizedGainView.vue', 'realizedGain', '/bff/realized-gain/export/schedules', false],
  ['ExchangeRateView.vue', 'exchangeRate', '/bff/exchange-rate/export/schedules', true],
  ['CommodityPriceView.vue', 'commodityPrice', '/bff/commodity-price/export/schedules', true]
]

test('each export page binds CRUD and run-now to its own schedule resource', () => {
  for (const [, , basePath] of views) {
    assert.ok(api.includes(`api.get('${basePath}'`), `GET ${basePath}`)
    assert.ok(api.includes(`api.post('${basePath}', data`), `POST ${basePath}`)
    assert.ok(api.includes(`api.put(\`${basePath}/\${id}\``), `PUT ${basePath}/{id}`)
    assert.ok(api.includes(`api.delete(\`${basePath}/\${id}\``), `DELETE ${basePath}/{id}`)
    assert.ok(api.includes(`api.post(\`${basePath}/\${id}/run-now\``), `POST ${basePath}/{id}/run-now`)
  }
})

test('all four pages distinguish load errors from empty schedules and keep edits draft-local', () => {
  for (const [filename, apiName, , hasRange] of views) {
    const view = readFileSync(new URL(`../views/${filename}`, import.meta.url), 'utf8')
    assert.ok(view.includes(`bffApi.${apiName}.getExportSchedules()`), `${filename} loads its schedule list`)
    assert.ok(view.includes(`bffApi.${apiName}.runExportScheduleNow(item.id)`), `${filename} runs the selected schedule`)
    assert.ok(view.includes('!scheduleLoadError && schedules.length === 0'), `${filename} has a deliberate empty state`)
    assert.ok(view.includes('scheduleLoadError'), `${filename} displays load errors`)
    assert.ok(view.includes('createExportScheduleDraft(item'), `${filename} edits a copied draft`)
    assert.ok(view.includes('scheduleDialog.draft.outputSubpath = p'), `${filename} applies folder picks to the draft`)
    if (hasRange) {
      assert.ok(view.includes('formatExportRangeMonths(item.rangeMonths)'), `${filename} shows each range`)
      assert.ok(view.includes('getExportRangeMonthOptions(scheduleDialog.draft.rangeMonths)'), `${filename} exposes custom stored ranges`)
    }
  }
})
