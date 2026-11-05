const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const Vue = require('vue')
const compiler = require('vue-template-compiler')
const strings = require('../strings/en-us.json')
const parsed = compiler.parseComponent(fs.readFileSync('components/tables/podcast/EpisodeRow.vue', 'utf8'))
assert.deepEqual(compiler.compile(parsed.template.content).errors, [])

function setup({ confirm = async () => ({ value: true }), cancel = async () => ({ status: 'CANCELLED' }), platform = 'android' } = {}) {
  const confirmations = [], calls = [], errors = [], commits = []
  const downloads = Vue.observable([{ id: 'transfer-one', libraryItemId: 'podcast', episodeId: 'one' }, { id: 'transfer-two', libraryItemId: 'podcast', episodeId: 'two' }])
  const downloadState = Vue.observable({ items: downloads })
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, cellularPermissionHelpers: {}, console: { error() {} },
    Dialog: { confirm: async payload => { confirmations.push(payload); return confirm(payload) } },
    AbsDownloader: { cancelDownloadItem: async payload => { calls.push(JSON.parse(JSON.stringify(payload))); return cancel(payload) } }
  })
  const view = new Vue({ ...module.exports, ...compiler.compileToFunctions(parsed.template.content),
    propsData: { libraryItemId: 'podcast', libraryItem: { id: 'podcast' }, episode: { id: 'one', title: 'Episode one', duration: 300 } },
    beforeCreate() {
      this.$platform = platform
      this.$strings = strings
      this.$getString = (key, subs) => strings[key].replace(/\{(\d+)\}/g, (_, n) => subs[n])
      this.$hapticsImpact = async () => {}
      this.$elapsedPretty = () => '5m'
      this.$toast = { error: message => errors.push(message) }
      this.$store = { state: {}, getters: { 'user/getUserCanDownload': true, getIsMediaStreaming: () => false,
        'user/getUserMediaProgress': () => null, 'globals/getDownloadItem': (item, episode) => downloadState.items.find(d => d.libraryItemId === item && d.episodeId === episode) },
        commit: (action, id) => { commits.push([action, id]); downloads.splice(downloads.findIndex(d => d.id === id), 1) }
      }
    }
  })
  return { view, confirmations, calls, errors, downloads, commits }
}
function cancelButton(view) {
  const visit = node => node.tag === 'button' && node.data?.attrs?.['aria-label'] === strings.ButtonCancelDownload ? node : (node.children || []).map(visit).find(Boolean)
  return visit(view._render())
}

test('in-progress icon is an accessible Android cancel button and stops row navigation', async () => {
  const { view } = setup({ confirm: async () => ({ value: false }) })
  let stopped = false
  await cancelButton(view).data.on.click({ stopPropagation() { stopped = true } })
  assert.equal(stopped, true)
  assert.equal(cancelButton(setup({ platform: 'ios' }).view), undefined)
})

test('Keep downloading leaves both transfers running', async () => {
  const { view, calls, confirmations, downloads } = setup({ confirm: async () => ({ value: false }) })
  await view.cancelDownload()
  assert.equal(calls.length, 0)
  assert.equal(downloads.length, 2)
  assert.equal(confirmations[0].title, 'Cancel download?')
  assert.match(confirmations[0].message, /Episode one.*partial files.*Completed downloads.*progress/)
  assert.equal(confirmations[0].cancelButtonTitle, 'Keep downloading')
})

test('confirmation cancels only this episode and restores the normal download icon', async () => {
  const { view, calls, downloads, commits } = setup()
  await view.cancelDownload()
  assert.deepEqual(calls, [{ downloadItemId: 'transfer-one' }])
  assert.deepEqual(downloads.map(d => d.id), ['transfer-two'])
  assert.deepEqual(commits, [['globals/removeItemDownload', 'transfer-one']])
  assert.equal(cancelButton(view), undefined)
  assert.equal(view.startingDownload, false)
})

test('repeated taps cannot open duplicate dialogs or cancel twice', async () => {
  let resolve
  const { view, confirmations, calls } = setup({ confirm: () => new Promise(r => { resolve = r }) })
  const pending = view.cancelDownload()
  await new Promise(r => setImmediate(r))
  assert.equal(cancelButton(view).data.attrs.disabled, true)
  await view.cancelDownload()
  assert.equal(confirmations.length, 1)
  resolve({ value: true })
  await pending
  assert.equal(calls.length, 1)
  assert.equal(view.cancellingDownload, false)
})

for (const status of ['FINISHING', 'INVALID_STAGING']) {
  test(`${status} preserves the queue and explains why cancellation did not run`, async () => {
    const { view, errors, commits } = setup({ cancel: async () => ({ status }) })
    await view.cancelDownload()
    assert.equal(commits.length, 0)
    assert.equal(errors[0], status === 'FINISHING' ? strings.MessageDownloadFinishing : strings.MessageCancelDownloadFailed)
    assert.equal(view.cancellingDownload, false)
  })
}

test('a missing native transfer removes only its stale UI entry', async () => {
  const { view, downloads } = setup({ cancel: async () => ({ status: 'NOT_FOUND' }) })
  await view.cancelDownload()
  assert.deepEqual(downloads.map(d => d.id), ['transfer-two'])
})

test('bridge failure leaves the transfer visible and unlocks retry', async () => {
  const { view, errors, commits } = setup({ cancel: async () => { throw Error('Bridge unavailable') } })
  await view.cancelDownload()
  assert.equal(commits.length, 0)
  assert.equal(errors.length, 1)
  assert.equal(view.cancellingDownload, false)
})

test('completion, episode switch, or destroyed row while confirming cannot cancel a different transfer', async () => {
  for (const change of [(v, d) => d.splice(0, 1), v => { v.localEpisode = { id: 'local-one' } }, v => { v.episode = { id: 'other' } }, v => { v._isDestroyed = true }]) {
    let view, downloads
    const app = setup({ confirm: async () => { change(view, downloads); return { value: true } } })
    ;({ view, downloads } = app)
    await view.cancelDownload()
    assert.equal(app.calls.length, 0)
  }
})
