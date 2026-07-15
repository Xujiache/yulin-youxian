import hashlib
import json
import zipfile
from pathlib import Path, PurePosixPath

workspace = Path(r'C:/Users/Administrator/Desktop/生鲜同城')
archives = [
    workspace / 'deliverables' / 'yulin-youxian-backend-source-no-comments-20260714.zip',
    workspace / 'deliverables' / 'yulin-youxian-admin-source-no-comments-20260714.zip',
    workspace / 'deliverables' / 'yulin-youxian-miniprogram-source-no-comments-20260714.zip',
]
verify_base = workspace / 'tmp' / 'verify-source-archives-20260714'
index = 2
while verify_base.exists():
    verify_base = workspace / 'tmp' / f'verify-source-archives-20260714-v{index}'
    index += 1
verify_base.mkdir(parents=True)
document_extensions = {'.md', '.mdx', '.txt', '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.rtf'}
forbidden_directories = {'node_modules', 'dist', 'target', '.git', 'docs', 'doc', 'coverage'}
results = []

for archive_path in archives:
    digest = hashlib.sha256()
    with archive_path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    with zipfile.ZipFile(archive_path) as archive:
        corrupt = archive.testzip()
        issues = []
        roots = set()
        for item in archive.infolist():
            pure = PurePosixPath(item.filename)
            parts = pure.parts
            if pure.is_absolute() or '..' in parts:
                issues.append(f'unsafe:{item.filename}')
            if parts:
                roots.add(parts[0])
            lowered = [part.lower() for part in parts]
            if any(part in forbidden_directories for part in lowered):
                issues.append(f'forbidden-directory:{item.filename}')
            suffix = Path(pure.name).suffix.lower()
            name = pure.name.lower()
            if suffix in document_extensions or name == 'project.private.config.json':
                issues.append(f'forbidden-file:{item.filename}')
            if name.startswith(('readme.', 'license.', 'changelog.', 'contributing.', 'code_of_conduct.')) and suffix in document_extensions:
                issues.append(f'document:{item.filename}')
        if corrupt:
            issues.append(f'crc:{corrupt}')
        if len(roots) != 1:
            issues.append(f'roots:{sorted(roots)}')
        if issues:
            raise RuntimeError(f'{archive_path.name}: {issues[:20]}')
        archive.extractall(verify_base)
        results.append({
            'archive': str(archive_path),
            'sha256': digest.hexdigest(),
            'bytes': archive_path.stat().st_size,
            'files': sum(not item.is_dir() for item in archive.infolist()),
            'root': next(iter(roots)),
            'crc': 'ok',
            'forbiddenEntries': 0,
        })

print(json.dumps({'verifyBase': str(verify_base), 'archives': results}, ensure_ascii=False, indent=2))
