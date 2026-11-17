"""Fetch immutable release inputs into this project's assets, checking digests before use."""
import base64
import hashlib
import io
from pathlib import Path
import tarfile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
PACKAGES = [
    ('https://registry.npmjs.org/@xterm/xterm/-/xterm-6.0.0.tgz', 'TQwDdQGtwwDt+2cgKDLn0IRaSxYu1tSUjgKarSDkUM0ZNiSRXFpjxEsvc/Zgc5kq5omJ+V0a8/kIM2WD3sMOYg==', {'lib/xterm.js':'xterm.js','css/xterm.css':'xterm.css','LICENSE':'LICENSE.xterm'}),
    ('https://registry.npmjs.org/@xterm/addon-fit/-/addon-fit-0.11.0.tgz','jYcgT6xtVYhnhgxh3QgYDnnNMYTcf8ElbxxFzX0IZo+vabQqSPAjC3c1wJrKB5E19VwQei89QCiZZP86DCPF7g==', {'lib/addon-fit.js':'fit.js','LICENSE':'LICENSE.fit'})]

def patch_xterm_selection(bundle):
    # In the pinned DOM renderer, a clear paints empty rows but skips updating the
    # model that handleResize replays. update() already handles missing endpoints.
    before = (b'this.renderRows(0,this._bufferService.rows-1),!e||!t)return;'
              b'if(this._selectionRenderModel.update(this._terminal,e,t,i),')
    after = (b'this.renderRows(0,this._bufferService.rows-1),'
             b'this._selectionRenderModel.update(this._terminal,e,t,i),')
    if bundle.count(before) != 1:
        raise ValueError('Unexpected xterm DOM renderer; review selection patch before upgrading')
    return bundle.replace(before, after, 1)

def main():
    for url, digest, files in PACKAGES:
        with urlopen(url,timeout=30) as f:
            data = f.read()
        if hashlib.sha512(data).digest() != base64.b64decode(digest):
            raise ValueError('terminal archive SHA-512 mismatch')
        with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as tar:
            for name, dest in files.items():
                content = tar.extractfile('package/'+name).read()
                if dest == 'xterm.js':
                    content = patch_xterm_selection(content)
                (ASSETS/'term'/dest).write_bytes(content)
    print('Verified terminal assets.')

if __name__=='__main__':
    main()
