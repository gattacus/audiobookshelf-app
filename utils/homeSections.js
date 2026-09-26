export const HOME_SECTIONS = [
  { id: 'continue-listening', labelStringKey: 'LabelContinueListening' },
  { id: 'continue-reading', labelStringKey: 'LabelContinueReading' },
  { id: 'continue-series', labelStringKey: 'LabelContinueSeries' },
  { id: 'recently-added', labelStringKey: 'LabelRecentlyAdded' },
  { id: 'recent-series', labelStringKey: 'LabelRecentSeries' },
  { id: 'discover', labelStringKey: 'LabelDiscover' },
  { id: 'listen-again', labelStringKey: 'LabelListenAgain' },
  { id: 'read-again', labelStringKey: 'LabelReadAgain' },
  { id: 'newest-authors', labelStringKey: 'LabelNewestAuthors' },
  { id: 'newest-episodes', labelStringKey: 'LabelNewestEpisodes' },
  { id: 'local-books-continue', labelStringKey: 'LabelContinueBooks' },
  { id: 'local-episodes-continue', labelStringKey: 'LabelContinueEpisodes' },
  { id: 'local-books', labelStringKey: 'LabelLocalBooks' },
  { id: 'local-podcasts', labelStringKey: 'LabelLocalPodcasts' }
]

export function normalizeHiddenHomeSections(value) {
  return Array.isArray(value) ? [...new Set(value.filter((id) => typeof id === 'string'))] : []
}

export function visibleHomeShelves(shelves, hiddenSections) {
  const hidden = new Set(normalizeHiddenHomeSections(hiddenSections))
  return shelves.filter((shelf) => !hidden.has(shelf.id))
}
