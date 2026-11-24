// scripts/install-git-hooks.mjs —— 一键安装本地 git hooks（幂等，可重复执行）
//
// 做三件事：
//   1) git config core.hooksPath .githooks   —— 让 git 去 .githooks/ 找钩子
//   2) git update-index --chmod=+x <钩子>     —— 把可执行位写进索引，
//      保证在 Linux/macOS 上检出时钩子带 x 位（否则 git 会拒绝执行）
//   3) 打印当前 core.hooksPath 与逐项安装结果
//
// 用法：node scripts/install-git-hooks.mjs
//
// 【已知边界】--chmod=+x 只能作用于**已入库**的文件；若钩子尚未被 git 跟踪，
//   git 会报 "Unable to mark file"，此时脚本会把这种情况标成 PENDING 而不是假装成功。
//   入库后重跑本脚本即可补上可执行位（幂等）。

import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(here, '..')

const HOOKS_PATH = '.githooks'
const HOOK_FILES = ['pre-commit', 'pre-push']

/** 同步跑一条 git 命令，统一返回 { code, out } */
function git(args) {
  const r = spawnSync('git', args, { cwd: ROOT, encoding: 'utf8', windowsHide: true })
  if (r.error) return { code: -1, out: `[spawn 失败] ${r.error.code || ''} ${r.error.message}`.trim() }
  return { code: r.status, out: `${r.stdout || ''}${r.stderr || ''}`.trim() }
}

// ---------- 0. 必须在 git 仓库内 ----------
const inside = git(['rev-parse', '--is-inside-work-tree'])
if (inside.code !== 0 || inside.out.trim() !== 'true') {
  console.log(`FAIL  当前目录不是 git 工作树：${ROOT}`)
  console.log(inside.out)
  process.exit(1)
}

// ---------- 1. 设置 core.hooksPath（幂等） ----------
const set = git(['config', 'core.hooksPath', HOOKS_PATH])
if (set.code !== 0) {
  console.log('FAIL  git config core.hooksPath 设置失败')
  console.log(set.out)
  process.exit(1)
}
console.log(`OK    core.hooksPath = ${HOOKS_PATH}`)

// ---------- 2. 给钩子文件写可执行位 ----------
let chmodOk = 0
let chmodPending = 0
for (const f of HOOK_FILES) {
  const rel = `${HOOKS_PATH}/${f}`
  const abs = path.join(ROOT, HOOKS_PATH, f)
  if (!existsSync(abs)) {
    console.log(`FAIL  ${rel} 不存在（跳过 chmod）`)
    continue
  }
  // --chmod 只对已入库文件生效，先确认跟踪状态，避免把 git 的报错当成"已成功"
  const tracked = git(['ls-files', '--error-unmatch', rel]).code === 0
  if (!tracked) {
    chmodPending++
    console.log(`PENDING  ${rel} 尚未入库，--chmod=+x 暂不生效（git add 后重跑本脚本即可）`)
    continue
  }
  const r = git(['update-index', '--chmod=+x', rel])
  if (r.code === 0) {
    chmodOk++
    console.log(`OK    ${rel} 已标记可执行（+x）`)
  } else {
    console.log(`WARN  ${rel} --chmod=+x 失败：${r.out}`)
  }
}

// ---------- 3. 打印最终状态 ----------
const current = git(['config', 'core.hooksPath'])
console.log('')
console.log('---------- 安装结果 ----------')
console.log(`当前 git config core.hooksPath = ${current.out || '(未设置)'}`)
console.log(`可执行位：${chmodOk} / ${HOOK_FILES.length} 已设置${chmodPending > 0 ? `，${chmodPending} 项待文件入库后重跑` : ''}`)
console.log('已启用的本地门禁：')
console.log('  · pre-commit  快：i18n 键完整性 + 前端单测（vitest run）')
console.log('  · pre-push    重：后端单测（mvn -o -B test）+ 前端构建（npm run build）')
console.log('临时绕过（仅限紧急，事后必须补跑 node scripts/regress-all.mjs）：git commit --no-verify')
