#!/usr/bin/env node
/**
 * 应用连通性检测脚本（零外部依赖，仅用 Node 内置模块）
 * ------------------------------------------------------------------
 * 用途：分别验证「当前应用 ↔ 后端服务」与「应用（后端）↔ 数据库」的连通性，
 *      在任一连接失败时返回具体的错误码 / 异常原因 / 超时详情，便于快速定位。
 *
 * 设计：
 *   1) 后端连通：先 TCP 探测后端端口（服务是否在监听），再 HTTP 探测一个
 *      真实接口（默认 /api/v3/api-docs，Spring Boot 在 dev 下默认开启）。
 *   2) 数据库连通：先 TCP 探测 3306 并解析 MySQL 握手包（确认确实是 MySQL
 *      且在监听、拿到服务端版本），再用本机 mysql 客户端做一次带鉴权的
 *      `SELECT 1` 查询（验证账号 / 密码 / 库名 / 查询能力）。
 *
 * 配置：全部可用环境变量覆盖，默认值取自本项目的 application.yml。
 *   后端：BACKEND_HOST / BACKEND_PORT / BACKEND_CONTEXT / BACKEND_PROBE_PATH
 *   数据库：DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD
 *         MYSQL_CLIENT（mysql.exe 路径）
 *   通用：TIMEOUT_MS（后端/数据库共用超时，默认 5000ms）
 *
 * 退出码：两部分都成功 → 0；任一失败 → 1。
 */

import net from 'node:net'
import http from 'node:http'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// ---------- 配置（带默认值，可被环境变量覆盖） ----------
const cfg = {
  backend: {
    host: process.env.BACKEND_HOST || 'localhost',
    port: Number(process.env.BACKEND_PORT || 8080),
    context: process.env.BACKEND_CONTEXT || '/api',
    // 默认探测一个「无需登录、必定返回 200」的轻量接口；
    // 想验证接口文档端点是否正常可改为 /v3/api-docs（本机该端点会返回 500，可借此验证失败上报）。
    probePath: process.env.BACKEND_PROBE_PATH || '/ai/status',
    timeoutMs: Number(process.env.TIMEOUT_MS || 5000),
  },
  db: {
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 3306),
    name: process.env.DB_NAME || 'smart_inventory',
    user: process.env.DB_USER || 'root',
    password: process.env.DB_PASSWORD || '123456',
    timeoutMs: Number(process.env.DB_TIMEOUT_MS || process.env.TIMEOUT_MS || 5000),
    mysqlClient:
      process.env.MYSQL_CLIENT ||
      'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
  },
}

// ---------- 工具函数 ----------
function tcpConnect(host, port, timeoutMs) {
  return new Promise((resolve) => {
    const start = Date.now()
    let settled = false
    const sock = net.createConnection({ host, port, timeout: timeoutMs })
    const done = (res) => {
      if (settled) return
      settled = true
      try { sock.destroy() } catch {}
      resolve(res)
    }
    sock.on('connect', () =>
      done({ ok: true, latencyMs: Date.now() - start, error: null })
    )
    sock.on('timeout', () =>
      done({
        ok: false,
        latencyMs: Date.now() - start,
        error: { code: 'ETIMEDOUT', message: `TCP 连接 ${host}:${port} 超时（${timeoutMs}ms 内未建立）` },
      })
    )
    sock.on('error', (err) =>
      done({
        ok: false,
        latencyMs: Date.now() - start,
        error: { code: err.code || 'SOCKET_ERROR', message: err.message },
      })
    )
  })
}

function httpGet(url, timeoutMs) {
  return new Promise((resolve) => {
    const start = Date.now()
    let settled = false
    const req = http.get(url, (res) => {
      let body = ''
      res.on('data', (c) => {
        body += c
        if (body.length > 1e5) req.destroy()
      })
      res.on('end', () => {
        if (settled) return
        settled = true
        resolve({
          ok: true,
          status: res.statusCode,
          latencyMs: Date.now() - start,
          body: body.slice(0, 2000),
          error: null,
        })
      })
    })
    const fail = (err) => {
      if (settled) return
      settled = true
      const code = err && err.code ? err.code : (err && err.message ? 'HTTP_ERROR' : 'HTTP_ERROR')
      resolve({
        ok: false,
        status: null,
        latencyMs: Date.now() - start,
        error: {
          code,
          message:
            err && err.message
              ? err.message
              : `HTTP 请求失败（${timeoutMs}ms）`,
        },
      })
    }
    req.on('error', fail)
    req.setTimeout(timeoutMs, () => {
      req.destroy(new Error('timeout'))
      fail({ code: 'ETIMEDOUT', message: `HTTP 请求超时（${timeoutMs}ms）` })
    })
  })
}

