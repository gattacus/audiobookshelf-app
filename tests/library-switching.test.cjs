const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const Vue = require('vue')
const Vuex = require('vuex')
const compiler = require('vue-template-compiler')
Vue.use(Vuex)

const quiet = { log() {}, warn() {}, error() {} }
function component(path, bindings = {}) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [], path)
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: quiet, Date, bookshelfCardsHelpers: {}, ...bindings
  })
  return { ...module.exports, ...compiler.compileToFunctions(parsed.template.content) }
}
const storeModule = { exports: {} }
vm.runInNewContext(fs.readFileSync('store/libraries.js', 'utf8').replace(/export const /g, 'const ') + '\nmodule.exports = { state, getters, actions, mutations };', {
  module: storeModule, console: quiet, require: () => ({ BookCoverAspectRatio: { STANDARD: 1 } })
})
const librariesModule = storeModule.exports
const libraryList = [
  { id: 'books', name: 'Audiobooks', mediaType: 'book' },
  { id: 'podcasts', name: 'Podcasts', mediaType: 'podcast' },
  { id: 'more-books', name: 'Other books', mediaType: 'book' }
]
function app(platform = 'android') {
  const requests = [], events = [], saved = [], errors = [], sorts = [], haptics = []
  const store = new Vuex.Store({
    state: { networkConnected: true, socketConnected: false, isCasting: false, isCastAvailable: false, playerIsPlaying: true, session: { id: 'playing-book' } },
    modules: {
      libraries: { namespaced: true, ...librariesModule },
      user: {
        namespaced: true,
        state: { user: { id: 'reader' }, serverConnectionConfig: { id: 'server' } },
        actions: { checkUpdateLibrarySortFilter(_, mediaType) { sorts.push(mediaType) } }
      }
    }
  })
  Object.assign(store, {
    $nativeHttp: { async get(url) {
      requests.push(url)
      const id = url.match(/libraries\/([^?]+)/)[1]
      return { library: { ...libraryList.find(lib => lib.id === id), settings: {} }, filterdata: { authors: [] }, numUserPlaylists: 2 }
    } },
    $localStore: { async setLastLibraryId(id) { saved.push(id) } },
    $eventBus: {
      $platform: platform,
      $emit(...args) { events.push(args) },
      $strings: { MessageSwitchLibraryFailed: 'Localized switch failure' },
      $toast: { error(message) { errors.push(message) } }
    },
    async $hapticsImpact() { haptics.push('impact') }
  })
  store.commit('libraries/set', libraryList.map(lib => ({ ...lib })))
  store.commit('libraries/setCurrentLibrary', 'books')
  const listeners = new Map()
  const document = {
    addEventListener(name, fn) { listeners.set(name, fn) },
    removeEventListener(name, fn) { if (listeners.get(name) === fn) listeners.delete(name) }
  }
  const definition = component('components/app/Appbar.vue', { document })
  const bar = new Vue({ ...definition, beforeCreate() {
    this.$platform = platform
    this.$store = store
    this.$route = { name: 'bookshelf' }
  } })
  return { store, bar, definition, listeners, requests, events, saved, errors, sorts, haptics }
}
const plain = (value) => JSON.parse(JSON.stringify(value))
const finger = (x, y, identifier = 1) => ({ clientX: x, clientY: y, identifier })
function touchEvent(touches, changedTouches = touches) {
  return { touches, changedTouches, cancelable: true, prevented: false, preventDefault() { this.prevented = true } }
}
async function swipe(bar, dx = 0, dy = 70) {
  bar.libraryTouchStart(touchEvent([finger(20, 20)]))
  const event = touchEvent([], [finger(20 + dx, 20 + dy)])
  await bar.libraryTouchEnd(event)
  return event
}

