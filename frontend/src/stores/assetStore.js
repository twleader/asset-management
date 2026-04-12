import { defineStore } from 'pinia'
import { snapshotApi, gainApi } from '@/api'

export const useAssetStore = defineStore('asset', {
  state: () => ({
    snapshots: [],
    currentSnapshot: null,
    history: [],
    realizedGains: [],
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

    async fetchSnapshotDetail(id) {
      this.loading = true
      try {
        this.currentSnapshot = await snapshotApi.getDetail(id)
        return this.currentSnapshot
      } finally {
        this.loading = false
      }
    },

    async fetchHistory() {
      this.history = await snapshotApi.getHistory()
    },

    async fetchRealizedGains() {
      this.realizedGains = await gainApi.getAll()
    },

    async createSnapshot(data) {
      const result = await snapshotApi.create(data)
      await this.fetchSnapshots()
      return result
    },

    async updateSnapshot(id, data) {
      const result = await snapshotApi.update(id, data)
      await this.fetchSnapshots()
      return result
    },

    async deleteSnapshot(id) {
      await snapshotApi.delete(id)
      await this.fetchSnapshots()
    },

    async importExcel(file) {
      const result = await snapshotApi.importExcel(file)
      await this.fetchSnapshots()
      await this.fetchRealizedGains()
      return result
    },

    async recalcDividends() {
      // Step 1: fetch missing dividend rates from market API
      await snapshotApi.enrichAllDividendRates()
      // Step 2: recompute estimatedDividend for stocks that now have rate + currentValue
      const result = await snapshotApi.recalcDividends()
      await this.fetchHistory()
      return result
    },

    async createRealizedGain(data) {
      const result = await gainApi.create(data)
      await this.fetchRealizedGains()
      return result
    },

    async deleteRealizedGain(id) {
      await gainApi.delete(id)
      await this.fetchRealizedGains()
    },

    async importRealizedGains(file) {
      const result = await gainApi.importExcel(file)
      await this.fetchRealizedGains()
      return result
    }
  }
})
