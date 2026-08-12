const fs = require("node:fs");
const path = require("node:path");

const projectRoot = path.resolve(__dirname, "..");
const sourceRoot = path.join(
  projectRoot,
  "node_modules",
  "tdesign-miniprogram",
  "miniprogram_dist"
);
const outputRoot = path.join(projectRoot, "miniprogram_npm");
const targetRoot = path.join(outputRoot, "tdesign-miniprogram");
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
  for (const match of source.matchAll(/\b(?:src)=["']([^"']+)["']/g)) {
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

const temporaryTarget = `${targetRoot}.tmp`;
fs.rmSync(temporaryTarget, { recursive: true, force: true });
for (const sourceFile of runtimeFiles) {
  const relative = path.relative(sourceRoot, sourceFile);
  const targetFile = path.join(temporaryTarget, relative);
  fs.mkdirSync(path.dirname(targetFile), { recursive: true });
  fs.copyFileSync(sourceFile, targetFile);
}

fs.rmSync(targetRoot, { recursive: true, force: true });
fs.renameSync(temporaryTarget, targetRoot);

const cascaderEntry = path.join(targetRoot, "cascader", "cascader.json");
if (!fs.existsSync(cascaderEntry)) {
  throw new Error(`级联选择器构建失败：${cascaderEntry}`);
}

console.log(
  `TDesign Cascader 已构建：${path.relative(projectRoot, targetRoot)}（${runtimeFiles.size} 个运行时文件）`
);
