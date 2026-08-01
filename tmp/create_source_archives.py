import json
import zipfile
from pathlib import Path

workspace = Path(r'C:/Users/Administrator/Desktop/生鲜同城')
source_base = workspace / 'deliverables' / '20260714-no-comments-v1'
output_base = workspace / 'deliverables'
projects = [
    'yulin-youxian-backend-source',
    'yulin-youxian-admin-source',
    'yulin-youxian-miniprogram-source',
]
results = []

for project in projects:
    source = source_base / project
    candidate = output_base / f'{project}-no-comments-20260714.zip'
    index = 2
    while candidate.exists():
        candidate = output_base / f'{project}-no-comments-20260714-v{index}.zip'
        index += 1
    file_count = 0
    with zipfile.ZipFile(candidate, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for file in sorted(source.rglob('*')):
            if file.is_file():
                archive.write(file, Path(project) / file.relative_to(source))
                file_count += 1
    results.append({'project': project, 'archive': str(candidate), 'files': file_count, 'bytes': candidate.stat().st_size})

print(json.dumps(results, ensure_ascii=False, indent=2))
