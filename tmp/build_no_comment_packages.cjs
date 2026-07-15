const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

const workspace = path.resolve(__dirname, '..')
const ts = require(path.join(workspace, 'art-lnb-master', 'node_modules', 'typescript'))
const outputRoot = process.argv[2]

if (!outputRoot) {
  throw new Error('Missing output directory')
}

const documentExtensions = new Set([
  '.md', '.mdx', '.txt', '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.rtf'
])

const binaryExtensions = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.woff', '.woff2', '.ttf', '.eot', '.mp3', '.mp4', '.webm'
])

const report = {
  outputRoot,
  packages: {}
}

function normalizeNewlines(text) {
  return text.replace(/\r\n?/g, '\n')
}

function stripScript(text, filename, forceTs = false) {
  const extension = path.extname(filename).toLowerCase()
  const kind = forceTs || extension === '.ts' || extension === '.tsx'
    ? (extension === '.tsx' ? ts.ScriptKind.TSX : ts.ScriptKind.TS)
    : (extension === '.jsx' ? ts.ScriptKind.JSX : ts.ScriptKind.JS)
  const source = ts.createSourceFile(filename, normalizeNewlines(text), ts.ScriptTarget.Latest, true, kind)
  const diagnostics = source.parseDiagnostics || []
  if (diagnostics.length) {
    const message = ts.flattenDiagnosticMessageText(diagnostics[0].messageText, '\n')
    throw new Error(`Cannot parse ${filename}: ${message}`)
  }
  const printer = ts.createPrinter({
    removeComments: true,
    newLine: ts.NewLineKind.LineFeed
  })
  return `${printer.printFile(source).trimEnd()}\n`
}

function stripDelimitedComments(text, options = {}) {
  const source = normalizeNewlines(text)
  const lineToken = options.lineToken || '//'
  const blockStart = options.blockStart || '/*'
  const blockEnd = options.blockEnd || '*/'
  let output = ''
  let index = 0
  let quote = null
  let textBlock = false

  while (index < source.length) {
    if (textBlock) {
      if (source.startsWith('"""', index)) {
        output += '"""'
        index += 3
        textBlock = false
      } else {
        output += source[index]
        index += 1
      }
      continue
    }

    if (quote) {
      const character = source[index]
      output += character
      index += 1
      if (character === '\\' && index < source.length) {
        output += source[index]
        index += 1
      } else if (character === quote) {
        quote = null
      }
      continue
    }

    if (source.startsWith('"""', index)) {
      output += '"""'
      index += 3
      textBlock = true
      continue
    }

    const character = source[index]
    if (character === '"' || character === "'" || character === '`') {
      quote = character
      output += character
      index += 1
      continue
    }

    if (blockStart && source.startsWith(blockStart, index)) {
      output += ' '
      index += blockStart.length
      while (index < source.length && !source.startsWith(blockEnd, index)) {
        if (source[index] === '\n') {
          output += '\n'
        }
        index += 1
      }
      index += source.startsWith(blockEnd, index) ? blockEnd.length : 0
      continue
    }

    if (lineToken && source.startsWith(lineToken, index)) {
      while (index < source.length && source[index] !== '\n') {
        index += 1
      }
      if (index < source.length) {
        output += '\n'
        index += 1
      }
      continue
    }

    output += character
    index += 1
  }

  return `${output.trimEnd()}\n`
}