test('swipes follow picker order in both directions, wrap, and ignore WebSocket status', async () => {
  const { bar, store, events, saved, haptics, sorts } = app()
  // Platform/strings/toast live on Vue.prototype, not on the Vuex store.
  assert.equal(store.$platform, undefined)
  for (const [dy, expected] of [[70, 'podcasts'], [70, 'more-books'], [70, 'books'], [-70, 'more-books'], [-70, 'podcasts']]) {
    assert.equal((await swipe(bar, 0, dy)).prevented, true)
    assert.equal(store.state.libraries.currentLibraryId, expected)
  }
  assert.deepEqual(saved, ['podcasts', 'more-books', 'books', 'more-books', 'podcasts'])
  assert.deepEqual(events, saved.map(id => ['library-changed', id]))
  assert.equal(haptics.length, 5)
  assert.deepEqual(sorts, ['podcast', 'book', 'book', 'book', 'podcast'])
  assert.equal(store.state.playerIsPlaying, true)
  assert.equal(store.state.session.id, 'playing-book')
})

test('50px threshold and 1.5x vertical dominance reject short, diagonal and horizontal movements', async () => {
  const { bar, requests } = app()
  for (const [dx, dy] of [[0, 49], [50, 60], [90, 10], [0, 0], [0, -49]]) {
    assert.equal((await swipe(bar, dx, dy)).prevented, false)
  }
  assert.equal(requests.length, 0)
  await swipe(bar, 0, 50)
  await swipe(bar, 40, -60)
  assert.equal(requests.length, 2)
})

test('swipe clicks are suppressed, while fresh taps and keyboard activation open the picker', async () => {
  const { bar, store } = app()
  await swipe(bar)
  bar.clickShowLibraryModal({ detail: 1 })
  assert.equal(store.state.libraries.showModal, false)
  bar.clickShowLibraryModal({ detail: 0 })
  assert.equal(store.state.libraries.showModal, true)
  store.commit('libraries/setShowModal', false)
  bar.libraryTouchStart(touchEvent([finger(20, 20)]))
  bar.libraryTouchEnd(touchEvent([], [finger(20, 21)]))
  bar.clickShowLibraryModal({ detail: 1 })
  assert.equal(store.state.libraries.showModal, true)
})

test('multitouch, including a finger outside the button, and cancellation never switch', async () => {
  const { bar, definition, requests, listeners } = app()
  bar.libraryTouchStart(touchEvent([finger(20, 20)]))
  listeners.get('touchstart')(touchEvent([finger(20, 20), finger(250, 200, 2)]))
  assert.equal(listeners.size, 0)
  await bar.libraryTouchEnd(touchEvent([], [finger(20, 100)]))
  bar.libraryTouchStart(touchEvent([finger(20, 20), finger(30, 30, 2)]))
  await bar.libraryTouchEnd(touchEvent([], [finger(20, 100)]))
  bar.libraryTouchStart(touchEvent([finger(20, 20)]))
  bar.libraryTouchCancel()
  await bar.libraryTouchEnd(touchEvent([], [finger(20, 100)]))
  assert.equal(requests.length, 0)
  bar.libraryTouchStart(touchEvent([finger(20, 20)]))
  definition.beforeDestroy.call(bar)
  assert.equal(listeners.size, 0)
  assert.equal(bar.libraryTouch, null)
})

test('offline, signed-out, missing-library and single-library swipes make no requests', async () => {
  for (const setup of [
    store => { store.state.networkConnected = false },
    store => { store.state.user.user = null },
    store => { store.commit('libraries/setCurrentLibrary', 'removed') },
    store => { store.commit('libraries/set', [libraryList[0]]) }
  ]) {
    const { bar, store, requests, events, saved, haptics } = app()
    setup(store)
    await swipe(bar)
    bar.clickShowLibraryModal({ detail: 1 })
    assert.equal(store.state.libraries.showModal, false)
    assert.equal(await store.dispatch('libraries/switchLibrary', { libraryId: 'podcasts' }), false)
    assert.equal(requests.length + events.length + saved.length + haptics.length, 0)
  }
})

