/*
 * libcloudexec.so - a small executable shipped as lib*.so in jniLibs (Android lets an app exec files from
 * nativeLibraryDir, even at targetSdk 37). It is:
 *
 *   libcloudexec.so [--] prog args...     start prog the same way the LD_PRELOAD hook would (PATH search, #!, linker64).
 *                                          Use it as the first program from Java, as Go's -toolexec / -exec, as CC prefix.
 *   libcloudexec.so --static prog args... userland exec of a static ELF (ET_EXEC or static-PIE) that linker64 cannot load.
 *                                          EXPERIMENTAL: maps the segments itself and jumps to the entry point.
 *   libcloudexec.so --check               prints "cloudexec ok" (used by the self-test).
 */
#include "cx_common.h"
#include <stdio.h>
#include <stdlib.h>
#include <sys/auxv.h>

#ifndef AT_RANDOM
#define AT_RANDOM 25
#endif

static long raw_execve(const char *p, char *const a[], char *const e[]) { return syscall(__NR_execve, p, a, e); }

#if defined(__aarch64__)
#define EXPECT_MACHINE EM_AARCH64
#define JUMP(sp, entry) __asm__ volatile("mov sp, %0\n\tmov x0, xzr\n\tbr %1\n" :: "r"(sp), "r"(entry) : "memory")
#elif defined(__x86_64__)
#define EXPECT_MACHINE EM_X86_64
#define JUMP(sp, entry) __asm__ volatile("mov %0, %%rsp\n\txor %%edx, %%edx\n\tjmp *%1\n" :: "r"(sp), "r"(entry) : "memory")
#else
#define EXPECT_MACHINE 0
#define JUMP(sp, entry) do { } while (0)
#endif

#define PGSZ 4096UL
static int fail(const char *a, const char *b) { cx_say(a, b, NULL); return 126; }

