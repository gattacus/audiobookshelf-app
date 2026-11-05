const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')
function component(path, bindings = {}) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), { module, ...bindings, Date, setTimeout, clearTimeout })
  return module.exports
}
function context(page, extra = {}) {
  const ctx = { ...page.data(), ...extra }
  for (const [key, fn] of Object.entries(page.methods || {})) ctx[key] = fn.bind(ctx)
  for (const [key, getter] of Object.entries(page.computed || {})) Object.defineProperty(ctx, key, { get: () => getter.call(ctx) })
  return ctx
}
test('new management controls compile and iOS never queries the Android bridge', async () => {
  const page = component('pages/listening.vue', { AbsListening: new Proxy({}, { get() { assert.fail('Android bridge queried on iOS') } }) })
  const ctx = context(page, { $platform: 'ios' })
  await ctx.load()
  await page.mounted.call(ctx)
  component('components/widgets/PodcastDownloadRule.vue')
  component('components/widgets/ListeningStatus.vue')
})
test('manual cleanup sends only selected eligible items and explains offline progress preservation', async () => {
  let confirmation, deleted
  const page = component('pages/listening.vue', {
    AbsListening: { async cleanupFinished(payload) { deleted = payload; return { removedBytes: 50, failures: [] } } },
    Dialog: { async confirm(payload) { confirmation = payload; return { value: true } } }
  })
  const identity = n => ({ configId: 'a', userId: 'u', itemId: `book-${n}`, episodeId: null })
  const ctx = context(page, { $platform: 'android', $bytesPretty: n => `${n} bytes` })
  ctx.candidates = [{ identity: identity(1), title: 'First', bytes: 50, eligible: true }, { identity: identity(2), title: 'Protected', bytes: 100, eligible: false }]
  ctx.selected = ctx.candidates.map(item => ctx.key(item.identity))
  ctx.load = async () => {}
  await ctx.cleanup()
  assert.equal(deleted.identities.length, 1)
  assert.equal(deleted.identities[0].itemId, 'book-1')
  assert.match(confirmation.message, /pending listening sessions stay on this phone/)
  assert.match(confirmation.message, /50 bytes/)
  assert.doesNotMatch(confirmation.message, /Protected/)
})
test('enabling cleanup previews proposed delay and cancellation preserves previous settings', async () => {
  let preview, saved = false
  const page = component('pages/listening.vue', {
    AbsListening: { async getCleanupPreview(payload) { preview = payload; return { items: [] } }, async saveManagementSettings() { saved = true } },
    Dialog: { async confirm() { return { value: false } } }
  })
  const ctx = context(page)
  ctx.savedSettings = { cleanBooks: false, cleanEpisodes: false, bookDelayHours: 24 }
  ctx.settings = { ...ctx.savedSettings, cleanBooks: true, bookDelayHours: 0 }
  ctx.load = async () => {}
  await ctx.saveSettings()
  assert.equal(preview.settings.bookDelayHours, 0)
  assert.equal(preview.automatic, true)
  assert.equal(saved, false)
  assert.equal(ctx.settings.cleanBooks, false)
})
test('late progress-status response cannot replace the current item status', async () => {
  const responses = []
  const page = component('components/widgets/ListeningStatus.vue', { AbsListening: { getProgressStatus() { return new Promise(resolve => responses.push(resolve)) } } })
  const ctx = context(page)
  const first = ctx.load(), second = ctx.load()
  responses[1]({ status: { title: 'New', state: 'waiting' } }); await second
  responses[0]({ status: { title: 'Old', state: 'synced' } }); await first
  assert.equal(ctx.status.title, 'New')
})
test('progress details display phone and server positions as hours, minutes and seconds', async () => {
  const Vue = { prototype: {} }
  const source = fs.readFileSync('plugins/init.client.js', 'utf8')
  vm.runInNewContext(source.match(/Vue\.prototype\.\$secondsToTimestampFull = \(seconds\) => \{[\s\S]*?\n\}/)[0], { Vue })
  let dialog
  const status = { state: 'synced', position: 3661.9, serverPosition: 59.9, pendingSessions: 0 }
  const page = component('components/widgets/ListeningStatus.vue', {
    AbsListening: { async getProgressStatus() { return { status } } },
    Dialog: { async confirm(payload) { dialog = payload; return { value: false } }, async alert(payload) { dialog = payload } }
  })
  const ctx = context(page, Vue.prototype)
  await ctx.showDetails()
  assert.match(dialog.message, /^Position: 01:01:01\n/)
  assert.match(dialog.message, /\nServer position: 00:00:59\n/)
  status.position = 90061.9
  status.serverPosition = null
  await ctx.showDetails()
  assert.match(dialog.message, /^Position: 25:01:01\n/)
  assert.match(dialog.message, /\nServer position: Unknown\n/)
  status.state = 'local_only'
  status.position = 0
  await ctx.showDetails()
  assert.equal(dialog.title, 'Progress on this phone')
  assert.match(dialog.message, /^Position: 00:00:00\n/)
})
test('management link requests player collapse while retaining its navigation target', () => {
  const Vue = require('vue')
  const source = fs.readFileSync('components/widgets/ListeningStatus.vue', 'utf8')
  const { template } = compiler.parseComponent(source)
  const compiled = compiler.compile(template.content)
  const widget = new Vue({ render: new Function(compiled.render), data: () => ({ label: 'Synced' }), methods: { showDetails() {} } })
  let opened = 0
  widget.$on('open-management', () => opened++)
  const link = widget._render().children.find(node => node.tag === 'nuxt-link')
  assert.equal(link.data.attrs.to, '/listening')
  link.data.nativeOn.click({})
  assert.equal(opened, 1)
  const player = compiler.parseComponent(fs.readFileSync('components/app/AudioPlayer.vue', 'utf8'))
  const find = node => node.tag === 'widgets-listening-status' ? node : (node.children || []).map(find).find(Boolean)
  const status = find(compiler.compile(player.template.content).ast)
  assert.equal(status.events['open-management'].value, 'collapseFullscreen')
})
test('retained episode metadata is excluded from downloaded filters only after explicit audio removal', () => {
  const page = component('components/tables/podcast/EpisodesTable.vue')
  const map = page.computed.localEpisodeMap.call({ localEpisodes: [{ id: 'legacy', serverEpisodeId: 'a' }, { id: 'removed', serverEpisodeId: 'b', audioRemoved: true }] })
  assert.equal(map.a.id, 'legacy')
  assert.equal(map.b, undefined)
})
