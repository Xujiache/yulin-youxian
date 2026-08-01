import hashlib
import json
import zipfile
from pathlib import Path, PurePosixPath

workspace = Path(r'C:/Users/Administrator/Desktop/生鲜同城')
archive_path = workspace / 'deliverables' / 'yulin-youxian-smart-delivery-three-end-source-no-comments-20260714.zip'
verify_base = workspace / 'tmp' / 'verify-smart-delivery-combined-20260714'
index = 2
while verify_base.exists():
    verify_base = workspace / 'tmp' / f'verify-smart-delivery-combined-20260714-v{index}'
    index += 1
verify_base.mkdir(parents=True)
document_extensions = {'.md', '.mdx', '.txt', '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.rtf'}
forbidden_directories = {'node_modules', 'dist', 'target', '.git', 'docs', 'doc', 'coverage'}
expected_projects = {
    'yulin-youxian-backend-source',
    'yulin-youxian-admin-source',
    'yulin-youxian-miniprogram-source',
}
issues = []

with zipfile.ZipFile(archive_path) as archive:
    corrupt = archive.testzip()
    roots = set()
    projects = set()
    files = 0
    for item in archive.infolist():
        pure = PurePosixPath(item.filename)
        parts = pure.parts
        if pure.is_absolute() or '..' in parts:
            issues.append(f'unsafe:{item.filename}')
        if parts:
            roots.add(parts[0])
        if len(parts) > 1:
            projects.add(parts[1])
        lowered = [part.lower() for part in parts]
        if any(part in forbidden_directories for part in lowered):
            issues.append(f'forbidden-directory:{item.filename}')
        suffix = Path(pure.name).suffix.lower()
        name = pure.name.lower()
        if suffix in document_extensions or name == 'project.private.config.json':
            issues.append(f'forbidden-file:{item.filename}')
        if not item.is_dir():
            files += 1
    if corrupt:
        issues.append(f'crc:{corrupt}')
    if len(roots) != 1:
        issues.append(f'roots:{sorted(roots)}')
    if projects != expected_projects:
        issues.append(f'projects:{sorted(projects)}')
    if files != 581:
        issues.append(f'files:{files}')
    if issues:
        raise RuntimeError(issues[:20])
    archive.extractall(verify_base)

digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
print(json.dumps({
    'archive': str(archive_path),
    'verifyBase': str(verify_base),
    'sha256': digest,
    'bytes': archive_path.stat().st_size,
    'files': files,
    'root': next(iter(roots)),
    'projects': sorted(projects),
    'crc': 'ok',
    'forbiddenEntries': 0,
}, ensure_ascii=False, indent=2))
