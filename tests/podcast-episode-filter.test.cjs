const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const Vue = require('vue')
const VueRouter = require('vue-router')
const compiler = require('vue-template-compiler')

Vue.use(VueRouter)

const helpers = { exports: {} }
vm.runInNewContext(fs.readFileSync('utils/podcastEpisode.js', 'utf8').replace('export function ', 'function ') + '\nmodule.exports = { episodeFilename }', { module: helpers })

function loadComponent(path) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, ...helpers.exports, console: { log() {}, error() {} }, cellularPermissionHelpers: {}, waitForEpisodePaint: async () => {}, rememberEpisodePage() {}, clearEpisodePage() {},
    URLSearchParams,
    window: { innerWidth: 400, outerHeight: 800, addEventListener() {}, removeEventListener() {} },
    document: { documentElement: { style: { setProperty() {} } } }
  })
  return module.exports
}

const table = loadComponent('components/tables/podcast/EpisodesTable.vue')
const row = loadComponent('components/tables/podcast/EpisodeRow.vue')

async function setup({ query = {}, filter = 'incomplete', platform = 'android' } = {}) {
  const router = new VueRouter({ mode: 'abstract', routes: [
    { path: '/item/:id/:episode', component: {} },
    { path: '/item/:id', component: {} },
    { path: '/downloads', component: {} }
  ] })
  await router.push('/downloads')
  await router.push({ path: '/item/podcast', query, hash: '#episodes' })
  const settings = { podcastEpisodesFilterBy: filter }
  const writes = [], replacements = []
  const store = {
    state: { user: { user: { mediaProgress: [{ libraryItemId: 'podcast', episodeId: 'finished', isFinished: true }, { libraryItemId: 'podcast', episodeId: 'started', isFinished: false }] } }, globals: { localMediaProgress: [] } },
    getters: {
      'user/getUserSetting': key => settings[key],
      'user/getUserMediaProgress': (_item, episode) => episode === 'finished' ? { isFinished: true } : episode === 'started' ? { isFinished: false } : null
    },
    dispatch(action, payload) {
      assert.equal(action, 'user/updateUserSettings')
      writes.push(JSON.parse(JSON.stringify(payload)))
      Object.assign(settings, payload)
      return Promise.resolve()
    }
  }
  const replace = router.replace.bind(router)
  router.replace = location => {
    const pending = replace(location)
    replacements.push(pending)
    return pending
  }
  async function mount() {
    const ctx = {
      ...table.data(), $platform: platform, $store: store, $router: router,
      $socket: { $on() {}, $off() {} }, libraryItem: { id: 'podcast' },
      episodes: ['downloaded', 'started', 'finished'].map(id => ({ id })),
      localEpisodes: [{ id: 'local-episode', serverEpisodeId: 'downloaded' }]
    }
    Object.defineProperty(ctx, '$route', { get: () => router.currentRoute })
    for (const [key, getter] of Object.entries(table.computed)) {
      Object.defineProperty(ctx, key, { get: () => (typeof getter === 'function' ? getter : getter.get).call(ctx) })
    }
    for (const [key, method] of Object.entries(table.methods)) ctx[key] = method.bind(ctx)
    table.watch.episodes.handler.call(ctx)
    table.mounted.call(ctx)
    await Promise.all(replacements)
    return ctx
  }
  async function openAndReturn(ctx) {
    table.beforeDestroy.call(ctx)
    // Use the real episode-row navigation, then simulate browser/Android Back.
    await row.methods.goToEpisodePage.call({ $platform: platform, $nextTick: async () => {}, $store: store, $router: router, libraryItemId: 'podcast', episode: { id: 'downloaded' } })
    await Vue.nextTick()
    assert.equal(router.currentRoute.path, '/item/podcast/downloaded')
    router.back()
    await Vue.nextTick()
    assert.equal(router.currentRoute.path, '/item/podcast')
    return mount()
  }
  return { router, settings, writes, replacements, mount, openAndReturn }
}

