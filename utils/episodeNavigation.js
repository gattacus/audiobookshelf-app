// A one-use handoff from the row to its detail page, never a persistent item cache.
const pendingPages = new WeakMap()

export function rememberEpisodePage(store, libraryItem, episode) {
  if (!libraryItem || libraryItem.id?.startsWith('local') || libraryItem.mediaType !== 'podcast') return
  const user = store.state.user
  const selected = { ...episode }
  delete selected.localEpisode
  const { localLibraryItem, ...item } = libraryItem
  pendingPages.set(store, {
    libraryItemId: libraryItem.id, episodeId: episode.id,
    connectionId: user.serverConnectionConfig?.id, userId: user.user?.id,
    createdAt: Date.now(),
    // The detail page needs one episode. Avoid observing the whole podcast again.
    libraryItem: { ...item, media: { ...libraryItem.media, episodes: [selected] } }
  })
}

export function takeEpisodePage(store, libraryItemId, episodeId) {
  const pending = pendingPages.get(store)
  pendingPages.delete(store)
  const user = store.state.user
  if (!pending || pending.libraryItemId !== libraryItemId || pending.episodeId !== episodeId ||
    !user.serverConnectionConfig || pending.connectionId !== user.serverConnectionConfig.id ||
    pending.userId !== user.user?.id || Date.now() - pending.createdAt > 30000) return null
  return pending.libraryItem
}

export function clearEpisodePage(store) {
  pendingPages.delete(store)
}
