const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')

function loadSource(path, bindings = {}) {
  const module = { exports: {} }
  const source = fs.readFileSync(path, 'utf8').replace(/^import .*$/gm, '').replace(/export default /g, 'module.exports = ').replace(/export (const|function) /g, '$1 ')
  vm.runInNewContext(source + '\n' + (path === 'store/globals.js' ? 'module.exports = { state, mutations, getters };' : path === 'utils/downloadStatus.js' ? 'module.exports = { storageFailureMessage };' : ''), { module, console, ...bindings })
  return module.exports
}
function loadComponent(path, bindings) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), { module, console, ...bindings })
  return module.exports
}
const { storageFailureMessage } = loadSource('utils/downloadStatus.js')
const { state, mutations, getters } = loadSource('store/globals.js')
const widget = loadComponent('components/widgets/DownloadProgressIndicator.vue', { storageFailureMessage })
const failedPart = () => ({ id: 'audio', downloadItemId: 'episode', filename: 'episode.opus', failed: true, completed: false, failureCode: 'INSUFFICIENT_STORAGE', availableStorageBytes: 5000000000, requiredStorageBytes: 5876000000 })
function context(platform = 'android') {
  const storeState = state()
  const toasts = []
  const ctx = { $platform: platform, $getString: (_, subs) => `Free ${subs[0]} and retry`, $bytesPretty: (bytes) => `${bytes} bytes`, $toast: { error: (text) => toasts.push(text) }, $store: { state: { globals: storeState }, commit: (name, payload) => mutations[name.split('/')[1]](storeState, payload) } }
  for (const [key, getter] of Object.entries(widget.computed)) Object.defineProperty(ctx, key, { get: () => getter.call(ctx) })
  for (const [key, method] of Object.entries(widget.methods)) ctx[key] = method.bind(ctx)
  return { ctx, storeState, toasts }
}

test('storage failure shows amount to free once, survives idle event, and does not animate', () => {
  const { ctx, storeState, toasts } = context()
  storeState.itemDownloads.push({ id: 'episode', downloadItemParts: [{ ...failedPart(), failed: false, failureCode: null }] })
  ctx.onDownloadItemPartUpdate(failedPart())
  ctx.onDownloadItemPartUpdate(failedPart())
  ctx.onQueueChanged({ hasWork: false })
  assert.deepEqual(toasts, ['Free 876000000 bytes and retry'])
  assert.equal(storeState.itemDownloads.length, 1)
  assert.equal(ctx.downloadItemPartsRemaining.length, 0)
  assert.equal(ctx.hasFailedDownloads, true)
  assert.equal(ctx.progress, 0)
  // An explicit retry resets the record, so a still-full phone gets one fresh warning.
  ctx.onDownloadItem({ id: 'episode', downloadItemParts: [{ ...failedPart(), failed: false, failureCode: null }] })
  ctx.onDownloadItemPartUpdate(failedPart())
  assert.equal(toasts.length, 2)
})

test('idle event preserves failures but removes finished records; iOS keeps existing behavior', () => {
  for (const platform of ['android', 'ios']) {
    const { ctx, storeState } = context(platform)
    storeState.itemDownloads.push({ id: 'episode', downloadItemParts: [failedPart()] }, { id: 'done', downloadItemParts: [{ completed: true, moved: true, failed: false }] })
    ctx.onQueueChanged({ hasWork: false })
    assert.equal(storeState.itemDownloads.length, platform === 'android' ? 1 : 0)
  }
})

test('failed part never inflates active download progress', () => {
  const { ctx, storeState } = context()
  storeState.itemDownloads.push({ id: 'episode', downloadItemParts: [{ ...failedPart(), fileSize: 1000000, bytesDownloaded: 999999 }, { id: 'active', failed: false, completed: false, fileSize: 100, bytesDownloaded: 25 }] })
  assert.equal(ctx.downloadItemPartsRemaining.length, 1)
  assert.equal(ctx.progress, 0.25)
})

test('retry is offered only when the item has stopped and invokes the Android bridge with its ID', async () => {
  let requested
  const page = loadComponent('pages/downloading.vue', { storageFailureMessage, AbsDownloader: { retryDownloadItem: async (data) => { requested = data.downloadItemId } } })
  const ctx = { ...page.data(), downloadItems: [{ id: 'episode', downloadItemParts: [failedPart(), { moved: false, failed: false }] }], $set: (obj, key, value) => { obj[key] = value }, $toast: { error: () => assert.fail('unexpected error') } }
  assert.equal(page.methods.canRetry.call(ctx, failedPart()), false)
  ctx.downloadItems[0].downloadItemParts[1].moved = true
  assert.equal(page.methods.canRetry.call(ctx, failedPart()), true)
  await page.methods.retry.call(ctx, failedPart())
  assert.equal(requested, 'episode')
  assert.equal(ctx.retrying.episode, false)
})


test('episode and book buttons stop pulsing for retained failures but keep queued work active', () => {
  const storeState = state()
  const item = { id: 'episode', libraryItemId: 'podcast', episodeId: 'episode-id', downloadItemParts: [failedPart()] }
  storeState.itemDownloads.push(item)
  const lookup = getters.getDownloadItem(storeState)
  assert.equal(lookup('podcast', 'episode-id'), undefined)
  item.downloadItemParts.push({ failed: false, moved: false })
  assert.equal(lookup('podcast', 'episode-id'), item)
  assert.equal(lookup('podcast', 'another-episode'), undefined)
  item.episodeId = null
  assert.equal(lookup('podcast'), item)
  item.downloadItemParts[1].moved = true
  assert.equal(lookup('podcast'), undefined)
})
