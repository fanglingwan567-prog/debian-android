"""Check the network-install APK, native entry and offline terminal; optionally inspect a rootfs fixture."""
import argparse
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('apk', nargs='?', type=Path, default=ROOT/'app/build/outputs/apk/debug/app-debug.apk')
parser.add_argument('--image', type=Path, help='Optional known Debian 13.7 gzip fixture (not packaged)')
parser.add_argument('--run-guest', action='store_true', help='Requires a host without Android app seccomp restrictions')
args=parser.parse_args()
if args.run_guest and not args.image: parser.error("--run-guest requires --image")
APK=args.apk
with zipfile.ZipFile(APK) as apk:
    assert apk.testzip() is None
    assets={n for n in apk.namelist() if n.startswith('assets/') and not n.endswith('/')}
    assert 'assets/debian.image' not in assets and all(apk.getinfo(n).file_size < 2_000_000 for n in assets)
    native=apk.read('lib/arm64-v8a/libbirch.so'); assert native==(ROOT/'app/src/main/jniLibs/arm64-v8a/libbirch.so').read_bytes()
    assert int.from_bytes(native[18:20],'little')==183
    labels = subprocess.check_output(['aapt2', 'dump', 'resources', str(APK)], text=True)
    for label in ['1/3 · 授予权限', '2/3 · 下载系统', '3/3 · 安装系统']:
        assert label in labels
    # The root helper must authorize this APK's actual package directory, not the legacy ID.
    badging = subprocess.check_output(['aapt2', 'dump', 'badging', str(APK)], text=True)
    assert "package: name='app.birch.debian'" in badging
    assert "launchable-activity: name='app.birch.debian.MainActivity'" in badging
    assert "application: label='Debian' icon='res/drawable/birch_mark.xml'" in badging
    manifest = subprocess.check_output(['aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(APK)], text=True)
    assert ':roundIcon(' not in manifest, 'desktop launchers must use the same unwrapped vector'
    assert b'/data/data/app.birch.debian/files' in native and b'/user/%u/app.birch.debian/files' in native
    assert b'app.birch.shell' not in native
    for f in ['xterm.js','xterm.css','fit.js','screen.js','index.html','LICENSE.xterm','LICENSE.fit']:
        assert apk.read('assets/term/'+f)==(ROOT/'app/src/main/assets/term'/f).read_bytes()
print('APK integrity, runtime files, three setup steps and offline terminal: PASS')
if not args.image: raise SystemExit(0)
data=args.image.read_bytes()
assert hashlib.sha256(data).hexdigest()=='acd43e9bebc37bc4ba7a5f47bd7598913337803e53d468f37d940f11f5d5f1ef'
with tempfile.TemporaryDirectory() as tmp,tarfile.open(fileobj=io.BytesIO(data)) as tar:
    names={m.name:m for m in tar.getmembers()}; assert tar.extractfile('etc/debian_version').read().strip()==b'13.7'
    assert all(not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts for name in names)
    for name in ['usr/bin/bash','usr/bin/apt','usr/sbin/chroot','var/lib/dpkg/status']:
        assert names[name].isfile() and names[name].size>0
    print('Debian regular file data:',sum(m.size for m in names.values() if m.isfile()),'bytes')
    dest=Path(tmp)
    # Execute the bundled ARM64 Bash and GNU coreutils using their own ELF loader; no chroot or su.
    for source in ['usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1','usr/lib/aarch64-linux-gnu/libc.so.6','usr/lib/aarch64-linux-gnu/libtinfo.so.6.5','usr/bin/bash','usr/bin/uname']:
        f=dest/PurePosixPath(source).name
        if 'libtinfo' in f.name: f=dest/'libtinfo.so.6'
        f.write_bytes(tar.extractfile(source).read());f.chmod(0o700)
    loader=str(dest/'ld-linux-aarch64.so.1');prefix=[loader,'--library-path',tmp]
    env=os.environ.copy()
    env.pop('LD_PRELOAD',None)
    env.pop('LD_LIBRARY_PATH',None)
    if args.run_guest:
        subprocess.run(prefix+[str(dest/'bash'),'--noprofile','--norc','-c','test "$((6*7))" = 42 && echo "Bash $BASH_VERSION OK"'],check=True,env=env)
        assert subprocess.check_output(prefix+[str(dest/'uname'),'-m'],env=env).strip()==b'aarch64'
print('Optional Debian fixture: PASS')
