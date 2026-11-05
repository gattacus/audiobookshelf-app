const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const Vue = require('vue')
const compiler = require('vue-template-compiler')
const strings = require('../strings/en-us.json')
const parsed = compiler.parseComponent(fs.readFileSync('components/tables/podcast/EpisodeRow.vue', 'utf8'))
assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
const clone = value => JSON.parse(JSON.stringify(value))

function setup({ platform = 'android', confirm = async () => ({ value: true }), remove, url = 'file:///app/downloads/one.mp3' } = {}) {
  const calls = [], confirmations = [], errors = []
  let stored = { id: 'local-podcast', libraryItemId: 'podcast', localFiles: [{ id: 'one', contentUrl: url }, { id: 'two', contentUrl: 'content://downloads/two' }],
    media: { episodes: [{ id: 'local-one', serverEpisodeId: 'one', audioTrack: { localFileId: 'one', contentUrl: url } },
      { id: 'local-two', serverEpisodeId: 'two', audioTrack: { localFileId: 'two', contentUrl: 'content://downloads/two' } }] } }
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: { error() {} }, cellularPermissionHelpers: {},
    Dialog: { confirm: async payload => { confirmations.push(payload); return confirm(payload) } },
    AbsFileSystem: { deleteTrackFromItem: async payload => {
      calls.push(clone(payload))
      if (remove) return remove(payload)
      stored.media.episodes = stored.media.episodes.filter(ep => ep.audioTrack.localFileId !== payload.trackLocalFileId)
      stored.localFiles = stored.localFiles.filter(file => file.id !== payload.trackLocalFileId)
      return clone(stored)
    } }
  })
  const component = { ...module.exports, ...compiler.compileToFunctions(parsed.template.content) }
  const progress = { isFinished: true, currentTime: 300, progress: 1, duration: 300 }
  const view = new Vue({ ...component,
    propsData: { libraryItemId: 'podcast', libraryItem: Vue.observable({ id: 'podcast', localLibraryItem: clone(stored) }), episode: { id: 'one', title: 'Episode one', duration: 300 },
      localLibraryItemId: 'local-podcast', localEpisode: clone(stored.media.episodes[0]), isLocal: false },
    beforeCreate() {
      this.$platform = platform
      this.$strings = strings
      this.$getString = (key, subs) => strings[key].replace(/\{(\d+)\}/g, (_, n) => subs[n])
      this.$db = { getLocalLibraryItem: async () => clone(stored) }
      this.$hapticsImpact = async () => {}
      this.$toast = { error: message => errors.push(message) }
      this.$elapsedPretty = () => '5m'
      this.$store = { state: {}, getters: { 'user/getUserCanDownload': true, getIsMediaStreaming: () => false,
        'user/getUserMediaProgress': () => progress, 'globals/getLocalMediaProgressById': () => progress, 'globals/getDownloadItem': () => null } }
    }
  })
  // Mirror the parent page's reactive localEpisodeMap, including Downloaded filtering.
  view.$watch(() => view.libraryItem.localLibraryItem, item => { view.localEpisode = item.media.episodes.find(ep => ep.serverEpisodeId === view.episode.id) })
  return { view, calls, confirmations, errors, progress, stored }
}

function removeButton(view) {
  const visit = node => node.tag === 'button' && node.data?.attrs?.['aria-label'] === strings.ButtonRemoveDownload ? node : (node.children || []).map(visit).find(Boolean)
  return visit(view._render())
}

test('green check is an accessible Android button and stops episode navigation', async () => {
  const { view, calls } = setup({ confirm: async () => ({ value: false }) })
  const button = removeButton(view)
  assert.ok(button)
  let stopped = false
  await button.data.on.click({ stopPropagation() { stopped = true } })
  assert.equal(stopped, true)
  assert.equal(calls.length, 0)
  assert.equal(removeButton(setup({ platform: 'ios' }).view), undefined)
})

test('cancel preserves the local item and progress', async () => {
  const { view, calls, confirmations, progress } = setup({ confirm: async () => ({ value: false }) })
  const before = clone(view.libraryItem)
  await view.removeDownload()
  assert.equal(confirmations.length, 1)
  assert.match(confirmations[0].message, /Episode one.*listening progress.*server copy/)
  assert.equal(confirmations[0].okButtonTitle, 'Remove download')
  assert.equal(confirmations[0].cancelButtonTitle, 'Cancel')
  assert.equal(calls.length, 0)
  assert.deepEqual(clone(view.libraryItem), before)
  assert.equal(progress.isFinished, true)
  assert.equal(view.removingDownload, false)
})

for (const url of ['file:///app/downloads/one.mp3', 'content://shared/downloads/one']) {
  test(`confirmed removal targets only the selected file and updates the green check: ${url}`, async () => {
    const { view, calls, errors, progress } = setup({ url })
    const beforeProgress = clone(progress)
    await view.removeDownload()
    await Vue.nextTick()
    assert.deepEqual(calls, [{ id: 'local-podcast', trackLocalFileId: 'one', trackContentUrl: url }])
    assert.deepEqual(view.libraryItem.localLibraryItem.media.episodes.map(ep => ep.serverEpisodeId), ['two'])
    assert.equal(view.localEpisode, undefined)
    assert.equal(removeButton(view), undefined)
    assert.deepEqual(progress, beforeProgress)
    assert.equal(errors.length, 0)
  })
}

for (const mutation of [s => { s.media.episodes[0].audioTrack = null }, s => { s.localFiles[0].contentUrl = 'different' },
  s => { s.media.episodes[0].audioTrack.contentUrl = '' }, s => { s.media.episodes[0].audioRemoved = true },
  s => { s.media.episodes[0].serverEpisodeId = 'other' }, s => { s.libraryItemId = 'other' }]) {
  test('invalid persisted file or identity never reaches deletion', async () => {
    const { view, stored, calls, confirmations, errors } = setup()
    mutation(stored)
    await view.removeDownload()
    assert.equal(calls.length, 0)
    assert.equal(confirmations.length, 0)
    assert.equal(errors.length, 1)
  })
}

test('repeated taps are locked while confirming; a replaced file is not deleted', async () => {
  let resolveConfirm
  const { view, stored, calls, confirmations, errors } = setup({ confirm: () => new Promise(resolve => { resolveConfirm = resolve }) })
  const pending = view.removeDownload()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(removeButton(view).data.attrs.disabled, true)
  await view.removeDownload()
  assert.equal(confirmations.length, 1)
  stored.media.episodes[0].audioTrack.contentUrl = stored.localFiles[0].contentUrl = 'file:///app/new-download.mp3'
  resolveConfirm({ value: true })
  await pending
  assert.equal(calls.length, 0)
  assert.equal(errors.length, 1)
  assert.equal(view.removingDownload, false)
})

for (const remove of [async () => ({ success: false }), async () => { throw new Error('Permission denied') }]) {
  test('native failure retains the downloaded indicator and permits retry', async () => {
    const { view, errors } = setup({ remove })
    await view.removeDownload()
    assert.ok(removeButton(view))
    assert.equal(view.removingDownload, false)
    assert.equal(errors.length, 1)
  })
}

test('a destroyed row or changed episode cancels an outstanding confirmation', async () => {
  for (const change of [view => { view._isDestroyed = true }, view => { view.episode = { id: 'other' } }]) {
    const { view, calls } = setup({ confirm: async () => { change(view); return { value: true } } })
    await view.removeDownload()
    assert.equal(calls.length, 0)
  }
})
