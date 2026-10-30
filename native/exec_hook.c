/*
 * libexec_hook.so - LD_PRELOAD library for Android 10+ apps with targetSdk >= 29.
 *
 * Intercepts execve, execv, execvp, execvpe, execl, execle, execlp, fexecve, posix_spawn, posix_spawnp and
 * readlink("/proc/self/exe"). A program inside the app's roots (CLOUDIDE_EXEC_ROOTS) is started through the
 * system linker instead of exec'd directly. See cx_common.h for the rules.
 *
 * Does not work for: processes that do not use libc (static binaries, Go's raw syscalls), see NATIVE_EXEC.md.
 */
#include "cx_common.h"
#include <dlfcn.h>
#include <spawn.h>
#include <stdarg.h>

#define EXPORT __attribute__((visibility("default")))

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
                        char *const[], char *const[]);
static spawn_fn real_spawn, real_spawnp;

__attribute__((constructor)) static void cx_init(void) {
    real_spawn = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    real_spawnp = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawnp");
}

static long raw_execve(const char *p, char *const a[], char *const e[]) { return syscall(__NR_execve, p, a, e); }

#define ARENA_BYTES (1u << 20)

/* the one place that execs; returns -1/errno on failure */
static int do_exec(const char *path, char *const argv[], char *const envp[], int allow_sh_fallback) {
    cx_arena ar; ar.base = NULL;
    if (cx_arena_init(&ar, ARENA_BYTES) < 0) return (int)raw_execve(path, argv, envp);
    cx_plan pl;
    int rc = cx_make_plan(&ar, path, argv, envp, 0, &pl);
    if (rc < 0) {
        int e = errno; cx_arena_free(&ar);
        if (allow_sh_fallback && e == ENOEXEC) {
            /* script without #!: POSIX says run it with the shell */
            int ac = cx_argc(argv);
            char *nv[ac + 3]; nv[0] = (char *)"sh"; nv[1] = (char *)path;
            for (int i = 1; i <= ac; i++) nv[i + 1] = argv[i];
            return do_exec("/system/bin/sh", nv, envp, 0);
        }
        errno = e; return -1;
    }
    if (rc == 1) {
        if (cx_debug(envp)) cx_say("exec ", path, " -> via helper");
        raw_execve(pl.path, pl.argv, pl.envp);
        int e = errno; cx_arena_free(&ar); errno = e; return -1;
    }
    /* not ours: run unchanged, but keep the preload in the environment so grandchildren are covered */
    char **ne = cx_cfg(envp, "CLOUDIDE_PRELOAD") ? cx_make_env(&ar, envp, NULL) : NULL;
    raw_execve(path, argv, ne ? ne : envp);
    int e = errno;
    if (e == ENOEXEC && allow_sh_fallback) {
        int ac = cx_argc(argv);
        char *nv[ac + 3]; nv[0] = (char *)"sh"; nv[1] = (char *)path;
        for (int i = 1; i <= ac; i++) nv[i + 1] = argv[i];
        cx_arena_free(&ar);
        return do_exec("/system/bin/sh", nv, envp, 0);
    }
    if (e == EACCES) { /* plain exec refused: SELinux. try the linker even outside the roots */
        cx_plan p2;
        if (cx_make_plan(&ar, path, argv, envp, 1, &p2) == 1) {
            if (cx_debug(envp)) cx_say("exec ", path, " refused, retry via helper");
            raw_execve(p2.path, p2.argv, p2.envp);
            e = errno;
        } else e = EACCES;
    }
    cx_arena_free(&ar); errno = e; return -1;
}

EXPORT int execve(const char *path, char *const argv[], char *const envp[]) { return do_exec(path, argv, envp, 0); }
EXPORT int execv(const char *path, char *const argv[]) { return do_exec(path, argv, environ, 0); }

static int search_exec(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) return do_exec(file, argv, envp, 1);
    const char *path = getenv("PATH");
    if (!path) path = "/system/bin";
    size_t fl = cx_len(file);
    if (fl > 255) { errno = ENAMETOOLONG; return -1; }
    int seen_eacces = 0;
    const char *p = path;
    for (;;) {
        char cand[CX_PATH]; size_t dl = 0;
        while (*p && *p != ':' && dl < CX_PATH - 300) cand[dl++] = *p++;
        if (dl == 0) { cand[dl++] = '.'; }
        if (cand[dl - 1] != '/') cand[dl++] = '/';
        memcpy(cand + dl, file, fl + 1);
        do_exec(cand, argv, envp, 1);
        switch (errno) {
            case EACCES: seen_eacces = 1; /* fallthrough */
            case ENOENT: case ENOTDIR: case ESTALE: case ENODEV: case ETIMEDOUT: break;
            default: return -1;
        }
        if (!*p) break;
        p++;
    }
    if (seen_eacces) errno = EACCES;
    return -1;
}

EXPORT int execvpe(const char *file, char *const argv[], char *const envp[]) { return search_exec(file, argv, envp); }
EXPORT int execvp(const char *file, char *const argv[]) { return search_exec(file, argv, environ); }

