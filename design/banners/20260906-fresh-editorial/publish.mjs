import { readFile, writeFile, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const dir = path.dirname(fileURLToPath(import.meta.url));
const base = 'http://127.0.0.1:8090';
const receipt = path.join(dir, 'published.json');
try { await access(receipt); throw new Error('Already published; inspect published.json before another change.'); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const manifest = JSON.parse(await readFile(path.join(dir, 'prompts.json'), 'utf8'));
if (manifest.length !== 10) throw new Error('Expected exactly ten banners');
// Read every deliverable before making any API changes.
const files = await Promise.all(manifest.map(s => readFile(path.join(dir, 'web', s.name + '.webp'))));
let token;
async function api(endpoint, options = {}) {
  const response = await fetch(base + endpoint, {
    ...options,
    headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...options.headers },
    signal: AbortSignal.timeout(60000),
  });
  const body = await response.json();
  if (!response.ok || body.code !== 0) throw new Error(endpoint + ': ' + response.status + ' ' + body.message);
  return body.data;
}
try {
  const login = await api('/api/admin/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: process.env.ADMIN_USERNAME, password: process.env.ADMIN_PASSWORD }),
  });
  token = login.token;
  if (!token) throw new Error('Login did not return a token');
  const original = await api('/api/admin/settings/banners');
  const categories = await api('/api/wx/categories');
  const vegetable = categories.find(c => c.name === '蔬菜类');
  const fruit = categories.find(c => c.name === '水果类');
  if (!vegetable || !fruit) throw new Error('Expected live fruit and vegetable categories');
  await writeFile(path.join(dir, 'before-publish.json'), JSON.stringify(original, null, 2) + '\n', { flag: 'wx' });
  const additions = [];
  for (const [index, spec] of manifest.entries()) {
    const data = new FormData();
    data.append('file', new Blob([files[index]], { type: 'image/webp' }), spec.name + '.webp');
    const uploaded = await api('/api/admin/settings/banners/images', { method: 'POST', body: data });
    const check = await fetch(base + uploaded.url, { signal: AbortSignal.timeout(15000) });
    if (!check.ok || !check.headers.get('content-type')?.startsWith('image/')) throw new Error('Uploaded image unavailable: ' + spec.name);
    additions.push({ id: null, title: spec.title, subtitle: spec.subtitle, imageUrl: uploaded.url,
      linkType: 'category', linkTarget: String(index >= 1 && index <= 5 ? fruit.id : vegetable.id),
      sortOrder: (index + 1) * 10, enabled: true });
    console.log('Uploaded', spec.name, files[index].length, 'bytes');
  }
  // Refuse to overwrite another administrator's concurrent edits.
  const current = await api('/api/admin/settings/banners');
  if (JSON.stringify(current) !== JSON.stringify(original)) throw new Error('Banner configuration changed during upload');
  const payload = [...additions, ...original.map((b, i) => ({ ...b, enabled: false, sortOrder: 1000 + i * 10 }))];
  const published = await api('/api/admin/settings/banners', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
  });
  await writeFile(receipt, JSON.stringify({ publishedAt: new Date().toISOString(), banners: published }, null, 2) + '\n', { flag: 'wx' });
  const home = await api('/api/wx/home');
  const expected = additions.map(b => b.imageUrl);
  if (JSON.stringify(home.banners.map(b => b.imageUrl)) !== JSON.stringify(expected)) throw new Error('Homepage does not match published banners; inspect receipt');
  console.log('Verified homepage:', home.banners.length, 'enabled banners; previous', original.length, 'retained disabled.');
} finally {
  if (token) await api('/api/admin/auth/logout', { method: 'POST' }).catch(() => console.error('Session logout failed'));
}