for (const platform of ['android', 'ios', 'web']) {
  test(`${platform}: Show All survives opening an episode and returning from Downloads`, async () => {
    const app = await setup({ platform, query: { episodefilter: 'downloaded', localLibraryItemId: 'local-podcast', other: 'keep' } })
    let ctx = await app.mount()
    assert.equal(ctx.filterKey, 'downloaded')
    assert.deepEqual(Array.from(ctx.episodesFiltered, episode => episode.id), ['downloaded'])
    const writesAfterEntry = app.writes.length
    ctx.setFilter('all')
    assert.equal(ctx.showFiltersModal, false)
    ctx = await app.openAndReturn(ctx)
    assert.equal(ctx.filterKey, 'all')
    assert.deepEqual(Array.from(ctx.episodesFiltered, episode => episode.id), ['downloaded', 'started', 'finished'])
    assert.deepEqual(app.router.currentRoute.query, { localLibraryItemId: 'local-podcast', other: 'keep' })
    assert.equal(app.router.currentRoute.hash, '#episodes')
    ctx = await app.openAndReturn(ctx)
    assert.equal(ctx.filterKey, 'all')
    assert.equal(app.writes.length, writesAfterEntry + 1, 'returning must not rewrite the saved choice')
    assert.equal(app.replacements.length, 1)
    app.router.back()
    await Vue.nextTick()
    assert.equal(app.router.currentRoute.path, '/downloads', 'consuming the hint must not add a history entry')
  })
}

for (const filter of ['all', 'incomplete', 'inProgress', 'complete', 'downloaded']) {
  test(`a saved ${filter} filter survives ordinary entry and an episode round trip`, async () => {
    const app = await setup({ filter })
    const ctx = await app.openAndReturn(await app.mount())
    assert.equal(ctx.filterKey, filter)
    assert.equal(app.writes.length, 0)
    assert.equal(app.replacements.length, 0)
  })
}

test('a fresh Downloads entry still selects Downloaded after a previous Show All choice', async () => {
  const app = await setup({ query: { episodefilter: 'downloaded' } })
  const first = await app.mount()
  first.setFilter('all')
  app.router.back()
  await Vue.nextTick()
  await app.router.push('/item/podcast?episodefilter=downloaded')
  const reopened = await app.mount()
  assert.equal(reopened.filterKey, 'downloaded')
  assert.equal(app.router.currentRoute.query.episodefilter, undefined)
})

// Use actual Vue reactivity and row rendering, rather than a hand-built computed context.
const strings = require('../strings/en-us.json')
const renderer = require('vue-server-renderer').createRenderer()
function renderedComponent(path) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  return { ...loadComponent(path), ...compiler.compileToFunctions(parsed.template.content) }
}
const renderedRow = renderedComponent('components/tables/podcast/EpisodeRow.vue')
const renderedTable = renderedComponent('components/tables/podcast/EpisodesTable.vue')

function reactiveTable({ platform = 'android', local = false, count = 3, filter = 'all', order = 'title' } = {}) {
  const episodes = Array.from({ length: count }, (_, n) => ({
    id: local ? `local-${n}` : `server-${n}`, serverEpisodeId: `server-${n}`,
    title: `Episode ${n}`, duration: 300, publishedAt: n + 1,
    season: n + 1, episode: n + 1,
    audioFile: local ? null : { metadata: { filename: `${n}.mp3` } },
    audioTrack: local ? { metadata: { filename: `${n}.mp3` } } : undefined
  }))
  const settings = Vue.observable({ podcastEpisodesFilterBy: filter, podcastEpisodesOrderBy: order, podcastEpisodesOrderDesc: false })
  const state = Vue.observable({ socketConnected: !local,
    user: { user: local ? null : { mediaProgress: [
      { libraryItemId: 'podcast', episodeId: 'server-1', isFinished: false },
      { libraryItemId: 'podcast', episodeId: 'server-2', isFinished: true },
      { libraryItemId: 'other', episodeId: 'server-0', isFinished: true }
    ] } },
    globals: { localMediaProgress: [
      { localLibraryItemId: 'podcast', localEpisodeId: 'local-1', isFinished: false },
      { localLibraryItemId: 'podcast', localEpisodeId: 'local-2', isFinished: true },
      { localLibraryItemId: 'other', localEpisodeId: 'local-0', isFinished: true }
    ] }
  })
  const store = { state, getters: {
    'user/getUserSetting': key => settings[key],
    'user/getUserMediaProgress': (item, episode) => state.user.user?.mediaProgress.find(p => p.libraryItemId === item && p.episodeId === episode),
    'globals/getLocalMediaProgressById': (item, episode) => state.globals.localMediaProgress.find(p => p.localLibraryItemId === item && p.localEpisodeId === episode)
  }, dispatch(_action, payload) { Object.assign(settings, payload) } }
  const view = new Vue({ ...renderedTable, propsData: { libraryItem: { id: 'podcast' }, episodes, isLocal: local,
    localEpisodes: local ? episodes : [{ id: 'local-0', serverEpisodeId: 'server-0' }]
  }, beforeCreate() { this.$store = store; this.$platform = platform; this.$strings = strings; this.$socket = { $on() {}, $off() {} } } })
  return { view, store, settings, episodes }
}
const ids = episodes => Array.from(episodes, e => e.id)
const expectedFilters = { all: [0, 1, 2], incomplete: [0, 1], inProgress: [1], complete: [2], downloaded: [0] }

