const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const Vue = require('vue')
const VueRouter = require('vue-router')
const compiler = require('vue-template-compiler')

Vue.use(VueRouter)

function loadComponent(path) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: { log() {} }, cellularPermissionHelpers: {}, waitForEpisodePaint: async () => {}, rememberEpisodePage() {}, clearEpisodePage() {}
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
