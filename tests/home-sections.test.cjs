const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')
const Vue = require('vue')
const renderer = require('vue-server-renderer').createRenderer()

function loadSource(path, exports, bindings = {}) {
  const module = { exports: {} }
  const source = fs.readFileSync(path, 'utf8').replace(/^import .*$/gm, '').replace(/export default /g, 'module.exports = ').replace(/export (const|function) /g, '$1 ')
  vm.runInNewContext(source + (exports ? `\nmodule.exports = { ${exports} };` : ''), { module, ...bindings })
  return module.exports
}
const helpers = loadSource('utils/homeSections.js', 'HOME_SECTIONS, normalizeHiddenHomeSections, visibleHomeShelves')
const userStore = loadSource('store/user.js', 'state, actions, mutations', helpers)
function component(path) {
  const source = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  const module = { exports: {} }
  vm.runInNewContext(source.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), { module, jumpLabelMixin: {}, ...helpers })
  return { ...module.exports, ...compiler.compileToFunctions(source.template.content) }
}
const home = component('pages/bookshelf/index.vue')
const settings = component('pages/settings.vue')
const ids = (shelves) => Array.from(shelves, (shelf) => shelf.id)
function device(saved) {
  const state = userStore.state()
  const commits = (type, payload) => userStore.mutations[type](state, payload)
  const runtime = {
    $localStore: {
      setUserSettings: async (value) => { saved = JSON.parse(JSON.stringify(value)) },
      getUserSettings: async () => saved
    },
    $eventBus: { $emit() {} }
  }
  return { state, saved: () => saved, load: () => userStore.actions.loadUserSettings.call(runtime, { state, commit: commits }), update: (payload) => userStore.actions.updateUserSettings.call(runtime, { state, commit: commits }, payload) }
}

test('existing installations and invalid stored values show all sections by default', async () => {
  for (const saved of [undefined, { playbackRate: 1.5 }, { hiddenHomeSections: null }, { hiddenHomeSections: 'listen-again' }, { hiddenHomeSections: {} }]) {
    const app = device(saved)
    await app.load()
    assert.deepEqual(Array.from(app.state.settings.hiddenHomeSections), [])
    assert.equal(app.state.settings.playbackRate, saved?.playbackRate || 1)
  }
  assert.deepEqual(Array.from(helpers.normalizeHiddenHomeSections(['listen-again', null, 5, 'listen-again'])), ['listen-again'])
})

test('global filtering handles books, podcasts, offline shelves and future section IDs without mutating source data', () => {
  const books = [{ id: 'listen-again', entities: ['finished'] }, { id: 'discover' }, { id: 'future-shelf' }]
  const podcasts = [{ id: 'listen-again' }, { id: 'newest-episodes' }]
  const offline = [{ id: 'local-books-continue' }, { id: 'local-books' }, { id: 'local-podcasts' }]
  const hidden = ['listen-again', 'local-books']
  assert.deepEqual(ids(helpers.visibleHomeShelves(books, hidden)), ['discover', 'future-shelf'])
  assert.deepEqual(ids(helpers.visibleHomeShelves(podcasts, hidden)), ['newest-episodes'])
  assert.deepEqual(ids(helpers.visibleHomeShelves(offline, hidden)), ['local-books-continue', 'local-podcasts'])
  assert.equal(books[0].entities[0], 'finished')
  assert.equal(books.length, 3)
})

test('settings switches save globally, restore after restart and re-enable hidden content', async () => {
  const app = device({ playbackRate: 1.5, mobileFilterBy: 'progress.finished' })
  await app.load()
  const ctx = {
    $store: { state: { user: app.state }, dispatch: (action, payload) => { assert.equal(action, 'user/updateUserSettings'); return app.update(payload) } },
    $hapticsImpact: async () => {}
  }
  Object.defineProperty(ctx, 'hiddenHomeSections', { get: () => settings.computed.hiddenHomeSections.call(ctx) })
  await settings.methods.toggleHomeSection.call(ctx, 'listen-again')
  await settings.methods.toggleHomeSection.call(ctx, 'discover')
  const restarted = device(app.saved())
  await restarted.load()
  assert.deepEqual(Array.from(restarted.state.settings.hiddenHomeSections), ['listen-again', 'discover'])
  assert.equal(restarted.state.settings.playbackRate, 1.5)
  assert.equal(restarted.state.settings.mobileFilterBy, 'progress.finished')
  userStore.mutations.logout(restarted.state)
  assert.deepEqual(Array.from(restarted.state.settings.hiddenHomeSections), ['listen-again', 'discover'])
  await settings.methods.toggleHomeSection.call(ctx, 'listen-again')
  assert.deepEqual(Array.from(app.saved().hiddenHomeSections), ['discover'])
})

