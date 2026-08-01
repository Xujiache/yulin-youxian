import hashlib
import json
import zipfile
from pathlib import Path

workspace = Path(r'C:/Users/Administrator/Desktop/生鲜同城')
source = workspace / 'deliverables' / '20260714-smart-delivery-source'
output = workspace / 'deliverables' / 'yulin-youxian-smart-delivery-three-end-source-no-comments-20260714.zip'
index = 2
while output.exists():
    output = workspace / 'deliverables' / f'yulin-youxian-smart-delivery-three-end-source-no-comments-20260714-v{index}.zip'
    index += 1
root_name = 'yulin-youxian-smart-delivery-three-end-source'
file_count = 0

with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for file in sorted(source.rglob('*')):
        if file.is_file():
            archive.write(file, Path(root_name) / file.relative_to(source))
            file_count += 1

digest = hashlib.sha256(output.read_bytes()).hexdigest()
print(json.dumps({
    'archive': str(output),
    'root': root_name,
    'files': file_count,
    'bytes': output.stat().st_size,
    'sha256': digest,
}, ensure_ascii=False, indent=2))
