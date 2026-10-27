const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const projectRoot = path.resolve(__dirname, "..");
const sourceRoot = path.join(
  projectRoot,
  "node_modules",
  "tdesign-miniprogram",
  "miniprogram_dist"
);
const targetRoot = path.join(projectRoot, "components", "tdesign-cascade");
const entryComponent = path.join(sourceRoot, "cascader", "cascader");
const componentExtensions = [".js", ".json", ".wxml", ".wxss"];

if (!fs.existsSync(sourceRoot)) {
  throw new Error("缺少 tdesign-miniprogram，请先在 client-wechat 目录执行 npm install");
}

const pending = [];
const runtimeFiles = new Set();

function addFile(file) {
  const normalized = path.normalize(file);
  if (
    !normalized.startsWith(sourceRoot)
    || runtimeFiles.has(normalized)
    || !fs.existsSync(normalized)
    || !fs.statSync(normalized).isFile()
  ) {
    return;
  }
  runtimeFiles.add(normalized);
  pending.push(normalized);
}

function addComponent(base) {
  componentExtensions.forEach((extension) => addFile(`${base}${extension}`));
}

function resolveRuntimeFile(fromFile, request, extensions = ["", ".js", ".json", ".wxs"]) {
  let base;
  if (request.startsWith(".")) {
    base = path.resolve(path.dirname(fromFile), request);
  } else if (request.startsWith("/")) {
    base = path.join(sourceRoot, request.slice(1));
  } else {
    base = path.join(sourceRoot, "miniprogram_npm", request);
  }
  const candidates = extensions.flatMap((extension) => [
    `${base}${extension}`,
    extension ? "" : path.join(base, "index.js")
  ]).filter(Boolean);
  return candidates.find((candidate) => fs.existsSync(candidate) && fs.statSync(candidate).isFile()) || "";
}

function addJavaScriptDependencies(file, source) {
  const importPattern = /(?:\bfrom\s*|\brequire\(\s*|\bimport\s*)["']([^"']+)["']/g;
  for (const match of source.matchAll(importPattern)) {
    const request = match[1];
    if (!/^(?:\.{0,2}\/|@?[\w-])[\w@./-]*$/.test(request)) {
      continue;
    }
    const resolved = resolveRuntimeFile(file, request);
    if (!resolved) {
      throw new Error(`无法解析运行时模块：${path.relative(sourceRoot, file)} -> ${request}`);
    }
    addFile(resolved);
  }
}

function addStyleDependencies(file, source) {
  for (const match of source.matchAll(/@import\s+["']([^"']+)["']/g)) {
    const resolved = resolveRuntimeFile(file, match[1], ["", ".wxss"]);
    if (!resolved) {
      throw new Error(`无法解析样式依赖：${path.relative(sourceRoot, file)} -> ${match[1]}`);
    }
    addFile(resolved);
  }
  for (const match of source.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)) {
    const request = match[1].trim();
    if (!request.startsWith(".")) {
      continue;
    }
    const resolved = resolveRuntimeFile(file, request, [""]);
    if (resolved) {
      addFile(resolved);
    }
  }
}

function addTemplateDependencies(file, source) {
  for (const match of source.matchAll(/\bsrc=["']([^"']+)["']/g)) {
    const request = match[1];
    if (!request.startsWith(".")) {
      continue;
    }
    const resolved = resolveRuntimeFile(file, request, ["", ".wxml", ".wxs"]);
    if (resolved) {
      addFile(resolved);
    }
  }
}

function outputRelative(sourceFile) {
  const relative = path.relative(sourceRoot, sourceFile);
  const npmPrefix = `miniprogram_npm${path.sep}`;
  return relative.startsWith(npmPrefix)
    ? path.join("_runtime", relative.slice(npmPrefix.length))
    : relative;
}

