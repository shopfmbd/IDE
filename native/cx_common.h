/*
 * cx_common.h - shared by libexec_hook.so (LD_PRELOAD) and libcloudexec.so (launcher).
 *
 * Decides how to start a file when plain exec() is refused (Android 10+, targetSdk >= 29, files in app storage):
 *   dynamic ELF in an allowed root  -> /system/bin/linker64 <file> args...
 *   static ELF / static-PIE         -> <launcher> --static <file> args...   (userland loader, experimental)
 *   "#!" script                     -> interpreter resolved, then the rules above (up to 4 levels)
 *   anything else                   -> left alone
 *
 * Everything here is async-signal-safe (no malloc, no stdio): it runs in the child between fork and exec.
 */
#ifndef CX_COMMON_H
#define CX_COMMON_H
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <elf.h>

extern char **environ;

#define CX_PATH 4096
#define CX_MAXDEPTH 4

#ifdef __ANDROID__
#define CX_DEFAULT_LINKER64 "/system/bin/linker64"
#define CX_DEFAULT_LINKER32 "/system/bin/linker"
#else /* host test build: glibc's ld.so behaves like linker64 (ld.so prog args) */
#define CX_DEFAULT_LINKER64 "/lib64/ld-linux-x86-64.so.2"
#define CX_DEFAULT_LINKER32 "/lib/ld-linux.so.2"
#endif

/* ---------- tiny libc replacements ---------- */
static inline size_t cx_len(const char *s) { size_t n = 0; while (s[n]) n++; return n; }
static inline int cx_eq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
static inline int cx_starts(const char *s, const char *p) { while (*p) { if (*s++ != *p++) return 0; } return 1; }
static inline char *cx_cpy(char *d, const char *s) { while ((*d = *s++)) d++; return d; }
static inline void cx_say(const char *a, const char *b, const char *c) {
    char buf[CX_PATH + 256]; char *p = buf;
    p = cx_cpy(p, "cloudexec: "); if (a) p = cx_cpy(p, a); if (b) p = cx_cpy(p, b); if (c) p = cx_cpy(p, c);
    *p++ = '\n';
    ssize_t r = write(2, buf, (size_t)(p - buf)); (void)r;
}

/* ---------- environment ---------- */
static inline const char *cx_env_in(char *const *envp, const char *name) {
    size_t n = cx_len(name);
    if (!envp) return NULL;
    for (; *envp; envp++) if (strncmp(*envp, name, n) == 0 && (*envp)[n] == '=') return *envp + n + 1;
    return NULL;
}
/* settings come from the env the child will get, else from this process */
static inline const char *cx_cfg(char *const *envp, const char *name) {
    const char *v = cx_env_in(envp, name);
    if (!v) v = cx_env_in(environ, name);
    return v;
}

/* ---------- arena (mmap, never malloc) ---------- */
typedef struct { char *base, *cur, *end; } cx_arena;
static inline int cx_arena_init(cx_arena *a, size_t bytes) {
    bytes = (bytes + 4095) & ~(size_t)4095;
    void *m = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (m == MAP_FAILED) return -1;
    a->base = a->cur = (char *)m; a->end = a->base + bytes;
    return 0;
}
static inline void cx_arena_free(cx_arena *a) { if (a->base) munmap(a->base, (size_t)(a->end - a->base)); a->base = NULL; }
static inline void *cx_alloc(cx_arena *a, size_t n) {
    n = (n + 15) & ~(size_t)15;
    if ((size_t)(a->end - a->cur) < n) return NULL;
    void *p = a->cur; a->cur += n; return p;
}
static inline char *cx_strdup(cx_arena *a, const char *s) {
    size_t n = cx_len(s) + 1; char *d = (char *)cx_alloc(a, n);
    if (d) memcpy(d, s, n);
    return d;
}

