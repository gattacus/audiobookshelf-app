const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const Vue = require('vue')
const compiler = require('vue-template-compiler')
const renderer = require('vue-server-renderer').createRenderer()
const strings = require('../strings/en-us.json')

function source(path, names, bindings = {}) {
  const module = { exports: {} }
  const code = fs.readFileSync(path, 'utf8').replace(/export (function|const) /g, '$1 ')
  vm.runInNewContext(code + `\nmodule.exports = { ${names} };`, { module, ...bindings })
  return module.exports
}
const list = source('utils/episodeList.js', 'episodeIndexAt, episodeWindow, getEpisodeLayout, rememberEpisodeLayout')
const podcast = source('utils/podcastEpisode.js', 'episodeFilename')
let now = 1000
const navigation = source('utils/episodeNavigation.js', 'rememberEpisodePage, takeEpisodePage, clearEpisodePage', { Date: { now: () => now } })
function component(path, bindings = {}) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: { log() {}, error() {} }, cellularPermissionHelpers: {}, ...list, ...podcast, ...navigation, ...bindings
  })
  return { ...module.exports, ...compiler.compileToFunctions(parsed.template.content) }
}
const virtual = component('components/tables/podcast/VirtualEpisodes.vue')
const table = component('components/tables/podcast/EpisodesTable.vue')
const detail = component('pages/item/_id/_episode/index.vue')
const episodes = Array.from({ length: 1200 }, (_, n) => ({ id: `episode-${n}`, title: `Episode ${n}`, audioFile: { metadata: { filename: `${n}.mp3` } } }))
const item = { id: 'podcast', mediaType: 'podcast', media: { metadata: { title: 'Large podcast' }, episodes } }
const newStore = () => ({ state: { user: { serverConnectionConfig: { id: 'server' }, user: { id: 'user', mediaProgress: [] } }, globals: { localMediaProgress: [] } }, getters: {
  'user/getUserSetting': key => ({ podcastEpisodesFilterBy: 'all', podcastEpisodesOrderBy: 'title', podcastEpisodesOrderDesc: false })[key],
  'user/getUserMediaProgress': () => { throw new Error('Show All must not scan progress per episode') }
} })

test('list remount restores measured offsets before rendering and does not reuse another account or podcast layout', () => {
  const store = newStore()
  const first = new Vue({ ...virtual, propsData: { itemId: 'podcast', episodes }, beforeCreate() { this.$store = store } })
  first.$set(first.rowHeights, 'episode-0', 300)
  first.$set(first.rowHeights, 'episode-600', 90)
  list.rememberEpisodeLayout(store, 'podcast', 360, first.rowHeights)
  const reopened = new Vue({ ...virtual, propsData: { itemId: 'podcast', episodes }, beforeCreate() { this.$store = store } })
  assert.equal(reopened.offsets.at(-1), first.offsets.at(-1))
  assert.equal(reopened.measuredWidth, 360)
  reopened.$set(reopened.rowHeights, 'episode-0', 320)
  assert.equal(first.rowHeights['episode-0'], 300, 'restored measurements must be copied')
  assert.equal(list.getEpisodeLayout(store, 'other-podcast'), null)
  store.state.user.user.id = 'other-user'
  assert.equal(list.getEpisodeLayout(store, 'podcast'), null)
})

test('virtual windows cover empty, first, middle, and last rows without mounting the whole list', () => {
  const offsets = [0]
  for (let n = 0; n < 1200; n++) offsets.push(offsets.at(-1) + (n % 2 ? 300 : 100))
  assert.deepEqual(JSON.parse(JSON.stringify(list.episodeWindow([0], 0, 800))), { start: 0, end: 0 })
  for (const top of [0, 80000, offsets.at(-1) - 800, offsets.at(-1) + 500]) {
    const { start, end } = list.episodeWindow(offsets, top, 800)
    assert.ok(end - start <= 20)
    assert.ok(start >= 0 && end <= 1200 && end > start)
    assert.ok(offsets[start] <= Math.max(0, top))
    assert.ok(offsets[end] >= Math.min(offsets.at(-1), top + 800))
  }
  assert.equal(list.episodeWindow(offsets, offsets.at(-1) - 100, 800).end, 1200)
})

test('rendered Android list bounds mounted rows, preserves total, and leaves iOS rendering unchanged', async () => {
  const row = { props: ['episode'], render(h) { return h('p', { attrs: { 'data-episode-row': this.episode.id } }, this.episode.title) } }
  for (const platform of ['android', 'ios']) {
    const view = new Vue({ ...table,
      propsData: { libraryItem: item, episodes, localEpisodes: [] },
      components: { 'tables-podcast-virtual-episodes': virtual, 'tables-podcast-episode-row': row },
      beforeCreate() { this.$platform = platform; this.$strings = strings; this.$store = newStore() }
    })
    const html = await renderer.renderToString(view)
    const count = (html.match(/data-episode-row=/g) || []).length
    assert.equal(platform === 'android' ? count < 20 : count === 1200, true)
    assert.match(html, /Episodes \(1200\)/)
    if (platform === 'android') assert.match(html, /aria-setsize="1200"/)
  }
})

