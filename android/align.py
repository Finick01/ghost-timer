# Adds classes.dex to the aapt2 output and 4-byte aligns every stored entry (what zipalign does).
import sys, zipfile
src, dex, dst = sys.argv[1:4]
zin = zipfile.ZipFile(src)
entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
entries.append((zipfile.ZipInfo('classes.dex', date_time=(2008,1,1,0,0,0)), open(dex,'rb').read()))
with zipfile.ZipFile(dst, 'w') as zout:
    for info, data in entries:
        zi = zipfile.ZipInfo(info.filename, date_time=(2008,1,1,0,0,0))
        stored = info.filename == 'resources.arsc' or info.filename.endswith('.png') or (hasattr(info,'compress_type') and info.compress_type == zipfile.ZIP_STORED and info.filename != 'classes.dex')
        zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        if stored:
            # local header = 30 + len(name) + len(extra); pad extra so data starts on a 4-byte boundary
            off = zout.fp.tell() + 30 + len(zi.filename.encode())
            pad = (-off) % 4
            zi.extra = b'\x00' * pad
        zout.writestr(zi, data)
print('aligned', dst)
