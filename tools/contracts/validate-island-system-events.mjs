#!/usr/bin/env node
/**
 * 校验星河岛系统事件标准清单(contracts/island-system-events.v1.json)和各家对照表。
 * 用法:node tools/contracts/validate-island-system-events.mjs
 * 标准清单要和 island-events 模块的代码对上:交换版本、交换字段、事件种类、每件事都带的内容、官方内容树的字段。
 * OPPO 对照表要和接入代码对上:服务号、服务意图、读的数据字段,全部从代码里的常量提取,不解析注释。
 */

import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = join(__dirname, '..', '..')
const read = (...s) => readFileSync(join(root, ...s), 'utf8')

let failed = false
function fail(msg) { console.error(`✗ ${msg}`); failed = true }
function pass(msg) { console.log(`✓ ${msg}`) }
const sameSet = (a, b) => a.length === b.length && new Set(a).size === new Set(b).size && a.every(x => b.includes(x))
const diff = (a, b) => `多出 [${a.filter(x => !b.includes(x))}],缺少 [${b.filter(x => !a.includes(x))}]`
function expectSet(label, actual, expected) {
  if (sameSet(actual ?? [], expected)) pass(label)
  else fail(`${label}:清单与代码不一致,清单${diff(actual ?? [], expected)}`)
}

// ── 标准清单与 island-events 的代码 ──
const events = join('island-events', 'src', 'main', 'java', 'com', 'astraisland', 'events')
const standard = JSON.parse(read('contracts', 'island-system-events.v1.json'))
const bridge = read(events, 'EventBridge.kt')
const codec = read(events, 'SystemEventCodec.kt')
const model = read(events, 'SystemEvent.kt')
const pageModel = read(events, 'PageNode.kt')
const constant = (source, name) => source.match(new RegExp(`const val ${name} = (?:"([^"]+)"|(\\d+))`))
const bridgeValue = key => constant(bridge, key)?.[1]

if (standard.bridge.version !== Number(constant(bridge, 'VERSION')?.[2])) fail('显示交接交换版本不一致')
else pass('显示交接交换版本一致')
expectSet('事件交换字段', standard.bridge.events, ['KEY_EVENTS', 'KEY_SETTINGS', 'KEY_ALIVE', 'KEY_ENDED'].map(bridgeValue))
expectSet('星河岛报的情况', standard.bridge.state, ['KEY_SPENT', 'KEY_ACTIVE', 'KEY_YIELD_ALLOWED'].map(bridgeValue))
if (standard.codec.version !== Number(constant(codec, 'VERSION')?.[2])) fail('编码版本不一致')
else pass('编码版本一致')

const enumIds = name => {
  const body = model.match(new RegExp(`enum class ${name}\\(val id: String\\) \\{([\\s\\S]*?);`))?.[1]
  if (!body) { fail(`模型里找不到 ${name}`); return [] }
  return [...body.matchAll(/[A-Z_]+\("([A-Za-z]+)"\)/g)].map(m => m[1])
}
const kindIds = standard.kinds.map(k => k.id)
for (const k of standard.kinds) if (!k.name) fail(`事件种类 ${k.id} 缺名字`)
expectSet('事件种类', kindIds, enumIds('EventKind'))
for (const [field, name] of [['alert', 'Alert'], ['instant', 'Instant'], ['callState', 'CallState']]) {
  expectSet(`${field} 的取值`, standard.event.fields.find(f => f.id === field)?.values, enumIds(name))
}
const properties = (source, cls) => {
  const body = source.match(new RegExp(`data class ${cls}\\(([\\s\\S]*?)\\n\\)`))?.[1]
  if (!body) { fail(`模型里找不到 ${cls}`); return [] }
  return [...body.matchAll(/^\s*val (\w+):/gm)].map(m => m[1])
}
for (const f of standard.event.fields) if (!f.meaning) fail(`每件事都带的内容 ${f.id} 没写意思`)
expectSet('每件事都带的内容', standard.event.fields.map(f => f.id), properties(model, 'SystemEvent'))
expectSet('官方内容树的字段', standard.page.fields.map(f => f.id), properties(pageModel, 'PageNode'))
const imageTypes = [...codec.matchAll(/putString\("type", "([a-z]+)"\)/g)].map(m => m[1])
expectSet('图的种类', standard.page.images, imageTypes)
if (standard.page.actionPrefix !== constant(model, 'PAGE_PREFIX')?.[1]) fail('元素动作的前缀不一致')
else pass('元素动作的前缀一致')

