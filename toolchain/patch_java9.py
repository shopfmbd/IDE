#!/usr/bin/env python3
"""Points every use of java.lang.Runtime.Version / Runtime.version() (Java 9, missing on Android) at cloudide.shim.RtVersion.

Why: the newest ECJ (and R8, apksig) call Runtime.version(). D8 cannot desugar it, so on a phone the dexed tool dies with
"NoSuchMethodError: No static method version()Ljava/lang/Runtime$Version;". This runs on the plain jar BEFORE D8.

How: it edits the class-file constant pool only, no bytecode is touched.
  1. every Utf8 text "java/lang/Runtime$Version" becomes "cloudide/shim/RtVersion" (class refs, descriptors, signatures)
  2. the one Methodref Runtime.version() gets the shim class as owner and the name current() (Java source cannot declare
     a static version() next to the instance version(); the class file format has no such limit but the shim is Java)
Classes that never mention Runtime$Version are copied byte for byte.

Usage: patch_java9.py in.jar out.jar        patch
       patch_java9.py --check file...       exit 1 if a .jar / .class / .dex still names java/lang/Runtime$Version
"""
import struct, sys, zipfile

OLD = b"java/lang/Runtime$Version"
NEW = b"cloudide/shim/RtVersion"
RUNTIME = b"java/lang/Runtime"


def drop_inner_entries(tail, cp, utf, shim):
    """The class-level InnerClasses attribute still says "RtVersion is member Version of java/lang/Runtime". D8 rebuilds the
    name java/lang/Runtime$Version from that, so remove the entries whose inner class is the shim."""
    if shim is None:
        return tail
    def skip_members(pos):
        n = struct.unpack(">H", tail[pos:pos + 2])[0]
        pos += 2
        for _ in range(n):                       # fields or methods: access, name, descriptor, attributes
            pos += 6
            na = struct.unpack(">H", tail[pos:pos + 2])[0]
            pos += 2
            for _ in range(na):
                pos += 2 + 4 + struct.unpack(">I", tail[pos + 2:pos + 6])[0]
        return pos
    pos = 6                                       # access, this, super
    pos += 2 + 2 * struct.unpack(">H", tail[pos:pos + 2])[0]   # interfaces
    pos = skip_members(pos)                       # fields
    pos = skip_members(pos)                       # methods
    na = struct.unpack(">H", tail[pos:pos + 2])[0]
    attrs_at = pos + 2
    pos = attrs_at
    out = bytearray(tail[:attrs_at])
    for _ in range(na):
        name_idx, length = struct.unpack(">HI", tail[pos:pos + 6])
        body = tail[pos + 6:pos + 6 + length]
        if utf(name_idx) == b"InnerClasses":
            count = struct.unpack(">H", body[:2])[0]
            keep = []
            for k in range(count):
                ent = body[2 + 8 * k:10 + 8 * k]
                if struct.unpack(">H", ent[:2])[0] != shim:
                    keep.append(ent)
            body = struct.pack(">H", len(keep)) + b"".join(keep)
        out += struct.pack(">HI", name_idx, len(body)) + body
        pos += 6 + length
    out += tail[pos:]
    return bytes(out)


def patch_class(data):
    if OLD not in data:
        return data, 0
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    cp = [None] * count            # (tag, payload bytes after the tag)
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            end = pos + 3 + n
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            end = pos + 5
        elif tag in (5, 6):
            end = pos + 9
        elif tag in (7, 8, 16, 19, 20):
            end = pos + 3
        elif tag == 15:
            end = pos + 4
        else:
            raise ValueError("bad constant pool tag %d" % tag)
        cp[i] = [tag, data[pos + 1:end]]
        pos = end
        i += 2 if tag in (5, 6) else 1
    tail = data[pos:]

    def utf(idx):
        e = cp[idx]
        return e[1][2:] if e and e[0] == 1 else None

    changed = 0
    # 1. rename the type everywhere it appears as text
    for e in cp:
        if e and e[0] == 1:
            text = e[1][2:]
            if OLD in text:
                text = text.replace(OLD, NEW)
                e[1] = struct.pack(">H", len(text)) + text
                changed += 1

    # find or add Class entry for the shim
    shim = None
    for idx, e in enumerate(cp):
        if e and e[0] == 7 and utf(struct.unpack(">H", e[1])[0]) == NEW:
            shim = idx
            break
    runtime_cls = set()
    for idx, e in enumerate(cp):
        if e and e[0] == 7 and utf(struct.unpack(">H", e[1])[0]) == RUNTIME:
            runtime_cls.add(idx)
    if shim is None and runtime_cls:
        u = None
        for idx, e in enumerate(cp):
            if e and e[0] == 1 and e[1][2:] == NEW:
                u = idx
                break
        if u is None:
            cp.append([1, struct.pack(">H", len(NEW)) + NEW])
            u = len(cp) - 1
        cp.append([7, struct.pack(">H", u)])
        shim = len(cp) - 1

    # 2. Runtime.version() -> RtVersion.current()
    cur_utf = None
    for e in list(cp):
        if e and e[0] == 10:       # Methodref
            ci, nt = struct.unpack(">HH", e[1])
            if ci in runtime_cls:
                ni, di = struct.unpack(">HH", cp[nt][1])
                if utf(ni) == b"version" and utf(di) == b"()L" + NEW + b";":
                    if cur_utf is None:
                        cp.append([1, struct.pack(">H", 7) + b"current"])
                        cur_utf = len(cp) - 1
                    cp.append([12, struct.pack(">HH", cur_utf, di)])
                    e[1] = struct.pack(">HH", shim, len(cp) - 1)
                    changed += 1
    tail = drop_inner_entries(tail, cp, utf, shim)
    out = bytearray(data[:8])
    out += struct.pack(">H", len(cp))
    for e in cp[1:]:
        if e is None:              # second slot of a long / double
            continue
        out.append(e[0])
        out += e[1]
    out += tail
    return bytes(out), changed


def patch_jar(src, dst):
    classes = patched = 0
    with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
        for info in zi.infolist():
            data = zi.read(info.filename)
            if info.filename.endswith(".class"):
                classes += 1
                data, n = patch_class(data)
                if n:
                    patched += 1
            zo.writestr(info, data)
    print("%s: %d classes, %d patched" % (src, classes, patched))
    return patched


def has_old(blob):
    return OLD in blob or b"Ljava/lang/Runtime$Version;" in blob


def context(blob):
    i = blob.find(OLD)
    if i < 0:
        i = blob.find(b"Runtime$Version")
    lo = max(0, i - 70)
    return repr(blob[lo:i + 70]) + " at offset %d" % i


def check(paths):
    bad = 0
    for p in paths:
        if p.endswith(".class") or p.endswith(".dex"):
            if has_old(open(p, "rb").read()):
                print("STILL REFERENCES Runtime$Version:", p)
                print("  around:", context(open(p, "rb").read()))
                bad += 1
            continue
        with zipfile.ZipFile(p) as z:
            for n in z.namelist():
                if (n.endswith(".class") or n.endswith(".dex")) and has_old(z.read(n)):
                    print("STILL REFERENCES Runtime$Version: %s!%s" % (p, n))
                    print("  around:", context(z.read(n)))
                    bad += 1
    if not bad:
        print("ok: no reference to java.lang.Runtime$Version left")
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--check":
        sys.exit(check(sys.argv[2:]))
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    patch_jar(sys.argv[1], sys.argv[2])
