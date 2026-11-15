const { BookCoverAspectRatio } = require('../plugins/constants')

export const state = () => ({
  libraries: [],
  lastLoad: 0,
  currentLibraryId: '',
  showModal: false,
  switchingLibrary: false,
  issues: 0,
  filterData: null,
  numUserPlaylists: 0,
  ereaderDevices: []
})

export const getters = {
  getCanSwitchLibrary: (state, getters, rootState) => {
    return !!rootState.user?.user && rootState.networkConnected && !state.switchingLibrary && state.libraries.length > 1 && !!getters.getCurrentLibrary
  },
  getCurrentLibrary: state => {
    return state.libraries.find(lib => lib.id === state.currentLibraryId)
  },
  getCurrentLibraryName: (state, getters) => {
    return getters.getCurrentLibrary?.name || null
  },
  getCurrentLibraryMediaType: (state, getters) => {
    return getters.getCurrentLibrary?.mediaType || null
  },
  getCurrentLibrarySettings: (state, getters) => {
    return getters.getCurrentLibrary?.settings || null
  },
  getBookCoverAspectRatio: (state, getters) => {
    if (isNaN(getters.getCurrentLibrarySettings?.coverAspectRatio)) return 1
    return getters.getCurrentLibrarySettings.coverAspectRatio === BookCoverAspectRatio.STANDARD ? 1.6 : 1
  },
  getLibraryIsAudiobooksOnly: (state, getters) => {
    return !!getters.getCurrentLibrarySettings?.audiobooksOnly
  }
}

export const actions = {
  // Android picker and swipe share one request, preference update and notification.
  async switchLibrary({ state, getters, commit, dispatch }, { libraryId, direction } = {}) {
    if (this.$eventBus.$platform !== 'android' || !getters.getCanSwitchLibrary) return false
    if (direction === 1 || direction === -1) {
      const index = state.libraries.findIndex(lib => lib.id === state.currentLibraryId)
      libraryId = state.libraries[(index + direction + state.libraries.length) % state.libraries.length].id
    }
    if (!state.libraries.some(lib => lib.id === libraryId) || libraryId === state.currentLibraryId) return false

    commit('setSwitchingLibrary', true)
    try {
      const data = await dispatch('fetch', libraryId)
      if (!data) {
        this.$eventBus.$toast.error(this.$eventBus.$strings.MessageSwitchLibraryFailed)
        return false
      }
      await this.$localStore.setLastLibraryId(libraryId)
      this.$eventBus.$emit('library-changed', libraryId)
      // A device feedback failure must not turn a successful switch into a failure.
      try {
        await this.$hapticsImpact()
      } catch (error) {
        console.warn('Library switch haptics unavailable', error)
      }
      return true
    } finally {
      commit('setSwitchingLibrary', false)
    }
  },
  fetch({ state, commit, dispatch, rootState }, libraryId) {
    if (!rootState.user || !rootState.user.user) {
      console.error('libraries/fetch - User not set')
      return false
    }

    const userId = rootState.user.user.id
    const serverId = rootState.user.serverConnectionConfig?.id
    return this.$nativeHttp
      .get(`/api/libraries/${libraryId}?include=filterdata`)
      .then((data) => {
        // Ignore a late Android response after disconnecting or switching accounts.
        if (this.$eventBus.$platform === 'android' && (!rootState.networkConnected || rootState.user.user?.id !== userId || rootState.user.serverConnectionConfig?.id !== serverId)) return false
        const library = data.library
        if (!library || library.id !== libraryId) return false
        const filterData = data.filterdata
        const issues = data.issues || 0
        const numUserPlaylists = data.numUserPlaylists || 0

        dispatch('user/checkUpdateLibrarySortFilter', library.mediaType, { root: true })

        commit('addUpdate', library)
        commit('setLibraryIssues', issues)
        commit('setLibraryFilterData', filterData)
        commit('setNumUserPlaylists', numUserPlaylists)
        commit('setCurrentLibrary', libraryId)
        return data
      })
      .catch((error) => {
        console.error('Failed', error)
        return false
      })
  },
  // Return true if calling load
  load({ state, commit, rootState }) {
    if (!rootState.user || !rootState.user.user) {
      console.error('libraries/load - User not set')
      return false
    }

    // Don't load again if already loaded in the last 5 minutes
    var lastLoadDiff = Date.now() - state.lastLoad
    if (lastLoadDiff < 5 * 60 * 1000) {
      // Already up to date
      return false
    }

    return this.$nativeHttp
      .get(`/api/libraries`)
      .then((data) => {
        // TODO: Server release 2.2.9 changed response to an object. Remove after a few releases
        const libraries = data.libraries || data

        // Set current library if not already set or was not returned in results
        if (libraries.length && (!state.currentLibraryId || !libraries.find(li => li.id == state.currentLibraryId))) {
          commit('setCurrentLibrary', libraries[0].id)
        }

        commit('set', libraries)
        commit('setLastLoad', Date.now())
        return true
      })
      .catch((error) => {
        console.error('Failed', error)
        commit('set', [])
        return false
      })
  },

}

