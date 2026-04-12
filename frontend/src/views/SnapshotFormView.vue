<template>
  <div v-loading="loading">
    <div class="page-header">
      <el-button :icon="ArrowLeft" @click="$router.back()">返回</el-button>
      <h2>{{ isEdit ? '管理資產' : '新增快照' }}</h2>
    </div>

    <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
      <!-- Basic Info + 資產彙整 -->
      <el-card style="margin-bottom:16px">
        <template #header>
          <div style="display:flex;align-items:center;justify-content:space-between">
            <span class="section-title">基本資訊</span>
            <el-button size="small" type="primary" :loading="saving" @click="submit">存檔</el-button>
          </div>
        </template>
        <!-- 基本欄位 -->
        <el-row :gutter="20" style="margin-bottom:16px">
          <el-col :span="6">
            <el-form-item label="日期" prop="snapshotDate">
              <el-date-picker v-model="form.snapshotDate" type="date" value-format="YYYY-MM-DD"
                placeholder="選擇日期" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="美元匯率">
              <el-input :model-value="numFmt(form.usdExchangeRate)" style="width:100%" disabled
                :input-style="{ textAlign: 'right' }" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="備註">
              <el-input v-model="form.notes" placeholder="備註" />
            </el-form-item>
          </el-col>
        </el-row>
        <!-- 資產彙整列 -->
        <div class="summary-bar">
          <!-- 總資產 -->
          <div class="sb-item sb-total">
            <div class="sb-label">🏆 總資產</div>
            <div class="sb-val">{{ fmt(depositTotal + fundTotalValue + allSummary.value) }}</div>
          </div>
          <div class="sb-sep" />
          <!-- 存款 -->
          <div class="sb-item">
            <div class="sb-label">💰 存款</div>
            <div class="sb-val">{{ fmt(depositTotal) }}</div>
          </div>
          <div class="sb-sep" />
          <!-- 台股 -->
          <div class="sb-item">
            <div class="sb-label">📈 台股現值</div>
            <div class="sb-val">{{ fmt(twSummary.value) }}</div>
          </div>
          <div class="sb-item">
            <div class="sb-label">台股損益</div>
            <div class="sb-val" :class="twSummary.profit >= 0 ? 'profit' : 'loss'">
              {{ fmt(twSummary.profit) }}
              <small style="font-weight:400"> ({{ pct(twSummary.profitRate) }})</small>
            </div>
          </div>
          <div class="sb-sep" />
          <!-- 美股 -->
          <div class="sb-item">
            <div class="sb-label">🇺🇸 美股現值</div>
            <div class="sb-val">{{ fmt(usSummary.value) }}</div>
          </div>
          <div class="sb-item">
            <div class="sb-label">美股損益</div>
            <div class="sb-val" :class="usSummary.profit >= 0 ? 'profit' : 'loss'">
              {{ fmt(usSummary.profit) }}
              <small style="font-weight:400"> ({{ pct(usSummary.profitRate) }})</small>
            </div>
          </div>
          <div class="sb-sep" />
          <!-- 共同基金 -->
          <div class="sb-item">
            <div class="sb-label">📊 共同基金現值</div>
            <div class="sb-val">{{ fmt(fundTotalValue) }}</div>
          </div>
          <div class="sb-item">
            <div class="sb-label">基金損益</div>
            <div class="sb-val" :class="fundTotalProfit >= 0 ? 'profit' : 'loss'">
              {{ fmt(fundTotalProfit) }}
              <small style="font-weight:400"> ({{ pct(fundTotalInvest > 0 ? fundTotalProfit / fundTotalInvest : 0) }})</small>
            </div>
          </div>
          <div class="sb-sep" />
          <!-- 預估年配息 -->
          <div class="sb-item">
            <div class="sb-label">預估年配息</div>
            <div class="sb-val sb-dividend">{{ fmt(allSummary.dividend) }}</div>
          </div>
        </div>
      </el-card>

      <!-- Deposits -->
      <el-card style="margin-bottom:16px">
        <template #header>
          <div style="display:flex;align-items:center;justify-content:space-between">
            <span class="section-title">💰 存款明細</span>
            <div style="display:flex;gap:8px">
              <el-button size="small" type="primary" :loading="saving" @click="submit">存檔</el-button>
              <el-button size="small" :loading="copyingPrev.deposits" @click="copyPrevDeposits">複製前一版</el-button>
              <el-button size="small" :icon="Plus" @click="addDeposit">新增</el-button>
            </div>
          </div>
        </template>
        <el-table :data="form.deposits" size="small">
          <el-table-column width="44">
            <template #default="{ $index }">
              <div class="sort-btns">
                <el-button size="small" text :disabled="$index===0" @click="moveRow(form.deposits,$index,-1)">↑</el-button>
                <el-button size="small" text :disabled="$index===form.deposits.length-1" @click="moveRow(form.deposits,$index,1)">↓</el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="銀行" width="140">
            <template #default="{ row }">
              <el-select v-model="row.bankId" size="small" style="width:100%" clearable>
                <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="存款類型" width="130">
            <template #default="{ row }">
              <el-select v-model="row.depositType" size="small" style="width:100%">
                <el-option v-for="t in depositTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="幣別" width="80">
            <template #default="{ row }">
              <el-select v-model="row.currency" size="small">
                <el-option value="TWD" label="TWD" />
                <el-option value="USD" label="USD" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="金額">
            <template #default="{ row }">
              <el-input v-model="row.amountStr" size="small" style="width:100%" :input-style="{ textAlign: 'right' }"
                @blur="row.amount = numParse(row.amountStr, row.currency === 'USD' ? 2 : 0); row.amountStr = numFmt(row.amount)" />
            </template>
          </el-table-column>
          <el-table-column label="台幣金額" align="right" width="130">
            <template #default="{ row }">
              <span style="font-size:13px;padding-right:6px">{{ fmt(depositTwd(row)) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="備註">
            <template #default="{ row }">
              <el-input v-model="row.notes" size="small" />
            </template>
          </el-table-column>
          <el-table-column width="50">
            <template #default="{ $index }">
              <el-button type="danger" size="small" :icon="Delete" circle @click="form.deposits.splice($index,1)" />
            </template>
          </el-table-column>
        </el-table>
        <div class="deposit-summary">
          <div class="ds-item">
            <span class="ds-label">存款總計</span>
            <span class="ds-val">{{ fmt(depositTotal) }}</span>
          </div>
          <div class="ds-sep" />
          <div class="ds-item">
            <span class="ds-label">定存</span>
            <span class="ds-val">{{ fmt(depositFixed) }}</span>
          </div>
          <div class="ds-sep" />
          <div class="ds-item">
            <span class="ds-label">活存</span>
            <span class="ds-val">{{ fmt(depositDemand) }}</span>
          </div>
        </div>
      </el-card>

      <!-- Funds -->
      <el-card style="margin-bottom:16px">
        <template #header>
          <div style="display:flex;align-items:center;justify-content:space-between">
            <span class="section-title">📊 信託基金</span>
            <div style="display:flex;gap:8px">
              <el-button size="small" type="primary" :loading="saving" @click="submit">存檔</el-button>
              <el-button size="small" :loading="copyingPrev.funds" @click="copyPrevFunds">複製前一版</el-button>
              <el-button size="small" :icon="Plus" @click="addFund">新增</el-button>
            </div>
          </div>
        </template>
        <el-table :data="form.funds" size="small">
          <el-table-column width="44">
            <template #default="{ $index }">
              <div class="sort-btns">
                <el-button size="small" text :disabled="$index===0" @click="moveRow(form.funds,$index,-1)">↑</el-button>
                <el-button size="small" text :disabled="$index===form.funds.length-1" @click="moveRow(form.funds,$index,1)">↓</el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="基金名稱" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.fundName" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="銀行" width="160">
            <template #default="{ row }">
              <el-select v-model="row.bankId" size="small" style="width:100%" clearable>
                <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="投資金額">
            <template #default="{ row }">
              <el-input v-model="row.investmentAmountStr" size="small" style="width:100%" :input-style="{ textAlign: 'right' }"
                @blur="row.investmentAmount = numParse(row.investmentAmountStr, 0); row.investmentAmountStr = numFmt(row.investmentAmount)" />
            </template>
          </el-table-column>
          <el-table-column label="現值">
            <template #default="{ row }">
              <el-input v-model="row.currentValueStr" size="small" style="width:100%" :input-style="{ textAlign: 'right' }"
                @blur="row.currentValue = numParse(row.currentValueStr, 0); row.currentValueStr = numFmt(row.currentValue)" />
            </template>
          </el-table-column>
          <el-table-column label="損益" width="120" align="right">
            <template #default="{ row }">
              <span :class="(row.currentValue-row.investmentAmount)>=0?'profit':'loss'">
                {{ fmt(row.currentValue - row.investmentAmount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column width="50">
            <template #default="{ $index }">
              <el-button type="danger" size="small" :icon="Delete" circle @click="form.funds.splice($index,1)" />
            </template>
          </el-table-column>
        </el-table>
        <div class="sec-summary">
          <div class="ds-item">
            <span class="ds-label">投資金額</span>
            <span class="ds-val">{{ fmt(fundTotalInvest) }}</span>
          </div>
          <div class="ds-sep" />
          <div class="ds-item">
            <span class="ds-label">現值</span>
            <span class="ds-val">{{ fmt(fundTotalValue) }}</span>
          </div>
          <div class="ds-sep" />
          <div class="ds-item">
            <span class="ds-label">損益</span>
            <span class="ds-val" :class="fundTotalProfit >= 0 ? 'profit' : 'loss'">
              {{ fmt(fundTotalProfit) }}
              <small style="font-weight:400"> ({{ pct(fundTotalInvest > 0 ? fundTotalProfit / fundTotalInvest : 0) }})</small>
            </span>
          </div>
        </div>
      </el-card>

      <!-- Stocks -->
      <el-card style="margin-bottom:16px">
        <template #header>
          <div style="display:flex;align-items:center;justify-content:space-between">
            <span class="section-title">📈 股票</span>
            <div style="display:flex;gap:8px">
              <el-button size="small" type="primary" :loading="saving" @click="submit">存檔</el-button>
              <el-button size="small" :loading="copyingPrev.stocks" @click="copyPrevStocks">複製前一版</el-button>
              <el-button size="small" @click="refreshAllPrices" :loading="refreshingAll">
                {{ isEdit ? '載入歷史股價' : '更新' }}
              </el-button>
            </div>
          </div>
        </template>

        <el-tabs v-model="stockTab">
          <!-- 台股 Tab -->
          <el-tab-pane name="tw">
            <template #label>
              <span style="display:inline-flex;align-items:center;gap:4px">
                <TaiwanMap :size="12" /> 台股
              </span>
              <el-badge :value="twStocks.length" type="primary" style="margin-left:4px" />
            </template>

            <div style="text-align:right;margin-bottom:8px">
              <el-button size="small" :icon="Plus" @click="addStock('台股')">新增台股</el-button>
            </div>

            <el-table :data="twStocks" size="small" row-key="_rowId" stripe>
              <el-table-column width="44">
                <template #default="{ row }">
                  <div class="sort-btns">
                    <el-button size="small" text :disabled="twStocks.indexOf(row)===0" @click="moveStock(row,-1)">↑</el-button>
                    <el-button size="small" text :disabled="twStocks.indexOf(row)===twStocks.length-1" @click="moveStock(row,1)">↓</el-button>
                  </div>
                </template>
              </el-table-column>
              <!-- Expand -->
              <el-table-column type="expand" width="40">
                <template #default="{ row }">
                  <div class="broker-expand">
                    <el-table :data="row.brokerRows" size="small" border style="width:100%">
                      <el-table-column label="券商" width="140">
                        <template #default="{ row: br }">
                          <el-select v-model="br.brokerId" size="small" style="width:100%" clearable>
                            <el-option v-for="b in brokerOptions" :key="b.value" :label="b.label" :value="b.value" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <el-table-column label="股數" width="110">
                        <template #default="{ row: br }">
                          <el-input v-model="br.sharesStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="br.shares = numParse(br.sharesStr, 0); br.sharesStr = numFmt(br.shares)" />
                        </template>
                      </el-table-column>
                      <!-- 均價（每股）→ 輸入後自動算總成本 -->
                      <el-table-column label="均價" width="110">
                        <template #default="{ row: br }">
                          <el-input v-model="br.avgCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="br.avgCost = numParse(br.avgCostStr, 2); br.avgCostStr = numFmt(br.avgCost); br.investmentCost = Math.round((br.avgCost||0) * (br.shares||0)); br.investmentCostStr = numFmt(br.investmentCost)" />
                        </template>
                      </el-table-column>
                      <!-- 持股成本（總額）→ 輸入後自動算均價 -->
                      <el-table-column label="持股成本" width="120">
                        <template #default="{ row: br }">
                          <el-input v-model="br.investmentCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="br.investmentCost = numParse(br.investmentCostStr, 0); br.investmentCostStr = numFmt(br.investmentCost); if (br.shares > 0) { br.avgCost = Number((br.investmentCost / br.shares).toFixed(2)); br.avgCostStr = numFmt(br.avgCost) }" />
                        </template>
                      </el-table-column>
                      <el-table-column label="現值" width="120" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px">{{ fmt(calcBrTwdValue(br, row)) }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="損益" width="110" align="right">
                        <template #default="{ row: br }">
                          <span :class="(calcBrTwdValue(br, row) - brCost(br))>=0?'profit':'loss'">
                            {{ fmt(calcBrTwdValue(br, row) - brCost(br)) }}
                          </span>
                        </template>
                      </el-table-column>
                      <el-table-column width="40">
                        <template #default="{ $index }">
                          <el-button type="danger" size="small" :icon="Delete" circle
                            @click="removeBrokerRow(row, $index)" />
                        </template>
                      </el-table-column>
                    </el-table>
                    <div style="display:flex;gap:8px;margin-top:8px">
                      <el-button size="small" :icon="Plus" @click="addBrokerRow(row, '台股')">新增券商持股</el-button>
                      <el-button size="small" type="primary" @click="submit" :loading="saving">存檔</el-button>
                    </div>
                  </div>
                </template>
              </el-table-column>

              <!-- 股號/股名 -->
              <el-table-column label="股號/股名" min-width="140">
                <template #default="{ row }">
                  <el-input v-model="row.stockCode" size="small" placeholder="代號"
                    style="margin-bottom:3px"
                    @blur="fetchPriceForRow(row)" />
                  <el-input v-model="row.stockName" size="small" placeholder="股票名稱" />
                </template>
              </el-table-column>

              <!-- 股數（唯讀，合計所有券商） -->
              <el-table-column label="股數" width="100" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ fmtShares(stockShares(row), '台股') }}</span>
                </template>
              </el-table-column>

              <!-- 均價（唯讀，加權平均；多券商時顯示 "-"） -->
              <el-table-column label="均價" width="90" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">
                    {{ row.brokerRows.length === 1
                        ? numFmt(row.brokerRows[0].avgCost)
                        : '-' }}
                  </span>
                </template>
              </el-table-column>

              <!-- 股價/漲跌 -->
              <el-table-column label="股價/漲跌(%)" width="160" align="right">
                <template #default="{ row }">
                  <div class="price-cell">
                    <span v-if="row.latestPrice" class="price-num">{{ fmtPrice(row.latestPrice) }}</span>
                    <span v-else class="price-empty">-</span>
                    <div v-if="row.priceChange !== null && row.latestPrice"
                      :class="Number(row.priceChange) >= 0 ? 'price-up' : 'price-down'"
                      class="price-change">
                      {{ Number(row.priceChange) >= 0 ? '▲' : '▼' }}
                      {{ Math.abs(Number(row.priceChange)).toFixed(2) }}
                      ({{ (Number(row.priceChangePct) * 100).toFixed(2) }}%)
                    </div>
                  </div>
                </template>
              </el-table-column>

              <!-- 持股成本（唯讀） -->
              <el-table-column label="持股成本" width="110" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ fmt(stockCost(row)) }}</span>
                </template>
              </el-table-column>

              <!-- 現值（股價×股數，唯讀） -->
              <el-table-column label="現值" width="105" align="right">
                <template #default="{ row }">{{ fmt(stockValue(row)) }}</template>
              </el-table-column>

              <!-- 損益 -->
              <el-table-column label="損益" width="120" align="right">
                <template #default="{ row }">
                  <span :class="stockProfit(row)>=0?'profit':'loss'">{{ fmt(stockProfit(row)) }}</span>
                  <small :class="stockProfit(row)>=0?'profit':'loss'" style="display:block;font-weight:400">
                    ({{ pct(stockProfitRate(row)) }})
                  </small>
                </template>
              </el-table-column>

              <!-- 配息率 -->
              <el-table-column label="配息率" width="90" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ row.dividendRate != null ? (Number(row.dividendRate) * 100).toFixed(2) + '%' : '-' }}</span>
                </template>
              </el-table-column>

              <!-- 預估配息 -->
              <el-table-column label="預估配息" width="100" align="right">
                <template #default="{ row }">{{ fmt(stockDividend(row)) }}</template>
              </el-table-column>

              <!-- 刪除 -->
              <el-table-column width="40" fixed="right">
                <template #default="{ row }">
                  <el-button type="danger" size="small" :icon="Delete" circle @click="removeStock(row)" />
                </template>
              </el-table-column>
            </el-table>

            <!-- 台股小計 -->
            <div class="sec-summary">
              <div class="ds-item">
                <span class="ds-label">投資成本</span>
                <span class="ds-val">{{ fmt(twSummary.cost) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">目前總值</span>
                <span class="ds-val">{{ fmt(twSummary.value) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">損益</span>
                <span class="ds-val" :class="twSummary.profit >= 0 ? 'profit' : 'loss'">
                  {{ fmt(twSummary.profit) }}
                  <small style="font-weight:400"> ({{ pct(twSummary.profitRate) }})</small>
                </span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">預估配息</span>
                <span class="ds-val ds-dividend">{{ fmt(twSummary.dividend) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">持股數</span>
                <span class="ds-val">{{ twStocks.length }} 檔</span>
              </div>
            </div>
          </el-tab-pane>

          <!-- 美股 Tab -->
          <el-tab-pane name="us">
            <template #label>
              <span style="display:inline-flex;align-items:center;gap:4px">
                <UsaMap :size="12" /> 美股
              </span>
              <el-badge :value="usStocks.length" type="warning" style="margin-left:4px" />
            </template>

            <div style="text-align:right;margin-bottom:8px">
              <el-button size="small" :icon="Plus" @click="addStock('美股')">新增美股</el-button>
            </div>

            <el-table :data="usStocks" size="small" row-key="_rowId" stripe>
              <el-table-column width="44">
                <template #default="{ row }">
                  <div class="sort-btns">
                    <el-button size="small" text :disabled="usStocks.indexOf(row)===0" @click="moveStock(row,-1)">↑</el-button>
                    <el-button size="small" text :disabled="usStocks.indexOf(row)===usStocks.length-1" @click="moveStock(row,1)">↓</el-button>
                  </div>
                </template>
              </el-table-column>
              <!-- Expand -->
              <el-table-column type="expand" width="40">
                <template #default="{ row }">
                  <div class="broker-expand">
                    <el-table :data="row.brokerRows" size="small" border style="width:100%">
                      <!-- 券商 -->
                      <el-table-column label="券商" width="140">
                        <template #default="{ row: br }">
                          <el-select v-model="br.brokerId" size="small" style="width:100%" clearable>
                            <el-option v-for="b in brokerOptions" :key="b.value" :label="b.label" :value="b.value" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <!-- 股數 -->
                      <el-table-column label="股數" width="130">
                        <template #default="{ row: br }">
                          <el-input v-model="br.sharesStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="() => { br.shares = numParse(br.sharesStr, 5); br.sharesStr = numFmt(br.shares); if (br.avgCost) { const p = br.currency === 'USD' ? 6 : 2; br.investmentCost = numParse(((br.avgCost||0)*(br.shares||0)).toFixed(p), p); br.investmentCostStr = numFmt(br.investmentCost) } }" />
                        </template>
                      </el-table-column>
                      <!-- 幣別 -->
                      <el-table-column label="幣別" width="110">
                        <template #default="{ row: br }">
                          <el-select v-model="br.currency" size="small" style="width:100%">
                            <el-option value="TWD" label="TWD" />
                            <el-option value="USD" label="USD" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <!-- 均價（輸入後自動計算持股成本） -->
                      <el-table-column label="均價" width="130">
                        <template #default="{ row: br }">
                          <el-input v-model="br.avgCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="() => { const p = br.currency === 'USD' ? 6 : 2; br.avgCost = numParse(br.avgCostStr, p); br.avgCostStr = numFmt(br.avgCost); br.investmentCost = numParse(((br.avgCost||0)*(br.shares||0)).toFixed(p), p); br.investmentCostStr = numFmt(br.investmentCost); if (br.currency === 'USD') br.originalCurrencyValue = br.avgCost }" />
                        </template>
                      </el-table-column>
                      <!-- 持股成本（可輸入，blur 後反算均價） -->
                      <el-table-column label="持股成本" width="130">
                        <template #default="{ row: br }">
                          <el-input v-model="br.investmentCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="() => { const p = br.currency === 'USD' ? 6 : 2; br.investmentCost = numParse(br.investmentCostStr, p); br.investmentCostStr = numFmt(br.investmentCost); if (br.shares > 0) { br.avgCost = parseFloat((br.investmentCost / br.shares).toFixed(p)); br.avgCostStr = numFmt(br.avgCost); if (br.currency === 'USD') br.originalCurrencyValue = br.avgCost } }" />
                        </template>
                      </el-table-column>
                      <!-- 現值(原幣)：唯讀，由最新股價 × 股數計算 -->
                      <el-table-column label="現值(原幣)" width="130" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px">
                            {{ br.currency === 'USD'
                              ? numFmt(calcBrOriginalValue(br, row).toFixed(2))
                              : fmt(calcBrTwdValue(br, row)) }}
                          </span>
                        </template>
                      </el-table-column>
                      <!-- 現值(台幣)：唯讀，USD 再乘匯率 -->
                      <el-table-column label="現值(台幣)" width="130" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px">{{ fmt(calcBrTwdValue(br, row)) }}</span>
                        </template>
                      </el-table-column>
                      <!-- 刪除 -->
                      <el-table-column width="40">
                        <template #default="{ $index }">
                          <el-button type="danger" size="small" :icon="Delete" circle
                            @click="removeBrokerRow(row, $index)" />
                        </template>
                      </el-table-column>
                    </el-table>
                    <div style="display:flex;gap:8px;margin-top:8px">
                      <el-button size="small" :icon="Plus" @click="addBrokerRow(row, '美股')">新增券商持股</el-button>
                      <el-button size="small" type="primary" @click="submit" :loading="saving">存檔</el-button>
                    </div>
                  </div>
                </template>
              </el-table-column>

              <!-- 股號/股名 -->
              <el-table-column label="股號/股名" min-width="155">
                <template #default="{ row }">
                  <el-input v-model="row.stockCode" size="small" placeholder="Ticker"
                    style="margin-bottom:3px"
                    @input="row.stockCode = row.stockCode.toUpperCase()"
                    @blur="fetchPriceForRow(row)" />
                  <el-input v-model="row.stockName" size="small" placeholder="股票名稱" />
                </template>
              </el-table-column>

              <!-- 股數（唯讀，合計所有券商） -->
              <el-table-column label="股數" width="110" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ fmtShares(stockShares(row), '美股') }}</span>
                </template>
              </el-table-column>

              <!-- 均價（唯讀，單券商顯示；多券商顯示 "-"） -->
              <el-table-column label="均價" width="100" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">
                    {{ row.brokerRows.length === 1
                        ? numFmt(row.brokerRows[0].avgCost)
                        : '-' }}
                  </span>
                </template>
              </el-table-column>

              <!-- 股價/漲跌 -->
              <el-table-column label="股價/漲跌(%)" width="185" align="right">
                <template #default="{ row }">
                  <div class="price-cell">
                    <span v-if="row.latestPrice" class="price-num">{{ fmtPriceUs(row.latestPrice) }}</span>
                    <span v-else class="price-empty">-</span>
                    <div v-if="row.priceChange !== null && row.latestPrice"
                      :class="Number(row.priceChange) >= 0 ? 'price-up' : 'price-down'"
                      class="price-change">
                      {{ Number(row.priceChange) >= 0 ? '▲' : '▼' }}
                      {{ Math.abs(Number(row.priceChange)).toFixed(4) }}
                      ({{ (Number(row.priceChangePct) * 100).toFixed(4) }}%)
                    </div>
                  </div>
                </template>
              </el-table-column>

              <!-- 持股成本（唯讀） -->
              <el-table-column label="持股成本" width="110" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ fmt(stockCost(row)) }}</span>
                </template>
              </el-table-column>

              <!-- 總現值 -->
              <el-table-column label="總現值(台幣)" width="120" align="right">
                <template #default="{ row }">{{ fmt(stockValue(row)) }}</template>
              </el-table-column>

              <!-- 損益 -->
              <el-table-column label="損益" width="130" align="right">
                <template #default="{ row }">
                  <span :class="stockProfit(row)>=0?'profit':'loss'">{{ fmt(stockProfit(row)) }}</span>
                  <small :class="stockProfit(row)>=0?'profit':'loss'" style="display:block;font-weight:400">
                    ({{ pct(stockProfitRate(row)) }})
                  </small>
                </template>
              </el-table-column>

              <!-- 配息率 -->
              <el-table-column label="配息率" width="90" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ row.dividendRate != null ? (Number(row.dividendRate) * 100).toFixed(2) + '%' : '-' }}</span>
                </template>
              </el-table-column>

              <!-- 預估配息 -->
              <el-table-column label="預估配息" width="100" align="right">
                <template #default="{ row }">{{ fmt(stockDividend(row)) }}</template>
              </el-table-column>

              <!-- 刪除 -->
              <el-table-column width="40" fixed="right">
                <template #default="{ row }">
                  <el-button type="danger" size="small" :icon="Delete" circle @click="removeStock(row)" />
                </template>
              </el-table-column>
            </el-table>

            <!-- 美股小計 -->
            <div class="sec-summary">
              <div class="ds-item">
                <span class="ds-label">投資成本</span>
                <span class="ds-val">{{ fmt(usSummary.cost) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">目前總值</span>
                <span class="ds-val">{{ fmt(usSummary.value) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">損益</span>
                <span class="ds-val" :class="usSummary.profit >= 0 ? 'profit' : 'loss'">
                  {{ fmt(usSummary.profit) }}
                  <small style="font-weight:400"> ({{ pct(usSummary.profitRate) }})</small>
                </span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">預估配息</span>
                <span class="ds-val ds-dividend">{{ fmt(usSummary.dividend) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">持股數</span>
                <span class="ds-val">{{ usStocks.length }} 檔</span>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>

      </el-card>

      <div style="text-align:center;margin-top:20px">
        <el-button @click="$router.back()">取消</el-button>
        <el-button type="primary" @click="submit" :loading="saving">
          {{ isEdit ? '存檔' : '儲存快照' }}
        </el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup>
import { ArrowLeft, Plus, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useAssetStore } from '@/stores/assetStore'
import { marketDataApi, institutionApi, snapshotApi } from '@/api/index'
import TaiwanMap from '@/components/TaiwanMap.vue'
import UsaMap from '@/components/UsaMap.vue'

const route  = useRoute()
const router = useRouter()
const store  = useAssetStore()
const formRef = ref()
const saving  = ref(false)
const loading = ref(false)

// ===== Row sort helpers =====
const moveRow = (array, idx, dir) => {
  const target = idx + dir
  if (target < 0 || target >= array.length) return
  const item = array.splice(idx, 1)[0]
  array.splice(target, 0, item)
}

const moveStock = (stock, dir) => {
  const peers = form.stocks.filter(s => s.market === stock.market)
  const vi = peers.indexOf(stock)
  const vt = vi + dir
  if (vt < 0 || vt >= peers.length) return
  const fi = form.stocks.indexOf(stock)
  const ft = form.stocks.indexOf(peers[vt])
  form.stocks.splice(fi, 1)
  form.stocks.splice(ft, 0, stock)
}

const isEdit = computed(() => !!route.params.id && route.params.id !== 'new')

const form = reactive({
  snapshotDate: '',
  usdExchangeRate: 31.5,
  notes: '',
  deposits: [],
  funds: [],
  stocks: []   // grouped: each item has brokerRows[]
})

const rules = {
  snapshotDate: [{ required: true, message: '請選擇日期' }]
}

// ===== Options =====
// 銀行/券商從 API 動態載入（取代 hardcoded enum）
const bankOptions        = ref([])   // { value: id, label: displayName }
const brokerOptions      = ref([])   // { value: id, label: displayName }
const depositTypeOptions = ref([])   // { value: code, label: displayName }

async function loadInstitutions() {
  const [banks, brokers, depositTypes] = await Promise.all([
    institutionApi.getAllBanks(),
    institutionApi.getAllBrokers(),
    institutionApi.getAllDepositTypes()
  ])
  bankOptions.value        = banks.filter(b => b.active).map(b => ({ value: b.id, label: b.displayName }))
  brokerOptions.value      = brokers.filter(b => b.active).map(b => ({ value: b.id, label: b.displayName }))
  depositTypeOptions.value = depositTypes.filter(d => d.active).map(d => ({ value: d.code, label: d.displayName }))
}

const stockTab = ref('tw')

// ===== 數字顯示：加千位逗號，保留小數部分 =====
const numFmt = (v) => {
  if (v === null || v === undefined || v === '') return ''
  const s = String(v).replace(/,/g, '')
  const dot = s.indexOf('.')
  const intPart = dot >= 0 ? s.slice(0, dot) : s
  const decPart = dot >= 0 ? s.slice(dot) : ''
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + decPart
}
// 字串解析為數字，precision=0 → 整數，>0 → 保留小數
const numParse = (v, precision = 0) => {
  const raw = String(v || '').replace(/,/g, '')
  const n = parseFloat(raw)
  if (isNaN(n)) return 0
  return precision === 0 ? Math.round(n) : parseFloat(n.toFixed(precision))
}

// ===== Formatters =====
const fmt = (v) => {
  if (v == null || isNaN(v)) return '-'
  return `$${Number(v).toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
}
const fmtPrice = (v) => {
  if (v == null) return '-'
  return Number(v).toLocaleString('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
}
const fmtPriceUs = (v) => {
  if (v == null) return '-'
  return Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 6 })
}
const pct = (v) => v != null ? `${(Number(v) * 100).toFixed(2)}%` : '-'
const fmtShares = (v, market) => {
  if (v == null) return '-'
  const n = Number(v)
  if (market === '美股') return n.toLocaleString('en-US', { minimumFractionDigits: 5, maximumFractionDigits: 5 })
  return n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })
}

// ===== Computed: stock group helpers =====
const stockShares = (s) => s.brokerRows.reduce((a, r) => a + Number(r.shares || 0), 0)

/** 單一 brokerRow 的原幣現值（美股永遠是 USD；台股永遠是 TWD） */
const calcBrOriginalValue = (br, stock) => {
  if (stock.latestPrice != null) return Number(br.shares || 0) * Number(stock.latestPrice)
  // fallback：無最新股價時用存檔值
  if (stock.market === '美股') {
    // currentValueOriginal 為 USD；若無則由台幣存檔值反推
    if (br.currentValueOriginal) return Number(br.currentValueOriginal)
    return Number(br.currentValue || 0) / (form.usdExchangeRate || 1)
  }
  return Number(br.currentValue || 0)
}

/** 單一 brokerRow 的台幣現值（美股原幣 × 匯率；台股直接為 TWD） */
const calcBrTwdValue = (br, stock) => {
  const orig = calcBrOriginalValue(br, stock)
  if (stock.market === '美股') return Math.round(orig * (form.usdExchangeRate || 1))
  return Math.round(orig)
}

/** 單一 brokerRow 的原幣成本：
 *  investmentCost > 0 時直接用；否則由 avgCost × shares 計算（處理 DB 成本為 0 但有填均價的情況） */
const brCost = (br) => {
  const ic = Number(br.investmentCost || 0)
  if (ic > 0) return ic
  const fromAvg = Number(br.avgCost || 0) * Number(br.shares || 0)
  return br.currency === 'USD'
    ? parseFloat(fromAvg.toFixed(6))
    : Math.round(fromAvg)
}

/** 台幣投資成本（USD 計價的 br 需乘匯率換算） */
const stockCost = (s) => s.brokerRows.reduce((a, r) => {
  const cost = brCost(r)
  return a + (r.currency === 'USD' ? Math.round(cost * (form.usdExchangeRate || 1)) : cost)
}, 0)

const stockValue    = (s) => s.brokerRows.reduce((a, r) => a + calcBrTwdValue(r, s), 0)
const stockProfit   = (s) => stockValue(s) - stockCost(s)
const stockProfitRate = (s) => { const c = stockCost(s); return c > 0 ? stockProfit(s) / c : 0 }
const stockDividend = (s) => stockValue(s) * Number(s.dividendRate || 0)

// ===== Computed: deposits =====
const depositTwd = (d) => {
  const amt = Number(d.amount || 0)
  return d.currency === 'USD' ? Math.round(amt * (form.usdExchangeRate || 1)) : amt
}
const depositTotal = computed(() =>
  form.deposits.reduce((sum, d) => sum + depositTwd(d), 0)
)
const depositFixed = computed(() =>
  form.deposits.filter(d => (d.depositType || '').includes('定存'))
    .reduce((sum, d) => sum + depositTwd(d), 0)
)
const depositDemand = computed(() =>
  form.deposits.filter(d => !(d.depositType || '').includes('定存'))
    .reduce((sum, d) => sum + depositTwd(d), 0)
)

const fundTotalInvest  = computed(() => form.funds.reduce((s, f) => s + Number(f.investmentAmount || 0), 0))
const fundTotalValue   = computed(() => form.funds.reduce((s, f) => s + Number(f.currentValue || 0), 0))
const fundTotalProfit  = computed(() => fundTotalValue.value - fundTotalInvest.value)

// ===== Computed: filtered stock groups =====
const twStocks = computed(() => form.stocks.filter(s => s.market === '台股'))
const usStocks = computed(() => form.stocks.filter(s => s.market === '美股'))

const calcGroupedSummary = (stocks) => {
  let cost = 0, value = 0, dividend = 0
  for (const stock of stocks) {
    cost     += stockCost(stock)
    value    += stockValue(stock)
    dividend += stockDividend(stock)
  }
  const profit = value - cost
  const profitRate = cost > 0 ? profit / cost : 0
  return { cost, value, profit, profitRate, dividend }
}

const twSummary  = computed(() => calcGroupedSummary(twStocks.value))
const usSummary  = computed(() => calcGroupedSummary(usStocks.value))
const allSummary = computed(() => calcGroupedSummary(form.stocks))

// ===== Mutations =====
const addDeposit = () =>
  form.deposits.push({ bankId: null, depositType: '活存', currency: 'TWD', amount: 0, amountStr: '0' })

const addFund = () =>
  form.funds.push({ fundName: '', bankId: null, investmentAmount: 0, investmentAmountStr: '0', currentValue: 0, currentValueStr: '0' })

let _idSeq = 1
const newBrokerRow = (_market) => ({
  _id: _idSeq++,
  brokerId: null,
  shares: 0,
  sharesStr: '0',
  currency: 'TWD',
  investmentCost: 0,
  investmentCostStr: '0',
  avgCost: 0,
  avgCostStr: '0',
  originalCurrencyValue: null,
  originalCurrencyValueStr: '',
  currentValueOriginal: 0,
  currentValueOriginalStr: '0',
  currentValue: 0,
  currentValueStr: '0'
})

const addStock = (market = '台股') => {
  form.stocks.push({
    _rowId: `${market}_new_${_idSeq++}`,
    stockCode: '',
    stockName: '',
    market,
    dividendRate: null,
    latestPrice: null,
    priceChange: null,
    priceChangePct: null,
    _fetchingPrice: false,
    _fetchingDividend: false,
    brokerRows: [newBrokerRow(market)]
  })
}

const removeStock = (row) => {
  const idx = form.stocks.indexOf(row)
  if (idx !== -1) form.stocks.splice(idx, 1)
}

const addBrokerRow = (stockRow, market) => {
  stockRow.brokerRows.push(newBrokerRow(market || stockRow.market))
}

const removeBrokerRow = (stockRow, idx) => {
  if (stockRow.brokerRows.length <= 1) {
    ElMessage.warning('每支股票至少需保留一筆券商持股')
    return
  }
  stockRow.brokerRows.splice(idx, 1)
}

// ===== 股票代號輸入完成後自動查詢價格 =====
/** 輸入代號 blur 時：
 *  - edit 模式（歷史快照）：查詢快照日期的歷史收盤價，
 *    無資料時針對該股票精準 backfill（從快照日期前 1 年）後重試
 *  - new 模式（新增快照）：從即時股價快取取得最新價格
 */
async function fetchPriceForRow(row) {
  if (!row.stockCode) return
  try {
    if (isEdit.value && form.snapshotDate) {
      // 歷史收盤價
      let prices = await marketDataApi.getPricesOnDate(form.snapshotDate, [{ code: row.stockCode, market: row.market }])
      if (prices.length === 0) {
        // 針對此股票精準 backfill：只拉快照日期前後各 30 天的資料，避免拉全量
        const sinceDate = new Date(new Date(form.snapshotDate).getTime() - 30 * 86400000)
          .toISOString().slice(0, 10)
        const untilDate = new Date(new Date(form.snapshotDate).getTime() + 5 * 86400000)
          .toISOString().slice(0, 10)
        try { await marketDataApi.backfillSingleStock(row.stockCode, row.market, sinceDate, untilDate) } catch {}
        prices = await marketDataApi.getPricesOnDate(form.snapshotDate, [{ code: row.stockCode, market: row.market }])
      }
      if (prices.length > 0 && prices[0].price != null) {
        row.latestPrice    = prices[0].price
        row.priceChange    = null
        row.priceChangePct = null
      }
    } else {
      // 新增快照：呼叫即時股價 API（同時取得股票名稱）
      const result = await marketDataApi.getPrice(row.stockCode, row.market)
      if (result && result.price != null) {
        row.latestPrice    = result.price
        row.priceChange    = result.change
        row.priceChangePct = result.changePct
      }
      if (result && result.stockName && !row.stockName) {
        row.stockName = result.stockName
      }
    }

    // 若名稱仍未填（任何市場），嘗試用即時 API 補名稱
    if (!row.stockName) {
      try {
        const result = await marketDataApi.getPrice(row.stockCode, row.market)
        if (result && result.stockName) row.stockName = result.stockName
      } catch {}
    }
    // 自動補查配息率；名稱若仍空白也補查（不受 dividendRate 是否已有值影響）
    if (row.dividendRate == null || !row.stockName) {
      try {
        const dr = await marketDataApi.getDividendRate(row.stockCode, row.market)
        if (dr && dr.dividendRate != null) row.dividendRate = dr.dividendRate
        if (dr && dr.stockName && !row.stockName) row.stockName = dr.stockName
      } catch {}
    }
  } catch {
    // 查不到就靜默略過，不打擾使用者
  }
}

// ===== Data fetching: price =====
const fetchPrice = async (row) => {
  if (!row.stockCode) {
    ElMessage.warning('請先輸入股票代號')
    return
  }
  row._fetchingPrice = true
  try {
    const result = await marketDataApi.getPrice(row.stockCode, row.market)
    row.latestPrice   = result.price
    row.priceChange   = result.change
    row.priceChangePct = result.changePct
    const sign = Number(result.change) >= 0 ? '▲' : '▼'
    const changePctStr = (Math.abs(Number(result.changePct)) * 100).toFixed(2)
    ElMessage.success(
      `${row.stockCode} ${fmtPrice(result.price)}　${sign}${Math.abs(Number(result.change)).toFixed(2)} (${changePctStr}%)　來源：${result.source}`
    )
  } catch {
    // interceptor shows error
  } finally {
    row._fetchingPrice = false
  }
}

// ===== 複製前一版 =====
const copyingPrev = reactive({ deposits: false, funds: false, stocks: false })

/** 找出「前一個」快照的 ID：
 *  - 新增模式：清單第一筆（最新）
 *  - 編輯模式：日期嚴格小於目前快照日期的最新那筆
 */
const getPrevSnapshotId = async () => {
  if (store.snapshots.length === 0) await store.fetchSnapshots()
  const snaps = store.snapshots   // 依日期 DESC 排列
  if (snaps.length === 0) return null
  if (!isEdit.value) return snaps[0].id
  const prev = snaps.find(s => s.snapshotDate < form.snapshotDate)
  return prev?.id ?? null
}

const copyPrevDeposits = async () => {
  const prevId = await getPrevSnapshotId()
  if (!prevId) { ElMessage.warning('找不到前一版本'); return }
  await ElMessageBox.confirm('確定要複製前一版的存款明細？目前內容將被取代。', '複製前一版', {
    confirmButtonText: '確定複製', cancelButtonText: '取消', type: 'warning'
  }).catch(() => { throw new Error('cancel') })
  copyingPrev.deposits = true
  try {
    const detail = await snapshotApi.getDetail(prevId)
    const rate = form.usdExchangeRate || 1
    form.deposits = detail.deposits.map(d => {
      const displayAmt = d.currency === 'USD'
        ? (d.originalAmount != null ? d.originalAmount : Number((d.amount / rate).toFixed(2)))
        : d.amount
      return { bankId: d.bankId || null, depositType: d.depositType, currency: d.currency,
               amount: displayAmt, amountStr: numFmt(displayAmt), notes: d.notes }
    })
    ElMessage.success(`已複製前一版存款明細（${detail.snapshotDate}，共 ${detail.deposits.length} 筆）`)
  } catch (e) { if (e?.message !== 'cancel') throw e
  } finally { copyingPrev.deposits = false }
}

const copyPrevFunds = async () => {
  const prevId = await getPrevSnapshotId()
  if (!prevId) { ElMessage.warning('找不到前一版本'); return }
  await ElMessageBox.confirm('確定要複製前一版的信託基金？目前內容將被取代。', '複製前一版', {
    confirmButtonText: '確定複製', cancelButtonText: '取消', type: 'warning'
  }).catch(() => { throw new Error('cancel') })
  copyingPrev.funds = true
  try {
    const detail = await snapshotApi.getDetail(prevId)
    form.funds = detail.funds.map(f => ({
      fundName: f.fundName, fundCode: f.fundCode, bankId: f.bankId || null,
      investmentAmount: f.investmentAmount, investmentAmountStr: numFmt(f.investmentAmount),
      currentValue: f.currentValue, currentValueStr: numFmt(f.currentValue)
    }))
    ElMessage.success(`已複製前一版信託基金（${detail.snapshotDate}，共 ${detail.funds.length} 筆）`)
  } catch (e) { if (e?.message !== 'cancel') throw e
  } finally { copyingPrev.funds = false }
}

const copyPrevStocks = async () => {
  const prevId = await getPrevSnapshotId()
  if (!prevId) { ElMessage.warning('找不到前一版本'); return }
  await ElMessageBox.confirm('確定要複製前一版的股票？目前內容將被取代。', '複製前一版', {
    confirmButtonText: '確定複製', cancelButtonText: '取消', type: 'warning'
  }).catch(() => { throw new Error('cancel') })
  copyingPrev.stocks = true
  try {
    const detail = await snapshotApi.getDetail(prevId)
    form.stocks = groupStocks(detail.stocks.map(s => ({
      stockCode: s.stockCode, stockName: s.stockName, market: s.market,
      brokerId: s.brokerId || null, shares: s.shares, investmentCost: s.investmentCost,
      currentValue: s.currentValue, dividendRate: s.dividendRate,
      currency: s.currency, originalCurrencyValue: s.originalCurrencyValue
    })))
    ElMessage.success(`已複製前一版股票（${detail.snapshotDate}，共 ${detail.stocks.length} 筆）`)
    // 補查缺名稱的股票（靜默）
    for (const row of form.stocks) {
      if (!row.stockName && row.stockCode) fetchPriceForRow(row)
    }
  } catch (e) { if (e?.message !== 'cancel') throw e
  } finally { copyingPrev.stocks = false }
}

// ===== 一次更新所有股票股價＋配息率 =====
const refreshingAll = ref(false)
const refreshAllPrices = async () => {
  refreshingAll.value = true
  try {
    if (isEdit.value) {
      // 歷史快照模式：針對每支股票精準 backfill（只拉快照日期前後 30 天），再批次載入
      const sinceDate = new Date(new Date(form.snapshotDate).getTime() - 30 * 86400000)
        .toISOString().slice(0, 10)
      const untilDate = new Date(new Date(form.snapshotDate).getTime() + 5 * 86400000)
        .toISOString().slice(0, 10)
      const stocksToBackfill = form.stocks.filter(s => s.stockCode)
      await Promise.all(stocksToBackfill.map(s =>
        marketDataApi.backfillSingleStock(s.stockCode, s.market, sinceDate, untilDate).catch(() => {})
      ))
      await loadAllPrices()
      await refreshAllDividendRates()
      const count = form.stocks.filter(s => s.latestPrice != null).length
      if (count > 0) {
        ElMessage.success(`已載入 ${count} 支股票的歷史收盤價及配息率（${form.snapshotDate}）`)
      } else {
        ElMessage.warning(`找不到 ${form.snapshotDate} 的歷史收盤價，請確認歷史資料是否已回補`)
      }
    } else {
      // 新增快照模式：先觸發後端抓取最新行情，再載入
      await marketDataApi.refreshPrices()
      await loadAllPrices()
      await refreshAllDividendRates()
      const count = form.stocks.filter(s => s.latestPrice != null).length
      ElMessage.success(`已更新 ${count} 支股票的股價及配息率`)
    }
  } catch {
    ElMessage.error('更新失敗')
  } finally {
    refreshingAll.value = false
  }
}

/** 批次更新所有股票的配息率 */
async function refreshAllDividendRates() {
  const promises = form.stocks
    .filter(s => s.stockCode)
    .map(async (row) => {
      try {
        const result = await marketDataApi.getDividendRate(row.stockCode, row.market)
        row.dividendRate = result.dividendRate
      } catch {
        // 部分股票（如 ETF）可能無配息率，忽略錯誤
      }
    })
  await Promise.all(promises)
}

// ===== Data fetching: dividend rate =====
const fetchDividendRate = async (row) => {
  if (!row.stockCode) {
    ElMessage.warning('請先輸入股票代號')
    return
  }
  row._fetchingDividend = true
  try {
    const result = await marketDataApi.getDividendRate(row.stockCode, row.market)
    row.dividendRate = result.dividendRate
    ElMessage.success(
      `${row.stockCode} 配息率：${(Number(result.dividendRate) * 100).toFixed(2)}%（${result.source} · ${result.description}）`
    )
  } catch {
    if (row.market === '台股' && /^0\d/.test(row.stockCode)) {
      ElMessage.warning(`${row.stockCode} 為台灣ETF，請手動填入配息率`)
    }
  } finally {
    row._fetchingDividend = false
  }
}

// ===== Group / Flatten helpers =====
const groupStocks = (flat) => {
  const map = new Map()
  for (const s of flat) {
    const key = `${s.market}_${s.stockCode}`
    if (!map.has(key)) {
      map.set(key, {
        _rowId: key,
        stockCode: s.stockCode,
        stockName: s.stockName,
        market: s.market,
        dividendRate: s.dividendRate,
        latestPrice: null,
        priceChange: null,
        priceChangePct: null,
        _fetchingPrice: false,
        _fetchingDividend: false,
        brokerRows: []
      })
    }
    const sh = Number(s.shares || 0)
    const ic = Number(s.investmentCost || 0)
    const cur = s.currency || 'TWD'
    // 若 USD 且有 originalCurrencyValue，以 USD 原幣均價為主；否則由台幣成本推算
    const avg = cur === 'USD' && s.originalCurrencyValue
      ? Number(s.originalCurrencyValue)
      : (sh > 0 ? Number((ic / sh).toFixed(4)) : 0)
    const totalCost = cur === 'USD' && s.originalCurrencyValue
      ? Number((avg * sh).toFixed(5))
      : ic
    map.get(key).brokerRows.push({
      _id: _idSeq++,
      brokerId: s.brokerId || null,
      shares: sh,
      sharesStr: numFmt(sh),
      currency: cur,
      investmentCost: totalCost,
      investmentCostStr: numFmt(totalCost),
      avgCost: avg,
      avgCostStr: numFmt(avg),
      originalCurrencyValue: cur === 'USD' ? avg : null,
      originalCurrencyValueStr: cur === 'USD' ? numFmt(avg) : '',
      currentValueOriginal: 0,
      currentValueOriginalStr: '0',
      currentValue: s.currentValue,
      currentValueStr: numFmt(s.currentValue)
    })
  }
  return [...map.values()]
}

const flattenStocks = () =>
  form.stocks.flatMap(stock =>
    stock.brokerRows.map(br => ({
      stockCode:             stock.stockCode,
      stockName:             stock.stockName,
      market:                stock.market,
      brokerId:              br.brokerId || null,
      shares:                br.shares,
      investmentCost:        brCost(br),
      currentValue:          calcBrTwdValue(br, stock),
      estimatedDividend:     Math.round(calcBrTwdValue(br, stock) * Number(stock.dividendRate || 0)),
      dividendRate:          stock.dividendRate,
      currency:              br.currency || 'TWD',
      originalCurrencyValue: br.currency === 'USD' ? br.originalCurrencyValue : null
    }))
  )

// ===== 自動載入與定時更新股價 =====
const marketStatus = ref({ twMarketOpen: false, usMarketOpen: false })
let priceTimer = null

/** 批次載入股價到各 row
 *  - edit 模式：用快照日期的歷史收盤價（closest-on-or-before）；
 *               若 DB 無資料則觸發 backfill 後重試；仍無資料則沿用 br.currentValue
 *  - new 模式：用 stock_price 快取表的最新價格
 */
async function loadAllPrices() {
  try {
    if (isEdit.value && form.snapshotDate) {
      // ── 歷史收盤價模式 ──
      const stocks = form.stocks
        .filter(s => s.stockCode)
        .map(s => ({ code: s.stockCode, market: s.market }))
      if (stocks.length === 0) return

      const applyHistoricalPrices = (prices) => {
        const map = {}
        for (const p of prices) map[`${p.market}_${p.stockCode}`] = p
        for (const row of form.stocks) {
          const key = `${row.market}_${row.stockCode}`
          const p = map[key]
          if (p && p.price != null) {
            row.latestPrice    = p.price
            row.priceChange    = null
            row.priceChangePct = null
          }
        }
      }

      // 第一次查詢
      let prices = await marketDataApi.getPricesOnDate(form.snapshotDate, stocks)
      if (prices.length > 0) {
        applyHistoricalPrices(prices)
        return
      }

      // DB 無資料 → 觸發 backfill 後重試一次
      console.warn('歷史股價資料不足，嘗試回補...')
      try { await marketDataApi.backfillHistory() } catch {}
      prices = await marketDataApi.getPricesOnDate(form.snapshotDate, stocks)
      if (prices.length > 0) {
        applyHistoricalPrices(prices)
        return
      }

      // 仍無資料：保持 latestPrice = null，由 calcBrOriginalValue 沿用 br.currentValue
      console.warn(`找不到 ${form.snapshotDate} 的歷史收盤價，將使用快照存檔值`)
      return
    }

    // ── 新增快照：最新股價模式 ──
    const [prices, status] = await Promise.all([
      marketDataApi.getAllPrices(),
      marketDataApi.getMarketStatus()
    ])
    marketStatus.value = status
    const map = {}
    for (const p of prices) map[`${p.market}_${p.stockCode}`] = p
    for (const row of form.stocks) {
      const key = `${row.market}_${row.stockCode}`
      const p = map[key]
      if (p && p.price != null) {
        row.latestPrice    = p.price
        row.priceChange    = p.priceChange
        row.priceChangePct = p.changePercent != null ? p.changePercent / 100 : null
      }
      if (p && p.stockName && !row.stockName) row.stockName = p.stockName
    }
  } catch (e) {
    console.warn('批次載入股價失敗:', e)
  }
}

/** 啟動 5 分鐘定時刷新（盤中自動更新） */
function startPriceAutoRefresh() {
  stopPriceAutoRefresh()
  priceTimer = setInterval(async () => {
    // 先觸發後端更新 stock_price 表，再載入
    try { await marketDataApi.refreshPrices() } catch {}
    await loadAllPrices()
  }, 5 * 60 * 1000) // 5 分鐘
}
function stopPriceAutoRefresh() {
  if (priceTimer) { clearInterval(priceTimer); priceTimer = null }
}

onUnmounted(() => stopPriceAutoRefresh())

// ===== 依日期查詢匯率 =====
async function loadExchangeRateForDate(date) {
  if (!date) return
  try {
    const isToday = date === new Date().toISOString().slice(0, 10)
    // 如果是今天，先觸發後端刷新（取得最新即時匯率）
    if (isToday) {
      try { await marketDataApi.refreshExchangeRate('USD') } catch {}
    }
    // 查詢該日期前後 10 天的歷史匯率，取最近一筆（應對假日無資料）
    const startDate = new Date(new Date(date).getTime() - 10 * 86400000).toISOString().slice(0, 10)
    const rates = await marketDataApi.getExchangeRate('USD', startDate, date)
    if (rates && rates.length > 0) {
      form.usdExchangeRate = rates[rates.length - 1].midRate
    }
  } catch (e) {
    console.warn('載入匯率失敗:', e)
  }
}

// ===== 新增快照時：日期變更自動查詢匯率 =====
watch(() => form.snapshotDate, (newDate) => {
  if (!isEdit.value && newDate) {
    loadExchangeRateForDate(newDate)
  }
})

// ===== Lifecycle =====
onMounted(async () => {
  // 先載入銀行/券商選項（取代 hardcoded）
  await loadInstitutions()

  if (isEdit.value) {
    loading.value = true
    const detail = await store.fetchSnapshotDetail(route.params.id)
    Object.assign(form, {
      snapshotDate:    detail.snapshotDate,
      usdExchangeRate: detail.usdExchangeRate,
      notes:           detail.notes,
      deposits: detail.deposits.map(d => {
        // amount 欄位存放「該幣別的原始金額」：
        //   USD：優先用 originalAmount；若無則由台幣金額 ÷ 匯率反推
        //   TWD：直接用 amount
        const rate = detail.usdExchangeRate || 1
        const displayAmt = d.currency === 'USD'
          ? (d.originalAmount != null ? d.originalAmount : Number((d.amount / rate).toFixed(2)))
          : d.amount
        return {
          bankId: d.bankId || null, depositType: d.depositType, currency: d.currency,
          amount: displayAmt,
          amountStr: numFmt(displayAmt),
          notes: d.notes
        }
      }),
      funds: detail.funds.map(f => ({
        fundName: f.fundName, fundCode: f.fundCode, bankId: f.bankId || null,
        investmentAmount: f.investmentAmount, investmentAmountStr: numFmt(f.investmentAmount),
        currentValue: f.currentValue, currentValueStr: numFmt(f.currentValue)
      })),
      stocks: groupStocks(detail.stocks.map(s => ({
        stockCode: s.stockCode, stockName: s.stockName, market: s.market,
        brokerId: s.brokerId || null, shares: s.shares, investmentCost: s.investmentCost,
        currentValue: s.currentValue, dividendRate: s.dividendRate,
        currency: s.currency, originalCurrencyValue: s.originalCurrencyValue
      })))
    })
    loading.value = false
  }

  // 新增快照時：載入今天匯率作為預設值
  if (!isEdit.value) {
    const today = new Date().toISOString().slice(0, 10)
    form.snapshotDate = today
    await loadExchangeRateForDate(today)
  }

  // 載入所有已快取的股價，並啟動自動更新
  await loadAllPrices()
  startPriceAutoRefresh()

  // 補查缺名稱的股票（載入後靜默補齊）
  for (const row of form.stocks) {
    if (!row.stockName && row.stockCode) fetchPriceForRow(row)
  }

})

// ===== Submit =====
const submit = async () => {
  await formRef.value.validate()
  saving.value = true
  try {
    const deposits = form.deposits.map(d => ({
      bankId: d.bankId || null,
      depositType: d.depositType,
      currency: d.currency,
      amount: depositTwd(d),                            // 台幣金額
      originalAmount: d.currency === 'USD' ? d.amount : null,  // 原幣金額
      notes: d.notes || null
    }))
    const payload = { ...form, deposits, stocks: flattenStocks() }
    if (isEdit.value) {
      await store.updateSnapshot(route.params.id, payload)
      ElMessage.success('更新成功')
    } else {
      const created = await store.createSnapshot(payload)
      ElMessage.success('建立成功')
      // 新建完成後跳到該快照的編輯頁
      if (created?.id) {
        router.replace(`/snapshots/${created.id}/edit`)
      }
    }
  } catch (e) {
    ElMessage.error('儲存失敗：' + (e.response?.data?.message || e.message || '未知錯誤'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.section-title { font-size: 15px; font-weight: 600; }
/* 存款小計 — reuses sec-summary base */
.deposit-summary {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-top: 10px;
  padding: 12px 18px;
  background: #1e293b;
  border-radius: 8px;
  width: fit-content;
}
/* 共用 ds-* 元件（deposit-summary + sec-summary 都使用） */
.ds-item  { display: flex; align-items: baseline; gap: 8px; }
.ds-label { font-size: 11px; color: #7dd3fc; font-weight: 600; white-space: nowrap; letter-spacing: 0.02em; }
.ds-val   { font-size: 15px; font-weight: 700; color: #f1f5f9; white-space: nowrap; }
.ds-sep   { width: 1px; height: 30px; background: #475569; flex-shrink: 0; }
.ds-dividend { color: #34d399; }
.deposit-summary .profit, .sec-summary .profit { color: #4ade80; }
.deposit-summary .loss,   .sec-summary .loss   { color: #f87171; }
/* (removed — replaced by .sec-summary) */
.profit { color: #16a34a; font-weight: 600; }
.loss   { color: #dc2626; font-weight: 600; }

/* broker sub-table */
.broker-expand {
  padding: 10px 16px 10px 56px;
  background: #f8fafc;
}

/* zebra stripe - 覆蓋 Element Plus 預設，改為極淺灰 */
:deep(.el-table__row--striped .el-table__cell) { background: #f7f8fa !important; }

/* sort buttons */
.sort-btns { display: flex; flex-direction: column; align-items: center; gap: 0; }
.sort-btns .el-button { padding: 0 2px; height: 16px; min-height: 0; font-size: 11px; color: #94a3b8; }
.sort-btns .el-button:not(:disabled):hover { color: #3b82f6; }

/* price cell */
.price-cell { display: flex; flex-direction: column; align-items: flex-end; gap: 3px; }
.price-main { display: flex; align-items: center; gap: 6px; }
.price-num  { font-size: 16px; font-weight: 700; color: #1e293b; }
.price-empty { color: #94a3b8; font-size: 13px; }
.price-change { font-size: 12px; font-weight: 600; }
.price-up   { color: #dc2626; }  /* 台股漲為紅 */
.price-down { color: #16a34a; }  /* 台股跌為綠 */
.fetch-btn  { padding: 3px 7px !important; font-size: 11px !important; }

/* 統一小計列 */
.sec-summary {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding: 12px 18px;
  background: #1e293b;
  border-radius: 8px;
  width: fit-content;
}

/* 資產彙整橫列 */
.summary-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 14px 20px;
  background: #1e293b;
  border-radius: 10px;
}
.sb-item { display: flex; flex-direction: column; gap: 3px; min-width: 80px; }
.sb-label { font-size: 11px; color: #7dd3fc; font-weight: 600; white-space: nowrap; letter-spacing: 0.02em; }
.sb-val   { font-size: 15px; font-weight: 700; color: #f1f5f9; white-space: nowrap; }
.sb-sep   { width: 1px; height: 40px; background: #475569; flex-shrink: 0; }
.sb-total .sb-label { color: #a78bfa; font-size: 12px; }
.sb-total .sb-val   { font-size: 20px; color: #c4b5fd; }
.sb-dividend { color: #34d399; }
.summary-bar .profit { color: #4ade80; }
.summary-bar .loss   { color: #f87171; }
</style>