// 解析 MySQL 初始握手包：buf[0..2]=payload长度, buf[3]=seq, buf[4]=协议版本,
// buf[5..]=以 \0 结尾的服务端版本字符串
function mysqlHandshake(host, port, timeoutMs) {
  return new Promise((resolve) => {
    const start = Date.now()
    let settled = false
    const sock = net.createConnection({ host, port, timeout: timeoutMs })
    const done = (res) => {
      if (settled) return
      settled = true
      try { sock.destroy() } catch {}
      resolve(res)
    }
    sock.on('timeout', () =>
      done({ ok: false, latencyMs: Date.now() - start, error: { code: 'ETIMEDOUT', message: `MySQL 握手超时（${timeoutMs}ms）` } })
    )
    sock.on('error', (err) =>
      done({ ok: false, latencyMs: Date.now() - start, error: { code: err.code || 'SOCKET_ERROR', message: err.message } })
    )
    sock.on('data', (buf) => {
      try {
        if (buf.length < 5) return // 太短，继续等待
        const protocol = buf[4]
        let end = buf.indexOf(0, 5)
        if (end === -1) end = buf.length
        const version = buf.toString('utf8', 5, end)
        const isMysql = protocol === 0x0a
        done({
          ok: true,
          latencyMs: Date.now() - start,
          protocol,
          version,
          isMysql,
          error: isMysql
            ? null
            : { code: 'NOT_MYSQL', message: `端口 ${port} 响应的不是 MySQL 协议（protocol=${protocol}）` },
        })
      } catch (e) {
        done({ ok: false, latencyMs: Date.now() - start, error: { code: 'PARSE_ERROR', message: e.message } })
      }
    })
  })
}

// 用本机 mysql 客户端做带鉴权的真实查询，校验账号/密码/库名/查询能力
function mysqlAuthQuery(db) {
  if (!fs.existsSync(db.mysqlClient)) {
    return { available: false, ran: false, note: `未找到 mysql 客户端：${db.mysqlClient}` }
  }
  const args = [
    `-h${db.host}`,
    `-P${db.port}`,
    `-u${db.user}`,
    `-p${db.password}`,
    '--default-character-set=utf8mb4',
    '-e',
    'SELECT 1 AS connectivity_ok, VERSION() AS server_version;',
    db.name,
  ]
  const r = spawnSync(db.mysqlClient, args, {
    encoding: 'utf8',
    timeout: db.timeoutMs + 3000,
  })
  // mysql 会打印 "Using a password on the command line interface can be insecure" 到 stderr，过滤掉
  const stderr = (r.stderr || '')
    .split('\n')
    .filter((l) => !/Using a password on the command line/i.test(l))
    .join('\n')
    .trim()
  const stdout = (r.stdout || '').trim()
  return {
    available: true,
    ran: true,
    exitCode: r.status,
    stdout,
    stderr,
    note: r.status === 0 ? '鉴权查询成功' : '鉴权查询失败',
  }
}

// ---------- 检测逻辑 ----------
async function checkBackend(b) {
  const portTest = await tcpConnect(b.host, b.port, b.timeoutMs)
  const base = `http://${b.host}:${b.port}${b.context}${b.probePath}`
  let httpTest = null
  if (portTest.ok) {
    httpTest = await httpGet(base, b.timeoutMs)
  }
  let status // 'success' | 'failure'
  let error = null
  let detail = ''

  if (!portTest.ok) {
    status = 'failure'
    error = portTest.error
    detail = `后端端口 ${b.host}:${b.port} 无法建立 TCP 连接`
  } else if (!httpTest.ok) {
    status = 'failure'
    error = httpTest.error
    detail = `后端端口已监听，但 HTTP 探测 ${b.probePath} 失败`
  } else if (httpTest.status >= 500) {
    // 服务可达，但接口内部报错 —— 视为失败并附上服务端返回的错误体，便于定位
    status = 'failure'
    error = {
      code: `HTTP_${httpTest.status}`,
      message:
        `后端接口返回 ${httpTest.status}（服务可达但接口异常）` +
        (httpTest.body ? `\n服务端返回: ${httpTest.body}` : ''),
    }
    detail = `后端端口已监听且 HTTP 有响应，但探测接口 ${b.probePath} 返回 ${httpTest.status}`
  } else if (httpTest.status >= 400) {
    // 4xx：连接本身是通的，多为「需登录(401)」或「路径不存在(404)」，不算连通失败
    status = 'success'
    detail =
      `后端已响应 HTTP ${httpTest.status}（链路连通；` +
      (httpTest.status === 401
        ? '该接口需要登录鉴权，属正常'
        : httpTest.status === 404
        ? '探测路径不存在，请核对 BACKEND_PROBE_PATH'
        : '请确认该状态码是否符合预期') +
      `）`
  } else {
    status = 'success'
    detail = `后端已正常响应 HTTP ${httpTest.status}`
  }

  return {
    part: '后端接口连通状态',
    target: `${b.host}:${b.port}${b.context}`,
    probeUrl: base,
    status,
    portOpen: portTest.ok,
    portLatencyMs: portTest.latencyMs,
    httpStatus: httpTest ? httpTest.status : null,
    httpLatencyMs: httpTest ? httpTest.latencyMs : null,
    httpBody: httpTest ? httpTest.body : null,
    detail,
    error,
    suggestion: buildBackendSuggestion(status, error, httpTest),
  }
}