/* ---------- paths ---------- */
/* absolute + lexically normalized (no symlink resolution). Returns 0 or -1. */
static inline int cx_abs(const char *in, char *out) {
    char tmp[CX_PATH * 2]; size_t n = 0;
    if (in[0] != '/') {
        long r = syscall(__NR_getcwd, tmp, CX_PATH);
        if (r <= 0) return -1;
        n = cx_len(tmp);
        if (n == 0 || tmp[n - 1] != '/') tmp[n++] = '/';
    }
    if (n + cx_len(in) + 1 >= sizeof tmp) return -1;
    cx_cpy(tmp + n, in);
    char *o = out; *o++ = '/';
    const char *p = tmp;
    while (*p) {
        while (*p == '/') p++;
        const char *s = p;
        while (*p && *p != '/') p++;
        size_t l = (size_t)(p - s);
        if (l == 0 || (l == 1 && s[0] == '.')) continue;
        if (l == 2 && s[0] == '.' && s[1] == '.') {
            if (o > out + 1) { o--; while (o > out + 1 && o[-1] != '/') o--; if (o > out + 1) {} }
            continue;
        }
        if ((size_t)(o - out) + l + 2 >= CX_PATH) return -1;
        if (o > out + 1 && o[-1] != '/') *o++ = '/';
        memcpy(o, s, l); o += l;
    }
    if (o > out + 1 && o[-1] == '/') o--;
    *o = 0;
    return 0;
}

/* full symlink resolution (async-signal-safe realpath). Returns 0 or -1. */
static inline int cx_canon(const char *in, char *out) {
    char cur[CX_PATH], link[CX_PATH];
    if (cx_abs(in, cur) < 0) return -1;
    for (int hops = 0; hops < 40; hops++) {
        char acc[CX_PATH]; size_t al = 0; int changed = 0;
        const char *p = cur;
        acc[0] = 0;
        while (*p) {
            while (*p == '/') p++;
            if (!*p) break;
            const char *s = p; while (*p && *p != '/') p++;
            size_t l = (size_t)(p - s);
            if (al + l + 2 >= CX_PATH) return -1;
            acc[al++] = '/'; memcpy(acc + al, s, l); al += l; acc[al] = 0;
            long r = syscall(__NR_readlinkat, AT_FDCWD, acc, link, CX_PATH - 1);
            if (r > 0) {
                link[r] = 0;
                /* new = (link absolute ? link : dirname(acc)/link) + rest */
                char nw[CX_PATH * 2]; char *q = nw;
                if (link[0] == '/') q = cx_cpy(q, link);
                else {
                    size_t d = al; while (d > 0 && acc[d - 1] != '/') d--;
                    if (d > 1) d--; /* drop slash, keep "/" for root */
                    memcpy(q, acc, d); q += d; *q++ = '/'; q = cx_cpy(q, link);
                }
                q = cx_cpy(q, p);
                if (cx_abs(nw, cur) < 0) return -1;
                changed = 1; break;
            }
        }
        if (!changed) { if (al == 0) { out[0] = '/'; out[1] = 0; } else cx_cpy(out, acc); return 0; }
    }
    return -1; /* too many links */
}

static inline int cx_under(const char *path, const char *root) {
    size_t n = cx_len(root);
    while (n > 1 && root[n - 1] == '/') n--;
    if (n == 0) return 0;
    if (strncmp(path, root, n) != 0) return 0;
    return path[n] == 0 || path[n] == '/' || n == 1;
}

/* is canonical path inside one of the ':'-separated roots (each root canonicalised too)? */
static inline int cx_in_roots(const char *canon, char *const *envp) {
    const char *r = cx_cfg(envp, "CLOUDIDE_EXEC_ROOTS");
    if (!r) r = cx_cfg(envp, "CLOUDIDE_PREFIX");
    if (!r) return 0;
    while (*r) {
        char one[CX_PATH], cr[CX_PATH]; size_t n = 0;
        while (*r && *r != ':' && n < CX_PATH - 1) one[n++] = *r++;
        one[n] = 0;
        if (*r == ':') r++;
        if (n == 0 || one[0] != '/') continue;
        if (cx_under(canon, one)) return 1;
        if (cx_canon(one, cr) == 0 && cx_under(canon, cr)) return 1;
    }
    return 0;
}