static int static_exec(const char *path, char **argv, char **envp) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fail("cannot open ", path);
    Elf64_Ehdr eh;
    if (pread(fd, &eh, sizeof eh, 0) != (ssize_t)sizeof eh || memcmp(eh.e_ident, ELFMAG, 4) != 0 || eh.e_ident[EI_CLASS] != ELFCLASS64)
        return fail("not a 64-bit ELF: ", path);
    if (eh.e_machine != EXPECT_MACHINE) return fail("wrong CPU architecture: ", path);
    if (eh.e_phnum == 0 || eh.e_phnum > 64 || eh.e_phentsize != sizeof(Elf64_Phdr)) return fail("bad program headers: ", path);
    Elf64_Phdr ph[64];
    size_t pl = (size_t)eh.e_phnum * sizeof(Elf64_Phdr);
    if (pread(fd, ph, pl, (off_t)eh.e_phoff) != (ssize_t)pl) return fail("cannot read program headers: ", path);

    uint64_t lo = ~0ULL, hi = 0;
    for (int i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type == PT_INTERP) return fail("dynamic program, not static: ", path);
        if (ph[i].p_type != PT_LOAD) continue;
        uint64_t s = ph[i].p_vaddr & ~(PGSZ - 1), e = (ph[i].p_vaddr + ph[i].p_memsz + PGSZ - 1) & ~(PGSZ - 1);
        if (s < lo) lo = s;
        if (e > hi) hi = e;
    }
    if (lo >= hi) return fail("no loadable segments: ", path);

    uint64_t base = 0;
    if (eh.e_type == ET_DYN) {
        void *r = mmap(NULL, hi - lo, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (r == MAP_FAILED) return fail("cannot reserve address space for ", path);
        base = (uint64_t)r - lo;
    } else if (eh.e_type != ET_EXEC) return fail("unsupported ELF type: ", path);

    for (int i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type != PT_LOAD) continue;
        uint64_t va = base + ph[i].p_vaddr, pg = va & ~(PGSZ - 1), off = ph[i].p_offset & ~(PGSZ - 1);
        uint64_t fl = ph[i].p_filesz + (va - pg), ml = ph[i].p_memsz + (va - pg);
        int prot = ((ph[i].p_flags & PF_R) ? PROT_READ : 0) | ((ph[i].p_flags & PF_W) ? PROT_WRITE : 0) | ((ph[i].p_flags & PF_X) ? PROT_EXEC : 0);
        uint64_t fmap = (fl + PGSZ - 1) & ~(PGSZ - 1);
        if (fl) {
            int fl_flags = MAP_PRIVATE | MAP_FIXED;
            if (eh.e_type == ET_EXEC) {
#ifdef MAP_FIXED_NOREPLACE
                fl_flags = MAP_PRIVATE | MAP_FIXED_NOREPLACE;
#endif
            }
            void *m = mmap((void *)pg, fmap, prot | PROT_READ, fl_flags, fd, (off_t)off);
            if (m == MAP_FAILED || (uint64_t)m != pg) return fail("cannot map segment (refused by the system, or address in use): ", path);
            if (!(prot & PROT_READ)) mprotect(m, fmap, prot);
            if (ph[i].p_memsz > ph[i].p_filesz && (prot & PROT_WRITE)) {
                uint64_t z = va + ph[i].p_filesz; /* zero the rest of the last file page (.bss start) */
                uint64_t end = (z + PGSZ - 1) & ~(PGSZ - 1);
                memset((void *)z, 0, end - z);
            }
        }
        if (ml > fmap) {
            void *m = mmap((void *)(pg + fmap), ml - fmap, prot, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
            if (m == MAP_FAILED) return fail("cannot map bss for ", path);
        }
    }
    /* program headers address (AT_PHDR) */
    uint64_t phdr_va = 0;
    for (int i = 0; i < eh.e_phnum; i++)
        if (ph[i].p_type == PT_LOAD && eh.e_phoff >= ph[i].p_offset && eh.e_phoff < ph[i].p_offset + ph[i].p_filesz)
            phdr_va = base + ph[i].p_vaddr + (eh.e_phoff - ph[i].p_offset);
    close(fd);

    /* new stack */
    size_t ss = 8UL << 20;
    char *stk = (char *)mmap(NULL, ss, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (stk == MAP_FAILED) return fail("cannot allocate a stack for ", path);
    char *top = stk + ss - 64;
    int argc = cx_argc(argv), envc = cx_argc(envp);
    char **na = (char **)alloca((size_t)(argc + 1) * sizeof(char *)), **ne = (char **)alloca((size_t)(envc + 1) * sizeof(char *));
    char *rnd; char *exefn;
    /* strings */
    top -= 16; rnd = top;
    { const unsigned char *src = (const unsigned char *)getauxval(AT_RANDOM); if (src) memcpy(rnd, src, 16); else memset(rnd, 0x5a, 16); }
    top -= cx_len(path) + 1; exefn = top; memcpy(exefn, path, cx_len(path) + 1);
    for (int i = envc - 1; i >= 0; i--) { size_t l = cx_len(envp[i]) + 1; top -= l; memcpy(top, envp[i], l); ne[i] = top; }
    for (int i = argc - 1; i >= 0; i--) { size_t l = cx_len(argv[i]) + 1; top -= l; memcpy(top, argv[i], l); na[i] = top; }
    top = (char *)((uintptr_t)top & ~15UL);

    struct { uint64_t t, v; } aux[24]; int ax = 0;
#define A(t_, v_) do { aux[ax].t = (t_); aux[ax].v = (uint64_t)(v_); ax++; } while (0)
    unsigned long vdso = getauxval(AT_SYSINFO_EHDR);
    if (vdso) A(AT_SYSINFO_EHDR, vdso);
    A(AT_HWCAP, getauxval(AT_HWCAP));
    A(AT_PAGESZ, PGSZ);
    A(AT_CLKTCK, 100);
    A(AT_PHDR, phdr_va);
    A(AT_PHENT, sizeof(Elf64_Phdr));
    A(AT_PHNUM, eh.e_phnum);
    A(AT_BASE, 0);
    A(AT_FLAGS, 0);
    A(AT_ENTRY, base + eh.e_entry);
    A(AT_UID, getuid()); A(AT_EUID, geteuid()); A(AT_GID, getgid()); A(AT_EGID, getegid());
    A(AT_SECURE, 0);
    A(AT_RANDOM, rnd);
    A(AT_EXECFN, exefn);
#ifdef AT_HWCAP2
    A(AT_HWCAP2, getauxval(AT_HWCAP2));
#endif
    A(AT_NULL, 0);
    size_t words = 1 + (size_t)(argc + 1) + (size_t)(envc + 1) + (size_t)ax * 2;
    uint64_t *sp = (uint64_t *)top - words;
    sp = (uint64_t *)((uintptr_t)sp & ~15UL);
    size_t k = 0;
    sp[k++] = (uint64_t)argc;
    for (int i = 0; i < argc; i++) sp[k++] = (uint64_t)na[i];
    sp[k++] = 0;
    for (int i = 0; i < envc; i++) sp[k++] = (uint64_t)ne[i];
    sp[k++] = 0;
    for (int i = 0; i < ax; i++) { sp[k++] = aux[i].t; sp[k++] = aux[i].v; }
    uint64_t entry = base + eh.e_entry;
    JUMP(sp, entry);
    return fail("jump failed for ", path);
}

int main(int argc, char **argv) {
    if (argc < 2) { cx_say("usage: libcloudexec.so [--static] program [args...]", NULL, NULL); return 2; }
    if (cx_eq(argv[1], "--check")) { puts("cloudexec ok"); return 0; }
    if (cx_eq(argv[1], "--static")) {
        if (argc < 3) return 2;
        return static_exec(argv[2], argv + 2, environ);
    }
    int i = 1;
    if (cx_eq(argv[i], "--")) i++;
    if (i >= argc) return 2;
    const char *file = argv[i];
    char **pa = argv + i;
    char found[CX_PATH];
    if (!strchr(file, '/')) {
        if (cx_search_path(file, getenv("PATH"), environ, found) != 0) { cx_say(file, ": not found", NULL); return 127; }
        file = found;
    }
    /* let static programs find this launcher */
    char self[CX_PATH];
    long r = syscall(__NR_readlinkat, AT_FDCWD, "/proc/self/exe", self, sizeof self - 1);
    if (r > 0 && !cx_cfg(environ, "CLOUDIDE_LAUNCHER")) { self[r] = 0; setenv("CLOUDIDE_LAUNCHER", self, 1); }

    cx_arena ar;
    if (cx_arena_init(&ar, 1u << 20) < 0) return 126;
    cx_plan pl;
    int rc = cx_make_plan(&ar, file, pa, environ, 0, &pl);
    if (rc < 0) { int e = errno; cx_say(file, ": ", strerror(e)); return e == ENOENT ? 127 : 126; }
    if (rc == 0) {
        raw_execve(file, pa, environ);
        int e = errno;
        if (e == EACCES) { cx_plan p2; if (cx_make_plan(&ar, file, pa, environ, 1, &p2) == 1) { raw_execve(p2.path, p2.argv, p2.envp); e = errno; } }
        if (e == ENOEXEC) { char *nv[argc + 3]; int n = 0; nv[n++] = (char *)"sh"; nv[n++] = (char *)file; for (int j = 1; pa[j]; j++) nv[n++] = pa[j]; nv[n] = NULL; raw_execve("/system/bin/sh", nv, environ); }
        cx_say(file, ": ", strerror(e));
        return e == ENOENT ? 127 : 126;
    }
    if (cx_cfg(environ, "CLOUDIDE_EXEC_DEBUG") && cx_debug(environ)) cx_say("launch ", file, " via helper");
    if (cx_eq(pl.path, cx_cfg(environ, "CLOUDIDE_LAUNCHER") ? cx_cfg(environ, "CLOUDIDE_LAUNCHER") : "") && pl.argv[1] && cx_eq(pl.argv[1], "--static"))
        return static_exec(pl.argv[2], pl.argv + 2, pl.envp); /* no second exec needed */
    raw_execve(pl.path, pl.argv, pl.envp);
    cx_say(pl.path, ": ", strerror(errno));
    return 126;
}
