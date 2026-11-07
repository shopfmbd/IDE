#!/usr/bin/env python3
"""Points platform packages Android lacks at stand-ins that ship with the toolchain.

Why: kotlinc calls java.lang.management.ManagementFactory (and com.sun.management.*). Android has neither. A dexed jar
cannot carry classes named java.*, so the classes cannot simply be added; the references are renamed instead. This runs
on the plain jars BEFORE D8, like patch_java9.py.

How: class-file constant pool only (Utf8 text), no bytecode is touched.
    java/lang/management/  ->  cloudide/shim/mgmt/       (toolchain/src/cloudide/shim/mgmt)
    com/sun/management/    ->  cloudide/shim/sunmgmt/    (toolchain/src/cloudide/shim/sunmgmt)
Class names, descriptors and generic signatures are all Utf8 entries, so one text replacement covers them.
Classes that never mention the old names are copied byte for byte.

Usage: patch_platform.py in.jar out.jar          patch
       patch_platform.py --check file...         exit 1 if a .jar / .class / .dex still names a renamed package
       patch_platform.py --scan android.jar target.jar [provided.jar ...]
                                                 list java/ and javax/ classes that target.jar uses but neither
                                                 android.jar, target.jar nor the provided jars contain (never fails)
"""
import re, struct, sys, zipfile

RULES = [
    (b"java/lang/management/", b"cloudide/shim/mgmt/"),
    (b"com/sun/management/", b"cloudide/shim/sunmgmt/"),
]

# Applied only to IntelliJ's own Unsafe wrapper inside kotlinc (not to guava/protobuf, which have their own fallbacks).
# The wrapper reflects on sun.misc.Unsafe.getAndAddInt, which Android lacks or hides; cloudide.shim.Unsafe provides it.
# Both the slash form (class refs, descriptors) and the dotted form (Class.forName strings) are renamed.
SCOPED_PREFIXES = ("org/jetbrains/kotlin/com/intellij/util/containers/Unsafe",)
# kotlinc's fast jar file system (FastJarFileSystem) calls sun.misc.Unsafe.invokeCleaner to unmap memory-mapped jars. Android has
# no such method, so kotlinc printed "your JDK doesn't seem to support mapped buffer unmapping" and read jars the slow way.
# cloudide.shim.Unsafe.invokeCleaner exists (a no-op: Android's GC releases mapped buffers), so the fast file system works.
SCOPED_NAME_PARTS = ("/FastJar",)
SCOPED_RULES = [
    (b"sun/misc/Unsafe", b"cloudide/shim/Unsafe"),
    (b"sun.misc.Unsafe", b"cloudide.shim.Unsafe"),
    (b"jdk/internal/misc/Unsafe", b"cloudide/shim/Unsafe"),
    (b"jdk.internal.misc.Unsafe", b"cloudide.shim.Unsafe"),
]


def scoped(name):
    return name.startswith(SCOPED_PREFIXES) or any(p in name for p in SCOPED_NAME_PARTS)


def rules_for(name):
    return RULES + SCOPED_RULES if scoped(name) else RULES


def parse_pool(data):
    """Returns (entries, end). entries[i] = [tag, payload bytes after the tag] or None for slot 0 / the 2nd slot of long+double."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    cp = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            end = pos + 3 + struct.unpack(">H", data[pos + 1:pos + 3])[0]
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
    return cp, pos


def patch_class(data, rules=None):
    rules = RULES if rules is None else rules
    if not any(old in data for old, _ in rules):
        return data, 0
    cp, end = parse_pool(data)
    changed = 0
    for e in cp:
        if e and e[0] == 1:
            text = e[1][2:]
            new = text
            for old, rep in rules:
                new = new.replace(old, rep)
            if new != text:
                e[1] = struct.pack(">H", len(new)) + new
                changed += 1
    if not changed:
        return data, 0
    out = bytearray(data[:8])
    out += struct.pack(">H", len(cp))
    for e in cp[1:]:
        if e is None:
            continue
        out.append(e[0])
        out += e[1]
    out += data[end:]
    return bytes(out), changed


def patch_jar(src, dst):
    classes = patched = 0
    with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
        for info in zi.infolist():
            data = zi.read(info.filename)
            if info.filename.endswith(".class"):
                classes += 1
                data, n = patch_class(data, rules_for(info.filename))
                if n:
                    patched += 1
                    if scoped(info.filename):
                        print("  Unsafe stand-in applied to %s" % info.filename)
            zo.writestr(info, data)
    print("%s: %d classes, %d patched" % (src, classes, patched))
    return patched


def stale(blob):
    return [old for old, _ in RULES if old in blob]


def check(paths):
    bad = 0
    for p in paths:
        if p.endswith(".class") or p.endswith(".dex"):
            blobs = [(p, open(p, "rb").read())]
        else:
            with zipfile.ZipFile(p) as z:
                blobs = [("%s!%s" % (p, n), z.read(n)) for n in z.namelist() if n.endswith(".class") or n.endswith(".dex")]
        for name, blob in blobs:
            s = stale(blob)
            if s:
                print("STILL REFERENCES %s: %s" % (b", ".join(s).decode(), name))
                bad += 1
    if not bad:
        print("ok: no reference to java/lang/management or com/sun/management left")
    return 1 if bad else 0


DESC = re.compile(rb"L((?:java|javax)/[A-Za-z0-9_/$]+);")


def class_names(blob):
    """Every java/ and javax/ type a class file mentions: Class entries plus L...; inside descriptors and signatures."""
    cp, _ = parse_pool(blob)
    utf = {i: e[1][2:] for i, e in enumerate(cp) if e and e[0] == 1}
    found = set()
    for e in cp:
        if e and e[0] == 7:
            n = utf.get(struct.unpack(">H", e[1])[0], b"")
            n = n.lstrip(b"[")
            if n.startswith(b"L") and n.endswith(b";"):
                n = n[1:-1]
            if n.startswith((b"java/", b"javax/")):
                found.add(n.decode())
    for t in utf.values():
        for m in DESC.finditer(t):
            found.add(m.group(1).decode())
    return found


def scan(android, target, provided):
    have = set()
    for p in [android] + list(provided):
        with zipfile.ZipFile(p) as z:
            have.update(n[:-6] for n in z.namelist() if n.endswith(".class"))
    missing = {}
    with zipfile.ZipFile(target) as z:
        classes = [n for n in z.namelist() if n.endswith(".class") and not n.startswith("META-INF/versions/")]
        have.update(n[:-6] for n in classes)
        for n in classes:
            try:
                names = class_names(z.read(n))
            except ValueError:
                continue
            for c in names:
                if c not in have:
                    missing.setdefault(c, []).append(n[:-6])
    if not missing:
        print("scan: every java/ and javax/ class used by %s exists in android.jar or the bundle" % target)
        return
    print("scan: %d java/ or javax/ classes are used by %s but exist nowhere (android.jar lacks them; not every one is loaded at run time):" % (len(missing), target))
    for c in sorted(missing):
        users = missing[c]
        print("  %-60s %4d user(s), e.g. %s" % (c.replace("/", "."), len(users), users[0].replace("/", ".")))


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--check":
        sys.exit(check(sys.argv[2:]))
    if len(sys.argv) >= 4 and sys.argv[1] == "--scan":
        scan(sys.argv[2], sys.argv[3], sys.argv[4:])
        sys.exit(0)
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    patch_jar(sys.argv[1], sys.argv[2])
