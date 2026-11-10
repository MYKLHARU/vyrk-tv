#!/usr/bin/env python3
"""Install a checksum-pinned Linux test tool without relying on apt mirrors."""
import argparse
import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

URL = ('https://files.pythonhosted.org/packages/de/0f/'
       '49292fbef69ee10ebcf97bb741602521caafcc6966d5fa1a2b868cc15655/'
       'imageio_ffmpeg-0.8.1-py3-none-manylinux_2_28_x86_64.whl')
# Published by PyPI for the immutable imageio-ffmpeg 0.8.1 Linux x86-64 wheel.
SHA256 = '050d6850601c3b3890bd2b28306f3b842b3daea390a561101174375a1c460bda'

p = argparse.ArgumentParser()
p.add_argument('--directory', type=Path, required=True)
a = p.parse_args()
with urllib.request.urlopen(URL, timeout=45) as response:
    wheel = response.read()
if hashlib.sha256(wheel).hexdigest() != SHA256:
    raise RuntimeError('Test tool checksum mismatch')
with zipfile.ZipFile(io.BytesIO(wheel)) as z:
    binaries = [n for n in z.namelist() if '/binaries/' in n and Path(n).name.startswith('ffmpeg-linux')]
    if len(binaries) != 1:
        raise RuntimeError('Expected one Linux ffmpeg executable')
    binary = z.read(binaries[0])
if binary[:4] != b'\x7fELF' or binary[18:20] != b'\x3e\x00':
    raise RuntimeError('Expected an x86-64 ELF executable')
a.directory.mkdir(parents=True, exist_ok=True)
target = a.directory / 'ffmpeg'
target.write_bytes(binary)
target.chmod(0o755)
print(f'Installed verified test ffmpeg: {target}')
