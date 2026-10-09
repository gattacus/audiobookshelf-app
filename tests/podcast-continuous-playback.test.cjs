const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')

function component(bridge = {}) {
  const parsed = compiler.parseComponent(fs.readFileSync('components/PodcastContinuousPlayback.vue', 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), { module, AbsAudioPlayer: bridge })
  return module.exports
}

function context(bridge, target = { libraryItemId: 'podcast', serverConnectionConfigId: 'account', podcastType: 'serial' }) {
  const definition = component(bridge)
  const events = [], errors = []
  const ctx = { ...definition.data(), $platform: 'android', target, $emit(...args) { events.push(args) }, $toast: { error(value) { errors.push(value) } } }
  for (const [name, method] of Object.entries(definition.methods)) ctx[name] = method.bind(ctx)
  return { definition, ctx, events, errors }
}

test('remembered toggle loads before Play is enabled and saves the canonical podcast target', async () => {
  const requests = []
  const { ctx, events } = context({
    async getPodcastContinuousPlayback(target) { requests.push(target); return { enabled: true } },
    async setPodcastContinuousPlayback(target) { requests.push(target); return { enabled: target.enabled } }
  })
  await ctx.load()
  assert.equal(ctx.enabled, true)
  assert.deepEqual(events, [['ready', false], ['change', true], ['ready', true]])
  await ctx.toggle()
  assert.equal(ctx.enabled, false)
  assert.deepEqual(JSON.parse(JSON.stringify(requests[1])), { libraryItemId: 'podcast', serverConnectionConfigId: 'account', podcastType: 'serial', enabled: false })
})

test('native preference is authoritative on reopen and a failed save keeps the prior choice', async () => {
  let saved = false
  const bridge = { async getPodcastContinuousPlayback() { return { enabled: saved } }, async setPodcastContinuousPlayback({ enabled }) { saved = enabled; return { enabled } } }
  const first = context(bridge)
  await first.ctx.load()
  assert.equal(first.ctx.enabled, false)
  await first.ctx.toggle()
  const reopened = context(bridge)
  await reopened.ctx.load()
  assert.equal(reopened.ctx.enabled, true)
  bridge.setPodcastContinuousPlayback = async () => { throw new Error('Write failed') }
  await reopened.ctx.toggle()
  assert.equal(reopened.ctx.enabled, true)
  assert.deepEqual(reopened.errors, ['Could not save continuous playback setting'])
})

test('a late preference response cannot apply to a different podcast or a destroyed page', async () => {
  const pending = []
  const { ctx, definition, events } = context({ getPodcastContinuousPlayback() { return new Promise(resolve => pending.push(resolve)) } })
  const old = ctx.load()
  ctx.target = { libraryItemId: 'another', serverConnectionConfigId: 'account' }
  const fresh = ctx.load()
  pending[1]({ enabled: false })
  await fresh
  pending[0]({ enabled: true })
  await old
  assert.equal(ctx.enabled, false)
  assert.deepEqual(events.filter(([type]) => type === 'change'), [['change', false]])
  const destroyed = ctx.load()
  definition.beforeDestroy.call(ctx)
  pending[2]({ enabled: true })
  await destroyed
  assert.equal(ctx.enabled, false)
})

test('double taps do not write competing choices; load failure can be retried', async () => {
  let finish, writes = 0, reads = 0
  const { ctx, errors } = context({
    async getPodcastContinuousPlayback() { if (!reads++) throw new Error('Unavailable'); return { enabled: false } },
    setPodcastContinuousPlayback() { writes++; return new Promise(resolve => { finish = resolve }) }
  })
  await ctx.load()
  assert.equal(ctx.loadFailed, true)
  await ctx.toggle()
  assert.equal(writes, 0)
  await ctx.load()
  const saving = ctx.toggle()
  await ctx.toggle()
  assert.equal(writes, 1)
  finish({ enabled: true })
  await saving
  assert.equal(ctx.enabled, true)
  assert.equal(ctx.saving, false)
  assert.equal(errors.length, 1)
})

test('iOS and web never call the Android preference bridge', async () => {
  for (const platform of ['ios', 'web']) {
    const { ctx } = context({ getPodcastContinuousPlayback() { assert.fail('Android bridge called') } })
    ctx.$platform = platform
    await ctx.load()
  }
})

test('podcast pages compile with an accessible switch and the main action reflects its choice', () => {
  for (const file of ['pages/item/_id/index.vue', 'pages/localMedia/item/_id.vue']) {
    const parsed = compiler.parseComponent(fs.readFileSync(file, 'utf8'))
    assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
    assert.match(parsed.template.content, /podcast-continuous-playback/)
  }
  const toggle = fs.readFileSync('components/PodcastContinuousPlayback.vue', 'utf8')
  assert.match(toggle, /<button type="button" role="switch" :aria-checked=/)
  assert.match(fs.readFileSync('pages/item/_id/index.vue', 'utf8'), /continuousPodcastPlayback && !isCasting \? 'Play continuously'/)
})
