const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const Vue = require('vue')
const compiler = require('vue-template-compiler')
const renderer = require('vue-server-renderer').createRenderer()
function component(path) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), { module })
  return { ...module.exports, ...compiler.compileToFunctions(parsed.template.content) }
}
const home = component('pages/bookshelf/index.vue')
const indicator = component('components/widgets/ConnectionIndicator.vue')
async function render(platform, attempting, background) {
  const app = new Vue({
    data: { shelves: [{ id: 'local-books' }], visibleShelves: [{ id: 'local-books' }], isLoading: false, altViewEnabled: false, user: null },
    beforeCreate() {
      this.$platform = platform
      this.$store = { state: { attemptingConnection: attempting, connectionAttemptIsBackground: background } }
      this.$strings = { MessageAttemptingServerConnection: 'Attempting server connection' }
    },
    computed: { attemptingConnection: home.computed.attemptingConnection, showConnectionAttempt: home.computed.showConnectionAttempt },
    methods: { getShelfLabel: () => 'Downloaded books' },
    components: {
      'bookshelf-shelf': { render(h) { return h('p', 'Downloaded books') } },
      'widgets-loading-spinner': { render(h) { return h('span', 'spinner') } }
    },
    render: home.render, staticRenderFns: home.staticRenderFns
  })
  return renderer.renderToString(app)
}

test('Android shows initial feedback but keeps downloaded shelves identical during retries and backoff', async () => {
  const initial = await render('android', true, false)
  assert.match(initial, /Attempting server connection/)
  const retry = await render('android', true, true)
  assert.doesNotMatch(retry, /Attempting server connection|spinner/)
  assert.match(retry, /Downloaded books/)
  assert.equal(retry, await render('android', false, false))
})

test('iOS connection feedback is unchanged', async () => {
  assert.match(await render('ios', true, true), /Attempting server connection/)
})

test('small connection icon and tap-for-status remain available during a quiet retry', () => {
  const ctx = { user: null, attemptingConnection: true, networkConnected: true, socketConnected: false, isCellular: false }
  assert.equal(indicator.computed.icon.call(ctx), 'cloud_sync')
  assert.equal(indicator.computed.iconClass.call(ctx), 'text-warning')
})