#define COLLECT(first, with_env, pathvar)                                         \
    int n = 1; va_list ap, cp;                                                    \
    va_start(ap, first); va_copy(cp, ap);                                         \
    while (va_arg(cp, char *)) n++;                                               \
    va_end(cp);                                                                   \
    char *argv[n + 1]; argv[0] = (char *)first;                                   \
    for (int i = 1; i <= n; i++) argv[i] = va_arg(ap, char *);                    \
    char *const *envp = environ;                                                  \
    if (with_env) envp = va_arg(ap, char *const *);                               \
    va_end(ap);

EXPORT int execl(const char *path, const char *arg, ...) { COLLECT(arg, 0, path); return do_exec(path, argv, envp, 0); }
EXPORT int execle(const char *path, const char *arg, ...) { COLLECT(arg, 1, path); return do_exec(path, argv, envp, 0); }
EXPORT int execlp(const char *file, const char *arg, ...) { COLLECT(arg, 0, file); return search_exec(file, argv, envp); }

EXPORT int fexecve(int fd, char *const argv[], char *const envp[]) {
    char link[64], tgt[CX_PATH]; char *q = cx_cpy(link, "/proc/self/fd/");
    char dg[16]; int d = 0, v = fd; if (v == 0) dg[d++] = '0'; while (v > 0) { dg[d++] = (char)('0' + v % 10); v /= 10; }
    while (d) { *q++ = dg[--d]; }
    *q = 0;
    long r = syscall(__NR_readlinkat, AT_FDCWD, link, tgt, sizeof tgt - 1);
    if (r <= 0) { errno = EBADF; return -1; }
    tgt[r] = 0;
    return do_exec(tgt, argv, envp, 0);
}

/* ---------- posix_spawn: plan in the parent, let libc do the fork ---------- */
static int do_spawn(int search, pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                    const posix_spawnattr_t *at, char *const argv[], char *const envp[]) {
    spawn_fn real = search ? real_spawnp : real_spawn;
    if (!real) { cx_init(); real = search ? real_spawnp : real_spawn; }
    if (!real) return ENOSYS;
    char found[CX_PATH]; const char *path = file;
    cx_arena ar; ar.base = NULL;
    if (search && file && *file && !strchr(file, '/')) {
        if (cx_search_path(file, getenv("PATH"), envp, found) != 0) return real(pid, file, fa, at, argv, envp);
        path = found;
    }
    if (cx_arena_init(&ar, ARENA_BYTES) < 0) return real(pid, file, fa, at, argv, envp);
    cx_plan pl;
    int rc = cx_make_plan(&ar, path, argv, envp, 0, &pl);
    int ret;
    if (rc == 1) {
        if (cx_debug(envp)) cx_say("spawn ", path, " -> via helper");
        ret = real_spawn(pid, pl.path, fa, at, pl.argv, pl.envp);
    } else if (rc < 0) {
        ret = errno;
        if (ret == ENOEXEC) { /* no #! line: the shell runs it */
            int ac = cx_argc(argv);
            char *nv[ac + 3]; nv[0] = (char *)"sh"; nv[1] = (char *)path;
            for (int i = 1; i <= ac; i++) nv[i + 1] = argv[i];
            ret = real_spawn(pid, "/system/bin/sh", fa, at, nv, envp);
        }
    } else {
        char **ne = cx_cfg(envp, "CLOUDIDE_PRELOAD") ? cx_make_env(&ar, envp, NULL) : NULL;
        ret = real(pid, file, fa, at, argv, ne ? ne : envp);
        if (ret == EACCES) {
            cx_plan p2;
            if (cx_make_plan(&ar, path, argv, envp, 1, &p2) == 1) ret = real_spawn(pid, p2.path, fa, at, p2.argv, p2.envp);
        }
    }
    cx_arena_free(&ar);
    return ret;
}

EXPORT int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *at,
                       char *const argv[], char *const envp[]) { return do_spawn(0, pid, path, fa, at, argv, envp); }
EXPORT int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *at,
                        char *const argv[], char *const envp[]) { return do_spawn(1, pid, file, fa, at, argv, envp); }

/* ---------- /proc/self/exe must name the real program, not the linker ---------- */
static int is_self_exe(const char *p) {
    if (!p) return 0;
    if (cx_eq(p, "/proc/self/exe")) return 1;
    if (cx_starts(p, "/proc/")) {
        char me[32]; char *q = cx_cpy(me, "/proc/"); long v = getpid(); char dg[16]; int d = 0;
        while (v > 0) { dg[d++] = (char)('0' + v % 10); v /= 10; }
        while (d) *q++ = dg[--d];
        cx_cpy(q, "/exe");
        return cx_eq(p, me);
    }
    return 0;
}
static ssize_t fake_exe(char *buf, size_t sz) {
    const char *s = cx_env_in(environ, "CLOUDIDE_SELF_EXE");
    if (!s || !*s) return -2;
    size_t n = cx_len(s); if (n > sz) n = sz;
    memcpy(buf, s, n);
    return (ssize_t)n;
}
EXPORT ssize_t readlink(const char *path, char *buf, size_t sz) {
    if (is_self_exe(path)) { ssize_t r = fake_exe(buf, sz); if (r != -2) return r; }
    return (ssize_t)syscall(__NR_readlinkat, AT_FDCWD, path, buf, sz);
}
EXPORT ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t sz) {
    if (path[0] == '/' && is_self_exe(path)) { ssize_t r = fake_exe(buf, sz); if (r != -2) return r; }
    return (ssize_t)syscall(__NR_readlinkat, dirfd, path, buf, sz);
}
