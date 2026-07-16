import { defineStore } from 'pinia'
import { snapshotApi } from '@/api'

export const useAssetStore = defineStore('asset', {
  state: () => ({
    snapshots: [],
    currentSnapshot: null,
    history: [],
    loading: false
  }),

  getters: {
    latestSnapshot: (state) => state.snapshots[0] ?? null,
    totalAssets: (state) => state.snapshots[0]?.totalAssets ?? 0,
    snapshotDates: (state) => state.snapshots.map(s => s.snapshotDate)
  },

  actions: {
    async fetchSnapshots() {
      this.loading = true
      try {
        this.snapshots = await snapshotApi.getAll()
      } finally {
        this.loading = false
      }
    },

    async fetchHistory() {
      this.history = await snapshotApi.getHistory()
    },

    async createSnapshot(data) {
      const result = await snapshotApi.create(data)
      await this.fetchSnapshots()
      return result
    },

    async updateSnapshot(id, data) {
      const result = await snapshotApi.update(id, data)
      await Promise.all([this.fetchSnapshots(), this.fetchHistory()])
      return result
    },

    async deleteSnapshot(id) {
      await snapshotApi.delete(id)
      await this.fetchSnapshots()
    },

    async recalcDividends() {
      // Step 1: fetch missing dividend rates from market API
      await snapshotApi.enrichAllDividendRates()
      // Step 2: recompute estimatedDividend for stocks that now have rate + currentValue
      const result = await snapshotApi.recalcDividends()
      await this.fetchHistory()
      return result
    },

  }
})
