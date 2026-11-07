#!/usr/bin/env python3
"""Lists classes (any package) that the compiler jar refers to but that no bundled jar, shim or android.jar defines.

A missing one shows up on the phone as NoClassDefFoundError, one at a time. This lists them all, grouped by package, so a
missing dependency (for example trove4j: gnu/trove/...) is seen in the build log. Informational, never fails.

Usage: scan_missing.py android.jar target.jar [other.jar ...]
"""
import sys, zipfile, struct, collections
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from patch_platform import parse_pool


def defined(path):
    with zipfile.ZipFile(path) as z:
        return {n[:-6] for n in z.namelist() if n.endswith(".class")}


def refs(blob):
    cp, _ = parse_pool(blob)
    utf = {i: e[1][2:] for i, e in enumerate(cp) if e and e[0] == 1}
    out = set()
    for e in cp:
        if e and e[0] == 7:
            n = utf.get(struct.unpack(">H", e[1])[0], b"").decode("utf-8", "replace")
            n = n.lstrip("[")
            if n.startswith("L") and n.endswith(";"):
                n = n[1:-1]
            if "/" in n and not n.startswith("["):
                out.add(n)
    return out


def main(android, target, others):
    have = set()
    for p in [android, target] + others:
        have |= defined(p)
    missing = collections.defaultdict(set)
    with zipfile.ZipFile(target) as z:
        for n in z.namelist():
            if not n.endswith(".class") or n.startswith("META-INF/versions/"):
                continue
            try:
                r = refs(z.read(n))
            except Exception:
                continue
            for c in r:
                if c not in have and not c.startswith(("java/", "javax/", "sun/", "jdk/", "android/", "dalvik/", "libcore/", "org/w3c/", "org/xml/", "org/ietf/")):
                    missing[c].add(n)
    groups = collections.Counter()
    for c in missing:
        groups["/".join(c.split("/")[:3])] += 1
    print("scan_missing: %d referenced classes are defined nowhere" % len(missing))
    for g, k in groups.most_common(40):
        ex = next(c for c in sorted(missing) if c.startswith(g + "/") or c == g)
        print("  %-50s %4d class(es), e.g. %s" % (g, k, ex))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