/* ---------- file sniffing ---------- */
enum { CX_NONE = 0, CX_ELF_DYN, CX_ELF_STATIC, CX_ELF32, CX_SCRIPT, CX_OTHER };

typedef struct {
    int kind;
    char interp[CX_PATH]; /* script: interpreter as written */
    char iarg[CX_PATH];   /* script: optional single argument */
} cx_info;

static inline int cx_sniff(const char *path, cx_info *ni) {
    ni->kind = CX_NONE; ni->interp[0] = ni->iarg[0] = 0;
    int fd = (int)syscall(__NR_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) < 0 || !S_ISREG(st.st_mode)) { close(fd); errno = S_ISDIR(st.st_mode) ? EISDIR : EACCES; return -1; }
    unsigned char b[1024];
    ssize_t n = pread(fd, b, sizeof b, 0);
    if (n < 4) { close(fd); ni->kind = CX_OTHER; return 0; }
    if (b[0] == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F') {
        if (b[EI_CLASS] == ELFCLASS32) { close(fd); ni->kind = CX_ELF32; return 0; }
        Elf64_Ehdr eh;
        if (n < (ssize_t)sizeof eh) { close(fd); ni->kind = CX_OTHER; return 0; }
        memcpy(&eh, b, sizeof eh);
        int dyn = 0;
        if ((eh.e_type == ET_DYN || eh.e_type == ET_EXEC) && eh.e_phentsize == sizeof(Elf64_Phdr) && eh.e_phnum < 64) {
            Elf64_Phdr ph[64];
            size_t want = (size_t)eh.e_phnum * sizeof(Elf64_Phdr);
            if (pread(fd, ph, want, (off_t)eh.e_phoff) == (ssize_t)want)
                for (int i = 0; i < eh.e_phnum; i++) if (ph[i].p_type == PT_INTERP) dyn = 1;
        }
        close(fd);
        ni->kind = dyn ? CX_ELF_DYN : CX_ELF_STATIC; /* no PT_INTERP = static (ET_EXEC or static-PIE): linker64 cannot load it */
        return 0;
    }
    close(fd);
    if (b[0] == '#' && b[1] == '!') {
        ssize_t i = 2, e = 2;
        while (e < n && b[e] != '\n' && b[e] != '\r') e++;
        while (i < e && (b[i] == ' ' || b[i] == '\t')) i++;
        size_t k = 0;
        while (i < e && b[i] != ' ' && b[i] != '\t' && k < CX_PATH - 1) ni->interp[k++] = (char)b[i++];
        ni->interp[k] = 0;
        while (i < e && (b[i] == ' ' || b[i] == '\t')) i++;
        k = 0;
        while (i < e && k < CX_PATH - 1) ni->iarg[k++] = (char)b[i++];
        while (k > 0 && (ni->iarg[k - 1] == ' ' || ni->iarg[k - 1] == '\t')) k--;
        ni->iarg[k] = 0;
        ni->kind = ni->interp[0] ? CX_SCRIPT : CX_OTHER;
        return 0;
    }
    ni->kind = CX_OTHER;
    return 0;
}

static inline int cx_isreg(const char *p) {
    struct stat st; return stat(p, &st) == 0 && S_ISREG(st.st_mode);
}