for (const v of standard.vendors) if (!existsSync(join(root, 'contracts', v.mapping))) fail(`对照表 ${v.mapping} 不存在`)

// ── OPPO 对照表与接入代码 ──
const oppo = JSON.parse(read('contracts', 'island-system-events.oppo.v1.json'))
expectSet('OPPO 对照表的事件种类', Object.keys(oppo.kinds), kindIds)
const derived = standard.event.fields.map(f => f.id).filter(id => !['userId', 'revision', 'kind', 'relatedNotifications'].includes(id))
expectSet('OPPO 对照表写明每件事都带的内容从哪里读', Object.keys(oppo.event), derived)

const dir = join('fluidcloud-core', 'src', 'main', 'java', 'com', 'astraflow', 'fluidcloud')
const services = read(dir, 'OfficialCloudServices.kt')
const consts = Object.fromEntries([...services.matchAll(/const val ([A-Z_]+) = "(\d+)"/g)].map(m => [m[1], m[2]]))
function setOf(name) {
  const m = services.match(new RegExp(`val ${name}(?:: [^=]+)? = setOf\\(([^)]*)\\)`))
  if (!m) { fail(`代码里找不到 ${name}`); return [] }
  return m[1].split(',').map(s => s.trim()).filter(Boolean).map(s => s.startsWith('"') ? s.slice(1, -1) : consts[s] ?? `未知常量 ${s}`)
}
const one = (...names) => names.map(name => { if (!consts[name]) fail(`代码里找不到服务号 ${name}`); return consts[name] })

expectSet('来电与通话的服务号', oppo.kinds.call.services, setOf('CALL_SERVICES'))
expectSet('卫星通话的服务号', oppo.kinds.satelliteCall.services, one('SATELLITE_CALL'))
expectSet('计时器的服务号', oppo.kinds.timer.services, one('TIMER', 'ONEPLUS_TIMER'))
expectSet('秒表的服务号', oppo.kinds.stopwatch.services, one('STOPWATCH'))
expectSet('闹钟的服务号', oppo.kinds.alarm.services, one('ALARM'))
expectSet('秒抢闹钟的服务号', oppo.kinds.flashAlarm.services, one('GARB_ALARM'))
expectSet('游戏计时的服务号', oppo.kinds.gameTimer.services, one('GAME_TIMER'))
expectSet('录屏的服务号', oppo.kinds.screenRecording.services, one('SCREEN_RECORDER'))
expectSet('录音的服务号', oppo.kinds.soundRecording.services, one('SOUND_RECORDER', 'ONEPLUS_SOUND_RECORDER'))
expectSet('录完已保存的服务号', oppo.kinds.recordingSaved.services, setOf('RECORDER_SERVICES'))
expectSet('用麦克风的系统服务', oppo.kinds.microphoneService.services, setOf('MICROPHONE_SERVICES'))
expectSet('系统开关的服务号', oppo.kinds.systemSwitch.services, setOf('SWITCH_SERVICES'))
expectSet('音乐的服务号', oppo.kinds.music.services, setOf('MEDIA_SERVICES'))
expectSet('充电胶囊的服务号', oppo.kinds.charging.services, one('CHARGE'))
expectSet('电池提示的服务号', oppo.kinds.batteryTip.services, one('BATTERY'))
expectSet('耳机等设备的服务号', oppo.kinds.device.services, setOf('DEVICE_SERVICES'))
expectSet('快递取件的服务号', oppo.kinds.parcelPickup.services, setOf('EXPRESS_PICKUP_SERVICES'))
expectSet('任务进度的服务号', oppo.kinds.taskProgress.services, setOf('TASK_SERVICES'))
expectSet('订单进度的服务号', oppo.kinds.orderProgress.services, setOf('TAKEOUT_SERVICES'))
expectSet('打车的服务号', oppo.kinds.ride.services, setOf('RIDE_SERVICES'))
expectSet('游戏复活倒计时的服务号', oppo.kinds.gameCountdown.services, setOf('COUNTDOWN_SERVICES'))
expectSet('提醒的服务号', oppo.kinds.reminder.services, setOf('REMINDER_SERVICES'))
expectSet('导航和红绿灯提醒合起来', [...oppo.kinds.navigation.services, ...oppo.kinds.trafficLight.services], setOf('NAVIGATION_LAYOUT_SERVICES'))
expectSet('红绿灯提醒的服务号', oppo.kinds.trafficLight.services, setOf('TRAFFIC_LIGHT_SERVICES'))
if (!services.includes('id.startsWith("common:") -> Layout.APP_NOTICE') || oppo.kinds.appNotice.servicePrefix !== 'common:') fail('应用通知卡片的认法和代码不一致')
else pass('应用通知卡片按服务号开头 common: 认')

