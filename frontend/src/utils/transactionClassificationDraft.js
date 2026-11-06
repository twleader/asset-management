// Classification is server-owned. This draft only tracks the user's unsaved choice.
export function createClassificationDraft(state, fetchClassification) {
  let sequence = 0
  const invalidate = () => {
    sequence++
    Object.assign(state, { selected: '', baseline: '', effective: null, assignable: false, loading: false, error: '' })
  }
  const load = async (identity) => {
    const request = ++sequence
    Object.assign(state, { selected: '', baseline: '', effective: null, assignable: false, loading: true, error: '' })
    try {
      const result = await fetchClassification(identity)
      if (request !== sequence) return
      Object.assign(state, {
        selected: result.assetClassOverride ?? '', baseline: result.assetClassOverride ?? '',
        effective: result.effectiveAssetClass, assignable: result.assetClassAssignable === true
      })
    } catch {
      if (request === sequence) state.error = '無法載入資產類別，請重試；未變更共用設定'
    } finally {
      if (request === sequence) state.loading = false
    }
  }
  const change = (value) => { state.selected = value }
  const payload = () => {
    if (state.selected === state.baseline) return undefined
    if (state.loading || state.error || !state.assignable) throw new Error('此標的目前無法變更共用資產類別')
    return { assetClass: state.selected || null }
  }
  // A name lookup can create a previously missing stock master. Refresh only
  // that unavailable target; never reset a choice for an already loaded target.
  const refreshAfterMasterLookup = (identity) => {
    if (state.assignable === false && !state.loading && !state.error && state.selected === state.baseline) {
      return load(identity)
    }
  }
  return { load, invalidate, change, payload, refreshAfterMasterLookup }
}
