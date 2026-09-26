const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')
const strings = require('../strings/en-us.json')

const parsed = compiler.parseComponent(fs.readFileSync('pages/localMedia/item/_id.vue', 'utf8'))
assert.deepEqual(compiler.compile(parsed.template.content).errors, [])

function setup({ platform = 'android', confirm = async () => ({ value: true }), remove } = {}) {
  const calls = [], confirmations = [], successes = [], errors = []
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, draggable: {}, console: { error() {} },
    Dialog: { confirm: async (payload) => { confirmations.push(payload); return confirm(payload) } },
    AbsFileSystem: { deleteTrackFromItem: async (payload) => {
      calls.push(payload)
      if (remove) return remove(payload, ctx)
      const item = JSON.parse(JSON.stringify(ctx.localLibraryItem))
      item.media.episodes = item.media.episodes.filter((ep) => ep.audioTrack.localFileId !== payload.trackLocalFileId)
      item.localFiles = item.localFiles.filter((file) => file.id !== payload.trackLocalFileId)
      return item
    } }
  })
  const page = module.exports
  const ctx = { ...page.data(), $platform: platform, $strings: strings,
    $getString: (key, subs) => strings[key].replace(/\{(\d+)\}/g, (_, index) => subs[index]),
    $toast: { success: (message) => successes.push(message), error: (message) => errors.push(message) }
  }
  for (const [key, getter] of Object.entries(page.computed)) Object.defineProperty(ctx, key, { get: () => getter.call(ctx) })
  for (const [key, method] of Object.entries(page.methods)) ctx[key] = method.bind(ctx)
  ctx.localLibraryItem = { id: 'podcast', folderId: 'internal-podcasts', mediaType: 'podcast', libraryItemId: 'server-item',
    media: { metadata: { title: 'Test podcast' }, episodes: [1, 2, 3].map((n) => ({ id: `ep-${n}`, title: `Episode ${n}`, size: 100,
      audioTrack: { localFileId: `file-${n}`, contentUrl: `file:///cache/ep-${n}.ogg`, duration: 60, mimeType: 'audio/ogg' } })) },
    localFiles: [1, 2, 3].map((n) => ({ id: `file-${n}`, contentUrl: `file:///cache/ep-${n}.ogg`, size: 100 })) }
  return { ctx, calls, confirmations, successes, errors }
}
const selectedIds = (ctx) => Array.from(ctx.selectedEpisodeIds)
const remainingIds = (ctx) => Array.from(ctx.episodes, (ep) => ep.id)

test('toggle individual episodes, select all, clear, and cancel selection', () => {
  const { ctx } = setup()
  ctx.selectingEpisodes = true
  ctx.toggleEpisodeSelection('ep-2')
  assert.deepEqual(selectedIds(ctx), ['ep-2'])
  ctx.toggleEpisodeSelection('ep-2')
  assert.deepEqual(selectedIds(ctx), [])
  ctx.toggleAllEpisodes()
  assert.equal(ctx.allEpisodesSelected, true)
  ctx.toggleAllEpisodes()
  assert.equal(ctx.selectedEpisodes.length, 0)
  ctx.toggleEpisodeSelection('ep-1')
  ctx.cancelEpisodeSelection()
  assert.equal(ctx.selectingEpisodes, false)
  assert.equal(ctx.selectedEpisodes.length, 0)
})

test('canceling the single confirmation deletes nothing and retains selection', async () => {
  const { ctx, calls, confirmations } = setup({ confirm: async () => ({ value: false }) })
  ctx.selectingEpisodes = true
  ctx.toggleAllEpisodes()
  await ctx.deleteSelectedEpisodes()
  assert.equal(confirmations.length, 1)
  assert.equal(calls.length, 0)
  assert.equal(ctx.selectedEpisodes.length, 3)
  assert.equal(ctx.deletingEpisodes, false)
})