for (const platform of ['android', 'ios', 'web']) {
  for (const local of [false, true]) {
    test(`${platform}: all filters use the correct ${local ? 'offline local' : 'server'} episode and progress IDs`, async () => {
      const { view, settings, store, episodes } = reactiveTable({ platform, local })
      const prefix = local ? 'local-' : 'server-'
      const originalIds = ids(episodes)
      // Check every directed pair, including empty -> nonempty transitions.
      for (const from of Object.keys(expectedFilters)) {
        for (const to of Object.keys(expectedFilters)) {
          view.setFilter(from)
          await Vue.nextTick()
          view.setFilter(to)
          await Vue.nextTick()
          const expected = local && to === 'downloaded' ? [0, 1, 2] : expectedFilters[to]
          assert.equal(settings.podcastEpisodesFilterBy, to)
          assert.deepEqual(ids(view.episodesFiltered), expected.map(n => prefix + n))
          assert.deepEqual(ids(view.episodesSorted), expected.map(n => prefix + n))
          assert.equal(view.episodesAreFiltered, expected.length !== episodes.length)
          assert.deepEqual(ids(episodes), originalIds, 'filters must preserve the source array')
        }
      }
      store.state.socketConnected = !store.state.socketConnected
      assert.deepEqual(ids(view.episodesFiltered), (local ? [0, 1, 2] : [0]).map(n => prefix + n), 'connection state must not change download identity')
      view.$destroy()
    })
  }
}

test('Downloaded follows added/removed local associations without matching a local ID to a server ID', async () => {
  const { view } = reactiveTable({ filter: 'downloaded' })
  assert.deepEqual(ids(view.episodesFiltered), ['server-0'])
  view.localEpisodes.push({ id: 'server-1', serverEpisodeId: 'unrelated-server-episode' })
  assert.deepEqual(ids(view.episodesFiltered), ['server-0'])
  view.localEpisodes.push({ id: 'local-1', serverEpisodeId: 'server-1' })
  await Vue.nextTick()
  assert.deepEqual(ids(view.episodesFiltered), ['server-0', 'server-1'])
  view.localEpisodes = []
  assert.equal(view.episodesFiltered.length, 0)
  view.episodes = []
  assert.equal(view.episodesSorted.length, 0)
})

test('local-only imported episodes remain Downloaded without server associations or duplicate local props', () => {
  const { view } = reactiveTable({ local: true, filter: 'downloaded' })
  view.localEpisodes = []
  view.episodes = [{ id: 'imported', title: 'Imported episode' }]
  table.watch.episodes.handler.call(view)
  assert.deepEqual(ids(view.episodesFiltered), ['imported'])
})

for (const local of [false, true]) {
  test(`${local ? 'local' : 'server'} progress filters react to finish, unfinish, reset, and episode replacement`, async () => {
    const { view, store } = reactiveTable({ local, filter: 'complete' })
    const progress = local ? store.state.globals.localMediaProgress : store.state.user.user.mediaProgress
    progress[1].isFinished = false
    await Vue.nextTick()
    assert.equal(view.episodesFiltered.length, 0)
    progress[0].isFinished = true
    assert.deepEqual(ids(view.episodesFiltered), [local ? 'local-1' : 'server-1'])
    if (local) store.state.globals.localMediaProgress = []
    else store.state.user.user = null
    view.setFilter('inProgress')
    assert.equal(view.episodesFiltered.length, 0)
    view.setFilter('incomplete')
    assert.equal(view.episodesFiltered.length, 3)
    view.episodes = [view.episodes[0]]
    await Vue.nextTick()
    assert.equal(view.episodesFiltered.length, 1)
    view.episodes = []
    await Vue.nextTick()
    assert.equal(view.episodesFiltered.length, 0)
  })
}

for (const local of [false, true]) {
  for (const sort of ['publishedAt', 'title', 'season', 'episode', 'audioFile.metadata.filename']) {
    test(`${local ? 'local' : 'server'} ${sort} sort works with each filter in both directions`, () => {
      const { view, episodes } = reactiveTable({ local, order: sort })
      for (const filter of Object.keys(expectedFilters)) {
        view.setFilter(filter)
        const expected = local && filter === 'downloaded' ? [0, 1, 2] : expectedFilters[filter]
        for (const descending of [false, true]) {
          view.sortDesc = descending
          assert.deepEqual(ids(view.episodesSorted), (descending ? [...expected].reverse() : expected).map(n => `${local ? 'local-' : 'server-'}${n}`))
          assert.deepEqual(ids(episodes), [0, 1, 2].map(n => `${local ? 'local-' : 'server-'}${n}`))
        }
      }
    })
  }
}

