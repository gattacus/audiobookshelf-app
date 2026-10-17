const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const Vue = require('vue')
const VueRouter = require('vue-router')
const compiler = require('vue-template-compiler')

Vue.use(VueRouter)

// Keep these client watcher tests separate from vue-server-renderer, which enables SSR mode.
function loadComponent(path) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: { log() {}, error() {} }, cellularPermissionHelpers: {}, URLSearchParams
  })
  return module.exports
}

async function fallbackPage({ connected = true, query = { noredirect: '1' }, fetchItem } = {}) {
  const page = loadComponent('pages/item/_id/index.vue')
  const local = { id: 'local_podcast', libraryItemId: 'podcast', serverAddress: 'https://test.invalid', isLocal: true, mediaType: 'podcast', media: { episodes: [{ id: 'local_ep', serverEpisodeId: 'ep' }] } }
  const state = Vue.observable({ socketConnected: connected })
  const getters = Vue.observable({ 'user/getServerAddress': local.serverAddress, 'user/getServerConnectionConfigId': 'config-a' })
  const store = { state, getters }
  const router = new VueRouter({ mode: 'abstract', routes: [{ path: '/item/:id', component: {} }] })
  await router.push({ path: `/item/${local.id}`, query, hash: '#episodes' })
  const redirects = []
  const data = await page.asyncData({ store, params: { id: local.id }, query, redirect: (url) => redirects.push(url), app: { $db: { getLocalLibraryItem: async () => local } } })
  let fetches = 0
  const view = new Vue({
    router,
    beforeCreate() {
      this.$store = store
      this.$nativeHttp = { get: async () => {
        fetches++
        if (fetchItem) return fetchItem()
        return { id: 'podcast', isLocal: false, mediaType: 'podcast', media: { episodes: [{ id: 'ep' }, { id: 'other' }] } }
      } }
    },
    data: () => ({ ...page.data(), ...data }),
    computed: page.computed,
    watch: page.watch,
    methods: { ...page.methods, init() {} }
  })
  return { page, view, state, getters, router, redirects, get fetches() { return fetches } }
}

test('fallback page upgrades when the socket reconnected before page creation', async () => {
  const app = await fallbackPage({ query: { noredirect: '1', keep: 'value' } })
  try {
    assert.deepEqual(app.redirects, [], 'noredirect initially prevents a redirect loop')
    await app.page.mounted.call(app.view)
    assert.equal(app.fetches, 1)
    assert.equal(app.view.isLocal, false)
    assert.equal(app.view.episodes.length, 2)
    assert.equal(app.view.localLibraryItemEpisodes.length, 1)
    assert.equal(app.router.currentRoute.path, '/item/podcast')
    assert.deepEqual(app.router.currentRoute.query, { keep: 'value', localLibraryItemId: 'local_podcast' })
    assert.equal(app.router.currentRoute.hash, '#episodes')
  } finally { app.view.$destroy() }
})

test('a real Vue socket transition during fallback mount starts only one upgrade', async () => {
  let finish
  const app = await fallbackPage({ connected: false, fetchItem: () => new Promise((resolve) => { finish = resolve }) })
  try {
    const mounting = app.page.mounted.call(app.view)
    app.state.socketConnected = true
    await Vue.nextTick()
    assert.equal(app.fetches, 1)
    finish({ id: 'podcast', isLocal: false })
    await mounting
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(app.view.isLocal, false)
    assert.equal(app.fetches, 1)
  } finally { app.view.$destroy() }
})

test('failed initial upgrade keeps local episodes and can retry on the next reconnect', async () => {
  let fail = true
  const app = await fallbackPage({ fetchItem: async () => {
    if (fail) throw new Error('Server still unavailable')
    return { id: 'podcast', isLocal: false }
  } })
  try {
    await app.page.mounted.call(app.view)
    assert.equal(app.view.isLocal, true)
    assert.equal(app.view.episodes.length, 1)
    assert.equal(app.view.upgradingToServerItem, false)
    app.state.socketConnected = false
    await Vue.nextTick()
    fail = false
    app.state.socketConnected = true
    await Vue.nextTick()
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(app.view.isLocal, false)
    assert.equal(app.fetches, 2)
  } finally { app.view.$destroy() }
})

test('late fallback upgrade cannot replace a page after the server account changes', async () => {
  let finish
  const app = await fallbackPage({ fetchItem: () => new Promise((resolve) => { finish = resolve }) })
  try {
    const mounting = app.page.mounted.call(app.view)
    await new Promise((resolve) => setImmediate(resolve))
    app.getters['user/getServerConnectionConfigId'] = 'config-b'
    finish({ id: 'podcast', isLocal: false })
    await mounting
    assert.equal(app.view.isLocal, true)
    assert.equal(app.router.currentRoute.path, '/item/local_podcast')
  } finally { app.view.$destroy() }
})

test('reconnecting while the server item is loading does not dereference a null item', async () => {
  const app = await fallbackPage({ connected: false })
  const errors = []
  const oldHandler = Vue.config.errorHandler
  Vue.config.errorHandler = (error) => errors.push(error)
  try {
    app.view.libraryItem = null
    app.state.socketConnected = true
    await Vue.nextTick()
    assert.deepEqual(errors, [])
    assert.equal(app.fetches, 0)
  } finally { Vue.config.errorHandler = oldHandler; app.view.$destroy() }
})