async function checkDatabase(db) {
  const tcp = await tcpConnect(db.host, db.port, db.timeoutMs)
  let handshake = null
  if (tcp.ok) handshake = await mysqlHandshake(db.host, db.port, db.timeoutMs)
  const auth = mysqlAuthQuery(db)

  let status = 'success'
  let error = null
  let detail = ''
  const facts = []

  if (!tcp.ok) {
    status = 'failure'
    error = tcp.error
    detail = `数据库端口 ${db.host}:${db.port} 无法建立 TCP 连接`
  } else if (!handshake.ok) {
    status = 'failure'
    error = handshake.error
    detail = `端口已监听，但 MySQL 握手失败或该端口不是 MySQL`
  } else if (!handshake.isMysql) {
    status = 'failure'
    error = handshake.error
    detail = `端口响应但不是 MySQL 服务`
  } else {
    facts.push(`MySQL ${handshake.version}（握手耗时 ${handshake.latencyMs}ms）`)
    if (auth.ran && auth.exitCode === 0) {
      status = 'success'
      detail = `TCP 可达 + 鉴权查询成功`
    } else if (auth.ran && auth.exitCode !== 0) {
      status = 'failure'
      error = { code: 'AUTH_FAILED', message: auth.stderr || '鉴权查询返回非零退出码' }
      detail = `TCP 可达且为 MySQL，但账号/密码/库名校验未通过`
    } else {
      // mysql 客户端不可用：握手成功即可认为“可达”，但鉴权未验证
      status = 'success'
      detail = `TCP 可达且确认为 MySQL（未做鉴权校验：${auth.note}）`
    }
  }

  return {
    part: '数据库连接状态',
    target: `${db.host}:${db.port}/${db.name}`,
    status,
    portOpen: tcp.ok,
    portLatencyMs: tcp.latencyMs,
    serverVersion: handshake && handshake.ok ? handshake.version : null,
    authRan: auth.ran,
    authDetail: auth.note,
    authRaw: auth.ran ? (auth.stdout || auth.stderr || '').trim() : null,
    detail,
    error,
    suggestion: buildDatabaseSuggestion(status, error, auth),
  }
}

function buildBackendSuggestion(status, error, httpTest) {
  if (status === 'success') return null
  const code = error && error.code
  if (code === 'ECONNREFUSED')
    return '后端进程未启动或端口不对：确认后端已运行（java -jar ...），端口/context-path 与 application.yml 一致。'
  if (code === 'ETIMEDOUT')
    return '连接超时：检查后端是否启动、主机是否可达、是否有防火墙/安全组拦截 8080。'
  if (code === 'ENOTFOUND')
    return '主机名无法解析：检查 BACKEND_HOST 是否正确（开发环境一般为 localhost）。'
  if (httpTest && httpTest.status && httpTest.status >= 500)
    return '后端已连通但返回 5xx：查看后端应用日志定位接口异常。'
  if (httpTest && httpTest.status === 404)
    return '端口连通但探测路径 404：生产 profile 可能禁用了 springdoc；可改用其它已存在接口（如 /api/product/list）作为探测路径。'
  return '未知后端错误，请查看上方 error 字段。'
}