// Render real EpisodeRow components: testing the computed count alone misses a row render exception.
async function renderRows({ local, filter, filenameShape } = {}) {
  const { view, store, episodes } = reactiveTable({ local, filter, order: 'audioFile.metadata.filename' })
  if (filenameShape) for (const e of episodes) Object.assign(e, filenameShape)
  const errors = []
  const previous = Vue.config.errorHandler
  Vue.config.errorHandler = error => errors.push(error)
  const rowView = Vue.extend({ ...renderedRow, beforeCreate() {
    this.$store = { ...store, getters: { ...store.getters, getIsMediaStreaming: () => false, 'globals/getDownloadItem': () => null } }
    this.$platform = 'android'; this.$strings = strings
    this.$getString = key => strings[key] || key
    this.$secondsToTimestamp = value => String(value)
    this.$elapsedPretty = value => String(value)
    this.$formatDate = value => String(value)
  } })
  view.$options.components = { ...view.$options.components, 'tables-podcast-episode-row': rowView }
  try {
    const html = await renderer.renderToString(view)
    assert.deepEqual(errors, [], 'every selected episode must render without an exception')
    assert.match(html, new RegExp(`Episodes \\(${view.episodesFiltered.length}\\)`))
    assert.equal((html.match(/episode-subtitle/g) || []).length, view.episodesFiltered.length, 'header count must equal rendered rows')
    if (!filenameShape && view.episodesFiltered.length) assert.match(html, /\.mp3/)
  } finally { Vue.config.errorHandler = previous }
}
for (const local of [false, true]) {
  for (const filter of Object.keys(expectedFilters)) {
    test(`rendered count matches ${local ? 'offline' : 'server'} ${filter} rows with Filename sorting`, () => renderRows({ local, filter }))
  }
}
for (const filenameShape of [{ audioFile: null, audioTrack: null }, { audioFile: {} }, { audioFile: { metadata: null } }]) {
  test(`missing filename metadata does not hide rows: ${JSON.stringify(filenameShape)}`, () => renderRows({ local: true, filter: 'all', filenameShape }))
}

test('Filename uses server metadata first, falls back to downloaded metadata, and sorts numerically', () => {
  const { episodeFilename } = helpers.exports
  assert.equal(episodeFilename({ audioFile: { metadata: { filename: 'server.mp3' } }, audioTrack: { metadata: { filename: 'local.mp3' } } }), 'server.mp3')
  assert.equal(episodeFilename({ audioFile: {}, audioTrack: { metadata: { filename: 'local.mp3' } } }), 'local.mp3')
  assert.equal(episodeFilename({}), '')
  const { view } = reactiveTable({ local: true, order: 'audioFile.metadata.filename' })
  view.episodes = [
    { id: 'ten', audioFile: null, audioTrack: { metadata: { filename: 'episode10.mp3' } } },
    { id: 'two', audioFile: null, audioTrack: { metadata: { filename: 'episode2.mp3' } } },
    { id: 'missing', audioFile: null, audioTrack: null }
  ]
  table.watch.episodes.handler.call(view)
  assert.deepEqual(ids(view.episodesSorted), ['missing', 'two', 'ten'])
  view.sortDesc = true
  assert.deepEqual(ids(view.episodesSorted), ['ten', 'two', 'missing'])
})