test('home filtering reacts immediately to changes and preserves the iOS home screen', async () => {
  const shelves = [{ id: 'listen-again' }, { id: 'discover' }]
  const store = Vue.observable({ state: { user: { settings: { hiddenHomeSections: [] } } } })
  const view = new Vue({ data: { shelves, isIos: false, $unused: null }, computed: { visibleShelves: home.computed.visibleShelves }, beforeCreate() { this.$store = store } })
  assert.deepEqual(ids(view.visibleShelves), ['listen-again', 'discover'])
  store.state.user.settings.hiddenHomeSections = ['listen-again']
  await Vue.nextTick()
  assert.deepEqual(ids(view.visibleShelves), ['discover'])
  view.isIos = true
  assert.deepEqual(ids(view.visibleShelves), ['listen-again', 'discover'])
  view.$destroy()
})

test('rendered home hides the selected shelf and offers Settings when all available sections are hidden', async () => {
  async function render(hidden) {
    const shelves = [{ id: 'listen-again', label: 'Listen Again', entities: [] }]
    const app = new Vue({
      data: { shelves, visibleShelves: helpers.visibleHomeShelves(shelves, hidden), attemptingConnection: false, isLoading: false, altViewEnabled: false, user: {} },
      beforeCreate() { this.$strings = { LabelHomeSections: 'Home sections', MessageHomeSectionsHidden: 'All sections hidden', MessageBookshelfEmpty: 'Bookshelf empty' } },
      methods: { getShelfLabel: (shelf) => shelf.label },
      components: { 'bookshelf-shelf': { props: ['label'], render(h) { return h('p', this.label) } }, 'ui-btn': { render(h) { return h('button', this.$slots.default) } } },
      render: home.render, staticRenderFns: home.staticRenderFns
    })
    return renderer.renderToString(app)
  }
  assert.match(await render([]), /Listen Again/)
  const hidden = await render(['listen-again'])
  assert.doesNotMatch(hidden, /Listen Again|Bookshelf empty/)
  assert.match(hidden, /All sections hidden/)
  assert.match(hidden, /<button[^>]*>Home sections<\/button>/)
})

test('switches expose explicit enabled and disabled states to accessibility services', async () => {
  const modal = component('components/modals/HomeSectionsModal.vue')
  const app = new Vue({
    ...modal,
    propsData: { value: true, hidden: ['listen-again'] },
    beforeCreate() { this.$strings = JSON.parse(fs.readFileSync('strings/en-us.json', 'utf8')) },
    components: { 'modals-modal': { render(h) { return h('div', this.$slots.default) } } }
  })
  const html = await renderer.renderToString(app)
  const buttons = html.match(/<button\b[^>]*>[\s\S]*?<\/button>/g)
  assert.equal(buttons.length, helpers.HOME_SECTIONS.length)
  assert.match(buttons.find((button) => button.includes('Listen Again')), /aria-checked="false"/)
  assert.match(buttons.find((button) => button.includes('Discover')), /aria-checked="true"/)
})

test('switch list includes every current shelf with English and German labels', () => {
  for (const language of ['en-us', 'de']) {
    const strings = JSON.parse(fs.readFileSync(`strings/${language}.json`, 'utf8'))
    for (const section of helpers.HOME_SECTIONS) assert.ok(strings[section.labelStringKey], `${language}: ${section.id}`)
    assert.ok(strings.LabelHomeSectionsHelp)
    assert.ok(strings.MessageHomeSectionsHidden)
  }
  for (const path of ['pages/settings.vue', 'pages/bookshelf/index.vue', 'components/modals/HomeSectionsModal.vue']) {
    const template = compiler.parseComponent(fs.readFileSync(path, 'utf8')).template.content
    assert.deepEqual(compiler.compile(template).errors, [], path)
  }
})