/* search PATH (colon list) for a regular file. in_roots files need no x bit (SELinux, not DAC, is what blocks them). */
static inline int cx_search_path(const char *name, const char *pathlist, char *const *envp, char *out) {
    if (!pathlist) pathlist = "/system/bin";
    size_t nl = cx_len(name);
    const char *p = pathlist;
    for (;;) {
        char dir[CX_PATH]; size_t dl = 0;
        while (*p && *p != ':' && dl < CX_PATH - 1) dir[dl++] = *p++;
        dir[dl] = 0;
        if (dl == 0) { dir[0] = '.'; dir[1] = 0; dl = 1; }
        if (dl + nl + 2 < CX_PATH) {
            char cand[CX_PATH], can2[CX_PATH]; char *q = cand;
            q = cx_cpy(q, dir); if (q[-1] != '/') *q++ = '/'; cx_cpy(q, name);
            struct stat st;
            if (stat(cand, &st) == 0 && S_ISREG(st.st_mode)) {
                int ok = (st.st_mode & 0111) != 0;
                if (!ok && cx_canon(cand, can2) == 0 && cx_in_roots(can2, envp)) ok = 1;
                if (ok) { cx_cpy(out, cand); return 0; }
            }
        }
        if (!*p) break;
        p++;
    }
    return -1;
}

/* interpreter named by a shebang may not exist on Android (/usr/bin/env, /bin/sh, /usr/bin/python3 ...) */
static inline int cx_map_interp(const char *interp, char *const *envp, char *out) {
    if (cx_isreg(interp)) { cx_cpy(out, interp); return 0; }
    const char *prefix = cx_cfg(envp, "CLOUDIDE_PREFIX");
    const char *base = interp; for (const char *s = interp; *s; s++) if (*s == '/') base = s + 1;
    if (!*base) return -1;
    if (prefix) {
        char c[CX_PATH]; char *q = cx_cpy(c, prefix); q = cx_cpy(q, "/bin/"); cx_cpy(q, base);
        if (cx_isreg(c)) { cx_cpy(out, c); return 0; }
    }
    { char c[CX_PATH]; char *q = cx_cpy(c, "/system/bin/"); cx_cpy(q, base); if (cx_isreg(c)) { cx_cpy(out, c); return 0; } }
    return -1;
}

/* ---------- the plan ---------- */
typedef struct {
    const char *path;   /* what to execve */
    char **argv;
    char **envp;
    int rewritten;
} cx_plan;

static inline const char *cx_linker_for(int kind, char *const *envp) {
    const char *l = cx_cfg(envp, "CLOUDIDE_LINKER");
    if (l && *l) return l;
    return kind == CX_ELF32 ? CX_DEFAULT_LINKER32 : CX_DEFAULT_LINKER64;
}

static inline int cx_argc(char *const *argv) { int n = 0; if (argv) while (argv[n]) n++; return n; }

/* env copy with LD_PRELOAD guaranteed (and CLOUDIDE_SELF_EXE set or removed) */
static inline char **cx_make_env(cx_arena *a, char *const *envp, const char *self_exe) {
    const char *pre = cx_cfg(envp, "CLOUDIDE_PRELOAD");
    int ec = cx_argc(envp);
    char **ne = (char **)cx_alloc(a, (size_t)(ec + 4) * sizeof(char *));
    if (!ne) return NULL;
    int k = 0, have_pre = 0;
    for (int i = 0; i < ec; i++) {
        const char *e = envp[i];
        if (strncmp(e, "CLOUDIDE_SELF_EXE=", 18) == 0) continue;
        if (pre && strncmp(e, "LD_PRELOAD=", 11) == 0) {
            if (strstr(e + 11, pre)) { have_pre = 1; ne[k++] = (char *)e; }
            else {
                size_t l = cx_len(pre) + cx_len(e) + 2; char *s = (char *)cx_alloc(a, l);
                if (!s) return NULL;
                char *q = cx_cpy(s, "LD_PRELOAD="); q = cx_cpy(q, pre); *q++ = ':'; cx_cpy(q, e + 11);
                ne[k++] = s; have_pre = 1;
            }
            continue;
        }
        ne[k++] = (char *)e;
    }
    if (pre && !have_pre) {
        char *s = (char *)cx_alloc(a, cx_len(pre) + 16);
        if (!s) return NULL;
        char *q = cx_cpy(s, "LD_PRELOAD="); cx_cpy(q, pre); ne[k++] = s;
    }
    if (self_exe) {
        char *s = (char *)cx_alloc(a, cx_len(self_exe) + 24);
        if (!s) return NULL;
        char *q = cx_cpy(s, "CLOUDIDE_SELF_EXE="); cx_cpy(q, self_exe); ne[k++] = s;
    }
    ne[k] = NULL;
    return ne;
}

