"""Opt-in Android root test. Only the newly created, disposable instance is modified."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import selectors
import struct
import shlex
import shutil
import tempfile
import subprocess as sp
import tarfile
import time

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / 'app/src/main/jniLibs/arm64-v8a/libbirch.so'
UID = os.getuid()
FILES = f'/data/user/{UID // 100000}/app.birch.debian/files'


def run(command, **kwargs):
    p=sp.run(['su','-c',command],stdout=sp.PIPE,stderr=sp.STDOUT,timeout=90,**kwargs)
    if p.returncode:
        raise RuntimeError(p.stdout.decode(errors='replace')[-800:])
    return p.stdout


def helper(op, fixture, files=FILES):
    # Overlay the Android user directory only inside this subprocess's private namespace.
    # Keep cwd pinned to the fixture before the bind, even if it masks the Termux package.
    bind_at = shlex.quote(str(Path(FILES).parents[1]))
    script = (f'cd {shlex.quote(str(fixture))} && /system/bin/mount -o rprivate none / && '
              f'/system/bin/mount --bind packages {bind_at} && exec ./launcher {op} {UID} {shlex.quote(files)}')
    return 'exec /system/bin/toybox unshare -m /system/bin/sh -c ' + shlex.quote(script)


class Session:
    def __init__(self, fixture):
        self.fixture = fixture

    def __enter__(self):
        self.p = sp.Popen(['su', '-c', helper('shell', self.fixture)], stdin=sp.PIPE,
                          stdout=sp.PIPE, stderr=sp.STDOUT, bufsize=0)
        self.pending = b''
        return self

    def __exit__(self, *exc):
        self.p.stdin.close()
        try:
            self.p.wait(timeout=5)
        except sp.TimeoutExpired:
            self.p.kill()
            self.p.wait()
        self.p.stdout.close()

    def frame(self, op, data):
        self.p.stdin.write(op + struct.pack('>H', len(data)) + data)

    def send(self, command):
        self.frame(b'I', (command + '\n').encode())

    def expect(self, marker):
        # Match the evaluated output, never an echoed command or a particular PS1.
        until = time.monotonic() + 15
        with selectors.DefaultSelector() as selector:
            selector.register(self.p.stdout, selectors.EVENT_READ)
            while marker not in self.pending:
                if not selector.select(max(0, until - time.monotonic())):
                    details = [line for line in self.pending.splitlines() if line.startswith((b'su:', b'SU-STATUS:'))]
                    raise AssertionError(f'timed out waiting for {marker!r}; su status: {details[-3:]!r}')
                chunk = os.read(self.p.stdout.fileno(), 8192)
                if not chunk:
                    raise AssertionError(f'shell exited before {marker!r}')
                self.pending += chunk
        self.pending = self.pending.split(marker, 1)[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('image',type=Path,help='Trusted official Debian 13.7 reference layer')
    image_path=parser.parse_args().image
    with image_path.open('rb') as source:
        expected=hashlib.file_digest(source,'sha256').hexdigest()
    if expected!='acd43e9bebc37bc4ba7a5f47bd7598913337803e53d468f37d940f11f5d5f1ef':
        raise ValueError('Not the trusted root smoke fixture')
    fixture = Path(tempfile.mkdtemp(prefix='private-smoke-',dir=ROOT/'app/build'))
    files = fixture/'packages/app.birch.debian/files'
    location = files/'debian'
    try:
        shutil.copy2(HELPER, fixture/'launcher')
        files.mkdir(parents=True,mode=0o700)
        # A symlink planted in the app-writable parent must fail, never be followed.
        (fixture/'outside').mkdir()
        location.symlink_to(fixture/'outside')
        for argument in (FILES, FILES+'/../files', f'/data/user/{UID // 100000}/app.birch.shell/files'):
            try:
                run(helper('check',fixture,argument))
            except RuntimeError as e:
                assert 'debian:' in str(e) or 'Not this application' in str(e), str(e)
            else:
                raise AssertionError('invalid private path accepted')
        assert not list((fixture/'outside').iterdir())
        location.unlink()
        assert run(helper('check',fixture)).strip().endswith(b'BIRCH_NEW')
        header=f'{expected} {image_path.stat().st_size}\n'.encode('ascii')
        # A damaged download must never publish the staged system.
        try: run(helper('install',fixture),input=header+b'truncated')
        except RuntimeError as ex: assert 'Incomplete image' in str(ex)
        else: raise AssertionError('truncated image accepted')
        assert run(helper('check',fixture)).strip().endswith(b'BIRCH_NEW')
        with tempfile.TemporaryFile() as wire,image_path.open('rb') as image:
            wire.write(header);shutil.copyfileobj(image,wire);wire.seek(0)
            run(helper('install',fixture), stdin=wire)
        assert run(helper('check',fixture)).strip().endswith(b'BIRCH_READY')
        with tarfile.open(image_path) as archive:
            for name in ['etc/passwd', 'etc/group', 'etc/shadow', 'etc/gshadow', 'usr/sbin/policy-rc.d']:
                digest = hashlib.sha256(archive.extractfile(name).read()).hexdigest().encode()
                assert run(f'/system/bin/toybox sha256sum {shlex.quote(str(location))}/root/{name}').split()[0] == digest, name
        with Session(fixture) as s:
            s.frame(b'W', struct.pack('>HH', 96, 31))
            s.send('printf "FIRST:%s:%s:%s\\n" "$(id -u)" "$(dpkg --print-architecture)" "$(stty size)"')
            s.expect(b'FIRST:0:arm64:31 96')
            # Configure a test-only account, using Debian tools, not launcher policy.
            password = secrets.token_hex(16)
            s.send(f"useradd -m smokeuser && echo smokeuser:{password} | chpasswd && touch /root/preserved && printf 'SETUP:%s\\n' ok")
            s.expect(b'SETUP:ok')
            s.send('su -s /bin/sh nobody')
            s.send('printf "FIRST-SWITCH:%s\\n" "$(id -un)"')
            s.expect(b'FIRST-SWITCH:nobody')
            s.send("su - smokeuser -c 'printf \"AUTH:%s\\n\" \"$(id -un)\"'; printf 'SU-STATUS:%s\\n' \"$?\"")
            s.expect(b'Password:')
            s.send(password)
            s.expect(b'AUTH:smokeuser')
            s.expect(b'SU-STATUS:0')
            s.send('exit')
            s.send('printf "BACK:%s\\n" "$(id -u)"; exit 0')
            s.expect(b'BACK:0')
            assert s.p.wait(timeout=10) == 0
        run(helper('install',fixture), stdin=sp.DEVNULL)  # Already installed: no image or account changes.
        with Session(fixture) as s:
            s.send('test -f /root/preserved && printf "PRESERVED:%s\\n" "$(id -un smokeuser)"; exit 0')
            s.expect(b'PRESERVED:smokeuser')
            assert s.p.wait(timeout=10) == 0
        print('Offline installation, original account files, PTY resize, Debian su, exit and persisted guest: PASS')
    finally:
        run('/system/bin/toybox rm -rf '+shlex.quote(str(fixture)))


if __name__ == '__main__':
    main()
