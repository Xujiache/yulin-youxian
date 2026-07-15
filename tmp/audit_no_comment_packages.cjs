const fs = require('fs')
const path = require('path')
const ts = require('C:/Users/Administrator/Desktop/生鲜同城/art-lnb-master/node_modules/typescript')

const base = process.argv[2] || 'C:/Users/Administrator/Desktop/生鲜同城/deliverables/20260714-no-comments-v1'
const roots = fs.readdirSync(base).map(name => path.join(base, name)).filter(file => fs.statSync(file).isDirectory())
const documentExtensions = new Set(['.md', '.mdx', '.txt', '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.rtf'])
const codeExtensions = new Set(['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs'])
const binaryExtensions = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.woff', '.woff2', '.ttf', '.otf', '.mp3', '.mp4', '.zip', '.gz', '.wasm'])
const issues = []
const totals = { files: 0, textFiles: 0, binaryFiles: 0, bytes: 0 }

function add(file, type, detail) {
  issues.push({ file: path.relative(base, file).replaceAll('\\', '/'), type, detail })
}

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (['node_modules', 'dist', 'target', '.git', 'docs', 'doc', 'coverage'].includes(entry.name.toLowerCase())) add(file, 'forbidden-directory', entry.name)
      walk(file)
      continue
    }
    audit(file)
  }
}

function typescriptComments(text) {
  const source = ts.createSourceFile('audit.ts', text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const ranges = new Map()
  const collect = (node) => {
    for (const position of [node.getFullStart(), node.end]) {
      for (const range of ts.getLeadingCommentRanges(text, position) || []) ranges.set(`${range.pos}:${range.end}`, range)
      for (const range of ts.getTrailingCommentRanges(text, position) || []) ranges.set(`${range.pos}:${range.end}`, range)
    }
    ts.forEachChild(node, collect)
  }
  collect(source)
  return [...ranges.values()].map(range => ({ position: range.pos, token: text.slice(range.pos, Math.min(range.end, range.pos + 3)) }))
}

function delimitedComments(text, lineMarker, blockStart, blockEnd) {
  const found = []
  let quote = ''
  let escaped = false
  for (let i = 0; i < text.length; i += 1) {
    const char = text[i]
    if (quote) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === quote) quote = ''
      continue
    }
    if (char === '"' || char === "'" || char === '`') {
      quote = char
      continue
    }
    if (blockStart && text.startsWith(blockStart, i)) {
      found.push({ position: i, token: blockStart })
      const end = text.indexOf(blockEnd, i + blockStart.length)
      i = end < 0 ? text.length : end + blockEnd.length - 1
      continue
    }
    if (lineMarker && text.startsWith(lineMarker, i)) {
      found.push({ position: i, token: lineMarker })
      const end = text.indexOf('\n', i + lineMarker.length)
      i = end < 0 ? text.length : end
    }
  }
  return found
}

function yamlComments(text) {
  const found = []
  const lines = text.split(/\r?\n/)
  lines.forEach((line, index) => {
    let quote = ''
    let escaped = false
    for (let i = 0; i < line.length; i += 1) {
      const char = line[i]
      if (quote) {
        if (escaped) escaped = false
        else if (char === '\\') escaped = true
        else if (char === quote) quote = ''
        continue
      }
      if (char === '"' || char === "'") quote = char
      else if (char === '#' && (i === 0 || /\s/.test(line[i - 1]))) {
        found.push({ position: index + 1, token: '#' })
        break
      }
    }
  })
  return found
}

function vueComments(text) {
  const found = []
  if (text.includes('<!--')) found.push({ position: text.indexOf('<!--'), token: '<!--' })
  for (const match of text.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) found.push(...typescriptComments(match[1]))
  for (const match of text.matchAll(/<style\b([^>]*)>([\s\S]*?)<\/style>/gi)) {
    const scss = /lang\s*=\s*["'](?:scss|sass)["']/i.test(match[1])
    found.push(...delimitedComments(match[2], scss ? '//' : '', '/*', '*/'))
  }
  return found
}

function audit(file) {
  const stat = fs.statSync(file)
  const ext = path.extname(file).toLowerCase()
  const name = path.basename(file).toLowerCase()
  totals.files += 1
  totals.bytes += stat.size
  if (documentExtensions.has(ext) || /^(readme|license|changelog|contributing|code_of_conduct)\.(md|mdx|txt|rtf)$/i.test(name)) add(file, 'document', name)
  if (name === 'project.private.config.json') add(file, 'private-config', name)
  if (binaryExtensions.has(ext)) {
    totals.binaryFiles += 1
    return
  }
  let text
  try {
    text = fs.readFileSync(file, 'utf8')
  } catch (error) {
    add(file, 'read-error', error.message)
    return
  }
  totals.textFiles += 1
  let found = []
  if (codeExtensions.has(ext)) found = typescriptComments(text)
  else if (ext === '.vue') found = vueComments(text)
  else if (ext === '.java') found = delimitedComments(text, '//', '/*', '*/')
  else if (['.css', '.wxss'].includes(ext)) found = delimitedComments(text, '', '/*', '*/')
  else if (['.scss', '.sass', '.less'].includes(ext)) found = delimitedComments(text, '//', '/*', '*/')
  else if (['.xml', '.wxml', '.svg', '.html', '.htm'].includes(ext)) {
    if (text.includes('<!--')) found.push({ position: text.indexOf('<!--'), token: '<!--' })
  } else if (['.sql'].includes(ext)) found = delimitedComments(text, '--', '/*', '*/')
  else if (['.yaml', '.yml'].includes(ext)) found = yamlComments(text)
  else if (name.startsWith('.env')) found = text.split(/\r?\n/).flatMap((line, index) => /^\s*#/.test(line) ? [{ position: index + 1, token: '#' }] : [])
  if (['.json'].includes(ext) || name === 'package.json' || name === 'tsconfig.json') {
    try { JSON.parse(text) } catch (error) { add(file, 'invalid-json', error.message) }
  }
  if (found.length) add(file, 'comment', `${found[0].token}@${found[0].position}; count=${found.length}`)
}

for (const root of roots) walk(root)
const result = { base, roots: roots.map(root => path.basename(root)), totals, issueCount: issues.length, issues: issues.slice(0, 100) }
process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
process.exitCode = issues.length ? 1 : 0