for (const storage of ['internal', 'shared']) {
  test(`bulk deletion removes only selected ${storage} files, awaits each save, and preserves metadata`, async () => {
    let inFlight = false
    const { ctx, calls, confirmations, errors } = setup({ remove: async (payload, ctx) => {
      assert.equal(inFlight, false, 'native deletions must run sequentially')
      inFlight = true
      const before = JSON.parse(JSON.stringify(ctx.localLibraryItem))
      await new Promise((resolve) => setTimeout(resolve, 5))
      before.media.episodes = before.media.episodes.filter((ep) => ep.audioTrack.localFileId !== payload.trackLocalFileId)
      before.localFiles = before.localFiles.filter((file) => file.id !== payload.trackLocalFileId)
      inFlight = false
      return before
    } })
    if (storage === 'shared') {
      ctx.localLibraryItem.folderId = 'shared-folder'
      for (let n = 0; n < 3; n++) {
        const url = `content://documents/episode-${n}`
        ctx.episodes[n].audioTrack.contentUrl = url
        ctx.localFiles[n].contentUrl = url
      }
    }
    ctx.localFiles.push({ id: 'cover', contentUrl: 'cover-url', size: 10 })
    ctx.selectingEpisodes = true
    ctx.toggleEpisodeSelection('ep-1')
    ctx.toggleEpisodeSelection('ep-3')
    await ctx.deleteSelectedEpisodes()
    assert.equal(confirmations.length, 1)
    assert.match(confirmations[0].message, /selected local episode files \(2\)/)
    assert.deepEqual(calls.map((call) => call.trackLocalFileId), ['file-1', 'file-3'])
    assert.deepEqual(remainingIds(ctx), ['ep-2'])
    assert.deepEqual(Array.from(ctx.localFiles, (file) => file.id), ['file-2', 'cover'])
    assert.equal(ctx.libraryItemId, 'server-item')
    assert.equal(ctx.totalEpisodesSize, 100)
    assert.equal(ctx.totalLibraryItemSize, 110)
    assert.equal(ctx.selectingEpisodes, false)
    assert.equal(errors.length, 0)
  })
}

test('partial failures and rejected calls keep failed episodes selected and continue deleting', async () => {
  let attempts = 0
  const { ctx, calls, successes, errors } = setup({ remove: async (payload, ctx) => {
    attempts++
    if (payload.trackLocalFileId === 'file-1') return { success: false }
    if (payload.trackLocalFileId === 'file-2') throw new Error('Permission denied')
    const item = JSON.parse(JSON.stringify(ctx.localLibraryItem))
    item.media.episodes.pop()
    item.localFiles.pop()
    return item
  } })
  ctx.selectingEpisodes = true
  ctx.toggleAllEpisodes()
  await ctx.deleteSelectedEpisodes()
  assert.equal(attempts, 3)
  assert.equal(calls.length, 3)
  assert.deepEqual(selectedIds(ctx), ['ep-1', 'ep-2'])
  assert.deepEqual(remainingIds(ctx), ['ep-1', 'ep-2'])
  assert.equal(ctx.selectingEpisodes, true)
  assert.match(successes[0], /Deleted episode files: 1/)
  assert.match(errors[0], /Failed to delete episode files: 2/)
  assert.equal(ctx.deletingEpisodes, false)
})

test('legacy missing or mismatched file references are never passed to the native bridge', async () => {
  const { ctx, calls, errors } = setup()
  ctx.episodes[0].audioTrack = null
  ctx.episodes[1].audioTrack.contentUrl = ''
  ctx.localFiles[2].contentUrl = 'different-file'
  ctx.toggleAllEpisodes()
  await ctx.deleteSelectedEpisodes()
  assert.equal(calls.length, 0)
  assert.equal(ctx.selectedEpisodes.length, 3)
  assert.match(errors[0], /Failed to delete episode files: 3/)
})