function rewriteBareImports(sourceFile, source) {
  const importPattern = /((?:\bfrom\s*|\brequire\(\s*|\bimport\s*)["'])([^"']+)(["'])/g;
  return source.replace(importPattern, (full, prefix, request, suffix) => {
    if (
      request.startsWith(".")
      || request.startsWith("/")
      || !/^(?:@?[\w-])[\w@./-]*$/.test(request)
    ) {
      return full;
    }
    const resolved = resolveRuntimeFile(sourceFile, request);
    if (!resolved) {
      throw new Error(`无法重写运行时模块：${path.relative(sourceRoot, sourceFile)} -> ${request}`);
    }
    const fromTarget = outputRelative(sourceFile);
    const dependencyTarget = outputRelative(resolved);
    let relativeRequest = path.relative(path.dirname(fromTarget), dependencyTarget).replace(/\\/g, "/");
    relativeRequest = relativeRequest.replace(/\.js$/, "");
    if (!relativeRequest.startsWith(".")) {
      relativeRequest = `./${relativeRequest}`;
    }
    return `${prefix}${relativeRequest}${suffix}`;
  });
}

addComponent(entryComponent);

while (pending.length) {
  const file = pending.shift();
  const extension = path.extname(file);
  const source = fs.readFileSync(file, "utf8");

  if (extension === ".json") {
    const config = JSON.parse(source);
    Object.values(config.usingComponents || {}).forEach((request) => {
      const componentBase = request.startsWith("/")
        ? path.join(sourceRoot, request.slice(1))
        : path.resolve(path.dirname(file), request);
      addComponent(componentBase);
    });
  } else if (extension === ".js" || extension === ".wxs") {
    addJavaScriptDependencies(file, source);
  } else if (extension === ".wxss") {
    addStyleDependencies(file, source);
  } else if (extension === ".wxml") {
    addTemplateDependencies(file, source);
  }
}

const temporaryTarget = fs.mkdtempSync(path.join(os.tmpdir(), "yulin-tdesign-cascade-"));
const expectedFiles = new Set();

try {
  for (const sourceFile of runtimeFiles) {
    const relative = outputRelative(sourceFile);
    expectedFiles.add(relative);
    const temporaryFile = path.join(temporaryTarget, relative);
    fs.mkdirSync(path.dirname(temporaryFile), { recursive: true });
    if ([".js", ".wxs"].includes(path.extname(sourceFile))) {
      const source = fs.readFileSync(sourceFile, "utf8");
      fs.writeFileSync(temporaryFile, rewriteBareImports(sourceFile, source), "utf8");
    } else {
      fs.copyFileSync(sourceFile, temporaryFile);
    }
  }

  // Keep the published component tree continuously available while DevTools is watching it.
  // Files are copied over in place; the component directory is never renamed or removed.
  for (const relative of expectedFiles) {
    const temporaryFile = path.join(temporaryTarget, relative);
    const targetFile = path.join(targetRoot, relative);
    fs.mkdirSync(path.dirname(targetFile), { recursive: true });
    const unchanged = fs.existsSync(targetFile)
      && fs.readFileSync(targetFile).equals(fs.readFileSync(temporaryFile));
    if (!unchanged) {
      fs.copyFileSync(temporaryFile, targetFile);
    }
  }

  if (fs.existsSync(targetRoot)) {
    const existingFiles = fs.readdirSync(targetRoot, { recursive: true, withFileTypes: true })
      .filter((entry) => entry.isFile())
      .map((entry) => path.relative(targetRoot, path.join(entry.parentPath, entry.name)));
    for (const relative of existingFiles) {
      if (!expectedFiles.has(relative)) {
        fs.rmSync(path.join(targetRoot, relative), { force: true });
      }
    }
  }
} finally {
  fs.rmSync(temporaryTarget, { recursive: true, force: true });
}

const cascaderEntry = path.join(targetRoot, "cascader", "cascader.json");
if (!fs.existsSync(cascaderEntry)) {
  throw new Error(`级联选择器构建失败：${cascaderEntry}`);
}

console.log(
  `TDesign Cascader 已生成为本地组件：${path.relative(projectRoot, targetRoot)}（${runtimeFiles.size} 个运行时文件）`
);
