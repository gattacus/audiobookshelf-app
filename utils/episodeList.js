// Offsets include the end of the last row. Binary search keeps scroll work bounded.
export function episodeIndexAt(offsets, position) {
  let low = 0
  let high = Math.max(0, offsets.length - 2)
  while (low < high) {
    const middle = Math.ceil((low + high) / 2)
    if (offsets[middle] <= position) low = middle
    else high = middle - 1
  }
  return low
}

export function episodeWindow(offsets, top, height, overscan = 500) {
  const count = offsets.length - 1
  if (!count) return { start: 0, end: 0 }
  const start = episodeIndexAt(offsets, Math.max(0, top - overscan))
  const end = Math.min(count, episodeIndexAt(offsets, Math.max(0, top + height + overscan)) + 1)
  return { start, end }
}

export function waitForEpisodePaint() {
  return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
}

// Keep only the last list's measured heights; no media or persistent user data.
const previousLayouts = new WeakMap()

function layoutIdentity(store, itemId) {
  return JSON.stringify([itemId, store.state.user.serverConnectionConfig?.id, store.state.user.user?.id])
}

export function getEpisodeLayout(store, itemId) {
  const layout = previousLayouts.get(store)
  if (!layout || layout.identity !== layoutIdentity(store, itemId)) return null
  return { width: layout.width, heights: { ...layout.heights } }
}

export function rememberEpisodeLayout(store, itemId, width, heights) {
  previousLayouts.set(store, { identity: layoutIdentity(store, itemId), width, heights: { ...heights } })
}