// 服务意图
const intents = {}
for (const m of services.matchAll(/add\(Layout\.([A-Z_]+),([^)]*)\)/g)) intents[m[1]] = [...m[2].matchAll(/"([^"]+)"/g)].map(x => `pantanal.intent.${x[1]}`)
expectSet('订单进度的服务意图', oppo.kinds.orderProgress.intents, intents.TAKEOUT ?? [])
expectSet('打车的服务意图', oppo.kinds.ride.intents, intents.RIDE ?? [])
expectSet('任务进度的服务意图', oppo.kinds.taskProgress.intents, intents.TASK ?? [])
expectSet('游戏复活倒计时的服务意图', oppo.kinds.gameCountdown.intents, intents.COUNTDOWN ?? [])
expectSet('提醒的服务意图', oppo.kinds.reminder.intents, intents.REMINDER ?? [])
expectSet('导航和红绿灯提醒的服务意图合起来', [...oppo.kinds.navigation.intents, ...oppo.kinds.trafficLight.intents], intents.NAVIGATION ?? [])
expectSet('红绿灯提醒按服务意图认', oppo.kinds.trafficLight.intents, setOf('TRAFFIC_LIGHT_INTENTS'))

// 同一个服务号只属于一种(录完已保存与录屏、录音按页分开,不算重复)
const owner = new Map()
for (const [kind, entry] of Object.entries(oppo.kinds)) {
  if (kind === 'recordingSaved') continue
  for (const sid of entry.services ?? []) {
    if (owner.has(sid)) fail(`服务号 ${sid} 同时属于 ${owner.get(sid)} 和 ${kind}`)
    owner.set(sid, kind)
  }
}
pass(`OPPO 服务号 ${owner.size} 个,各属一种`)

// 接入代码读的卡片数据字段与对照表一致
const files = readdirSync(join(root, dir)).filter(f => f.startsWith('OfficialCloud') && f.endsWith('.kt'))
const keys = new Set()
for (const f of files) {
  const src = read(dir, f)
  for (const m of src.matchAll(/data\["([A-Za-z_]+)"\]/g)) keys.add(m[1])
  // 按一串字段名挨个读的(listOf("dataSourcePkgName", ...) 后面跟着按 key 读 data)
  for (const m of src.matchAll(/listOf\(((?:"[A-Za-z_]+",?\s*)+)\)(?:[^\n]*\n){0,3}[^\n]*data\[key\]/g)) for (const x of m[1].matchAll(/"([^"]+)"/g)) keys.add(x[1])
}
expectSet('接入代码读的卡片数据字段', Object.keys(oppo.dataKeys), [...keys])

if (failed) { console.error('\n系统事件标准清单校验不通过'); process.exit(1) }
console.log('\n系统事件标准清单校验通过')