test('picker and gesture share a single pending request and update only after success', async () => {
  const { bar, store, requests, events, saved, haptics } = app()
  let resolve
  store.$nativeHttp.get = (url) => { requests.push(url); return new Promise(r => { resolve = r }) }
  const pending = swipe(bar)
  assert.equal(store.state.libraries.switchingLibrary, true)
  assert.equal(store.state.libraries.currentLibraryId, 'books')
  const picker = component('components/modals/LibrariesModal.vue')
  const ctx = { $platform: 'android', $store: store, show: true }
  await picker.methods.clickedOption.call(ctx, libraryList[2])
  await swipe(bar, 0, -70)
  assert.equal(ctx.show, false)
  assert.equal(requests.length, 1)
  assert.equal(saved.length + events.length + haptics.length, 0)
  resolve({ library: libraryList[1], filterdata: { genres: ['Audio'] }, issues: 3, numUserPlaylists: 4 })
  await pending
  assert.equal(store.state.libraries.currentLibraryId, 'podcasts')
  assert.equal(store.state.libraries.switchingLibrary, false)
  assert.equal(store.state.libraries.issues, 3)
  assert.equal(store.state.libraries.numUserPlaylists, 4)
  assert.deepEqual(plain(store.state.libraries.filterData), { genres: ['Audio'] })
  assert.deepEqual(events, [['library-changed', 'podcasts']])
  assert.deepEqual(saved, ['podcasts'])
  assert.equal(haptics.length, 1)
})

test('failed and malformed responses preserve selection and preference and allow retry', async () => {
  for (const response of [() => Promise.reject(new Error('Offline server')), async () => ({}), async () => ({ library: libraryList[0] })]) {
    const { store, bar, errors, events, saved, haptics } = app()
    const success = store.$nativeHttp.get
    store.$nativeHttp.get = response
    await swipe(bar)
    assert.equal(store.state.libraries.currentLibraryId, 'books')
    assert.equal(store.state.libraries.switchingLibrary, false)
    assert.equal(events.length + saved.length + haptics.length, 0)
    assert.deepEqual(errors, ['Localized switch failure'])
    store.$nativeHttp.get = success
    await swipe(bar)
    assert.equal(store.state.libraries.currentLibraryId, 'podcasts')
  }
})

test('late responses after network loss, logout or account change cannot select a library', async () => {
  for (const change of [
    store => { store.state.networkConnected = false },
    store => { store.state.user.user = null; store.commit('libraries/reset') },
    store => { store.state.user.serverConnectionConfig = { id: 'other-server' } }
  ]) {
    const { bar, store, events, saved } = app()
    let resolve
    store.$nativeHttp.get = () => new Promise(r => { resolve = r })
    const pending = swipe(bar)
    change(store)
    resolve({ library: libraryList[1] })
    await pending
    assert.notEqual(store.state.libraries.currentLibraryId, 'podcasts')
    assert.equal(store.state.libraries.switchingLibrary, false)
    assert.equal(events.length + saved.length, 0)
  }
})

test('Android picker uses shared switching; selecting current/unknown library is a no-op', async () => {
  const { store, requests, events } = app()
  const picker = component('components/modals/LibrariesModal.vue')
  const ctx = { $platform: 'android', $store: store, show: true }
  await picker.methods.clickedOption.call(ctx, libraryList[0])
  await store.dispatch('libraries/switchLibrary', { libraryId: 'unknown' })
  assert.equal(requests.length, 0)
  await picker.methods.clickedOption.call(ctx, libraryList[2])
  assert.equal(store.state.libraries.currentLibraryId, 'more-books')
  assert.deepEqual(events, [['library-changed', 'more-books']])
})

test('haptics failure cannot fail a completed switch or leave it locked', async () => {
  const { store, events } = app()
  store.$hapticsImpact = async () => { throw new Error('Unavailable') }
  assert.equal(await store.dispatch('libraries/switchLibrary', { direction: 1 }), true)
  assert.equal(store.state.libraries.switchingLibrary, false)
  assert.deepEqual(events, [['library-changed', 'podcasts']])
})