test('measuring variable-height rows anchors the viewport and cleanup removes observers and listeners', () => {
  const ctx = new Vue({ ...virtual, propsData: { episodes }, data: { rowHeights: {}, viewportTop: 400, viewportHeight: 800 } })
  ctx.scheduleViewport = () => {}
  ctx.scrollRoot = { scrollTop: 1000 }
  ctx.measureRows([{ target: { dataset: { index: '0' }, getBoundingClientRect: () => ({ height: 240 }) } }])
  assert.equal(ctx.offsets[1], 240)
  assert.equal(ctx.scrollRoot.scrollTop, 1080)
  ctx.viewportTop = 160000
  assert.ok(ctx.visibleEpisodes.length < 20)
  assert.ok(Number(ctx.visibleEpisodes[0].id.split('-')[1]) > 900)
  const calls = []
  const definition = component('components/tables/podcast/VirtualEpisodes.vue', {
    cancelAnimationFrame: n => calls.push(['cancel', n]), window: { removeEventListener: name => calls.push(['window', name]) }
  })
  definition.beforeDestroy.call({ frame: 42, resizeObserver: { disconnect: () => calls.push(['disconnect']) }, scrollRoot: { removeEventListener: name => calls.push(['root', name]) } })
  assert.deepEqual(calls, [['cancel', 42], ['disconnect'], ['root', 'scroll'], ['window', 'resize']])
})

test('Android progress indexing respects podcast identity and local IDs and reacts to updates', () => {
  const store = newStore()
  store.state.user.user.mediaProgress = [{ libraryItemId: 'other', episodeId: 'episode-0', isFinished: true }, { libraryItemId: 'podcast', episodeId: 'episode-1', isFinished: true }]
  store.state.globals.localMediaProgress = [{ localLibraryItemId: 'podcast', localEpisodeId: 'episode-2', isFinished: true }]
  Vue.observable(store.state)
  const view = new Vue({ ...table, propsData: { libraryItem: item, episodes, isLocal: false }, beforeCreate() { this.$store = store; this.$platform = 'android' } })
  assert.equal(view.getEpisodeProgress(episodes[0]), undefined)
  assert.equal(view.getEpisodeProgress(episodes[1]).isFinished, true)
  store.state.user.user.mediaProgress[1].isFinished = false
  assert.equal(view.getEpisodeProgress(episodes[1]).isFinished, false)
  view.isLocal = true
  assert.equal(view.getEpisodeProgress(episodes[1]), undefined)
  assert.equal(view.getEpisodeProgress(episodes[2]).isFinished, true)
})

test('detail handoff avoids the full request, retains playback IDs, and refreshes local download association', async () => {
  const store = newStore()
  const stale = { ...item, localLibraryItem: { id: 'stale' } }
  navigation.rememberEpisodePage(store, stale, { ...episodes[2], localEpisode: { id: 'stale' } })
  const local = { id: 'local-podcast', media: { episodes: [{ id: 'local-2', serverEpisodeId: 'episode-2' }] } }
  const result = await detail.asyncData({ store, params: { id: 'podcast', episode: 'episode-2' }, redirect: () => assert.fail('redirect'), app: {
    $platform: 'android', $nativeHttp: { get: () => assert.fail('full request') }, $db: { getLocalLibraryItemByLId: async () => local }
  } })
  assert.equal(result.libraryItem.media.episodes.length, 1)
  assert.equal(result.episode.id, 'episode-2')
  assert.equal(result.episode.localEpisode.id, 'local-2')
  assert.equal(result.libraryItem.localLibraryItem.id, 'local-podcast')
  assert.equal(episodes[2].localEpisode, undefined, 'handoff must not mutate the source list')
  assert.equal(navigation.takeEpisodePage(store, 'podcast', 'episode-2'), null, 'handoff is one-use')
})

test('wrong episode, changed account/server, and expired handoffs cannot supply stale detail data', () => {
  for (const change of ['episode', 'account', 'server', 'expired']) {
    now = 1000
    const store = newStore()
    navigation.rememberEpisodePage(store, item, episodes[0])
    if (change === 'account') store.state.user.user.id = 'another-user'
    if (change === 'server') store.state.user.serverConnectionConfig.id = 'another-server'
    if (change === 'expired') now = 32000
    assert.equal(navigation.takeEpisodePage(store, 'podcast', change === 'episode' ? 'episode-1' : 'episode-0'), null)
  }
})

