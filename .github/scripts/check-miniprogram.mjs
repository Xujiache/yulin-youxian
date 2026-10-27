import { readdirSync, statSync } from "node:fs";
import { resolve, join, relative } from "node:path";
import { spawnSync } from "node:child_process";

const root = resolve(process.argv[2] || "client-wechat");
const ignoredDirectories = new Set(["node_modules", "dist", ".git"]);
const files = [];

function collect(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && ignoredDirectories.has(entry.name)) {
      continue;
    }
    const fullPath = join(directory, entry.name);
    if (entry.isDirectory()) {
      collect(fullPath);
    } else if (entry.isFile() && entry.name.endsWith(".js")) {
      files.push(fullPath);
    }
  }
}

if (!statSync(root).isDirectory()) {
  throw new Error(`Mini-program root is not a directory: ${root}`);
}

collect(root);
files.sort();

if (files.length === 0) {
  throw new Error(`No JavaScript files found under ${root}`);
}

for (const file of files) {
  const result = spawnSync(process.execPath, ["--check", file], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.status !== 0) {
    process.stderr.write(`Syntax check failed: ${relative(process.cwd(), file)}\n`);
    process.stderr.write(result.stderr);
    process.exit(result.status || 1);
  }
}

console.log(`Node syntax check passed for ${files.length} mini-program files.`);
