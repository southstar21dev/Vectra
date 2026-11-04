import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..')
const SRC = path.join(ROOT, 'src')

const files = []
function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (e.name === 'node_modules') continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (/\.(ts|tsx)$/.test(e.name)) files.push(path.relative(ROOT, p))
  }
}
walk(SRC)

const content = new Map()
for (const f of files) content.set(f, fs.readFileSync(path.join(ROOT, f), 'utf8'))

const isTest = (f) => /\.test\.(ts|tsx)$/.test(f) || f.includes('__tests__')
const EXPORT_RE = /^[ \t]*export\s+(?:declare\s+)?(?:default\s+)?(?:async\s+)?(?:function|const|let|var|class|type|interface|enum)\s+([A-Za-z0-9_$]+)/gm
const EXPORT_LIST_RE = /^[ \t]*export\s+(?:type\s+)?\{([^}]*)\}/gm

const dead = []
const internalOnly = []
const testOnly = []

for (const f of files) {
  const c = content.get(f)
  const names = new Set()
  let m
  EXPORT_RE.lastIndex = 0
  while ((m = EXPORT_RE.exec(c))) names.add(m[1])
  EXPORT_LIST_RE.lastIndex = 0
  while ((m = EXPORT_LIST_RE.exec(c))) {
    for (const part of m[1].split(',')) {
      const name = part.trim().split(/\s+as\s+/).pop().trim()
      if (name && name !== 'type') names.add(name)
    }
  }

  for (const name of names) {
    let own = 0
    let other = 0
    let tests = 0
    for (const g of files) {
      const re = new RegExp(`\\b${name.replace(/\$/g, '\\$')}\\b`, 'g')
      const n = (content.get(g).match(re) || []).length
      if (!n) continue
      if (g === f) own += n
      else if (isTest(g)) tests += n
      else other += n
    }
    const record = `${f} :: ${name}`
    if (other > 0) continue
    if (tests > 0) testOnly.push(`${record}  (own=${own}, tests=${tests})`)
    else if (own > 1) internalOnly.push(`${record}  (own refs=${own})`)
    else dead.push(record)
  }
}

const print = (title, rows) => {
  console.log(`\n=== ${title} (${rows.length}) ===`)
  console.log(rows.sort().join('\n'))
}
console.log(`Scanned ${files.length} files.`)
print('DEAD — no references anywhere, delete or archive', dead)
print('INTERNAL-ONLY — drop `export` in the Phase 4 API-surface pass', internalOnly)
print('TEST-ONLY — keep, tests are consumers', testOnly)
