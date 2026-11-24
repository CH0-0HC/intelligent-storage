// 一次性校验脚本：扫描 src 下所有 t('a.b') 用到的 i18n 键，
// 与 zh-CN.js / en.js 的实际键集合比对，报告缺失键。
// 用法：node scripts/check-i18n-keys.mjs （在仓库根目录执行）
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const srcDir = join(root, 'frontend', 'src')

const files = []
;(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p)
    else if (/\.(vue|js)$/.test(name)) files.push(p)
  }
})(srcDir)

const used = new Map() // key -> [files]
for (const f of files) {
  const text = readFileSync(f, 'utf8')
  for (const m of text.matchAll(/\bt\(\s*['"]([a-zA-Z0-9_.]+)['"]/g)) {
    const key = m[1]
    if (!used.has(key)) used.set(key, [])
    used.get(key).push(f.replace(root, ''))
  }
}

const loadKeys = async (file) => {
  const mod = await import(pathToFileURL(file).href)
  const flat = new Set()
  ;(function collect(obj, prefix) {
    for (const [k, v] of Object.entries(obj)) {
      const path = prefix ? `${prefix}.${k}` : k
      if (v && typeof v === 'object') collect(v, path)
      else flat.add(path)
    }
  })(mod.default, '')
  return flat
}

const zhKeys = await loadKeys(join(srcDir, 'i18n', 'locales', 'zh-CN.js'))
const enKeys = await loadKeys(join(srcDir, 'i18n', 'locales', 'en.js'))

let missing = 0
for (const [key, locs] of [...used.entries()].sort()) {
  const missZh = !zhKeys.has(key)
  const missEn = !enKeys.has(key)
  if (missZh || missEn) {
    missing++
    console.log(`MISSING ${key}  zh-CN:${missZh ? '缺' : '有'} en:${missEn ? '缺' : '有'}  @ ${[...new Set(locs)].join(', ')}`)
  }
}
console.log(`\n扫描 ${files.length} 个文件，共 ${used.size} 个键；zh-CN ${zhKeys.size} 键 / en ${enKeys.size} 键；缺失 ${missing} 个`)
