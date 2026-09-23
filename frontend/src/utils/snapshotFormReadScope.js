/** Cancel reads and fence every continuation, including clients that ignore AbortSignal. */
export function createSnapshotFormReadScope(getContext) {
  let generation = 0
  let disposed = false
  const pending = new Set()
  const slots = new Map()

  function capture({ dateSensitive = true, accept = () => true, slot } = {}) {
    const expected = getContext()
    const version = generation
    const controller = new AbortController()
    if (slot != null) slots.get(slot)?.abort()
    if (slot != null) slots.set(slot, controller)
    pending.add(controller)
    if (disposed) controller.abort()
    return {
      signal: controller.signal,
      routeId: expected.routeId,
      date: expected.date,
      isCurrent: () => !disposed && !controller.signal.aborted && version === generation
        && getContext().routeId === expected.routeId
        && (!dateSensitive || getContext().date === expected.date) && accept(),
      finish() {
        pending.delete(controller)
        if (slot != null && slots.get(slot) === controller) slots.delete(slot)
      }
    }
  }

  function invalidate() {
    generation += 1
    for (const controller of pending) controller.abort()
    pending.clear()
    slots.clear()
  }

  return { capture, invalidate, dispose() { disposed = true; invalidate() }, get disposed() { return disposed } }
}