function stripCss(text, extension) {
  let output = stripDelimitedComments(text, { lineToken: null })
  if (extension === '.scss' || extension === '.sass' || extension === '.less') {
    output = output
      .split('\n')
      .filter((line) => !/^\s*\/\//.test(line))
      .join('\n')
  }
  return `${output.trimEnd()}\n`
}

function stripXmlComments(text) {
  return `${normalizeNewlines(text).replace(/<!--[\s\S]*?-->/g, '').trimEnd()}\n`
}

function stripYamlComments(text) {
  const lines = normalizeNewlines(text).split('\n')
  const cleaned = lines.map((line) => {
    let single = false
    let double = false
    for (let index = 0; index < line.length; index += 1) {
      const character = line[index]
      if (character === '\\' && double) {
        index += 1
        continue
      }
      if (character === "'" && !double) {
        single = !single
        continue
      }
      if (character === '"' && !single) {
        double = !double
        continue
      }
      if (character === '#' && !single && !double && (index === 0 || /\s/.test(line[index - 1]))) {
        return line.slice(0, index).trimEnd()
      }
    }
    return line
  })
  return `${cleaned.join('\n').trimEnd()}\n`
}

function stripEnvComments(text) {
  return `${normalizeNewlines(text)
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n')
    .trimEnd()}\n`
}

function stripVue(text, filename) {
  let output = stripXmlComments(text)
  output = output.replace(/(<script\b[^>]*>)([\s\S]*?)(<\/script>)/gi, (full, open, content, close) => {
    const forceTs = /\blang\s*=\s*["']ts["']/i.test(open)
    return `${open}\n${stripScript(content, filename, forceTs).trimEnd()}\n${close}`
  })
  output = output.replace(/(<style\b[^>]*>)([\s\S]*?)(<\/style>)/gi, (full, open, content, close) => {
    const extension = /\blang\s*=\s*["'](?:scss|sass)["']/i.test(open) ? '.scss' : '.css'
    return `${open}\n${stripCss(content, extension).trimEnd()}\n${close}`
  })
  return `${output.trimEnd()}\n`
}

function stripHtmlScripts(text, filename) {
  let output = stripXmlComments(text)
  output = output.replace(/(<script\b(?![^>]*\bsrc=)[^>]*>)([\s\S]*?)(<\/script>)/gi, (full, open, content, close) => {
    if (!content.trim()) return full
    return `${open}\n${stripScript(content, filename).trimEnd()}\n${close}`
  })
  output = output.replace(/(<style\b[^>]*>)([\s\S]*?)(<\/style>)/gi, (full, open, content, close) => {
    return `${open}\n${stripCss(content, '.css').trimEnd()}\n${close}`
  })
  return `${output.trimEnd()}\n`
}

function sanitizeText(text, filename) {
  const extension = path.extname(filename).toLowerCase()
  if (['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs'].includes(extension)) {
    return stripScript(text, filename)
  }
  if (extension === '.vue') return stripVue(text, filename)
  if (extension === '.java') return stripDelimitedComments(text)
  if (['.css', '.scss', '.sass', '.less', '.wxss'].includes(extension)) return stripCss(text, extension)
  if (['.wxml', '.xml', '.svg'].includes(extension)) return stripXmlComments(text)
  if (extension === '.html' || extension === '.htm') return stripHtmlScripts(text, filename)
  if (extension === '.sql') return stripDelimitedComments(text, { lineToken: '--' })
  if (extension === '.yml' || extension === '.yaml') return stripYamlComments(text)
  if (extension === '.env' || path.basename(filename).startsWith('.env')) return stripEnvComments(text)
  if (extension === '.json') {
    return `${JSON.stringify(JSON.parse(text), null, 2)}\n`
  }
  return normalizeNewlines(text)
}

function isDocument(filename) {
  return documentExtensions.has(path.extname(filename).toLowerCase())
}

function copyFile(source, destination, packageReport) {
  if (isDocument(source)) return
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  const extension = path.extname(source).toLowerCase()
  if (binaryExtensions.has(extension)) {
    fs.copyFileSync(source, destination)
    packageReport.binaryFiles += 1
    packageReport.bytes += fs.statSync(source).size
    return
  }
  const content = fs.readFileSync(source, 'utf8')
  const sanitized = sanitizeText(content, source)
  fs.writeFileSync(destination, sanitized, 'utf8')
  packageReport.textFiles += 1
  packageReport.bytes += Buffer.byteLength(sanitized)
}

function copyTree(source, destination, packageReport, options = {}) {
  const excludedSegments = new Set(options.excludedSegments || [])
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    if (excludedSegments.has(entry.name)) continue
    const sourcePath = path.join(source, entry.name)
    const destinationPath = path.join(destination, entry.name)
    if (entry.isDirectory()) {
      copyTree(sourcePath, destinationPath, packageReport, options)
    } else if (entry.isFile()) {
      copyFile(sourcePath, destinationPath, packageReport)
    }
  }
}

function createPackage(name, sourceRoot, rootFiles, directories, excludedSegments = []) {
  const destinationRoot = path.join(outputRoot, name)
  fs.mkdirSync(destinationRoot, { recursive: true })
  const packageReport = { textFiles: 0, binaryFiles: 0, bytes: 0 }
  for (const filename of rootFiles) {
    copyFile(path.join(sourceRoot, filename), path.join(destinationRoot, filename), packageReport)
  }
  for (const directory of directories) {
    copyTree(
      path.join(sourceRoot, directory),
      path.join(destinationRoot, directory),
      packageReport,
      { excludedSegments }
    )
  }
  report.packages[name] = packageReport
}

fs.mkdirSync(outputRoot, { recursive: false })

createPackage(
  'yulin-youxian-backend-source',
  path.join(workspace, 'server'),
  ['pom.xml'],
  ['src', 'scripts']
)

createPackage(
  'yulin-youxian-admin-source',
  path.join(workspace, 'art-lnb-master'),
  [
    'package.json', 'pnpm-lock.yaml', 'pnpm-workspace.yaml', 'tsconfig.json',
    'vite.config.ts', 'index.html', '.env', '.env.development', '.env.production'
  ],
  ['src', 'public', 'scripts'],
  ['_examples']
)

createPackage(
  'yulin-youxian-miniprogram-source',
  path.join(workspace, 'client-wechat'),
  ['app.js', 'app.json', 'app.wxss', 'project.config.json', 'sitemap.json'],
  ['api', 'assets', 'components', 'pages', 'utils']
)

report.sha256 = crypto.createHash('sha256').update(JSON.stringify(report.packages)).digest('hex')
fs.writeFileSync(`${outputRoot}.audit.json`, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
