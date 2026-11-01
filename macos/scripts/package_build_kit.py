"""Create a Mac source/build ZIP with executable command scripts and LF text."""
from pathlib import Path
import hashlib
import stat
import zipfile

ROOT=Path(__file__).resolve().parents[1]
output=ROOT/'dist'; output.mkdir(exist_ok=True)
name='AlterEgoMR-Mac-AppleSilicon-1.0.0-BuildKit.zip'
prefix='AlterEgoMR-Mac-1.0.0'
forbidden={'.p12','.p8','.exe','.dll','.mobileprovision','.provisionprofile'}
excluded={'build','dist','__pycache__','xcuserdata','.DS_Store'}
text={'.swift','.plist','.entitlements','.pbxproj','.xcscheme','.command','.md','.txt','.py','.json','.gitignore'}
with zipfile.ZipFile(output/name,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
    for path in sorted(ROOT.rglob('*')):
        relative=path.relative_to(ROOT)
        if not path.is_file() or any(part in excluded for part in relative.parts) or path.suffix=='.pyc': continue
        if path.suffix.lower() in forbidden or path.name=='Signing.local.xcconfig': raise ValueError(f'Refusing to package signing/Windows material: {relative}')
        content=path.read_bytes()
        if path.suffix in text or path.name=='.gitignore': content=content.replace(b'\r\n',b'\n')
        item=zipfile.ZipInfo(prefix+'/'+relative.as_posix())
        item.create_system=3
        mode=0o755 if path.suffix=='.command' else 0o644
        item.external_attr=(stat.S_IFREG|mode)<<16
        item.compress_type=zipfile.ZIP_DEFLATED
        archive.writestr(item,content)
digest=hashlib.sha256((output/name).read_bytes()).hexdigest().upper()
(output/'SHA256SUMS.txt').write_text(f'{digest}  {name}\n',encoding='utf-8')
with zipfile.ZipFile(output/name) as archive:
    assert archive.testzip() is None
    for item in archive.infolist():
        if item.filename.endswith('.command'):
            assert item.external_attr>>16 & 0o111 == 0o111
            assert b'\r\n' not in archive.read(item)
    assert any(item.filename.endswith('/project.pbxproj') for item in archive.infolist())
print(output/name)
print(f'{(output/name).stat().st_size:,} bytes; SHA256 {digest}')
print('Source/build kit only — no compiled .app is represented as delivered.')