function buildDatabaseSuggestion(status, error, auth) {
  if (status === 'success') return null
  const code = error && error.code
  if (code === 'ECONNREFUSED')
    return 'MySQL 未启动或端口不对：启动 MySQL 服务，确认其监听 3306（jdbc url 中 host/port）。'
  if (code === 'ETIMEDOUT')
    return '连接超时：检查 MySQL 是否启动、host 是否可达、防火墙/安全组是否放行 3306。'
  if (code === 'NOT_MYSQL')
    return '该端口不是 MySQL：确认 DB_HOST/DB_PORT 指向正确的 MySQL 实例。'
  if (code === 'AUTH_FAILED') {
    const m = (auth.stderr || '') + (auth.stdout || '')
    if (/Access denied/i.test(m))
      return '账号或密码错误：核对 application.yml 中的 DB_USERNAME / DB_PASSWORD（或 prod 环境变量）。'
    if (/Unknown database/i.test(m))
      return '数据库不存在：确认 smart_inventory 库已创建（执行 db/init.sql 初始化）。'
    if (/Can't connect/i.test(m))
      return '客户端连不上（与 TCP 层不一致）：检查 MySQL 的 bind-address / skip-networking 配置。'
    return '鉴权查询失败，请查看 error 字段中的原始报错。'
  }
  return '未知数据库错误，请查看上方 error 字段。'
}

// ---------- 输出 ----------
function mask(s) {
  if (!s) return s
  return s.length <= 2 ? '*'.repeat(s.length) : s[0] + '*'.repeat(s.length - 2) + s[s.length - 1]
}

function printResult(r) {
  const tag = r.status === 'success' ? '【成功】' : '【失败】'
  console.log(`\n${tag} ${r.part}`)
  console.log(`   目标       : ${r.target}`)
  if (r.probeUrl) console.log(`   探测地址   : ${r.probeUrl}`)
  console.log(`   端口连通   : ${r.portOpen ? '是' : '否'}${r.portLatencyMs != null ? ` (${r.portLatencyMs}ms)` : ''}`)
  if (r.httpStatus != null) console.log(`   HTTP 状态 : ${r.httpStatus}${r.httpLatencyMs != null ? ` (${r.httpLatencyMs}ms)` : ''}`)
  if (r.httpBody) console.log(`   响应内容   : ${r.httpBody}`)
  if (r.serverVersion) console.log(`   服务端版本 : ${r.serverVersion}`)
  if (r.authRan != null) console.log(`   鉴权查询   : ${r.authRan ? (r.authDetail || '') : '未执行'}`)
  if (r.authRaw) console.log(`   查询结果   : ${r.authRaw.replace(/\n/g, ' | ')}`)
  console.log(`   说明       : ${r.detail}`)
  if (r.error) {
    console.log(`   错误码     : ${r.error.code}`)
    console.log(`   错误信息   : ${r.error.message}`)
  }
  if (r.suggestion) console.log(`   排查建议   : ${r.suggestion}`)
}

async function main() {
  console.log('==================================================')
  console.log(' 应用连通性检测（后端接口 / 数据库）')
  console.log('==================================================')
  console.log('检测配置：')
  console.log(`   后端      : ${cfg.backend.host}:${cfg.backend.port}${cfg.backend.context}  探测路径=${cfg.backend.probePath}`)
  console.log(`   数据库    : ${cfg.db.host}:${cfg.db.port}/${cfg.db.name}  账号=${cfg.db.user} 密码=${mask(cfg.db.password)}`)
  console.log(`   mysql客户端: ${cfg.db.mysqlClient}`)
  console.log(`   超时      : ${cfg.backend.timeoutMs}ms`)

  const backend = await checkBackend(cfg.backend)
  const database = await checkDatabase(cfg.db)

  printResult(backend)
  printResult(database)

  const allOk = backend.status === 'success' && database.status === 'success'
  console.log('\n==================================================')
  console.log(` 总体结论：${allOk ? '全部连通正常' : '存在连接问题，请按上方排查建议处理'}`)
  console.log('==================================================')

  const report = {
    timestamp: new Date().toISOString(),
    summary: allOk ? 'all_ok' : 'has_failure',
    backend,
    database,
  }
  try {
    const reportPath = process.env.REPORT_PATH || path.join(__dirname, 'connectivity-report.json')
    fs.writeFileSync(reportPath, JSON.stringify(report, null, 2), 'utf8')
    console.log(`\n报告已写入：${reportPath}`)
  } catch (e) {
    console.log(`\n（报告写入失败：${e.message}）`)
  }

  process.exit(allOk ? 0 : 1)
}

main().catch((e) => {
  console.error('检测脚本异常：', e)
  process.exit(2)
})