test('direct entry and iOS fetch the authoritative detail and missing episodes redirect safely', async () => {
  for (const platform of ['android', 'ios']) {
    const store = newStore()
    let requests = 0
    if (platform === 'ios') navigation.rememberEpisodePage(store, item, episodes[0])
    const result = await detail.asyncData({ store, params: { id: 'podcast', episode: 'episode-0' }, redirect: () => assert.fail('redirect'), app: {
      $platform: platform, $nativeHttp: { get: async () => { requests++; return item } }, $db: { getLocalLibraryItemByLId: async () => null }
    } })
    assert.equal(result.episode.id, 'episode-0')
    assert.equal(requests, 1)
  }
  const result = await detail.asyncData({ store: newStore(), params: { id: 'podcast', episode: 'gone' }, redirect: path => path, app: {
    $platform: 'android', $nativeHttp: { get: async () => ({ ...item, media: { episodes: [] } }) },
    $db: { getLocalLibraryItemByLId: async () => ({ media: { episodes: [{ serverEpisodeId: 'gone' }] } }) }
  } })
  assert.equal(result, '/item/podcast')
})

test('episode tap paints loading before navigation, ignores double taps, and clears aborted handoff', async () => {
  let finishPaint, abort, pushes = 0
  const row = component('components/tables/podcast/EpisodeRow.vue', { waitForEpisodePaint: () => new Promise(resolve => { finishPaint = resolve }) })
  const ctx = { ...row.data(), $platform: 'android', $store: newStore(), libraryItem: item, libraryItemId: 'podcast', episode: episodes[0], $nextTick: async () => {},
    $router: { push(_path, _done, onAbort) { pushes++; abort = onAbort } } }
  const pending = row.methods.goToEpisodePage.call(ctx)
  await new Promise(setImmediate)
  assert.equal(ctx.openingEpisode, true)
  assert.equal(pushes, 0)
  await row.methods.goToEpisodePage.call(ctx)
  finishPaint()
  await pending
  assert.equal(pushes, 1)
  abort()
  assert.equal(ctx.openingEpisode, false)
  assert.equal(navigation.takeEpisodePage(ctx.$store, 'podcast', 'episode-0'), null)
  const destroyed = row.methods.goToEpisodePage.call(ctx)
  await new Promise(setImmediate)
  ctx._isDestroyed = true
  finishPaint()
  await destroyed
  assert.equal(pushes, 1)
})

for (const platform of ['android', 'ios']) {
  test(`${platform}: filter counts and rendered rows agree across the virtualization threshold`, async () => {
    const store = newStore()
    const settings = Vue.observable({ podcastEpisodesFilterBy: 'all', podcastEpisodesOrderBy: 'title', podcastEpisodesOrderDesc: false })
    Vue.observable(store.state)
    store.getters['user/getUserSetting'] = key => settings[key]
    store.dispatch = (_action, payload) => Object.assign(settings, payload)
    store.state.user.user.mediaProgress = episodes.slice(0, 3).map(e => ({ libraryItemId: 'podcast', episodeId: e.id, isFinished: true }))
    store.getters['user/getUserMediaProgress'] = (id, episode) => store.state.user.user.mediaProgress.find(p => p.libraryItemId === id && p.episodeId === episode)
    const row = { props: ['episode'], render(h) { return h('p', { attrs: { 'data-episode-row': this.episode.id } }, this.episode.title) } }
    const view = new Vue({ ...table, propsData: { libraryItem: item, episodes, localEpisodes: [] },
      components: { 'tables-podcast-virtual-episodes': virtual, 'tables-podcast-episode-row': row },
      beforeCreate() { this.$platform = platform; this.$strings = strings; this.$store = store }
    })
    for (const count of [0, 3, 100, 101, 1200, 101, 100, 3, 0]) {
      view.localEpisodes = episodes.slice(0, count).map(e => ({ id: `local-${e.id}`, serverEpisodeId: e.id }))
      for (const filter of ['all', 'downloaded', 'complete', 'inProgress', 'incomplete', 'downloaded', 'all']) {
        view.setFilter(filter)
        await Vue.nextTick()
        const expected = filter === 'all' ? 1200 : filter === 'downloaded' ? count : filter === 'complete' ? 3 : filter === 'inProgress' ? 0 : 1197
        assert.equal(view.episodesSorted.length, expected)
        const html = await renderer.renderToString(view)
        const rows = (html.match(/data-episode-row=/g) || []).length
        assert.match(html, new RegExp(`Episodes \\(${expected}\\)`))
        if (platform === 'android' && expected > 100) {
          assert.ok(rows > 0 && rows < 20)
          assert.match(html, new RegExp(`aria-setsize="${expected}"`))
        } else {
          assert.equal(rows, expected)
          assert.doesNotMatch(html, /aria-setsize/)
        }
      }
    }
  })
}