test('selection and repeated deletion stay locked while confirmation is open', async () => {
  let finishConfirm
  const { ctx, calls, confirmations } = setup({ confirm: () => new Promise((resolve) => { finishConfirm = resolve }) })
  ctx.selectingEpisodes = true
  ctx.toggleEpisodeSelection('ep-1')
  const pending = ctx.deleteSelectedEpisodes()
  ctx.toggleEpisodeSelection('ep-2')
  ctx.toggleAllEpisodes()
  ctx.cancelEpisodeSelection()
  await ctx.deleteSelectedEpisodes()
  assert.deepEqual(selectedIds(ctx), ['ep-1'])
  assert.equal(confirmations.length, 1)
  finishConfirm({ value: true })
  await pending
  assert.equal(calls.length, 1)
})

test('empty selections and iOS never invoke bulk deletion', async () => {
  for (const platform of ['android', 'ios']) {
    const { ctx, calls, confirmations } = setup({ platform })
    if (platform === 'ios') ctx.toggleAllEpisodes()
    await ctx.deleteSelectedEpisodes()
    assert.equal(calls.length, 0)
    assert.equal(confirmations.length, 0)
  }
})

test('confirmation failure releases the lock without deleting files', async () => {
  const { ctx, calls, errors } = setup({ confirm: async () => { throw new Error('Dialog unavailable') } })
  ctx.toggleAllEpisodes()
  await ctx.deleteSelectedEpisodes()
  assert.equal(calls.length, 0)
  assert.equal(ctx.deletingEpisodes, false)
  assert.equal(ctx.selectedEpisodes.length, 3)
  assert.equal(errors.length, 1)
})

test('rendered Android controls expose checkboxes and selection count while iOS keeps its existing rows', async () => {
  const Vue = require('vue')
  const renderer = require('vue-server-renderer').createRenderer()
  const compiled = compiler.compileToFunctions(parsed.template.content)
  const button = compiler.parseComponent(fs.readFileSync('components/ui/Btn.vue', 'utf8'))
  const buttonModule = { exports: {} }
  vm.runInNewContext(button.script.content.replace('export default ', 'module.exports = '), { module: buttonModule })
  const btn = { ...buttonModule.exports, ...compiler.compileToFunctions(button.template.content) }
  for (const platform of ['android', 'ios']) {
    const { ctx } = setup({ platform })
    const view = new Vue({
      ...compiled, components: { 'ui-btn': btn },
      data: { localLibraryItem: ctx.localLibraryItem, selectingEpisodes: false, selectedEpisodeIds: [], deletingEpisodes: false, orderChanged: false },
      computed: Object.fromEntries(['isAndroid', 'isIos', 'media', 'mediaMetadata', 'libraryItemId', 'liServerAddress', 'isPodcast', 'mediaType', 'episodes', 'selectedEpisodes', 'allEpisodesSelected', 'localFiles', 'ebookFile', 'localFileForEbook', 'audioTracks', 'otherFiles', 'totalEpisodesSize', 'totalOtherFilesSize', 'totalLibraryItemSize'].map((key) => [key, () => ctx[key]])),
      beforeCreate() { this.$strings = strings; this.$getString = ctx.$getString; this.$bytesPretty = String; this.$elapsedPretty = String },
      methods: { showItemDialog() {}, showTrackDialog() {} }
    })
    let html = await renderer.renderToString(view)
    assert.equal(html.includes('Select episodes'), platform === 'android')
    assert.equal(html.includes('type="checkbox"'), false)
    if (platform === 'android') {
      ctx.toggleEpisodeSelection('ep-2')
      view.selectingEpisodes = true
      view.selectedEpisodeIds = ctx.selectedEpisodeIds
      html = await renderer.renderToString(view)
      assert.equal((html.match(/type="checkbox"/g) || []).length, 3)
      assert.match(html, /Selected episodes: 1/)
      assert.match(html, /Delete selected files/)
      assert.match(html, /aria-label="Episode 2" checked="checked"/)
      assert.doesNotMatch(html, /showTrackDialog/)
    }
  }
})