test('iOS and web gestures do nothing and keep their existing tap and picker paths', async () => {
  for (const platform of ['ios', 'web']) {
    const { store, bar, requests, events, saved } = app(platform)
    await swipe(bar)
    assert.equal(requests.length, 0)
    assert.equal(await store.dispatch('libraries/switchLibrary', { direction: 1 }), false)
    bar.clickShowLibraryModal({ detail: 1 })
    assert.equal(store.state.libraries.showModal, true)
    const picker = component('components/modals/LibrariesModal.vue')
    await picker.methods.clickedOption.call({ $platform: platform, $store: store, $eventBus: store.$eventBus, $localStore: store.$localStore, $hapticsImpact: store.$hapticsImpact, currentLibraryId: 'books' }, libraryList[1])
    assert.deepEqual(events, [['library-changed', 'podcasts']])
    assert.deepEqual(saved, ['podcasts'])
  }
})

test('rendered library button owns touch listeners and only Android disables native touch handling', () => {
  function findButton(node) {
    if (node.data?.attrs?.['aria-label'] === 'Show library modal') return node
    return node.children?.map(findButton).find(Boolean)
  }
  for (const platform of ['android', 'ios', 'web']) {
    const { bar, store } = app(platform)
    const button = findButton(bar._render())
    for (const event of ['click', 'touchstart', 'touchmove', 'touchend', 'touchcancel']) assert.equal(typeof button.data.on[event], 'function')
    assert.equal(button.data.style?.touchAction, platform === 'android' ? 'none' : undefined)
    store.commit('libraries/setSwitchingLibrary', true)
    assert.equal(findButton(bar._render()).data.attrs['aria-busy'], platform === 'android' ? 'true' : 'false')
  }
})

test('offline Home includes both local books and podcasts regardless of selected library', async () => {
  const home = component('pages/bookshelf/index.vue')
  for (const currentLibraryMediaType of ['book', 'podcast']) {
    const ctx = {
      networkConnected: false, user: {}, currentLibraryId: 'selected', currentLibraryMediaType,
      lastLocalFetch: 0, lastServerFetch: 0,
      $db: { async getLocalLibraryItems() { return [{ id: 'book', mediaType: 'book' }, { id: 'podcast', mediaType: 'podcast', media: { episodes: [] } }] } },
      $store: { getters: { 'globals/getLocalMediaProgressById': () => null } },
      $strings: {}, $nativeHttp: { get() { assert.fail('Offline Home must not request server categories') } }
    }
    ctx.getLocalMediaItemCategories = home.methods.getLocalMediaItemCategories.bind(ctx)
    await home.methods.fetchCategories.call(ctx)
    assert.deepEqual(Array.from(ctx.shelves, shelf => shelf.id), ['local-books', 'local-podcasts'])
  }
})

test('existing library-change handlers leave unsupported book/podcast pages for Home', () => {
  const cases = [
    ['components/bookshelf/LazyBookshelf.vue', { page: 'series', currentLibraryMediaType: 'podcast' }],
    ['components/bookshelf/LazyBookshelf.vue', { page: 'collections', currentLibraryMediaType: 'podcast' }],
    ['pages/bookshelf/authors.vue', { loadedLibraryId: 'old', $store: { getters: { 'libraries/getCurrentLibraryMediaType': 'podcast' } } }],
    ['pages/bookshelf/latest.vue', { loadedLibraryId: 'old', $store: { getters: { 'libraries/getCurrentLibraryMediaType': 'book' } } }],
    ['pages/bookshelf/add-podcast.vue', { $store: { getters: { 'libraries/getCurrentLibraryMediaType': 'book' } } }]
  ]
  for (const [path, context] of cases) {
    const routes = []
    component(path).methods.libraryChanged.call({ ...context, $router: { replace(path) { routes.push(path) } } }, 'new')
    assert.deepEqual(routes, ['/bookshelf'], path)
  }
})