test('offline fallback podcast page upgrades to full server episode list on reconnect and after episode round trip', async () => {
  const itemPage = loadComponent('pages/item/_id/index.vue')
  const localEpisodes = Array.from({ length: 9 }, (_, i) => ({
    id: `local_ep_${i}`,
    serverEpisodeId: `ep_${i}`,
    title: `Episode ${i}`,
    publishedAt: i + 1
  }))
  const serverEpisodes = Array.from({ length: 31 }, (_, i) => ({
    id: `ep_${i}`,
    title: `Episode ${i}`,
    publishedAt: i + 1
  }))
  const localItem = {
    id: 'local_sleep_with_me',
    libraryItemId: 'sleep_with_me',
    serverAddress: 'http://nucbox:13378',
    serverUserId: 'user-1',
    serverConnectionConfigId: 'cfg-1',
    mediaType: 'podcast',
    isLocal: true,
    media: { metadata: { title: 'Sleep With Me Selection' }, episodes: localEpisodes }
  }
  const serverItem = {
    id: 'sleep_with_me',
    libraryId: 'lib-1',
    mediaType: 'podcast',
    isLocal: false,
    media: { metadata: { title: 'Sleep With Me Selection' }, episodes: serverEpisodes }
  }

  const state = Vue.observable({
    socketConnected: false,
    networkConnected: false,
    attemptingConnection: false,
    playerIsPlaying: false,
    playerIsStartingPlayback: false,
    playerStartingPlaybackMediaId: null,
    isCasting: false,
    lastItemScrollData: {},
    libraries: { currentLibraryId: 'lib-1' },
    user: {
      serverConnectionConfig: { id: 'cfg-1', address: 'http://nucbox:13378' },
      user: { id: 'user-1', mediaProgress: [] }
    },
    globals: { localMediaProgress: [] }
  })
  const store = {
    state,
    getters: {
      'user/getServerAddress': 'http://nucbox:13378',
      'user/getServerConnectionConfigId': 'cfg-1',
      'user/getUserCanDownload': true,
      'user/getIsAdminOrUp': true,
      'libraries/getBookCoverAspectRatio': 1,
      'globals/getDownloadItem': () => null
    },
    dispatch: async () => {},
    commit() {}
  }
  const db = {
    getLocalLibraryItem: async (id) => (id === localItem.id ? JSON.parse(JSON.stringify(localItem)) : null),
    getLocalLibraryItemByLId: async (id) => (id === serverItem.id ? JSON.parse(JSON.stringify(localItem)) : null)
  }
  const router = new VueRouter({
    mode: 'abstract',
    routes: [
      { path: '/item/:id/:episode', component: {} },
      { path: '/item/:id', component: {} }
    ]
  })

  // 1. Offline fallback route (?noredirect=1) loads 9 local episodes and strips noredirect into localfallback=1
  await router.push('/item/local_sleep_with_me?noredirect=1')
  const redirects = []
  const asyncData1 = await itemPage.asyncData({
    store,
    params: { id: 'local_sleep_with_me' },
    query: router.currentRoute.query,
    redirect: (url) => redirects.push(url),
    app: { $db: db }
  })
  assert.deepEqual(redirects, [])
  assert.equal(asyncData1.libraryItem.id, 'local_sleep_with_me')

  global.window = { innerWidth: 400, outerHeight: 800, addEventListener() {}, removeEventListener() {} }
  global.document = { documentElement: { style: { setProperty() {} } } }
  const ctx = {
    ...itemPage.data(),
    ...asyncData1,
    $refs: {},
    $platform: 'android',
    $store: store,
    $router: router,
    $db: db,
    $localStore: { setLastLibraryId() {} },
    $eventBus: { $on() {}, $off() {} },
    $socket: { $on() {}, $off() {} },
    $nativeHttp: {
      get: async (url) => {
        assert.equal(url, '/api/items/sleep_with_me?expanded=1&include=rssfeed')
        return JSON.parse(JSON.stringify(serverItem))
      }
    }
  }
  Object.defineProperty(ctx, '$route', { get: () => router.currentRoute })
  for (const [key, getter] of Object.entries(itemPage.computed)) {
    Object.defineProperty(ctx, key, { get: () => getter.call(ctx) })
  }
  for (const [key, method] of Object.entries(itemPage.methods)) {
    ctx[key] = method.bind(ctx)
  }

  await itemPage.mounted.call(ctx)
  assert.equal(ctx.episodes.length, 9)
  assert.equal(router.currentRoute.query.noredirect, undefined, 'noredirect must be consumed so back navigation is not poisoned')
  assert.equal(router.currentRoute.query.localfallback, '1')

  // 2. If user navigates to an episode and presses Back after socket reconnects, asyncData redirects to server item WITHOUT forcing episodefilter=downloaded
  state.socketConnected = true
  const backRedirects = []
  await itemPage.asyncData({
    store,
    params: { id: 'local_sleep_with_me' },
    query: router.currentRoute.query,
    redirect: (url) => backRedirects.push(url),
    app: { $db: db }
  })
  assert.deepEqual(backRedirects, ['/item/sleep_with_me?localLibraryItemId=local_sleep_with_me'])

  // 3. If user stays on the local fallback page when socketConnected becomes true, it upgrades in-place to all 31 server episodes
  await itemPage.watch.socketConnected.call(ctx, true)
  assert.equal(ctx.isLocal, false)
  assert.equal(ctx.libraryItemId, 'sleep_with_me')
  assert.equal(ctx.episodes.length, 31)
  assert.equal(ctx.localLibraryItemEpisodes.length, 9)
  assert.equal(router.currentRoute.path, '/item/sleep_with_me')
  assert.equal(router.currentRoute.query.episodefilter, undefined)
})
