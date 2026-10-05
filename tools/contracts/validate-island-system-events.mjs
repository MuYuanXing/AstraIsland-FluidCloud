#!/usr/bin/env node
/**
 * 校验星河岛系统事件标准清单(contracts/island-system-events.v1.json)和各家对照表。
 * 用法:node tools/contracts/validate-island-system-events.mjs
 * OPPO 对照表还要和 OPPO 接入代码逐项对上:服务号、服务意图、位置代号与角色、数据字段、按钮动作、卡片排法,
 * 全部从代码里的常量提取,不解析注释。标准清单里的编号和编码用的键名由单测 SystemEventContractTest 逐个核对。
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
  else fail(`${label}:对照表与代码不一致,对照表${diff(actual ?? [], expected)}`)
}

// ── 标准清单本身 ──
const standard = JSON.parse(read('contracts', 'island-system-events.v1.json'))
const kindIds = standard.kinds.map(k => k.id)
if (new Set(kindIds).size !== kindIds.length) fail('事件种类有重复的编号')
else pass(`事件种类 ${kindIds.length} 种,编号不重复`)
for (const k of standard.kinds) {
  if (!k.name || !k.drawnAs) fail(`事件种类 ${k.id} 缺名字或画法`)
  const ids = (k.fields ?? []).map(f => f.id)
  if (new Set(ids).size !== ids.length) fail(`事件种类 ${k.id} 的内容项有重复`)
  for (const f of k.fields ?? []) if (!f.meaning) fail(`事件种类 ${k.id} 的内容项 ${f.id} 没写意思`)
}
const eventIds = standard.event.fields.map(f => f.id)
if (new Set(eventIds).size !== eventIds.length) fail('每件事都带的内容有重复')
const cardIds = standard.card.fields.map(f => f.id)
if (new Set(cardIds).size !== cardIds.length) fail('卡片内容有重复的项')
const kindFieldIds = standard.kinds.flatMap(k => (k.fields ?? []).map(f => f.id))
const clash = kindFieldIds.filter(id => eventIds.includes(id))
if (clash.length) fail(`种类的内容项和每件事都带的内容重名:${clash.join('、')}`)
const textRoles = [...standard.card.textRoles, ...standard.card.mirrorRoles, ...standard.card.trafficRoles].map(r => r.id)
const imageRoles = standard.card.imageRoles.map(r => r.id)
if (new Set(textRoles).size !== textRoles.length) fail('文字角色有重复')
if (new Set(imageRoles).size !== imageRoles.length) fail('图的角色有重复')
const toneIds = standard.textTones.values.map(t => t.id)
pass(`文字角色 ${textRoles.length} 个,图的角色 ${imageRoles.length} 个,文字含义 ${toneIds.length} 种`)

for (const v of standard.vendors) {
  if (!existsSync(join(root, 'contracts', v.mapping))) fail(`对照表 ${v.mapping} 不存在`)
}

// ── OPPO 对照表 ──
const oppo = JSON.parse(read('contracts', 'island-system-events.oppo.v1.json'))
for (const id of kindIds) if (!oppo.kinds[id]) fail(`OPPO 对照表缺事件种类 ${id}`)
for (const id of Object.keys(oppo.kinds)) if (!kindIds.includes(id)) fail(`OPPO 对照表里的 ${id} 不是标准事件种类`)
const roleOf = value => String(value).split(/[(/ ]/)[0].trim()
for (const [code, role] of Object.entries(oppo.levels.text)) if (!textRoles.includes(roleOf(role))) fail(`位置代号 ${code} 对应的 ${role} 不是文字角色`)
for (const [code, role] of Object.entries(oppo.levels.mirror)) {
  const r = roleOf(role)
  if (!textRoles.includes(r) && !imageRoles.includes(r)) fail(`对称卡片的位置代号 ${code} 对应的 ${role} 不是角色`)
}
for (const [code, role] of Object.entries(oppo.levels.image)) {
  const r = roleOf(role)
  if (!imageRoles.includes(r) && !cardIds.includes(r)) fail(`位置代号 ${code} 对应的 ${role} 不是图的角色`)
}
const layoutValues = standard.card.fields.find(f => f.id === 'layout').values
for (const [raw, value] of Object.entries(oppo.layouts)) if (!layoutValues.includes(value)) fail(`排法 ${raw} 对应的 ${value} 不在标准里`)
const switchValues = standard.kinds.find(k => k.id === 'systemSwitch').fields.find(f => f.id === 'switch').values
for (const [sid, s] of Object.entries(oppo.kinds.systemSwitch.switch)) if (!switchValues.includes(s)) fail(`开关 ${sid} 对应的 ${s} 不在标准里`)
const detailValues = standard.event.fields.find(f => f.id === 'details').values
for (const d of [...Object.values(oppo.details.services), ...Object.values(oppo.details.intents)]) {
  if (!detailValues.includes(d)) fail(`明细 ${d} 不在标准里`)
}

// ── 和 OPPO 接入代码逐项对上 ──
const dir = join('fluidcloud-core', 'src', 'main', 'java', 'com', 'astraflow', 'fluidcloud')
const services = read(dir, 'OfficialCloudServices.kt')
const translator = read(dir, 'OfficialCloudTranslator.kt')
const decoder = read(dir, 'OfficialCloudDecoder.kt')
const model = read('island-events', 'src', 'main', 'java', 'com', 'astraisland', 'events', 'SystemEvent.kt')

const consts = Object.fromEntries([...services.matchAll(/const val ([A-Z_]+) = "(\d+)"/g)].map(m => [m[1], m[2]]))
function setOf(source, name) {
  const m = source.match(new RegExp(`val ${name}(?:: [^=]+)? = setOf\\(([^)]*)\\)`))
  if (!m) { fail(`代码里找不到 ${name}`); return [] }
  return m[1].split(',').map(s => s.trim()).filter(Boolean).map(s => s.startsWith('"') ? s.slice(1, -1) : consts[s] ?? `未知常量 ${s}`)
}
const one = name => { if (!consts[name]) fail(`代码里找不到服务号 ${name}`); return [consts[name]] }

expectSet('来电与通话的服务号', oppo.kinds.call.services, one('CALL'))
expectSet('卫星通话的服务号', oppo.kinds.satelliteCall.services, one('SATELLITE_CALL'))
expectSet('计时器的服务号', oppo.kinds.timer.services, one('TIMER'))
expectSet('秒表的服务号', oppo.kinds.stopwatch.services, one('STOPWATCH'))
expectSet('闹钟的服务号', oppo.kinds.alarm.services, one('ALARM'))
expectSet('秒抢闹钟的服务号', oppo.kinds.flashAlarm.services, one('GARB_ALARM'))
expectSet('游戏计时的服务号', oppo.kinds.gameTimer.services, one('GAME_TIMER'))
expectSet('计时类合起来', ['timer', 'stopwatch', 'alarm', 'flashAlarm', 'gameTimer'].flatMap(k => oppo.kinds[k].services), setOf(services, 'TIMER_SERVICES'))
expectSet('录屏的服务号', oppo.kinds.screenRecording.services, one('SCREEN_RECORDER'))
expectSet('录音的服务号', oppo.kinds.soundRecording.services, one('SOUND_RECORDER'))
expectSet('录完已保存的服务号', oppo.kinds.recordingSaved.services, setOf(services, 'RECORDER_SERVICES'))
expectSet('用麦克风的系统服务', oppo.kinds.microphoneService.services, setOf(services, 'MICROPHONE_SERVICES'))
expectSet('系统开关的服务号', oppo.kinds.systemSwitch.services, setOf(services, 'SWITCH_SERVICES'))
expectSet('系统开关的分类', Object.keys(oppo.kinds.systemSwitch.switch), setOf(services, 'SWITCH_SERVICES'))
expectSet('音乐的服务号', oppo.kinds.music.services, setOf(services, 'MEDIA_SERVICES'))
expectSet('充电胶囊的服务号', oppo.kinds.charging.services, one('CHARGE'))
expectSet('电池提示的服务号', oppo.kinds.batteryTip.services, one('BATTERY'))
expectSet('耳机等设备的服务号', oppo.kinds.device.services, one('MY_DEVICES'))
expectSet('快递取件的服务号', oppo.kinds.parcelPickup.services, setOf(services, 'EXPRESS_PICKUP_SERVICES'))
expectSet('任务进度的服务号', oppo.kinds.taskProgress.services, setOf(services, 'TASK_SERVICES'))
expectSet('订单进度的服务号', oppo.kinds.orderProgress.services, setOf(services, 'TAKEOUT_SERVICES'))
expectSet('打车的服务号', oppo.kinds.ride.services, setOf(services, 'RIDE_SERVICES'))
expectSet('游戏复活倒计时的服务号', oppo.kinds.gameCountdown.services, setOf(services, 'COUNTDOWN_SERVICES'))
expectSet('提醒的服务号', oppo.kinds.reminder.services, setOf(services, 'REMINDER_SERVICES'))
expectSet('导航和红绿灯提醒合起来', [...oppo.kinds.navigation.services, ...oppo.kinds.trafficLight.services], setOf(services, 'NAVIGATION_LAYOUT_SERVICES'))
expectSet('红绿灯提醒的服务号', oppo.kinds.trafficLight.services, setOf(services, 'TRAFFIC_LIGHT_SERVICES'))
if (!services.includes('id.startsWith("common:") -> Layout.APP_NOTICE') || oppo.kinds.appNotice.servicePrefix !== 'common:') fail('应用通知卡片的认法和代码不一致')
else pass('应用通知卡片按服务号开头 common: 认')

// 服务意图
const intents = {}
for (const m of services.matchAll(/add\(Layout\.([A-Z_]+),([^)]*)\)/g)) {
  intents[m[1]] = [...m[2].matchAll(/"([^"]+)"/g)].map(x => `pantanal.intent.${x[1]}`)
}
expectSet('订单进度的服务意图', oppo.kinds.orderProgress.intents, intents.TAKEOUT ?? [])
expectSet('打车的服务意图', oppo.kinds.ride.intents, intents.RIDE ?? [])
expectSet('任务进度的服务意图', oppo.kinds.taskProgress.intents, intents.TASK ?? [])
expectSet('游戏复活倒计时的服务意图', oppo.kinds.gameCountdown.intents, intents.COUNTDOWN ?? [])
expectSet('提醒的服务意图', oppo.kinds.reminder.intents, intents.REMINDER ?? [])
expectSet('导航和红绿灯提醒的服务意图合起来', [...oppo.kinds.navigation.intents, ...oppo.kinds.trafficLight.intents], intents.NAVIGATION ?? [])
expectSet('红绿灯提醒的服务意图', oppo.kinds.trafficLight.intents, (intents.NAVIGATION ?? []).filter(i => i.includes('.traffic_light.')))
expectSet('红绿灯提醒按服务意图认', oppo.kinds.trafficLight.intents, setOf(services, 'TRAFFIC_LIGHT_INTENTS'))

// 明细拆法
const recipeMap = (name) => {
  const m = services.match(new RegExp(`val ${name} = mapOf\\(([\\s\\S]*?)\\)\\n`))
  if (!m) { fail(`代码里找不到 ${name}`); return {} }
  return Object.fromEntries([...m[1].matchAll(/"([^"]+)" to Details\.([A-Z]+)/g)].map(x => [x[1], x[2].toLowerCase()]))
}
const sameMap = (label, a, b) => JSON.stringify(Object.entries(a).sort()) === JSON.stringify(Object.entries(b).sort()) ? pass(label) : fail(`${label}:对照表与代码不一致`)
sameMap('明细按服务号', oppo.details.services, recipeMap('DETAILS_RECIPES'))
sameMap('明细按服务意图', oppo.details.intents, recipeMap('DETAILS_INTENTS'))

// 本来不放左图
expectSet('本来不放左图的服务号', oppo.noPicture.services, setOf(translator, 'NO_CARD_IMAGE_SERVICES'))
expectSet('本来不放左图的页', oppo.noPicture.pages, setOf(translator, 'NO_CARD_IMAGE_PAGES'))
expectSet('本来不放左图的服务意图', oppo.noPicture.intents, setOf(translator, 'NO_CARD_IMAGE_INTENTS'))

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

// 位置代号:代码里用到的每一个都在对照表里
const files = readdirSync(join(root, dir)).filter(f => f.endsWith('.kt'))
const used = new Set()
for (const f of files) for (const m of read(dir, f).matchAll(/"([A-G]\d{0,2})\*?"/g)) used.add(m[1])
const mapped = new Set([...Object.keys(oppo.levels.text), ...Object.keys(oppo.levels.mirror), ...Object.keys(oppo.levels.image),
  ...Object.keys(oppo.levels.button), ...Object.keys(oppo.levels.tag)])
const unmapped = [...used].filter(c => !mapped.has(c))
if (unmapped.length) fail(`代码用到的位置代号没写进对照表:${unmapped.join('、')}`)
else pass(`代码用到的位置代号 ${used.size} 个都在对照表里`)

// 数据字段
const keys = new Set()
for (const f of files) {
  const src = read(dir, f)
  for (const m of src.matchAll(/data\["([A-Za-z_]+)"\]/g)) keys.add(m[1])
}
for (const m of translator.matchAll(/GAME_PACKAGE_KEYS = listOf\(([^)]*)\)/g)) for (const x of m[1].matchAll(/"([^"]+)"/g)) keys.add(x[1])
// 按一串字段名挨个读的(listOf("dayText", ...).map { snapshot.data[it] }、listOf(...).forEach { key -> snapshot.data[key] })
for (const f of files) {
  for (const m of read(dir, f).matchAll(/listOf\(((?:"[A-Za-z_]+",?\s*)+)\)\.(?:map|forEach|firstNotNullOfOrNull)\s*\{[^}\n]*data\[(?:it|key)\]/g)) {
    for (const x of m[1].matchAll(/"([^"]+)"/g)) keys.add(x[1])
  }
}
const missingKeys = [...keys].filter(k => !(k in oppo.dataKeys))
if (missingKeys.length) fail(`代码读的数据字段没写进对照表:${missingKeys.join('、')}`)
else pass(`代码读的数据字段 ${keys.size} 个都在对照表里`)

// 按钮动作
const methods = new Set()
// 只看按按钮动作分情况的几段(when (button.action?.method) / when (action?.method)),到这一段的右括号为止
for (const m of services.matchAll(/when \((?:button\.)?action\?\.method\) \{\n([\s\S]*?)\n\s*\}/g)) {
  for (const line of m[1].split('\n')) {
    const arrow = line.match(/^\s*((?:"[A-Za-z]+"\s*,\s*)*"[A-Za-z]+")\s*->/)
    if (arrow) for (const x of arrow[1].matchAll(/"([A-Za-z]+)"/g)) methods.add(x[1])
  }
}
for (const m of services.matchAll(/action\?\.method == "([A-Za-z]+)"/g)) methods.add(m[1])
// 接入件自己补的动作(秒表)
for (const m of services.matchAll(/CloudAction\([^,]+, [A-Z_]+, "([A-Za-z]+)"\)/g)) methods.add(m[1])
for (const m of services.matchAll(/action\?\.method !in setOf\(([^)]*)\)/g)) for (const x of m[1].matchAll(/"([^"]+)"/g)) methods.add(x[1])
if (methods.size === 0) fail('代码里没找到按钮动作,检查程序需要跟着代码改')
const missingMethods = [...methods].filter(m => !(m in oppo.buttonUse))
if (missingMethods.length) fail(`代码认的按钮动作没写进对照表:${missingMethods.join('、')}`)
else pass(`代码认的按钮动作 ${methods.size} 个都在对照表里`)

// 角色的编号(标准事件模型里的枚举 → 清单里的编号)
const enumIds = name => {
  const body = model.match(new RegExp(`enum class ${name}\\(val id: String\\) \\{([\\s\\S]*?);`))?.[1]
  if (!body) { fail(`模型里找不到 ${name}`); return {} }
  return Object.fromEntries([...body.matchAll(/([A-Z_]+)\("([A-Za-z]+)"\)/g)].map(m => [m[1], m[2]]))
}
const textRoleIds = enumIds('TextRole'), imageRoleIds = enumIds('ImageRole'), layoutIds = enumIds('CardLayout')
function codeMap(name, enumName, ids) {
  const m = translator.match(new RegExp(`val ${name} = mapOf\\(([\\s\\S]*?)\\)\\n`))
  if (!m) { fail(`代码里找不到 ${name}`); return {} }
  const consts = Object.fromEntries([...translator.matchAll(/const val ([A-Z_]+) = "([^"]+)"/g)].map(x => [x[1], x[2]]))
  return Object.fromEntries([...m[1].matchAll(new RegExp(`(?:"([^"]+)"|([A-Z_]+)) to ${enumName}\\.([A-Z_]+)`, 'g'))]
    .map(x => [x[1] ?? consts[x[2]], ids[x[3]] ?? `未知 ${x[3]}`]))
}
const tableOf = (table, keep) => Object.fromEntries(Object.entries(table).map(([k, v]) => [k, roleOf(v)]).filter(([k, v]) => keep(k, v)))
sameMap('文字的位置代号与角色', codeMap('TEXT_ROLES', 'TextRole', textRoleIds), tableOf(oppo.levels.text, k => k !== 'B'))
sameMap('对称卡片中间的位置代号与角色', codeMap('MIRROR_ROLES', 'TextRole', textRoleIds), tableOf(oppo.levels.mirror, (k, v) => textRoles.includes(v)))
sameMap('图的位置代号与角色', codeMap('IMAGE_ROLES', 'ImageRole', imageRoleIds), tableOf(oppo.levels.image, (k, v) => imageRoles.includes(v)))
if (!/level\.startsWith\("B"\)\) TextRole\.LARGE else TextRole\.OTHER/.test(translator)) fail('表里没有的以 B 开头的位置代号不是按 large 读')
else pass('表里没有的以 B 开头的位置代号按 large 读,其余按 other')

// 卡片中间那一块的排法
sameMap('卡片中间那一块的排法', codeMap('LAYOUTS', 'CardLayout', layoutIds), oppo.layouts)
const guide = decoder.match(/GUIDE_LAYOUT = "([^"]+)"/)?.[1]
if (!guide || !(guide in oppo.layouts)) fail(`读卡片时认的引导排法 ${guide} 没写进对照表`)
else pass('读卡片时认的引导排法在对照表里')

if (failed) { console.error('\n系统事件标准清单校验不通过'); process.exit(1) }
console.log('\n系统事件标准清单校验通过')