export const mutations = {
  setSwitchingLibrary(state, val) {
    state.switchingLibrary = val
  },
  setShowModal(state, val) {
    state.showModal = val
  },
  setLastLoad(state, val) {
    state.lastLoad = val
  },
  reset(state) {
    state.lastLoad = 0
    state.currentLibraryId = null
    state.libraries = []
  },
  setCurrentLibrary(state, val) {
    state.currentLibraryId = val
  },
  set(state, libraries) {
    state.libraries = libraries
  },
  addUpdate(state, library) {
    var index = state.libraries.findIndex(a => a.id === library.id)
    if (index >= 0) {
      state.libraries.splice(index, 1, library)
    } else {
      state.libraries.push(library)
    }
  },
  remove(state, library) {
    state.libraries = state.libraries.filter(a => a.id !== library.id)
  },
  setLibraryIssues(state, val) {
    state.issues = val
  },
  setNumUserPlaylists(state, numUserPlaylists) {
    state.numUserPlaylists = numUserPlaylists
  },
  setLibraryFilterData(state, filterData) {
    state.filterData = filterData
  },
  updateFilterDataWithAudiobook(state, audiobook) {
    if (!audiobook || !audiobook.book || !state.filterData) return
    if (state.currentLibraryId !== audiobook.libraryId) return
    /*
    var filterdata = {
      authors: [],
      genres: [],
      tags: [],
      series: [],
      narrators: []
    }
    */

    if (audiobook.book.authorFL) {
      audiobook.book.authorFL.split(', ').forEach((author) => {
        if (author && !state.filterData.authors.includes(author)) {
          state.filterData.authors.push(author)
        }
      })
    }
    if (audiobook.book.narratorFL) {
      audiobook.book.narratorFL.split(', ').forEach((narrator) => {
        if (narrator && !state.filterData.narrators.includes(narrator)) {
          state.filterData.narrators.push(narrator)
        }
      })
    }
    if (audiobook.book.series && !state.filterData.series.includes(audiobook.book.series)) {
      state.filterData.series.push(audiobook.book.series)
    }
    if (audiobook.tags && audiobook.tags.length) {
      audiobook.tags.forEach((tag) => {
        if (tag && !state.filterData.tags.includes(tag)) state.filterData.tags.push(tag)
      })
    }
    if (audiobook.book.genres && audiobook.book.genres.length) {
      audiobook.book.genres.forEach((genre) => {
        if (genre && !state.filterData.genres.includes(genre)) state.filterData.genres.push(genre)
      })
    }
  },
  setEReaderDevices(state, ereaderDevices) {
    state.ereaderDevices = ereaderDevices
  }
}
