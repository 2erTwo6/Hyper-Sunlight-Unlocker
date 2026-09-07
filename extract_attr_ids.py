#!/usr/bin/env python3
"""Parse framework-res.apk's AndroidManifest.xml string pool + resource map
to extract attr-name -> framework attr resource id mapping."""
import struct, sys, zipfile

def parse(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        xml = z.read('AndroidManifest.xml')
    off = 8
    pool = None
    resmap = None
    while off < len(xml):
        ctype, hsize, size = struct.unpack_from('<HHI', xml, off)
        if ctype == 0x0001:  # string pool
            scount, stylecnt, sflags, sstart, sstyles = struct.unpack_from('<IIIII', xml, off+8)
            utf8 = sflags & 0x100
            str_offs = [struct.unpack_from('<I', xml, off+28+4*i)[0] for i in range(scount)]
            pool = []
            for so in str_offs:
                base = off + sstart + so
                if utf8:
                    n = xml[base]; base += 1
                    if n & 0x80: n = ((n & 0x7f) << 8) | xml[base]; base += 1
                    n2 = xml[base]; base += 1
                    if n2 & 0x80: n2 = ((n2 & 0x7f) << 8) | xml[base]; base += 1
                    s = xml[base:base+n2].decode('utf-8')
                else:
                    n = struct.unpack_from('<H', xml, base)[0]; base += 2
                    if n & 0x8000:
                        n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', xml, base)[0]; base += 2
                    s = xml[base:base+2*n].decode('utf-16-le')
                pool.append(s)
        elif ctype == 0x0180:  # resource map
            n = (size - 8) // 4
            resmap = list(struct.unpack_from('<%dI' % n, xml, off+8))
        off += size
    return pool, resmap

pool, resmap = parse(sys.argv[1])
d = {}
for i, rid in enumerate(resmap):
    if rid and i < len(pool):
        d[pool[i]] = rid
need = ['package','name','value','label','hasCode','minSdkVersion','targetSdkVersion','versionCode','versionName','extractNativeLibs','debuggable']
for k in need:
    print(k, hex(d[k]) if k in d else 'MISSING')
print('---total attrs:', len(d))