static inline int cx_debug(char *const *envp) { const char *d = cx_cfg(envp, "CLOUDIDE_EXEC_DEBUG"); return d && d[0] == '1'; }

/*
 * Returns 1 and fills *pl when the call must be rewritten, 0 when it should go through unchanged
 * (the caller still passes pl->envp = original). Returns -1 with errno set on a hard error (ENOENT, ...).
 * force: also rewrite files outside the roots (used after a plain exec failed with EACCES).
 */
static inline int cx_make_plan(cx_arena *a, const char *path, char *const *argv, char *const *envp, int force, cx_plan *pl) {
    pl->path = path; pl->argv = (char **)argv; pl->envp = (char **)envp; pl->rewritten = 0;
    const char *off = cx_cfg(envp, "CLOUDIDE_EXEC_DISABLE");
    if (off && off[0] == '1') return 0;
    if (!path || !*path) return 0;

    char cur[CX_PATH], canon[CX_PATH];
    if (cx_abs(path, cur) < 0 || cx_canon(path, canon) < 0) return 0;
    int in_roots = cx_in_roots(canon, envp);
    if (!in_roots && !force) return 0;

    int argc = cx_argc(argv);
    /* working argv: new array, at most CX_MAXDEPTH*2 extra entries */
    char **cv = (char **)cx_alloc(a, (size_t)(argc + 2 * CX_MAXDEPTH + 8) * sizeof(char *));
    if (!cv) return 0;
    /* cv[0..n) = argv for the program we are about to start; cv[0] is the program path */
    int n = 0;
    cv[n++] = cx_strdup(a, cur);
    for (int i = 1; i < argc; i++) cv[n++] = argv[i];
    if (argc == 0) { /* no argv at all: still give the program a name */ }

    char prog[CX_PATH]; cx_cpy(prog, cur);
    for (int depth = 0; depth <= CX_MAXDEPTH; depth++) {
        cx_info ni;
        if (cx_sniff(prog, &ni) < 0) return (in_roots ? -1 : 0);
        if (ni.kind == CX_SCRIPT) {
            if (depth == CX_MAXDEPTH) { errno = ELOOP; return -1; }
            char ip[CX_PATH], ipabs[CX_PATH];
            const char *want = ni.interp; char iarg[CX_PATH]; cx_cpy(iarg, ni.iarg);
            int has_iarg = iarg[0] != 0;
            const char *wb = want; for (const char *s = want; *s; s++) if (*s == '/') wb = s + 1;
            int is_env = cx_eq(wb, "env");
            if (is_env && !cx_isreg(want)) {
                /* #!/usr/bin/env [-S] prog [args]: resolve prog through PATH, no env binary needed */
                char *s = iarg; int split = 0;
                for (;;) {
                    while (*s == ' ') s++;
                    if (s[0] == '-' && s[1] == 'S' && (s[2] == ' ' || s[2] == 0)) { s += 2; split = 1; continue; }
                    if (s[0] == '-' && s[1] == 'i' && (s[2] == ' ' || s[2] == 0)) { s += 2; continue; }
                    break;
                }
                if (!*s) { errno = ENOENT; return -1; }
                char name[CX_PATH]; size_t k = 0;
                while (*s && *s != ' ' && k < CX_PATH - 1) name[k++] = *s++;
                name[k] = 0;
                while (*s == ' ') s++;
                char rest[CX_PATH]; cx_cpy(rest, s);
                if (name[0] == '/' ? cx_map_interp(name, envp, ip) != 0 :
                    cx_search_path(name, cx_cfg(envp, "PATH"), envp, ip) != 0) { errno = ENOENT; return -1; }
                /* argv for the interpreter: [ip, rest(split if -S), script, args...] */
                int extra = (rest[0] ? (split ? 8 : 1) : 0);
                char **nv = (char **)cx_alloc(a, (size_t)(n + extra + 4) * sizeof(char *));
                if (!nv) return 0;
                int m = 0;
                nv[m++] = cx_strdup(a, ip);
                if (rest[0]) {
                    if (split) {
                        char *r = cx_strdup(a, rest);
                        while (*r && m < n + extra) { while (*r == ' ') r++; if (!*r) break; nv[m++] = r; while (*r && *r != ' ') r++; if (*r) *r++ = 0; }
                    } else nv[m++] = cx_strdup(a, rest);
                }
                nv[m++] = cx_strdup(a, prog);
                for (int i = 1; i < n; i++) nv[m++] = cv[i];
                cv = nv; n = m;
                cx_cpy(prog, ip);
                continue;
            }
            if (cx_map_interp(want, envp, ip) != 0) { errno = ENOENT; return -1; }
            if (cx_abs(ip, ipabs) < 0) { errno = ENAMETOOLONG; return -1; }
            char **nv = (char **)cx_alloc(a, (size_t)(n + 4) * sizeof(char *));
            if (!nv) return 0;
            int m = 0;
            nv[m++] = cx_strdup(a, ipabs);
            if (has_iarg) nv[m++] = cx_strdup(a, iarg);
            nv[m++] = cx_strdup(a, prog);
            for (int i = 1; i < n; i++) nv[m++] = cv[i];
            cv = nv; n = m;
            cx_cpy(prog, ipabs);
            continue;
        }
        /* prog is the real program now */
        char pcanon[CX_PATH];
        int p_in = (cx_canon(prog, pcanon) == 0) && cx_in_roots(pcanon, envp);
        if (ni.kind == CX_ELF_DYN || ni.kind == CX_ELF32) {
            if (!p_in && !force) { /* interpreter outside our roots (e.g. /system/bin/sh): run it normally */
                if (depth == 0) return 0;
                cv[0] = cx_strdup(a, prog); cv[n] = NULL;
                pl->path = cv[0]; pl->argv = cv;
                pl->envp = cx_make_env(a, envp, NULL);
                pl->rewritten = 1;
                return pl->envp ? 1 : 0;
            }
            const char *ld = cx_linker_for(ni.kind, envp);
            char **nv = (char **)cx_alloc(a, (size_t)(n + 3) * sizeof(char *));
            if (!nv) return 0;
            int m = 0;
            nv[m++] = (char *)ld;
            nv[m++] = cx_strdup(a, prog);
            for (int i = 1; i < n; i++) nv[m++] = cv[i];
            nv[m] = NULL;
            pl->path = ld; pl->argv = nv; pl->rewritten = 1;
            pl->envp = cx_make_env(a, envp, prog);
            return pl->envp ? 1 : 0;
        }
        if (ni.kind == CX_ELF_STATIC) {
            const char *ln = cx_cfg(envp, "CLOUDIDE_LAUNCHER");
            if (!ln || !*ln || (!p_in && !force)) { if (depth == 0) return 0; }
            if (!ln || !*ln) { errno = ENOEXEC; return -1; }
            char **nv = (char **)cx_alloc(a, (size_t)(n + 4) * sizeof(char *));
            if (!nv) return 0;
            int m = 0;
            nv[m++] = (char *)ln;
            nv[m++] = (char *)"--static";
            nv[m++] = cx_strdup(a, prog);
            for (int i = 1; i < n; i++) nv[m++] = cv[i];
            nv[m] = NULL;
            pl->path = ln; pl->argv = nv; pl->rewritten = 1;
            pl->envp = cx_make_env(a, envp, prog);
            return pl->envp ? 1 : 0;
        }
        /* not ELF, not a script */
        if (depth == 0) { errno = ENOEXEC; return in_roots ? -1 : 0; }
        errno = ENOEXEC; return -1;
    }
    errno = ELOOP;
    return -1;
}

#endif
