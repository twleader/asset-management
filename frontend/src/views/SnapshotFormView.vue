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
            <div class="sb-val">{{ fmt(summaryTotalAssets) }}</div>
          </div>
          <div class="sb-sep" />
          <!-- 存款 -->
          <div class="sb-item">
            <div class="sb-label">💰 存款</div>
            <div class="sb-val">{{ fmt(summaryDeposit) }}</div>
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
          <!-- 英股 -->
          <div class="sb-item">
            <div class="sb-label">🇬🇧 英股現值</div>
            <div class="sb-val">{{ fmt(ukSummary.value) }}</div>
          </div>
          <div class="sb-item">
            <div class="sb-label">英股損益</div>
            <div class="sb-val" :class="ukSummary.profit >= 0 ? 'profit' : 'loss'">
              {{ fmt(ukSummary.profit) }}
              <small style="font-weight:400"> ({{ pct(ukSummary.profitRate) }})</small>
            </div>
          </div>
          <div class="sb-sep" />
          <!-- 共同基金 -->
          <div class="sb-item">
            <div class="sb-label">📊 共同基金現值</div>
            <div class="sb-val">{{ fmt(summaryFundValue) }}</div>
          </div>
          <div class="sb-item">
            <div class="sb-label">基金損益</div>
            <div class="sb-val" :class="summaryFundProfit >= 0 ? 'profit' : 'loss'">
              {{ fmt(summaryFundProfit) }}
              <small style="font-weight:400"> ({{ pct(summaryFundCost > 0 ? summaryFundProfit / summaryFundCost : 0) }})</small>
            </div>
          </div>
          <div class="sb-sep" />
          <!-- 預估年配息 -->
          <div class="sb-item">
            <div class="sb-label">預估年配息</div>
            <div class="sb-val sb-dividend">{{ fmt(summaryDividend) }}</div>
          </div>
        </div>
      </el-card>

      <!-- Deposits -->
      <!-- Deposits -->
      <el-card style="margin-bottom:16px">
        <template #header>
          <div style="display:flex;align-items:center;justify-content:space-between">
            <span class="section-title">💰 存款明細</span>
            <div style="display:flex;gap:8px">
              <el-button size="small" type="primary" :loading="saving" @click="submit">存檔</el-button>
              <el-button size="small" :loading="copyingPrev.deposits" @click="copyPrevDeposits">複製前一版</el-button>
              <el-button size="small" :icon="Plus" @click="addDeposit(depositTab)">新增</el-button>
            </div>
          </div>
        </template>

        <el-tabs v-model="depositTab">
          <!-- 台幣 Tab -->
          <el-tab-pane label="台幣" name="TWD">
            <el-table ref="twdDepositTableRef" :data="twdDeposits" size="small" row-key="_rowId">
              <el-table-column width="36" align="center">
                <template #default>
                  <el-icon class="row-drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
                </template>
              </el-table-column>
              <el-table-column label="銀行" width="160">
                <template #default="{ row }">
                  <el-select v-model="row.bankId" size="small" style="width:100%" clearable>
                    <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="存款類型" width="150">
                <template #default="{ row }">
                  <el-select v-model="row.depositType" size="small" style="width:100%">
                    <el-option v-for="t in twdDepositTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="金額（TWD）" align="right">
                <template #default="{ row }">
                  <el-input v-model="row.amountStr" size="small" style="width:100%" :input-style="{ textAlign: 'right' }"
                    @blur="row.amount = numParse(row.amountStr, 0); row.amountStr = numFmt(row.amount)" />
                </template>
              </el-table-column>
              <el-table-column label="年利率(%)" align="right" width="110">
                <template #default="{ row }">
                  <el-input v-model="row.annualInterestRateStr" size="small" style="width:100%"
                    :input-style="{ textAlign: 'right' }" placeholder="0"
                    @blur="row.annualInterestRate = numParse(row.annualInterestRateStr, 4); row.annualInterestRateStr = row.annualInterestRate ? numFmt(row.annualInterestRate, 4) : ''" />
                </template>
              </el-table-column>
              <el-table-column label="預估利息" align="right" width="120">
                <template #default="{ row }">
                  <span style="font-size:13px;color:#64748b">{{ depositInterestTwd(row) > 0 ? fmt(depositInterestTwd(row)) : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="備註">
                <template #default="{ row }">
                  <el-input v-model="row.notes" size="small" />
                </template>
              </el-table-column>
              <el-table-column width="50">
                <template #default="{ row }">
                  <el-popconfirm title="確定刪除此筆存款？" width="220" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                    @confirm="form.deposits.splice(form.deposits.indexOf(row),1)">
                    <template #reference>
                      <el-button type="danger" size="small" :icon="Delete" circle />
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- 美元 Tab -->
          <el-tab-pane label="美元" name="USD">
            <el-table ref="usdDepositTableRef" :data="usdDeposits" size="small" row-key="_rowId">
              <el-table-column width="36" align="center">
                <template #default>
                  <el-icon class="row-drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
                </template>
              </el-table-column>
              <el-table-column label="銀行" width="160">
                <template #default="{ row }">
                  <el-select v-model="row.bankId" size="small" style="width:100%" clearable>
                    <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="存款類型" width="150">
                <template #default="{ row }">
                  <el-select v-model="row.depositType" size="small" style="width:100%">
                    <el-option v-for="t in usdDepositTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="金額（USD）" align="right">
                <template #default="{ row }">
                  <el-input v-model="row.amountStr" size="small" style="width:100%" :input-style="{ textAlign: 'right' }"
                    @blur="row.amount = numParse(row.amountStr, 2); row.amountStr = numFmt(row.amount)" />
                </template>
              </el-table-column>
              <el-table-column label="台幣等值" align="right" width="120">
                <template #default="{ row }">
                  <span style="font-size:13px;color:#64748b">{{ fmt(depositTwd(row)) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="年利率(%)" align="right" width="110">
                <template #default="{ row }">
                  <el-input v-model="row.annualInterestRateStr" size="small" style="width:100%"
                    :input-style="{ textAlign: 'right' }" placeholder="0"
                    @blur="row.annualInterestRate = numParse(row.annualInterestRateStr, 4); row.annualInterestRateStr = row.annualInterestRate ? numFmt(row.annualInterestRate, 4) : ''" />
                </template>
              </el-table-column>
              <el-table-column label="預估利息(TWD)" align="right" width="130">
                <template #default="{ row }">
                  <span style="font-size:13px;color:#64748b">{{ depositInterestTwd(row) > 0 ? fmt(depositInterestTwd(row)) : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="備註">
                <template #default="{ row }">
                  <el-input v-model="row.notes" size="small" />
                </template>
              </el-table-column>
              <el-table-column width="50">
                <template #default="{ row }">
                  <el-popconfirm title="確定刪除此筆存款？" width="220" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                    @confirm="form.deposits.splice(form.deposits.indexOf(row),1)">
                    <template #reference>
                      <el-button type="danger" size="small" :icon="Delete" circle />
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>
            <!-- 美元小計 -->
            <div class="deposit-summary">
              <div class="ds-item">
                <span class="ds-label">活存</span>
                <span class="ds-val">USD ${{ numFmt(usdDemandAmt.toFixed(2)) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">定存</span>
                <span class="ds-val">USD ${{ numFmt(usdFixedAmt.toFixed(2)) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label" :style="{ color: transitUsdNetAmt < 0 ? '#dc2626' : '#16a34a' }">在途款項</span>
                <span class="ds-val" :style="{ color: transitUsdNetAmt < 0 ? '#dc2626' : '#16a34a' }">
                  {{ transitUsdNetAmt < 0 ? '-' : '+' }}USD ${{ numFmt(Math.abs(transitUsdNetAmt).toFixed(2)) }}
                </span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">美元總計</span>
                <span class="ds-val">USD ${{ numFmt(usdGrandAmt.toFixed(2)) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">台幣等值</span>
                <span class="ds-val">{{ fmt(usdGrandTwd) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">匯率</span>
                <span class="ds-val" style="font-size:13px">{{ numFmt(form.usdExchangeRate) }} TWD/USD</span>
              </div>
            </div>
          </el-tab-pane>

          <!-- 在途款項 Tab -->
          <el-tab-pane label="在途款項" name="TRANSIT">
            <el-tabs v-model="transitTab" size="small" style="margin-top:4px">
              <!-- 台幣 -->
              <el-tab-pane label="台幣" name="TWD">
                <el-table ref="transitTwdDepositTableRef" :data="transitTwdDeposits" size="small" row-key="_rowId">
                  <el-table-column width="36" align="center">
                    <template #default>
                      <el-icon class="row-drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
                    </template>
                  </el-table-column>
                  <el-table-column label="銀行" width="160">
                    <template #default="{ row }">
                      <el-select v-model="row.bankId" size="small" style="width:100%" clearable>
                        <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="類型" width="170">
                    <template #default="{ row }">
                      <el-select v-model="row.depositType" size="small" style="width:100%">
                        <el-option v-for="t in transitTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="金額（TWD）" align="right">
                    <template #default="{ row }">
                      <el-input v-model="row.amountStr" size="small" style="width:100%"
                        :input-style="{ textAlign:'right', color: isTransitPayable(row) ? '#dc2626' : '#16a34a' }"
                        @blur="row.amount = numParse(row.amountStr, 0); row.amountStr = numFmt(row.amount)" />
                    </template>
                  </el-table-column>
                  <el-table-column label="備註">
                    <template #default="{ row }">
                      <el-input v-model="row.notes" size="small" />
                    </template>
                  </el-table-column>
                  <el-table-column width="50">
                    <template #default="{ row }">
                      <el-popconfirm title="確定刪除此筆在途款項？" width="240" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                        @confirm="form.deposits.splice(form.deposits.indexOf(row),1)">
                        <template #reference>
                          <el-button type="danger" size="small" :icon="Delete" circle />
                        </template>
                      </el-popconfirm>
                    </template>
                  </el-table-column>
                </el-table>
                <div class="deposit-summary" style="background:#f8fafc">
                  <div class="ds-item">
                    <span class="ds-label" style="color:#dc2626">待付</span>
                    <span class="ds-val" style="color:#dc2626">
                      {{ fmt(transitTwdDeposits.filter(isTransitPayable).reduce((s,d)=>s+Number(d.amount||0),0)) }}
                    </span>
                  </div>
                  <div class="ds-sep" />
                  <div class="ds-item">
                    <span class="ds-label" style="color:#16a34a">待收</span>
                    <span class="ds-val" style="color:#16a34a">
                      {{ fmt(transitTwdDeposits.filter(d=>!isTransitPayable(d)).reduce((s,d)=>s+Number(d.amount||0),0)) }}
                    </span>
                  </div>
                </div>
              </el-tab-pane>
              <!-- 外幣 -->
              <el-tab-pane label="外幣" name="USD">
                <el-table ref="transitUsdDepositTableRef" :data="transitUsdDeposits" size="small" row-key="_rowId">
                  <el-table-column width="36" align="center">
                    <template #default>
                      <el-icon class="row-drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
                    </template>
                  </el-table-column>
                  <el-table-column label="銀行" width="160">
                    <template #default="{ row }">
                      <el-select v-model="row.bankId" size="small" style="width:100%" clearable>
                        <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="類型" width="170">
                    <template #default="{ row }">
                      <el-select v-model="row.depositType" size="small" style="width:100%">
                        <el-option v-for="t in transitTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="金額（USD）" align="right">
                    <template #default="{ row }">
                      <el-input v-model="row.amountStr" size="small" style="width:100%"
                        :input-style="{ textAlign:'right', color: isTransitPayable(row) ? '#dc2626' : '#16a34a' }"
                        @blur="row.amount = numParse(row.amountStr, 2); row.amountStr = numFmt(row.amount)" />
                    </template>
                  </el-table-column>
                  <el-table-column label="台幣等值" align="right" width="120">
                    <template #default="{ row }">
                      <span :style="{ fontSize:'13px', color: isTransitPayable(row) ? '#dc2626' : '#16a34a' }">
                        {{ isTransitPayable(row) ? '-' : '' }}{{ fmt(Math.abs(depositTwd(row))) }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="備註">
                    <template #default="{ row }">
                      <el-input v-model="row.notes" size="small" />
                    </template>
                  </el-table-column>
                  <el-table-column width="50">
                    <template #default="{ row }">
                      <el-popconfirm title="確定刪除此筆在途款項？" width="240" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                        @confirm="form.deposits.splice(form.deposits.indexOf(row),1)">
                        <template #reference>
                          <el-button type="danger" size="small" :icon="Delete" circle />
                        </template>
                      </el-popconfirm>
                    </template>
                  </el-table-column>
                </el-table>
                <div class="deposit-summary" style="background:#f8fafc">
                  <div class="ds-item">
                    <span class="ds-label" style="color:#dc2626">待付（台幣等值）</span>
                    <span class="ds-val" style="color:#dc2626">
                      {{ fmt(transitUsdDeposits.filter(isTransitPayable).reduce((s,d)=>s+Math.abs(depositTwd(d)),0)) }}
                    </span>
                  </div>
                  <div class="ds-sep" />
                  <div class="ds-item">
                    <span class="ds-label" style="color:#16a34a">待收（台幣等值）</span>
                    <span class="ds-val" style="color:#16a34a">
                      {{ fmt(transitUsdDeposits.filter(d=>!isTransitPayable(d)).reduce((s,d)=>s+Math.abs(depositTwd(d)),0)) }}
                    </span>
                  </div>
                </div>
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
        </el-tabs>

        <!-- 合計（單列）：僅台幣 tab 顯示 -->
        <div v-if="depositTab === 'TWD'" class="deposit-summary" style="border-top:1px solid #e2e8f0;margin-top:0">
          <div class="ds-item">
            <span class="ds-label">台幣存款總計</span>
            <span class="ds-val">{{ fmt(depositTwdTotal) }}</span>
          </div>
          <div class="ds-sep" />
          <div class="ds-item">
            <span class="ds-label">定存</span>
            <span class="ds-val">{{ fmt(depositTwdFixed) }}</span>
          </div>
          <div class="ds-sep" />
          <div class="ds-item">
            <span class="ds-label">活存／其他</span>
            <span class="ds-val">{{ fmt(depositTwdDemand) }}</span>
          </div>
          <template v-if="transitNetTwd !== 0">
            <div class="ds-sep" />
            <div class="ds-item">
              <span class="ds-label" :style="{ color: transitNetTwd < 0 ? '#dc2626' : '#16a34a' }">在途款項</span>
              <span class="ds-val" :style="{ color: transitNetTwd < 0 ? '#dc2626' : '#16a34a' }">
                {{ transitNetTwd < 0 ? '-' : '+' }}{{ fmt(Math.abs(transitNetTwd)) }}
              </span>
            </div>
          </template>
          <div class="ds-sep" />
          <div class="ds-item">
            <span class="ds-label">預估年利息</span>
            <span class="ds-val sb-dividend">{{ fmt(depositInterestTotal) }}</span>
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

            <el-table ref="twStockTableRef" :data="twStocks" size="small" row-key="_rowId" stripe
              @row-dblclick="onStockDblClick">
              <el-table-column width="36" align="center">
                <template #default>
                  <el-icon class="stock-drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
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
                      <!-- 買/賣 -->
                      <el-table-column label="買/賣" width="80">
                        <template #default="{ row: br }">
                          <el-select v-model="br.transactionType" size="small" style="width:100%">
                            <el-option value="買" label="買" />
                            <el-option value="賣" label="賣" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <!-- 交易日期 -->
                      <el-table-column label="交易日期" width="145">
                        <template #default="{ row: br }">
                          <el-date-picker v-model="br.transactionDate" type="date" size="small"
                            style="width:100%" value-format="YYYY-MM-DD" placeholder="選擇日期" />
                        </template>
                      </el-table-column>
                      <el-table-column label="股數" width="110">
                        <template #default="{ row: br }">
                          <el-input v-model="br.sharesStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="br.shares = numParse(br.sharesStr, 0); br.sharesStr = numFmt(br.shares); br.investmentCost = numParse(((br.avgCost||0) * (br.shares||0)).toFixed(2), 2); br.investmentCostStr = numFmt(br.investmentCost)" />
                        </template>
                      </el-table-column>
                      <!-- 均價（每股）→ 輸入後自動算總成本 -->
                      <el-table-column label="買入均價" width="110">
                        <template #default="{ row: br }">
                          <el-input v-model="br.avgCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="br.avgCost = numParse(br.avgCostStr, 2); br.avgCostStr = numFmt(br.avgCost); br.investmentCost = numParse(((br.avgCost||0) * (br.shares||0)).toFixed(2), 2); br.investmentCostStr = numFmt(br.investmentCost)" />
                        </template>
                      </el-table-column>
                      <!-- 持股成本（總額）→ 輸入後自動算均價 -->
                      <el-table-column label="持股成本" width="120">
                        <template #default="{ row: br }">
                          <el-input v-model="br.investmentCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="br.investmentCost = numParse(br.investmentCostStr, 2); br.investmentCostStr = numFmt(br.investmentCost); if (br.shares > 0) { br.avgCost = Number((br.investmentCost / br.shares).toFixed(2)); br.avgCostStr = numFmt(br.avgCost) }" />
                        </template>
                      </el-table-column>
                      <el-table-column label="現值" width="120" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px">{{ fmt(calcBrTwdValue(br, row)) }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="損益" width="130" align="right">
                        <template #default="{ row: br }">
                          <span :class="(calcBrTwdValue(br, row) - brCost(br))>=0?'profit':'loss'">
                            {{ fmt(calcBrTwdValue(br, row) - brCost(br)) }}
                          </span>
                          <small v-if="brCost(br) > 0"
                            :class="(calcBrTwdValue(br, row) - brCost(br))>=0?'profit':'loss'"
                            style="display:block;font-weight:400">
                            ({{ pct((calcBrTwdValue(br, row) - brCost(br)) / brCost(br)) }})
                          </small>
                        </template>
                      </el-table-column>
                      <el-table-column width="40">
                        <template #default="{ $index }">
                          <el-popconfirm title="確定刪除此筆券商持股？" width="240" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                            @confirm="removeBrokerRow(row, $index)">
                            <template #reference>
                              <el-button type="danger" size="small" :icon="Delete" circle />
                            </template>
                          </el-popconfirm>
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
              <el-table-column label="股號/股名" min-width="170">
                <template #default="{ row }">
                  <div style="display:flex;gap:4px">
                    <el-input v-model="row.stockCode" size="small" placeholder="代號"
                      style="width:80px;flex-shrink:0"
                      @blur="fetchPriceForRow(row)" />
                    <el-input v-model="row.stockName" size="small" placeholder="股票名稱" />
                  </div>
                </template>
              </el-table-column>

              <!-- 股價/漲跌 -->
              <el-table-column label="股價/漲跌(%)" width="190" align="right">
                <template #default="{ row }">
                  <div class="price-cell">
                    <span v-if="row.latestPrice" class="price-num">{{ fmtPrice(row.latestPrice) }}</span>
                    <span v-else class="price-empty">-</span>
                    <div v-if="row.priceChange !== null && row.latestPrice"
                      :class="Number(row.priceChange) >= 0 ? 'price-up' : 'price-down'"
                      class="price-change">
                      {{ Number(row.priceChange) >= 0 ? '▲' : '▼' }}
                      ${{ Math.abs(Number(row.priceChange)).toFixed(2) }}
                      ({{ Number(row.priceChangePct).toFixed(2) }}%)
                    </div>
                  </div>
                </template>
              </el-table-column>

              <!-- 股數（唯讀，合計所有券商） -->
              <el-table-column label="股數" width="100" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ fmtShares(stockShares(row), '台股') }}</span>
                </template>
              </el-table-column>

              <!-- 均價（唯讀，加權平均） -->
              <el-table-column label="買入均價" width="110" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{
                    (() => {
                      const totalShares = row.brokerRows.reduce((s, br) => s + Number(br.shares || 0), 0)
                      const totalCost   = row.brokerRows.reduce((s, br) => s + Number(br.investmentCost || 0), 0)
                      return totalShares > 0 ? '$' + numFmt(Number((totalCost / totalShares).toFixed(2))) : '-'
                    })()
                  }}</span>
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
              <el-table-column label="損益" width="160" align="right">
                <template #default="{ row }">
                  <span :class="stockProfit(row)>=0?'profit':'loss'">{{ fmt(stockProfit(row)) }}</span>
                  <small :class="stockProfit(row)>=0?'profit':'loss'" style="font-weight:400;margin-left:4px">
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
                  <el-popconfirm title="確定刪除此檔股票（含所有券商持股）？" width="280" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                    @confirm="removeStock(row)">
                    <template #reference>
                      <el-button type="danger" size="small" :icon="Delete" circle />
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>

            <!-- 台股小計 -->
            <div class="sec-summary">
              <div class="ds-item">
                <span class="ds-label">目前總值</span>
                <span class="ds-val">{{ fmt(twSummary.value) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">投資成本</span>
                <span class="ds-val">{{ fmt(twSummary.cost) }}</span>
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
                <UsFlag :size="20" /> 美股
              </span>
              <el-badge :value="usStocks.length" type="warning" style="margin-left:4px" />
            </template>

            <div style="text-align:right;margin-bottom:8px">
              <el-button size="small" :icon="Plus" @click="addStock('美股')">新增美股</el-button>
            </div>

            <el-table ref="usStockTableRef" :data="usStocks" size="small" row-key="_rowId" stripe
              @row-dblclick="onStockDblClick">
              <el-table-column width="36" align="center">
                <template #default>
                  <el-icon class="stock-drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
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
                      <!-- 買/賣 -->
                      <el-table-column label="買/賣" width="80">
                        <template #default="{ row: br }">
                          <el-select v-model="br.transactionType" size="small" style="width:100%">
                            <el-option value="買" label="買" />
                            <el-option value="賣" label="賣" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <!-- 交易日期 + 自動抓匯率 -->
                      <el-table-column label="交易日期" width="155">
                        <template #default="{ row: br }">
                          <el-date-picker v-model="br.transactionDate" type="date" size="small"
                            style="width:100%" value-format="YYYY-MM-DD" placeholder="選擇日期"
                            @change="(d) => onUsTransactionDateChange(br, d)" />
                        </template>
                      </el-table-column>
                      <!-- 股數 -->
                      <el-table-column label="股數" width="130">
                        <template #default="{ row: br }">
                          <el-input v-model="br.sharesStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="() => {
                              br.shares = numParse(br.sharesStr, 5); br.sharesStr = numFmt(br.shares)
                              if (br.avgCost) {
                                // avgCost 永遠以 USD 為基準
                                const usd = numParse(((br.avgCost||0)*(br.shares||0)).toFixed(6), 6)
                                br.investmentCost = usd
                                if (br.currency === 'TWD') br.investmentCostTwd = Math.round(usd * effectiveRate(br))
                                syncBrCostStr(br)
                              }
                            }" />
                        </template>
                      </el-table-column>
                      <!-- 幣別（持股成本幣別） -->
                      <el-table-column label="成本幣別" width="110">
                        <template #default="{ row: br }">
                          <el-select v-model="br.currency" size="small" style="width:100%"
                            @change="() => {
                              if (br.avgCost && br.shares) {
                                const usd = numParse(((br.avgCost||0)*(br.shares||0)).toFixed(6), 6)
                                br.investmentCost = usd
                                br.investmentCostTwd = br.currency === 'TWD' ? Math.round(usd * effectiveRate(br)) : null
                              }
                              syncBrCostStr(br)
                            }">
                            <el-option value="TWD" label="TWD" />
                            <el-option value="USD" label="USD" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <!-- 均價(USD)：永遠以 USD 輸入；連動計算持股成本 -->
                      <el-table-column label="買入均價(USD)" width="140">
                        <template #default="{ row: br }">
                          <el-input v-model="br.avgCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="() => {
                              br.avgCost = numParse(br.avgCostStr, 6)
                              br.avgCostStr = numFmt(br.avgCost)
                              br.originalCurrencyValue = br.avgCost
                              const usd = numParse(((br.avgCost||0)*(br.shares||0)).toFixed(6), 6)
                              br.investmentCost = usd
                              br.investmentCostTwd = br.currency === 'TWD' ? Math.round(usd * effectiveRate(br)) : null
                              syncBrCostStr(br)
                            }" />
                        </template>
                      </el-table-column>
                      <!-- 持股成本：依幣別顯示／輸入（TWD→TWD，USD→USD） -->
                      <el-table-column label="持股成本" width="145">
                        <template #default="{ row: br }">
                          <el-input v-model="br.investmentCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            :placeholder="br.currency"
                            @blur="() => {
                              const decimals = br.currency === 'USD' ? 6 : 0
                              const parsed = numParse(br.investmentCostStr, decimals)
                              if (br.currency === 'TWD') {
                                br.investmentCostTwd = Math.round(parsed)
                                br.investmentCost = effectiveRate(br) > 0
                                  ? Number((parsed / effectiveRate(br)).toFixed(6)) : 0
                              } else {
                                br.investmentCost = parsed
                                br.investmentCostTwd = null
                              }
                              syncBrCostStr(br)
                              if (br.shares > 0) {
                                br.avgCost = parseFloat((br.investmentCost / br.shares).toFixed(6))
                                br.avgCostStr = numFmt(br.avgCost)
                                br.originalCurrencyValue = br.avgCost
                              }
                            }" />
                        </template>
                      </el-table-column>
                      <!-- 現值(USD)：唯讀 -->
                      <el-table-column label="現值(USD)" width="110" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px">${{ numFmt(calcBrOriginalValue(br, row).toFixed(2)) }}</span>
                        </template>
                      </el-table-column>
                      <!-- 現值(台幣)：唯讀 -->
                      <el-table-column label="現值(台幣)" width="120" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px">{{ fmt(calcBrTwdValue(br, row)) }}</span>
                        </template>
                      </el-table-column>
                      <!-- 交易日匯率（自動填入，唯讀；無交易日匯率時顯示快照匯率） -->
                      <el-table-column label="交易日匯率" width="115" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px; color: #606266;">
                            {{ Number(effectiveRate(br)).toFixed(4) }}
                          </span>
                        </template>
                      </el-table-column>
                      <!-- 刪除 -->
                      <el-table-column width="40">
                        <template #default="{ $index }">
                          <el-popconfirm title="確定刪除此筆券商持股？" width="240" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                            @confirm="removeBrokerRow(row, $index)">
                            <template #reference>
                              <el-button type="danger" size="small" :icon="Delete" circle />
                            </template>
                          </el-popconfirm>
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
              <el-table-column label="股號/股名" min-width="220">
                <template #default="{ row }">
                  <div style="display:flex;gap:4px">
                    <el-input v-model="row.stockCode" size="small" placeholder="Ticker"
                      style="width:80px;flex-shrink:0"
                      @input="row.stockCode = row.stockCode.toUpperCase()"
                      @blur="fetchPriceForRow(row)" />
                    <el-input v-model="row.stockName" size="small" placeholder="股票名稱" />
                  </div>
                </template>
              </el-table-column>

              <!-- 股價/漲跌 -->
              <el-table-column label="股價/漲跌(%)" width="200" align="right">
                <template #default="{ row }">
                  <div class="price-cell">
                    <span v-if="row.latestPrice" class="price-num">{{ fmtPriceUs(row.latestPrice) }}</span>
                    <span v-else class="price-empty">-</span>
                    <div v-if="row.priceChange !== null && row.latestPrice"
                      :class="Number(row.priceChange) >= 0 ? 'price-up' : 'price-down'"
                      class="price-change">
                      {{ Number(row.priceChange) >= 0 ? '▲' : '▼' }}
                      ${{ Math.abs(Number(row.priceChange)).toFixed(2) }}
                      ({{ Number(row.priceChangePct).toFixed(2) }}%)
                    </div>
                  </div>
                </template>
              </el-table-column>

              <!-- 股數（唯讀，合計所有券商） -->
              <el-table-column label="股數" width="110" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ fmtShares(stockShares(row), '美股') }}</span>
                </template>
              </el-table-column>

              <!-- 均價(USD)（唯讀，加權平均 originalCurrencyValue） -->
              <el-table-column label="買入均價(USD)" width="130" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{
                    (() => {
                      const totalShares  = row.brokerRows.reduce((s, br) => s + Number(br.shares || 0), 0)
                      const totalUsdCost = row.brokerRows.reduce((s, br) =>
                        s + Number(br.originalCurrencyValue || br.avgCost || 0) * Number(br.shares || 0), 0)
                      return totalShares > 0 ? '$' + numFmt(Number((totalUsdCost / totalShares).toFixed(4))) : '-'
                    })()
                  }}</span>
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
              <el-table-column label="損益" width="170" align="right">
                <template #default="{ row }">
                  <span :class="stockProfit(row)>=0?'profit':'loss'">{{ fmt(stockProfit(row)) }}</span>
                  <small :class="stockProfit(row)>=0?'profit':'loss'" style="font-weight:400;margin-left:4px">
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
                  <el-popconfirm title="確定刪除此檔股票（含所有券商持股）？" width="280" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                    @confirm="removeStock(row)">
                    <template #reference>
                      <el-button type="danger" size="small" :icon="Delete" circle />
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>

            <!-- 美股小計 -->
            <div class="sec-summary">
              <div class="ds-item">
                <span class="ds-label">目前總值</span>
                <span class="ds-val">{{ fmt(usSummary.value) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">投資成本</span>
                <span class="ds-val">{{ fmt(usSummary.cost) }}</span>
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

          <!-- 英股 Tab（LSE UCITS ETF，USD 計價，欄位結構同美股） -->
          <el-tab-pane name="uk">
            <template #label>
              <span style="display:inline-flex;align-items:center;gap:4px">
                <span style="font-size:18px">🇬🇧</span> 英股
              </span>
              <el-badge :value="ukStocks.length" type="info" style="margin-left:4px" />
            </template>

            <div style="text-align:right;margin-bottom:8px">
              <el-button size="small" :icon="Plus" @click="addStock('英股')">新增英股</el-button>
            </div>

            <el-table ref="ukStockTableRef" :data="ukStocks" size="small" row-key="_rowId" stripe
              @row-dblclick="onStockDblClick">
              <el-table-column width="36" align="center">
                <template #default>
                  <el-icon class="stock-drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
                </template>
              </el-table-column>
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
                      <el-table-column label="買/賣" width="80">
                        <template #default="{ row: br }">
                          <el-select v-model="br.transactionType" size="small" style="width:100%">
                            <el-option value="買" label="買" />
                            <el-option value="賣" label="賣" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <el-table-column label="交易日期" width="155">
                        <template #default="{ row: br }">
                          <el-date-picker v-model="br.transactionDate" type="date" size="small"
                            style="width:100%" value-format="YYYY-MM-DD" placeholder="選擇日期"
                            @change="(d) => onUsTransactionDateChange(br, d)" />
                        </template>
                      </el-table-column>
                      <el-table-column label="股數" width="130">
                        <template #default="{ row: br }">
                          <el-input v-model="br.sharesStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="() => {
                              br.shares = numParse(br.sharesStr, 5); br.sharesStr = numFmt(br.shares)
                              if (br.avgCost) {
                                const usd = numParse(((br.avgCost||0)*(br.shares||0)).toFixed(6), 6)
                                br.investmentCost = usd
                                if (br.currency === 'TWD') br.investmentCostTwd = Math.round(usd * effectiveRate(br))
                                syncBrCostStr(br)
                              }
                            }" />
                        </template>
                      </el-table-column>
                      <el-table-column label="成本幣別" width="110">
                        <template #default="{ row: br }">
                          <el-select v-model="br.currency" size="small" style="width:100%"
                            @change="() => {
                              if (br.avgCost && br.shares) {
                                const usd = numParse(((br.avgCost||0)*(br.shares||0)).toFixed(6), 6)
                                br.investmentCost = usd
                                br.investmentCostTwd = br.currency === 'TWD' ? Math.round(usd * effectiveRate(br)) : null
                              }
                              syncBrCostStr(br)
                            }">
                            <el-option value="TWD" label="TWD" />
                            <el-option value="USD" label="USD" />
                          </el-select>
                        </template>
                      </el-table-column>
                      <el-table-column label="買入均價(USD)" width="140">
                        <template #default="{ row: br }">
                          <el-input v-model="br.avgCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            @blur="() => {
                              br.avgCost = numParse(br.avgCostStr, 6)
                              br.avgCostStr = numFmt(br.avgCost)
                              br.originalCurrencyValue = br.avgCost
                              const usd = numParse(((br.avgCost||0)*(br.shares||0)).toFixed(6), 6)
                              br.investmentCost = usd
                              br.investmentCostTwd = br.currency === 'TWD' ? Math.round(usd * effectiveRate(br)) : null
                              syncBrCostStr(br)
                            }" />
                        </template>
                      </el-table-column>
                      <el-table-column label="持股成本" width="145">
                        <template #default="{ row: br }">
                          <el-input v-model="br.investmentCostStr" size="small"
                            style="width:100%" :input-style="{ textAlign: 'right' }"
                            :placeholder="br.currency"
                            @blur="() => {
                              const decimals = br.currency === 'USD' ? 6 : 0
                              const parsed = numParse(br.investmentCostStr, decimals)
                              if (br.currency === 'TWD') {
                                br.investmentCostTwd = Math.round(parsed)
                                br.investmentCost = effectiveRate(br) > 0
                                  ? Number((parsed / effectiveRate(br)).toFixed(6)) : 0
                              } else {
                                br.investmentCost = parsed
                                br.investmentCostTwd = null
                              }
                              syncBrCostStr(br)
                              if (br.shares > 0) {
                                br.avgCost = parseFloat((br.investmentCost / br.shares).toFixed(6))
                                br.avgCostStr = numFmt(br.avgCost)
                                br.originalCurrencyValue = br.avgCost
                              }
                            }" />
                        </template>
                      </el-table-column>
                      <el-table-column label="現值(USD)" width="110" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px">${{ numFmt(calcBrOriginalValue(br, row).toFixed(2)) }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="現值(台幣)" width="120" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px">{{ fmt(calcBrTwdValue(br, row)) }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="交易日匯率" width="115" align="right">
                        <template #default="{ row: br }">
                          <span style="font-size:13px; color: #606266;">
                            {{ Number(effectiveRate(br)).toFixed(4) }}
                          </span>
                        </template>
                      </el-table-column>
                      <el-table-column width="40">
                        <template #default="{ $index }">
                          <el-popconfirm title="確定刪除此筆券商持股？" width="240" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                            @confirm="removeBrokerRow(row, $index)">
                            <template #reference>
                              <el-button type="danger" size="small" :icon="Delete" circle />
                            </template>
                          </el-popconfirm>
                        </template>
                      </el-table-column>
                    </el-table>
                    <div style="display:flex;gap:8px;margin-top:8px">
                      <el-button size="small" :icon="Plus" @click="addBrokerRow(row, '英股')">新增券商持股</el-button>
                      <el-button size="small" type="primary" @click="submit" :loading="saving">存檔</el-button>
                    </div>
                  </div>
                </template>
              </el-table-column>

              <el-table-column label="股號/股名" min-width="220">
                <template #default="{ row }">
                  <div style="display:flex;gap:4px">
                    <el-input v-model="row.stockCode" size="small" placeholder="代號 (如 CSPX)"
                      style="width:100px;flex-shrink:0"
                      @input="row.stockCode = row.stockCode.toUpperCase()"
                      @blur="fetchPriceForRow(row)" />
                    <el-input v-model="row.stockName" size="small" placeholder="股票名稱" />
                  </div>
                </template>
              </el-table-column>

              <el-table-column label="股價/漲跌(%)" width="200" align="right">
                <template #default="{ row }">
                  <div class="price-cell">
                    <span v-if="row.latestPrice" class="price-num">{{ fmtPriceUs(row.latestPrice) }}</span>
                    <span v-else class="price-empty">-</span>
                    <div v-if="row.priceChange !== null && row.latestPrice"
                      :class="Number(row.priceChange) >= 0 ? 'price-up' : 'price-down'"
                      class="price-change">
                      {{ Number(row.priceChange) >= 0 ? '▲' : '▼' }}
                      ${{ Math.abs(Number(row.priceChange)).toFixed(2) }}
                      ({{ Number(row.priceChangePct).toFixed(2) }}%)
                    </div>
                  </div>
                </template>
              </el-table-column>

              <el-table-column label="股數" width="110" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ fmtShares(stockShares(row), '英股') }}</span>
                </template>
              </el-table-column>

              <el-table-column label="買入均價(USD)" width="130" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{
                    (() => {
                      const totalShares  = row.brokerRows.reduce((s, br) => s + Number(br.shares || 0), 0)
                      const totalUsdCost = row.brokerRows.reduce((s, br) =>
                        s + Number(br.originalCurrencyValue || br.avgCost || 0) * Number(br.shares || 0), 0)
                      return totalShares > 0 ? '$' + numFmt(Number((totalUsdCost / totalShares).toFixed(4))) : '-'
                    })()
                  }}</span>
                </template>
              </el-table-column>

              <el-table-column label="持股成本" width="110" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ fmt(stockCost(row)) }}</span>
                </template>
              </el-table-column>

              <el-table-column label="總現值(台幣)" width="120" align="right">
                <template #default="{ row }">{{ fmt(stockValue(row)) }}</template>
              </el-table-column>

              <el-table-column label="損益" width="170" align="right">
                <template #default="{ row }">
                  <span :class="stockProfit(row)>=0?'profit':'loss'">{{ fmt(stockProfit(row)) }}</span>
                  <small :class="stockProfit(row)>=0?'profit':'loss'" style="font-weight:400;margin-left:4px">
                    ({{ pct(stockProfitRate(row)) }})
                  </small>
                </template>
              </el-table-column>

              <el-table-column label="配息率" width="90" align="right">
                <template #default="{ row }">
                  <span style="font-size:13px">{{ row.dividendRate != null ? (Number(row.dividendRate) * 100).toFixed(2) + '%' : '-' }}</span>
                </template>
              </el-table-column>

              <el-table-column label="預估配息" width="100" align="right">
                <template #default="{ row }">{{ fmt(stockDividend(row)) }}</template>
              </el-table-column>

              <el-table-column width="40" fixed="right">
                <template #default="{ row }">
                  <el-popconfirm title="確定刪除此檔股票（含所有券商持股）？" width="280" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                    @confirm="removeStock(row)">
                    <template #reference>
                      <el-button type="danger" size="small" :icon="Delete" circle />
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>

            <!-- 英股小計 -->
            <div class="sec-summary">
              <div class="ds-item">
                <span class="ds-label">目前總值</span>
                <span class="ds-val">{{ fmt(ukSummary.value) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">投資成本</span>
                <span class="ds-val">{{ fmt(ukSummary.cost) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">損益</span>
                <span class="ds-val" :class="ukSummary.profit >= 0 ? 'profit' : 'loss'">
                  {{ fmt(ukSummary.profit) }}
                  <small style="font-weight:400"> ({{ pct(ukSummary.profitRate) }})</small>
                </span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">預估配息</span>
                <span class="ds-val ds-dividend">{{ fmt(ukSummary.dividend) }}</span>
              </div>
              <div class="ds-sep" />
              <div class="ds-item">
                <span class="ds-label">持股數</span>
                <span class="ds-val">{{ ukStocks.length }} 檔</span>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>

      </el-card>

      <!-- Funds -->
      <el-card style="margin-bottom:16px">
        <template #header>
          <div style="display:flex;align-items:center;justify-content:space-between">
            <span class="section-title">📊 信託基金</span>
            <div style="display:flex;gap:8px">
              <el-button size="small" type="primary" :loading="saving" @click="submit">存檔</el-button>
              <el-button size="small" :loading="copyingPrev.funds" @click="copyPrevFunds">複製前一版</el-button>
              <el-button size="small" :loading="refreshingFundNav" @click="refreshFundNav">刷新最新淨值</el-button>
              <el-button size="small" :icon="Plus" @click="addFund">新增</el-button>
            </div>
          </div>
        </template>
        <el-table ref="fundTableRef" :data="form.funds" size="small" row-key="_rowId">
          <el-table-column width="36" align="center">
            <template #default>
              <el-icon class="row-drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
            </template>
          </el-table-column>
          <el-table-column label="基金" min-width="280">
            <template #default="{ row }">
              <el-select v-model="row.fundCode" size="small" filterable clearable style="width:100%"
                placeholder="（選擇基金 — 至「信託基金設定」管理選項）"
                @change="onFundCodeChange(row)">
                <el-option v-for="o in fundOptions" :key="o.value" :label="o.label" :value="o.value" :disabled="o.disabled" />
              </el-select>
              <!-- 舊資料 fundCode 為空時顯示原 fundName，以兼容歷史 snapshot 的展示 -->
              <div v-if="!row.fundCode && row.fundName" class="legacy-fund-name">{{ row.fundName }}</div>
            </template>
          </el-table-column>
          <el-table-column label="銀行" width="160">
            <template #default="{ row }">
              <el-select v-model="row.bankId" size="small" style="width:100%" clearable>
                <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="投資金額" width="130" align="right" header-align="right">
            <template #default="{ row }">
              <el-input v-model="row.investmentAmountStr" size="small" style="width:100%" :input-style="{ textAlign: 'right' }"
                @blur="row.investmentAmount = numParse(row.investmentAmountStr, 0); row.investmentAmountStr = numFmt(row.investmentAmount)" />
            </template>
          </el-table-column>
          <el-table-column label="單位數" width="130" align="right" header-align="right">
            <template #default="{ row }">
              <el-input v-model="row.unitsStr" size="small" style="width:100%" :input-style="{ textAlign: 'right' }"
                placeholder="（選填）"
                @blur="onUnitsBlur(row)" />
            </template>
          </el-table-column>
          <el-table-column label="現值" width="160" align="right" header-align="right">
            <template #default="{ row }">
              <div v-if="row.fundCode && row.units != null && row.units !== ''"
                   style="display:flex;align-items:center;justify-content:flex-end;gap:6px">
                <span style="font-weight:500">{{ fmt(row.currentValue) }}</span>
                <el-tooltip :content="navHint(row)" placement="top">
                  <el-icon style="color:#94a3b8"><InfoFilled /></el-icon>
                </el-tooltip>
              </div>
              <el-input v-else v-model="row.currentValueStr" size="small" style="width:100%" :input-style="{ textAlign: 'right' }"
                @blur="row.currentValue = numParse(row.currentValueStr, 0); row.currentValueStr = numFmt(row.currentValue)" />
            </template>
          </el-table-column>
          <el-table-column label="預估年配息" width="130" align="right" header-align="right">
            <template #default="{ row }">
              <span v-if="row.estimatedDividend != null && row.estimatedDividend !== ''"
                    style="color:#34d399">
                {{ fmt(row.estimatedDividend) }}
              </span>
              <span v-else style="color:#94a3b8">-</span>
            </template>
          </el-table-column>
          <el-table-column label="損益" width="150" align="right">
            <template #default="{ row }">
              <span :class="(row.currentValue-row.investmentAmount)>=0?'profit':'loss'">
                {{ fmt(row.currentValue - row.investmentAmount) }}
                <small v-if="row.investmentAmount>0" style="font-weight:400">
                  ({{ pct((row.currentValue - row.investmentAmount) / row.investmentAmount) }})
                </small>
              </span>
            </template>
          </el-table-column>
          <el-table-column width="50">
            <template #default="{ $index }">
              <el-popconfirm title="確定刪除此筆基金？" width="220" confirm-button-text="刪除" cancel-button-text="取消" confirm-button-type="danger"
                @confirm="form.funds.splice($index,1)">
                <template #reference>
                  <el-button type="danger" size="small" :icon="Delete" circle />
                </template>
              </el-popconfirm>
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
          <div class="ds-sep" />
          <div class="ds-item">
            <span class="ds-label">預估年配息</span>
            <span class="ds-val sb-dividend">{{ fmt(fundTotalDividend) }}</span>
          </div>
        </div>
      </el-card>

      <div style="text-align:center;margin-top:20px">
        <el-button @click="$router.back()">取消</el-button>
        <el-button type="primary" @click="submit" :loading="saving">
          {{ isEdit ? '存檔' : '儲存快照' }}
        </el-button>
      </div>
    </el-form>

    <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" :usd-rate="form.usdExchangeRate" />
  </div>
</template>

<script setup>
import { ArrowLeft, Plus, Delete, Operation, InfoFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useAssetStore } from '@/stores/assetStore'
import { bffApi } from '@/api/index'
import TaiwanMap from '@/components/TaiwanMap.vue'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'
import UsFlag from '@/components/UsFlag.vue'
import Sortable from 'sortablejs'

const route  = useRoute()
const router = useRouter()
const store  = useAssetStore()
const formRef = ref()
const saving  = ref(false)
const loading = ref(false)

// ===== Row sort helpers =====
// 將某市場（台股 / 美股）內的股票，從 oldIndex 拖移到 newIndex（皆為市場內視覺索引）
const reorderStockByMarket = (market, oldIndex, newIndex) => {
  if (oldIndex === newIndex) return
  const peers = form.stocks.filter(s => s.market === market)
  const moving = peers[oldIndex]
  if (!moving) return
  form.stocks.splice(form.stocks.indexOf(moving), 1)
  const remaining = form.stocks.filter(s => s.market === market)
  if (newIndex < remaining.length) {
    form.stocks.splice(form.stocks.indexOf(remaining[newIndex]), 0, moving)
  } else if (remaining.length === 0) {
    form.stocks.push(moving)
  } else {
    const lastPeer = remaining[remaining.length - 1]
    form.stocks.splice(form.stocks.indexOf(lastPeer) + 1, 0, moving)
  }
}

// ===== Stock 拖拉排序（與 DashboardView 相同模式） =====
const twStockTableRef = ref(null)
const usStockTableRef = ref(null)
const ukStockTableRef = ref(null)
let twStockSortable = null
let usStockSortable = null
let ukStockSortable = null

function initStockSortable(market) {
  const tableRef = market === '台股' ? twStockTableRef
    : market === '英股' ? ukStockTableRef : usStockTableRef
  const existing = market === '台股' ? twStockSortable
    : market === '英股' ? ukStockSortable : usStockSortable
  if (existing) { existing.destroy() }
  const el = tableRef.value?.$el
  if (!el) return null
  const tbody = el.querySelector('.el-table__body tbody') ?? el.querySelector('tbody')
  if (!tbody || tbody.children.length === 0) return null
  const sortable = Sortable.create(tbody, {
    handle: '.stock-drag-handle',
    animation: 150,
    onEnd({ oldIndex, newIndex, item, from }) {
      if (oldIndex === newIndex) return
      // 還原 Sortable 的 DOM 變動，避免與 el-table 虛擬渲染衝突
      from.removeChild(item)
      if (oldIndex >= from.children.length) {
        from.appendChild(item)
      } else {
        from.insertBefore(item, from.children[oldIndex])
      }
      reorderStockByMarket(market, oldIndex, newIndex)
    }
  })
  if (market === '台股') twStockSortable = sortable
  else if (market === '英股') ukStockSortable = sortable
  else usStockSortable = sortable
  return sortable
}

function refreshStockSortables() {
  nextTick(() => {
    initStockSortable('台股')
    initStockSortable('美股')
    initStockSortable('英股')
  })
}

// ===== 通用 Sortable 綁定工具 =====
// 對 el-table tbody 綁定 Sortable，並在 onEnd 中還原 Sortable 的 DOM 變動，
// 避免與 el-table 的渲染衝突；資料層的順序變動由 onReorder callback 處理。
function bindRowSortable(tableRef, handleSelector, onReorder) {
  const el = tableRef.value?.$el
  if (!el) return null
  const tbody = el.querySelector('.el-table__body tbody') ?? el.querySelector('tbody')
  if (!tbody || tbody.children.length === 0) return null
  return Sortable.create(tbody, {
    handle: handleSelector,
    animation: 150,
    onEnd({ oldIndex, newIndex, item, from }) {
      if (oldIndex === newIndex) return
      from.removeChild(item)
      if (oldIndex >= from.children.length) from.appendChild(item)
      else from.insertBefore(item, from.children[oldIndex])
      onReorder(oldIndex, newIndex)
    }
  })
}

// ===== 在途/存款 拖拉排序 =====
// form.deposits 是單一陣列，但畫面依 currency 篩出多個子表（TWD/USD/TRANSIT_TWD/TRANSIT_USD）。
// 拖拉發生在子表內，需要將子表內的 oldIndex/newIndex 對應回 form.deposits 的真實位置。
const reorderDepositByCurrency = (currency, oldIndex, newIndex) => {
  if (oldIndex === newIndex) return
  const peers = form.deposits.filter(d => d.currency === currency)
  const moving = peers[oldIndex]
  if (!moving) return
  form.deposits.splice(form.deposits.indexOf(moving), 1)
  const remaining = form.deposits.filter(d => d.currency === currency)
  if (newIndex < remaining.length) {
    form.deposits.splice(form.deposits.indexOf(remaining[newIndex]), 0, moving)
  } else if (remaining.length === 0) {
    form.deposits.push(moving)
  } else {
    form.deposits.splice(form.deposits.indexOf(remaining[remaining.length - 1]) + 1, 0, moving)
  }
}

const twdDepositTableRef        = ref(null)
const usdDepositTableRef        = ref(null)
const transitTwdDepositTableRef = ref(null)
const transitUsdDepositTableRef = ref(null)
const fundTableRef              = ref(null)
let twdDepositSortable        = null
let usdDepositSortable        = null
let transitTwdDepositSortable = null
let transitUsdDepositSortable = null
let fundSortable              = null

function refreshDepositSortables() {
  nextTick(() => {
    if (twdDepositSortable)        { twdDepositSortable.destroy();        twdDepositSortable = null }
    if (usdDepositSortable)        { usdDepositSortable.destroy();        usdDepositSortable = null }
    if (transitTwdDepositSortable) { transitTwdDepositSortable.destroy(); transitTwdDepositSortable = null }
    if (transitUsdDepositSortable) { transitUsdDepositSortable.destroy(); transitUsdDepositSortable = null }
    twdDepositSortable        = bindRowSortable(twdDepositTableRef,        '.row-drag-handle', (o, n) => reorderDepositByCurrency('TWD',         o, n))
    usdDepositSortable        = bindRowSortable(usdDepositTableRef,        '.row-drag-handle', (o, n) => reorderDepositByCurrency('USD',         o, n))
    transitTwdDepositSortable = bindRowSortable(transitTwdDepositTableRef, '.row-drag-handle', (o, n) => reorderDepositByCurrency('TRANSIT_TWD', o, n))
    transitUsdDepositSortable = bindRowSortable(transitUsdDepositTableRef, '.row-drag-handle', (o, n) => reorderDepositByCurrency('TRANSIT_USD', o, n))
  })
}

function refreshFundSortable() {
  nextTick(() => {
    if (fundSortable) { fundSortable.destroy(); fundSortable = null }
    fundSortable = bindRowSortable(fundTableRef, '.row-drag-handle', (oldIndex, newIndex) => {
      const moved = form.funds.splice(oldIndex, 1)[0]
      form.funds.splice(newIndex, 0, moved)
    })
  })
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
const twdDepositTypeOptions = computed(() => depositTypeOptions.value.filter(t => !t.value.startsWith('美元')))
const usdDepositTypeOptions = computed(() => depositTypeOptions.value.filter(t => t.value.startsWith('美元')))

// 信託基金主檔（Requirement 19）：含最新 NAV / FX，前端用 fundCode 對應算出 currentValue
const fundMasterMap      = ref({})    // { [fundCode]: { fundName, bankId, currency, latestNav, latestNavDate, latestFxRate, twdPerUnit } }
const fundOptions        = computed(() =>
  Object.values(fundMasterMap.value).map(f => ({
    value: f.fundCode,
    label: `${f.fundCode}　${f.fundName}${f.active === false ? '（已停售）' : ''}`,
    disabled: f.active === false
  }))
)
const refreshingFundNav  = ref(false)

async function loadInstitutions() {
  const { banks, brokers, depositTypes, transitFundTypes } = await bffApi.snapshotForm.getLookups()
  bankOptions.value        = banks.map(b => ({ value: b.id, label: b.displayName }))
  brokerOptions.value      = brokers.map(b => ({ value: b.id, label: b.displayName }))
  depositTypeOptions.value = depositTypes.map(d => ({ value: d.code, label: d.displayName }))
  transitTypeOptions.value = transitFundTypes.map(t => ({ value: t.code, label: t.displayName, payable: t.payable }))
  transitPayableSet.value  = new Set(transitFundTypes.filter(t => t.payable).map(t => t.code))
}

async function loadFundMasters() {
  try {
    // Requirement 21：傳 snapshotDate 讓 backend 取「基準日 NAV / FX / 配息」而非最新值
    const list = await bffApi.snapshotForm.getFunds(form.snapshotDate || undefined)
    const map = {}
    for (const f of list) map[f.fundCode] = f
    fundMasterMap.value = map
    // 重抓後既有 row 的現值 / 預估配息要用新基準日 NAV 重算
    for (const row of form.funds) {
      if (row.fundCode && row.units != null && row.units !== '') {
        const cv = autoCalcFundCurrentValue(row.fundCode, row.units)
        if (cv != null) {
          row.currentValue = cv
          row.currentValueStr = numFmt(cv)
        }
        const div = autoCalcFundDividend(row.fundCode, row.units)
        if (div != null) row.estimatedDividend = div
      }
    }
  } catch (e) {
    console.warn('載入基金主檔失敗', e)
  }
}

/** 給定 fundCode + units 算 台幣現值；無 NAV / FX 時回 null（caller 應 fallback 手填值）。 */
function autoCalcFundCurrentValue(fundCode, units) {
  if (!fundCode || units == null || units === '' || isNaN(Number(units))) return null
  const m = fundMasterMap.value[fundCode]
  if (!m || m.twdPerUnit == null) return null
  const v = Number(units) * Number(m.twdPerUnit)
  return Math.round(v * 100) / 100
}

/** 選擇 fundCode 時自動帶入 fundName / bankId（若 row 還沒填），並重算現值。 */
function onFundCodeChange(row) {
  const m = fundMasterMap.value[row.fundCode]
  if (m) {
    if (!row.fundName) row.fundName = m.fundName
    if (row.bankId == null && m.bankId != null) row.bankId = m.bankId
  }
  recalcRowCurrentValue(row)
}

function onUnitsBlur(row) {
  const raw = String(row.unitsStr ?? '').replace(/,/g, '').trim()
  if (raw === '') { row.units = null; row.unitsStr = ''; recalcRowCurrentValue(row); return }
  const n = parseFloat(raw)
  if (isNaN(n)) { row.units = null; row.unitsStr = ''; recalcRowCurrentValue(row); return }
  // 保留至 4 位小數，移除多餘 trailing zero（647.9300 → 647.93；647.9304 → 647.9304）
  row.units = parseFloat(n.toFixed(4))
  row.unitsStr = String(row.units)
  recalcRowCurrentValue(row)
}

/** Requirement 20：給定 fundCode + units 算預估年配息台幣，無資料回 null。 */
function autoCalcFundDividend(fundCode, units) {
  if (!fundCode || units == null || units === '' || isNaN(Number(units))) return null
  const m = fundMasterMap.value[fundCode]
  if (!m || m.annualDividendPerUnitTwd == null) return null
  const v = Number(units) * Number(m.annualDividendPerUnitTwd)
  return Math.round(v * 100) / 100
}

function recalcRowCurrentValue(row) {
  if (row.fundCode && row.units != null && row.units !== '') {
    const cv = autoCalcFundCurrentValue(row.fundCode, row.units)
    if (cv != null) {
      row.currentValue = cv
      row.currentValueStr = numFmt(cv)
    }
    const div = autoCalcFundDividend(row.fundCode, row.units)
    if (div != null) row.estimatedDividend = div
  }
}

function navHint(row) {
  const m = fundMasterMap.value[row.fundCode]
  if (!m || m.latestNav == null) return '無 NAV 資料'
  const navStr = `淨值 ${m.latestNav} ${m.currency}（${m.latestNavDate || '-'}）`
  const fxStr = m.currency === 'TWD'
    ? '台幣計價，無需匯率'
    : `匯率 ${m.latestFxRate || '-'}（${m.latestFxDate || '-'}）`
  let divStr = ''
  if (m.annualDividendPerUnitTwd != null) {
    divStr = `\n預估年配息／單位：${m.annualDividendPerUnitTwd} TWD（近 ${m.dividendMonthsCounted || 0} 個月）`
  }
  return `${navStr}　${fxStr}${divStr}`
}

async function refreshFundNav() {
  refreshingFundNav.value = true
  try {
    const r = await bffApi.snapshotForm.refreshFundNav()
    await loadFundMasters()
    // 重新計算現值
    for (const row of form.funds) {
      if (row.fundCode && row.units != null && row.units !== '') {
        const v = autoCalcFundCurrentValue(row.fundCode, row.units)
        if (v != null) {
          row.currentValue = v
          row.currentValueStr = numFmt(v)
        }
      }
    }
    if (r && r.failed > 0) {
      ElMessage.warning(`刷新完成：成功 ${r.success}、失敗 ${r.failed} / 共 ${r.total} 支`)
    } else if (r && r.success != null) {
      ElMessage.success(`刷新完成：${r.success} 支基金 NAV 已更新`)
    }
  } catch (e) {
    ElMessage.error('刷新 NAV 失敗：' + (e?.message || e))
  } finally {
    refreshingFundNav.value = false
  }
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
  return `$${Number(v).toLocaleString('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`
}
const fmtPriceUs = (v) => {
  if (v == null) return '-'
  return `$${Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 6 })}`
}
const pct = (v) => v != null ? `${(Number(v) * 100).toFixed(2)}%` : '-'
const fmtShares = (v, market) => {
  if (v == null) return '-'
  const n = Number(v)
  if (market === '美股' || market === '英股') return n.toLocaleString('en-US', { minimumFractionDigits: 5, maximumFractionDigits: 5 })
  return n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })
}

// ===== Computed: stock group helpers =====
const stockShares = (s) => s.brokerRows.reduce((a, r) => a + Number(r.shares || 0), 0)

/** 單一 brokerRow 的原幣現值（美股 / 英股 UCITS 為 USD；台股為 TWD） */
const calcBrOriginalValue = (br, stock) => {
  if (stock.latestPrice != null) return Number(br.shares || 0) * Number(stock.latestPrice)
  // fallback：無最新股價時用存檔值
  if (stock.market === '美股' || stock.market === '英股') {
    // currentValueOriginal 為 USD；若無則由台幣存檔值反推
    if (br.currentValueOriginal) return Number(br.currentValueOriginal)
    return Number(br.currentValue || 0) / (form.usdExchangeRate || 1)
  }
  return Number(br.currentValue || 0)
}

/** 取得 br 的有效匯率：優先用交易日匯率，備援快照匯率 */
const effectiveRate = (br) => br.transactionExchangeRate || form.usdExchangeRate || 1

/** 依幣別重新填入持股成本顯示字串：TWD 顯示 investmentCostTwd，USD 顯示 investmentCost */
function syncBrCostStr(br) {
  if (br.currency === 'TWD') {
    br.investmentCostStr = numFmt(br.investmentCostTwd ?? 0)
  } else {
    br.investmentCostStr = numFmt(br.investmentCost ?? 0)
  }
}

/** 日期選定後自動抓取歷史匯率，並依新匯率重算 USD 成本
 *  - TWD 幣別：investmentCostTwd（台幣固定支出）÷ 新匯率 → USD 成本
 *  - USD 幣別：成本本身就是 USD，不受匯率影響 */
async function onUsTransactionDateChange(br, date) {
  if (!date) { br.transactionExchangeRate = null; return }
  // el-date-picker 可能回傳 Date 物件，統一轉為 yyyy-MM-dd 字串
  const dateStr = date instanceof Date
    ? date.toISOString().slice(0, 10)
    : String(date).slice(0, 10)
  try {
    const res = await bffApi.snapshotForm.exchangeRate(dateStr)
    const newRate = Number(res.midRate)
    br.transactionExchangeRate = newRate
    // TWD 幣別：以台幣固定支出反推新 USD 成本（顯示維持 TWD）
    if (br.currency === 'TWD' && br.investmentCostTwd && newRate > 0) {
      const newUsd = Number((br.investmentCostTwd / newRate).toFixed(6))
      br.investmentCost = newUsd
      syncBrCostStr(br)
      if (br.shares > 0) {
        br.avgCost = Number((newUsd / br.shares).toFixed(6))
        br.avgCostStr = numFmt(br.avgCost)
        br.originalCurrencyValue = br.avgCost
      }
    }
  } catch {
    br.transactionExchangeRate = null
  }
}

/** 匯率改變後依 avgCost × shares × rate 重算持股成本（僅 TWD 幣別的美股適用） */
function recalcCostByRate(br) {
  if (br.currency !== 'TWD' || !br.avgCost || !br.shares) return
  const rate = effectiveRate(br)
  br.investmentCost = Math.round((br.avgCost || 0) * (br.shares || 0) * rate)
  br.investmentCostStr = numFmt(br.investmentCost)
}

/** 單一 brokerRow 的台幣現值（美股 / 英股 UCITS 原幣 × 快照匯率；台股直接為 TWD） */
const calcBrTwdValue = (br, stock) => {
  const orig = calcBrOriginalValue(br, stock)
  if (stock.market === '美股' || stock.market === '英股') return Math.round(orig * (form.usdExchangeRate || 1))
  return Math.round(orig)
}

/** 單一 brokerRow 的原幣成本：
 *  - 美股 TWD 幣別（有 investmentCostTwd）：回傳台幣原始支出
 *  - 其他（台股 TWD、美股 USD）：直接回傳 investmentCost */
const brCost = (br) => {
  // 美股 TWD 幣別：優先使用台幣原始支出
  if (br.currency === 'TWD' && br.investmentCostTwd != null) return br.investmentCostTwd
  // 台股或其他：直接回傳（台股 = 台幣；美股 USD = 美元）【v3-fixed】
  const ic = Number(br.investmentCost || 0)
  if (ic > 0) return ic
  const fromAvg = Number(br.avgCost || 0) * Number(br.shares || 0)
  return br.currency === 'USD'
    ? parseFloat(fromAvg.toFixed(6))
    : Math.round(fromAvg)
}

/** 台幣投資成本（USD 計價的 br 需乘有效匯率換算） */
const stockCost = (s) => s.brokerRows.reduce((a, r) => {
  const cost = brCost(r)
  return a + (r.currency === 'USD' ? Math.round(cost * effectiveRate(r)) : cost)
}, 0)

const stockValue    = (s) => s.brokerRows.reduce((a, r) => a + calcBrTwdValue(r, s), 0)
const stockProfit   = (s) => stockValue(s) - stockCost(s)
const stockProfitRate = (s) => { const c = stockCost(s); return c > 0 ? stockProfit(s) / c : 0 }
// 預估配息：優先用 snapshot 儲存當下的值（與 AssetHistory / 其他頁面同源），
// 新增的持股或舊資料缺值時才 fallback 用即時 stockValue × dividendRate 計算。
const stockDividend = (s) => {
  const stored = Number(s?.storedEstimatedDividend || 0)
  if (stored > 0) return stored
  return stockValue(s) * Number(s?.dividendRate || 0)
}

// ===== Computed: deposits =====
const isTransitPayable = (d) => transitPayableSet.value.has(d.depositType)

const depositTwd = (d) => {
  const amt = Number(d.amount || 0)
  if (d.currency === 'USD')         return Math.round(amt * (form.usdExchangeRate || 1))
  if (d.currency === 'DEBT')        return -amt   // 舊版相容
  if (d.currency === 'TRANSIT_TWD') return isTransitPayable(d) ? -amt : amt
  if (d.currency === 'TRANSIT_USD') {
    const twd = Math.round(amt * (form.usdExchangeRate || 1))
    return isTransitPayable(d) ? -twd : twd
  }
  return amt
}
// 該筆存款的預估年利息（TWD）— amount 已是台幣等值，rate 為百分比（1.5 = 1.5%）
// TRANSIT_* 不適用，rate null / 非正回 0
const depositInterestTwd = (d) => {
  if (!d) return 0
  if (d.currency === 'TRANSIT_TWD' || d.currency === 'TRANSIT_USD') return 0
  const rate = Number(d.annualInterestRate || 0)
  if (rate <= 0) return 0
  return Math.round(depositTwd(d) * rate / 100)
}
const depositInterestTotal = computed(() =>
  form.deposits.reduce((sum, d) => sum + depositInterestTwd(d), 0)
)
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
const fundTotalDividend = computed(() => form.funds.reduce((s, f) => s + Number(f.estimatedDividend || 0), 0))

// ===== Computed: filtered stock groups =====
const twStocks = computed(() => form.stocks.filter(s => s.market === '台股'))
const usStocks = computed(() => form.stocks.filter(s => s.market === '美股'))
const ukStocks = computed(() => form.stocks.filter(s => s.market === '英股'))

// 雙擊持股 → 開啟股票分析 dialog
const analysisVisible = ref(false)
const analysisStock = ref(null)
function onStockDblClick(row) {
  if (!row?.stockCode) return
  analysisStock.value = {
    stockCode: row.stockCode,
    stockName: row.stockName,
    market: row.market,
    shares: row.shares,
    investmentCost: row.investmentCost
  }
  analysisVisible.value = true
}

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
const ukSummary  = computed(() => calcGroupedSummary(ukStocks.value))
const allSummary = computed(() => calcGroupedSummary(form.stocks))

/**
 * 顯示用的彙整：
 *  - 編輯模式（既有快照）→ 一律以 BFF 回傳的快照原始 stored 值為準（單一事實來源）。
 *    為何：歷史快照可能在 usdExchangeRate=null 時被存入，USD 存款的 amount 欄位
 *    與 originalAmount 被存成同一個數字（未換匯）；若 live 重算（amount × rate），
 *    同一筆會被當成 USD 再乘一次匯率，造成總資產被放大數倍。
 *    儲存後重新呼叫 BFF detail，summary 會自動更新。
 *  - 新增模式（尚無 stored 值）→ 用 reactive 即時計算結果。
 */
const storedDetail = computed(() => store.currentSnapshot)
// userEdited 在 onMounted 載入完成後才開始追蹤；一旦使用者改動 deposits/funds/stocks/usdExchangeRate，
// summary 就切換回 live 重算，讓 KPI 即時反映編輯（修正：原本鎖 stored 導致改在途款項後總資產不變）
const userEdited = ref(false)
const pickStored = (storedKey, live, fallback = 0) => {
  if (!isEdit.value || userEdited.value) return Number(live ?? fallback)
  const stored = storedDetail.value?.[storedKey]
  return stored != null ? Number(stored) : Number(live ?? fallback)
}

const summaryDeposit = computed(() => pickStored('totalDeposit', depositTotal.value))
const summaryFundValue = computed(() => pickStored('totalFundValue', fundTotalValue.value))
const summaryFundCost = computed(() => pickStored('totalFundCost', fundTotalInvest.value))
const summaryFundProfit = computed(() => summaryFundValue.value - summaryFundCost.value)
// 總資產一律從 bar 上顯示的四個分項（存款 / 台股 / 美股 / 基金）加總，避免 stored totalAssets
// 與分項顯示值不同步時 bar 算不平（例：stored 抓自 BFF 的快照值，但分項是 reactive 即時值）。
const summaryTotalAssets = computed(() =>
  Number(summaryDeposit.value) + Number(summaryFundValue.value)
  + Number(twSummary.value.value) + Number(usSummary.value.value) + Number(ukSummary.value.value))
// 預估年配息合計 = 股票 + 基金 + 存款預估年利息（即時計算；不用 pickStored 因為基金部分使用者改 units 時要即時反應）
const summaryDividend = computed(() => allSummary.value.dividend + fundTotalDividend.value + depositInterestTotal.value)

// ===== Deposit helpers =====
const transitTypeOptions = ref([])
const transitPayableSet  = ref(new Set(['信用卡待付款', '買股待付款']))

// 將後端 deposit DTO 轉成前端 row（顯示用金額一律為正數）
const mapDepositFromApi = (d, rate = 1) => {
  let currency = d.currency || 'TWD'
  let displayAmt
  if (currency === 'USD') {
    displayAmt = d.originalAmount != null ? d.originalAmount : Number((d.amount / rate).toFixed(2))
  } else if (currency === 'TRANSIT_USD') {
    displayAmt = d.originalAmount != null ? d.originalAmount : Number((Math.abs(d.amount) / rate).toFixed(2))
  } else if (currency === 'DEBT') {
    // 舊版 DEBT → 移到 TRANSIT_TWD
    currency = 'TRANSIT_TWD'
    displayAmt = Math.abs(d.amount)
  } else if (currency === 'TRANSIT_TWD') {
    displayAmt = Math.abs(d.amount)
  } else if (d.amount < 0) {
    // 舊版 TWD 負值 → 移到 TRANSIT_TWD
    currency = 'TRANSIT_TWD'
    displayAmt = Math.abs(d.amount)
  } else {
    displayAmt = d.amount
  }
  const interestRate = d.annualInterestRate != null ? Number(d.annualInterestRate) : null
  return {
    _rowId: `dep_${_idSeq++}`,
    bankId: d.bankId || null,
    depositType: d.depositType,
    currency,
    amount: displayAmt,
    amountStr: numFmt(displayAmt),
    annualInterestRate: interestRate,
    annualInterestRateStr: interestRate != null ? numFmt(interestRate) : '',
    notes: d.notes
  }
}

// ===== Deposit Tabs =====
const depositTab  = ref('TWD')
const transitTab  = ref('TWD')
const twdDeposits         = computed(() => form.deposits.filter(d => d.currency === 'TWD'))
const usdDeposits         = computed(() => form.deposits.filter(d => d.currency === 'USD'))
const transitTwdDeposits  = computed(() => form.deposits.filter(d => d.currency === 'TRANSIT_TWD'))
const transitUsdDeposits  = computed(() => form.deposits.filter(d => d.currency === 'TRANSIT_USD'))
const transitDeposits     = computed(() => [...transitTwdDeposits.value, ...transitUsdDeposits.value])
const depositTwdFixed  = computed(() => twdDeposits.value.filter(d => (d.depositType || '').includes('定存')).reduce((s, d) => s + depositTwd(d), 0))
const depositTwdDemand = computed(() => twdDeposits.value.filter(d => !(d.depositType || '').includes('定存')).reduce((s, d) => s + depositTwd(d), 0))
// 台幣存款總計 包含 TRANSIT_TWD（在途）以對齊 stored total_deposit / 上方 KPI
const depositTwdTotal  = computed(() => depositTwdFixed.value + depositTwdDemand.value + transitNetTwd.value)
const depositUsdTotal  = computed(() => usdDeposits.value.reduce((s, d) => s + depositTwd(d), 0))
const transitNetTwd    = computed(() => transitDeposits.value.reduce((s, d) => s + depositTwd(d), 0))
const transitUsdNetTwd = computed(() => transitUsdDeposits.value.reduce((s, d) => s + depositTwd(d), 0))
const usdDemandAmt     = computed(() => usdDeposits.value.filter(d => !d.depositType?.includes('定存')).reduce((s, d) => s + Number(d.amount || 0), 0))
const usdFixedAmt      = computed(() => usdDeposits.value.filter(d => d.depositType?.includes('定存')).reduce((s, d) => s + Number(d.amount || 0), 0))
const transitUsdNetAmt = computed(() => transitUsdDeposits.value.reduce((s, d) => s + (isTransitPayable(d) ? -1 : 1) * Number(d.amount || 0), 0))
const usdGrandAmt      = computed(() => usdDemandAmt.value + usdFixedAmt.value + transitUsdNetAmt.value)
const usdGrandTwd      = computed(() => Math.round(usdGrandAmt.value * (form.usdExchangeRate || 1)))

// ===== Mutations =====
const addDeposit = (outerTab = 'TWD') => {
  if (outerTab === 'TRANSIT') {
    const currency = transitTab.value === 'USD' ? 'TRANSIT_USD' : 'TRANSIT_TWD'
    const defaultType = transitTypeOptions.value[0]?.value ?? '信用卡待付款'
    form.deposits.push({ _rowId: `dep_${_idSeq++}`, bankId: null, depositType: defaultType, currency, amount: 0, amountStr: '0', annualInterestRate: null, annualInterestRateStr: '' })
    return
  }
  const typeMap = { USD: '美元活存', TWD: '活存' }
  form.deposits.push({ _rowId: `dep_${_idSeq++}`, bankId: null, depositType: typeMap[outerTab] ?? '活存', currency: outerTab, amount: 0, amountStr: '0', annualInterestRate: null, annualInterestRateStr: '' })
}

const addFund = () =>
  form.funds.push({ _rowId: `fund_${_idSeq++}`, fundCode: null, fundName: '', bankId: null,
    investmentAmount: 0, investmentAmountStr: '0',
    units: null, unitsStr: '',
    currentValue: 0, currentValueStr: '0',
    estimatedDividend: null })

let _idSeq = 1
const newBrokerRow = (market) => ({
  _id: _idSeq++,
  brokerId: null,
  shares: 0,
  sharesStr: '0',
  // 英股 UCITS（CSPX.L 等）為 USD 計價，預設 USD；美股 / 台股維持 TWD 預設（使用者可手動切換）
  currency: market === '英股' ? 'USD' : 'TWD',
  investmentCost: 0,
  investmentCostStr: '0',
  investmentCostTwd: null,
  avgCost: 0,
  avgCostStr: '0',
  originalCurrencyValue: null,
  originalCurrencyValueStr: '',
  currentValueOriginal: 0,
  currentValueOriginalStr: '0',
  currentValue: 0,
  currentValueStr: '0',
  transactionType: '買',
  transactionDate: null,
  transactionExchangeRate: null
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
  if (!row.stockCode || !form.snapshotDate) return
  try {
    // 一支 BFF 端點處理：歷史收盤價 + 自動 backfill + 名稱 + 配息率 + 漲跌
    const result = await bffApi.snapshotForm.prices(form.snapshotDate,
      [{ code: row.stockCode, market: row.market }])
    applyEnrichedPrices(result, [row])
  } catch {
    // 查不到就靜默略過
  }
}

/** 把 BFF 批次回傳的 enriched prices 套用到指定的 rows 上 */
function applyEnrichedPrices(prices, rows) {
  const map = {}
  for (const p of prices ?? []) map[`${p.market}_${p.stockCode}`] = p
  for (const row of rows) {
    const p = map[`${row.market}_${row.stockCode}`]
    if (!p) continue
    if (p.price != null) row.latestPrice = Number(p.price)
    if (p.priceChange != null) row.priceChange = Number(p.priceChange)
    if (p.changePercent != null) row.priceChangePct = Number(p.changePercent)
    if (p.stockName && !row.stockName) row.stockName = p.stockName
    if (p.dividendRate != null) row.dividendRate = Number(p.dividendRate)
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
    const list = await bffApi.snapshotForm.prices(form.snapshotDate,
      [{ code: row.stockCode, market: row.market }])
    const result = list?.[0] ?? {}
    row.latestPrice    = result.price != null ? Number(result.price) : null
    row.priceChange    = result.priceChange != null ? Number(result.priceChange) : null
    row.priceChangePct = result.changePercent != null ? Number(result.changePercent) : null
    if (result.stockName && !row.stockName) row.stockName = result.stockName
    if (result.dividendRate != null) row.dividendRate = Number(result.dividendRate)
    const change = Number(result.priceChange ?? 0)
    const changePctVal = Number(result.changePercent ?? 0)
    const sign = change >= 0 ? '▲' : '▼'
    const changePctStr = Math.abs(changePctVal).toFixed(2)
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
    const detail = await bffApi.snapshotForm.get(prevId)
    const rate = form.usdExchangeRate || 1
    form.deposits = detail.deposits.map(d => mapDepositFromApi(d, rate))
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
    const detail = await bffApi.snapshotForm.get(prevId)
    form.funds = detail.funds.map(f => ({
      _rowId: `fund_${_idSeq++}`,
      fundName: f.fundName, fundCode: f.fundCode, bankId: f.bankId || null,
      investmentAmount: f.investmentAmount, investmentAmountStr: numFmt(f.investmentAmount),
      units: f.units != null ? f.units : null,
      unitsStr: f.units != null ? String(f.units) : '',
      currentValue: f.currentValue, currentValueStr: numFmt(f.currentValue),
      estimatedDividend: f.estimatedDividend != null ? f.estimatedDividend : null
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
    const detail = await bffApi.snapshotForm.get(prevId)
    form.stocks = groupStocks(detail.stocks.map(s => ({
      stockCode: s.stockCode, stockName: s.stockName, market: s.market,
      brokerId: s.brokerId || null, shares: s.shares, investmentCost: s.investmentCost,
      currentValue: s.currentValue, dividendRate: s.dividendRate,
      currency: s.currency, originalCurrencyValue: s.originalCurrencyValue,
      transactionType: s.transactionType, transactionDate: s.transactionDate,
      transactionExchangeRate: s.transactionExchangeRate
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
    // 新增模式：先觸發後端 refresh live 行情，再載入
    if (!isEdit.value) {
      try { await bffApi.snapshotForm.realtime() } catch {}
    }
    // 一支 BFF 端點處理批次：歷史價 + 自動 backfill + 名稱 + 配息率 + 漲跌
    await loadAllPrices()
    const count = form.stocks.filter(s => s.latestPrice != null).length
    if (count > 0) {
      ElMessage.success(isEdit.value
        ? `已載入 ${count} 支股票的歷史收盤價及配息率（${form.snapshotDate}）`
        : `已更新 ${count} 支股票的股價及配息率`)
    } else if (isEdit.value) {
      ElMessage.warning(`找不到 ${form.snapshotDate} 的歷史收盤價，請確認歷史資料是否已回補`)
    }
  } catch {
    ElMessage.error('更新失敗')
  } finally {
    refreshingAll.value = false
  }
}

// ===== Data fetching: dividend rate =====
const fetchDividendRate = async (row) => {
  if (!row.stockCode) {
    ElMessage.warning('請先輸入股票代號')
    return
  }
  row._fetchingDividend = true
  try {
    // 透過 BFF 批次端點取得配息率（同時也會帶回名稱與股價）
    const list = await bffApi.snapshotForm.prices(form.snapshotDate,
      [{ code: row.stockCode, market: row.market }])
    const result = list?.[0] ?? {}
    if (result.dividendRate != null) {
      row.dividendRate = Number(result.dividendRate)
      ElMessage.success(`${row.stockCode} 配息率：${(row.dividendRate * 100).toFixed(2)}%`)
    } else if (row.market === '台股' && /^0\d/.test(row.stockCode)) {
      ElMessage.warning(`${row.stockCode} 為台灣ETF，請手動填入配息率`)
    }
    if (result.stockName && !row.stockName) row.stockName = result.stockName
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
        // 累計各券商列的 stored estimated_dividend（snapshot 儲存當下算好的值，
        // 用來對齊 AssetHistory 等其他頁面顯示的總配息）
        storedEstimatedDividend: 0,
        latestPrice: null,
        priceChange: null,
        priceChangePct: null,
        _fetchingPrice: false,
        _fetchingDividend: false,
        brokerRows: []
      })
    }
    map.get(key).storedEstimatedDividend += Number(s.estimatedDividend || 0)
    const sh = Number(s.shares || 0)
    const ic = Number(s.investmentCost || 0)
    const isUs = s.market === '美股'
    // 台股永遠使用 TWD，不受 DB 中可能錯誤的 currency 值影響
    const cur = isUs ? (s.currency || 'TWD') : 'TWD'
    const txRate = s.transactionExchangeRate ? Number(s.transactionExchangeRate) : null

    // 美股：持股成本統一以 USD 儲存在 br.investmentCost；TWD 原始金額存 investmentCostTwd
    let avg, totalCost, investmentCostTwd
    if (isUs && cur === 'TWD' && ic > 0 && txRate) {
      // DB 存 TWD，轉換為 USD 供顯示
      investmentCostTwd = ic
      totalCost = Number((ic / txRate).toFixed(6))
      avg = sh > 0 ? Number((totalCost / sh).toFixed(6)) : 0
    } else if (isUs && cur === 'USD' && s.originalCurrencyValue) {
      investmentCostTwd = null
      avg = Number(s.originalCurrencyValue)
      totalCost = Number((avg * sh).toFixed(5))
    } else {
      // 台股（及美股 fallback）：直接以原幣成本計算
      investmentCostTwd = null
      avg = sh > 0 ? Number((ic / sh).toFixed(2)) : 0
      totalCost = ic
    }

    // 顯示用字串：TWD 顯示原始 TWD 金額；USD 顯示 USD
    const costDisplay = isUs && cur === 'TWD' && investmentCostTwd
      ? investmentCostTwd
      : totalCost
    map.get(key).brokerRows.push({
      _id: _idSeq++,
      brokerId: s.brokerId || null,
      shares: sh,
      sharesStr: numFmt(sh),
      currency: cur,
      investmentCost: totalCost,
      investmentCostStr: numFmt(costDisplay),
      investmentCostTwd,                              // 美股 TWD 幣別的原始台幣成本（供 DB 儲存及重算用）
      avgCost: avg,
      avgCostStr: numFmt(avg),
      originalCurrencyValue: isUs ? avg : null,
      originalCurrencyValueStr: isUs ? numFmt(avg) : '',
      currentValueOriginal: 0,
      currentValueOriginalStr: '0',
      currentValue: s.currentValue,
      currentValueStr: numFmt(s.currentValue),
      transactionType: s.transactionType || '買',
      transactionDate: s.transactionDate || null,
      transactionExchangeRate: txRate
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
      investmentCost:        stock.market === '美股' ? brCost(br) : Number(br.investmentCost || 0),
      currentValue:          calcBrTwdValue(br, stock),
      estimatedDividend:     Math.round(calcBrTwdValue(br, stock) * Number(stock.dividendRate || 0)),
      dividendRate:          stock.dividendRate,
      currency:              stock.market === '美股' ? (br.currency || 'TWD') : 'TWD',
      originalCurrencyValue: stock.market === '美股' ? (br.originalCurrencyValue || null) : null,
      transactionType:       br.transactionType || '買',
      transactionDate:       br.transactionDate || null,
      transactionExchangeRate: br.transactionExchangeRate || null
    }))
  )

// ===== 自動載入與定時更新股價 =====
const marketStatus = ref({ twMarketOpen: false, usMarketOpen: false })
let priceTimer = null

/**
 * 批次載入股價到各 row：
 *  - 一支 BFF 端點處理：歷史收盤價 (USD/TWD 原幣別) + 自動 backfill + 名稱 + 配息率 + 漲跌
 *  - 同步取得即時市場開盤狀態（供「漲跌(%)」顯示用）
 */
async function loadAllPrices() {
  try {
    const stocks = form.stocks
      .filter(s => s.stockCode)
      .map(s => ({ code: s.stockCode, market: s.market }))

    if (stocks.length > 0 && form.snapshotDate) {
      const prices = await bffApi.snapshotForm.prices(form.snapshotDate, stocks)
      applyEnrichedPrices(prices, form.stocks)
    }

    try {
      const realtime = await bffApi.snapshotForm.realtime()
      marketStatus.value = realtime?.marketStatus ?? marketStatus.value
    } catch {}
  } catch (e) {
    console.warn('批次載入股價失敗:', e)
  }
}

/** 啟動 5 分鐘定時刷新（盤中自動更新） */
function startPriceAutoRefresh() {
  stopPriceAutoRefresh()
  priceTimer = setInterval(loadAllPrices, 2 * 60 * 1000)
}
function stopPriceAutoRefresh() {
  if (priceTimer) { clearInterval(priceTimer); priceTimer = null }
}

onUnmounted(() => {
  stopPriceAutoRefresh()
  if (twStockSortable)          { twStockSortable.destroy();          twStockSortable = null }
  if (usStockSortable)          { usStockSortable.destroy();          usStockSortable = null }
  if (twdDepositSortable)        { twdDepositSortable.destroy();        twdDepositSortable = null }
  if (usdDepositSortable)        { usdDepositSortable.destroy();        usdDepositSortable = null }
  if (transitTwdDepositSortable) { transitTwdDepositSortable.destroy(); transitTwdDepositSortable = null }
  if (transitUsdDepositSortable) { transitUsdDepositSortable.destroy(); transitUsdDepositSortable = null }
  if (fundSortable)              { fundSortable.destroy();              fundSortable = null }
})

// 切換 Tab 或筆數變動時，重新綁定對應 Sortable
watch(stockTab, refreshStockSortables)
watch(() => twStocks.value.length, refreshStockSortables)
watch(() => usStocks.value.length, refreshStockSortables)
watch(depositTab, refreshDepositSortables)
watch(transitTab, refreshDepositSortables)
watch(() => twdDeposits.value.length, refreshDepositSortables)
watch(() => usdDeposits.value.length, refreshDepositSortables)
watch(() => transitTwdDeposits.value.length, refreshDepositSortables)
watch(() => transitUsdDeposits.value.length, refreshDepositSortables)
watch(() => form.funds.length, refreshFundSortable)

// ===== 依日期查詢匯率（一支 BFF 端點處理 today 刷新 + 假日 fallback） =====
async function loadExchangeRateForDate(date) {
  if (!date) return
  try {
    const res = await bffApi.snapshotForm.exchangeRate(date)
    if (res && res.midRate != null) {
      form.usdExchangeRate = Number(res.midRate)
    }
  } catch (e) {
    console.warn('載入匯率失敗:', e)
  }
}

// ===== 日期變更：自動查詢匯率 + 重新抓取各股收盤價 =====
// oldDate 為 '' 時代表 onMounted 初始賦值，不重複觸發（onMounted 自行呼叫 loadAllPrices）
watch(() => form.snapshotDate, async (newDate, oldDate) => {
  if (!oldDate || !newDate || newDate === oldDate) return
  // 1. 匯率（新增與編輯都查）
  loadExchangeRateForDate(newDate)
  // 2. 清空舊股價，讓畫面立即反映「正在查詢」
  for (const row of form.stocks) {
    row.latestPrice    = null
    row.priceChange    = null
    row.priceChangePct = null
  }
  // 3 + 4. 並行：批次查詢新日期收盤價 + 依基準日重抓 fund_master NAV / 配息（彼此獨立）
  const tasks = [loadFundMasters()]
  if (form.stocks.length > 0) tasks.push(loadAllPrices())
  await Promise.allSettled(tasks)
})

// ===== Lifecycle =====
onMounted(async () => {
  // 銀行/券商選項 + fund_master 並行載入（彼此獨立）
  await Promise.allSettled([loadInstitutions(), loadFundMasters()])
  // 編輯模式時：fund_master 載入後自動算入既有 row 的預估年配息（原值若 DB 已凍結，這裡只覆寫顯示用）
  // 這個 watcher 會在 form.funds 更新後觸發一次（loadDetail 之後）
  watchEffect(() => {
    if (Object.keys(fundMasterMap.value).length === 0) return
    for (const row of form.funds) {
      if (row.fundCode && row.units != null && row.units !== '' && row.estimatedDividend == null) {
        const v = autoCalcFundDividend(row.fundCode, row.units)
        if (v != null) row.estimatedDividend = v
      }
    }
  })

  if (isEdit.value) {
    loading.value = true
    const detail = await bffApi.snapshotForm.get(route.params.id)
    store.currentSnapshot = detail
    Object.assign(form, {
      snapshotDate:    detail.snapshotDate,
      usdExchangeRate: detail.usdExchangeRate,
      notes:           detail.notes,
      deposits: detail.deposits.map(d => mapDepositFromApi(d, detail.usdExchangeRate || 1)),
      funds: detail.funds.map(f => ({
        _rowId: `fund_${_idSeq++}`,
        fundName: f.fundName, fundCode: f.fundCode, bankId: f.bankId || null,
        investmentAmount: f.investmentAmount, investmentAmountStr: numFmt(f.investmentAmount),
        units: f.units != null ? f.units : null,
        unitsStr: f.units != null ? String(f.units) : '',
        currentValue: f.currentValue, currentValueStr: numFmt(f.currentValue),
        estimatedDividend: f.estimatedDividend != null ? f.estimatedDividend : null
      })),
      stocks: groupStocks(detail.stocks.map(s => ({
        stockCode: s.stockCode, stockName: s.stockName, market: s.market,
        brokerId: s.brokerId || null, shares: s.shares, investmentCost: s.investmentCost,
        currentValue: s.currentValue, dividendRate: s.dividendRate,
        currency: s.currency, originalCurrencyValue: s.originalCurrencyValue,
        transactionType: s.transactionType, transactionDate: s.transactionDate,
        transactionExchangeRate: s.transactionExchangeRate
      })))
    })
    loading.value = false
    // 舊快照若未存匯率，補抓
    if (!form.usdExchangeRate) {
      await loadExchangeRateForDate(form.snapshotDate)
    }
  }

  // 新增快照時：載入今天匯率作為預設值
  if (!isEdit.value) {
    const today = new Date().toISOString().slice(0, 10)
    form.snapshotDate = today
    await loadExchangeRateForDate(today)
  }

  // 載入所有已快取的股價，並啟動自動更新；BFF 已用 per-market basedate 規則決定要不要回即時值，
  // 基準日非今日時 polling 取得的還是同一筆歷史收盤，行為仍正確。
  await loadAllPrices()
  startPriceAutoRefresh()

  // 補抓美股 broker row 有交易日期但無匯率的情況
  for (const stock of form.stocks) {
    if (stock.market === '美股') {
      for (const br of stock.brokerRows) {
        if (br.transactionDate && !br.transactionExchangeRate) {
          onUsTransactionDateChange(br, br.transactionDate)
        }
      }
    }
  }

  // 補查缺名稱或缺配息率的股票（載入後靜默補齊）
  for (const row of form.stocks) {
    if (row.stockCode && (!row.stockName || row.dividendRate == null)) {
      fetchPriceForRow(row)
    }
  }

  refreshStockSortables()
  refreshDepositSortables()
  refreshFundSortable()

  // 等所有 post-load 的程式化 mutation（loadExchangeRateForDate / onUsTransactionDateChange / fetchPriceForRow）
  // 都 settle 後再註冊 watcher，避免初始化噪音誤觸 userEdited
  await nextTick()
  watch(
    () => [form.deposits, form.funds, form.stocks, form.usdExchangeRate],
    () => { userEdited.value = true },
    { deep: true }
  )
})

// ===== Submit =====
const submit = async () => {
  saving.value = true
  try {
    await formRef.value.validate()
    const deposits = form.deposits.map(d => {
      const isUs = d.currency === 'USD' || d.currency === 'TRANSIT_USD'
      const isTransit = d.currency === 'TRANSIT_TWD' || d.currency === 'TRANSIT_USD'
      // 後端負責：(1) USD/TRANSIT_USD 用 snapshot 匯率算 amount；(2) TRANSIT_* 依 deposit_type 自動處理正負號
      // 前端只送原始輸入：amount 一律送正數，USD 類另外送 originalAmount
      const absAmt = Math.abs(Number(d.amount || 0))
      const rate = !isTransit && d.annualInterestRate != null && Number(d.annualInterestRate) > 0
        ? Number(d.annualInterestRate) : null
      return {
        bankId: d.bankId || null,
        depositType: d.depositType,
        currency: d.currency,
        amount: isUs ? null : absAmt,
        originalAmount: isUs ? absAmt : null,
        annualInterestRate: rate,
        notes: d.notes || null
      }
    })
    const funds = form.funds.map(f => ({
      fundName: f.fundName,
      fundCode: f.fundCode || null,
      bankId: f.bankId || null,
      investmentAmount: f.investmentAmount,
      currentValue: f.currentValue,
      units: f.units != null && f.units !== '' ? Number(f.units) : null,
      estimatedDividend: f.estimatedDividend != null && f.estimatedDividend !== '' ? Number(f.estimatedDividend) : null
    }))
    const payload = {
      snapshotDate: form.snapshotDate,
      usdExchangeRate: form.usdExchangeRate,
      notes: form.notes,
      deposits,
      funds,
      stocks: flattenStocks()
    }
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
    const msg = e.response?.data?.detail || e.response?.data?.message || e.message || '未知錯誤'
    console.error('存檔失敗', e)
    ElMessage.error('儲存失敗：' + msg)
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
.price-cell { display: flex; flex-direction: row; justify-content: flex-end; align-items: baseline; gap: 6px; white-space: nowrap; }
.price-main { display: flex; align-items: center; gap: 6px; }
.price-num  { font-size: 13px; font-weight: 600; color: #1e293b; }
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
.legacy-fund-name { font-size: 12px; color: #94a3b8; margin-top: 2px; padding-left: 4px; }
</style>
