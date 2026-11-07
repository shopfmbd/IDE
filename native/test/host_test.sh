#!/usr/bin/env bash
# Logic test on a normal Linux box. glibc's ld.so behaves like Android's linker64 ("ld.so prog args"), and removing the
# x bit from files in the fake prefix makes the kernel refuse them, like SELinux does on Android 10+ app storage.
set -u
here="$(cd "$(dirname "$0")/.." && pwd)"
bash "$here/build.sh" host >/dev/null || exit 1
B="$here/build-host"
T="$(mktemp -d)"; P="$T/usr"; mkdir -p "$P/bin" "$P/lib"
LD=/lib64/ld-linux-x86-64.so.2; [ -e "$LD" ] || LD=$(ls /lib*/ld-linux-x86-64.so.2 /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 2>/dev/null | head -1)
cp /bin/echo "$P/bin/echo"; cp "$(command -v python3)" "$P/bin/python"; cp "$(command -v sh)" "$P/bin/sh"
cp "$(command -v env)" "$P/bin/env"; cp "$(command -v cat)" "$P/bin/cat"
ln -s echo "$P/bin/echo-link"
printf '#!%s/bin/sh\n%s/bin/echo "script ran: $1"\n' "$P" "$P" > "$P/bin/hello.sh"
printf '#!/usr/bin/env python\nimport sys,os\nprint("env-shebang python", sys.argv[1:], os.readlink("/proc/self/exe"))\n' > "$P/bin/tool.py"
printf '#!/bin/sh -e\n%s/bin/echo "bin-sh mapped: $1"\n' "$P" > "$P/bin/mapped.sh"
printf '#include <stdio.h>\nint main(void){return puts("static hello"),0;}\n' > "$T/s.c"; gcc -static -no-pie -o "$P/bin/hello-static" "$T/s.c"
gcc -static-pie -o "$P/bin/hello-spie" "$T/s.c" 2>/dev/null
chmod 644 "$P"/bin/*   # "SELinux": nothing in the prefix is executable any more
chmod 755 "$P/bin/tool.py" "$P/bin/mapped.sh"   # dash refuses to even try files without x, so scripts looked up by PATH keep it (their interpreters stay blocked)
export CLOUDIDE_PREFIX="$P" CLOUDIDE_EXEC_ROOTS="$T" CLOUDIDE_LINKER="$LD" CLOUDIDE_PRELOAD="$B/libexec_hook.so" CLOUDIDE_LAUNCHER="$B/libcloudexec.so"
export PATH="$P/bin:/usr/bin:/bin"
pass=0; fail=0
t() { # name expected-substring command...
  local name="$1" want="$2"; shift 2
  local out; out=$(LD_PRELOAD="$B/libexec_hook.so" "$@" 2>&1)
  if [[ "$out" == *"$want"* ]]; then echo "PASS $name"; pass=$((pass+1)); else echo "FAIL $name: got [$out]"; fail=$((fail+1)); fi
}
# control: without the preload the kernel refuses
out=$(env -u LD_PRELOAD /bin/sh -c "$P/bin/echo hi" 2>&1); [[ "$out" == *"ermission denied"* ]] && echo "PASS control (plain exec is refused)" || { echo "FAIL control: [$out]"; fail=$((fail+1)); }
t "sh -c absolute ELF"      "hi"                    /bin/sh -c "$P/bin/echo hi"
t "symlink to ELF"          "viaLink"               /bin/sh -c "$P/bin/echo-link viaLink"
t "shebang script"          "script ran: A"         /bin/sh -c "$P/bin/hello.sh A"
t "env shebang + /proc/self/exe" "env-shebang python ['X'] $P/bin/python" /bin/sh -c "tool.py X"
t "#!/bin/sh mapped"        "bin-sh mapped: M"      /bin/sh -c "mapped.sh M"
t "python subprocess"       "Python 3"              /bin/sh -c "python -c 'import subprocess; subprocess.run([\"python\",\"--version\"])'"
t "python subprocess shell" "from-popen"            /bin/sh -c "python -c 'import subprocess; subprocess.run(\"echo from-popen\", shell=True)'"
t "python os.posix_spawn"   "spawned"               /bin/sh -c "python -c 'import os; os.waitpid(os.posix_spawn(\"$P/bin/echo\",[\"echo\",\"spawned\"],os.environ),0)'"
t "python os.execv"         "execv-ok"              /bin/sh -c "python -c 'import os; os.execv(\"$P/bin/echo\",[\"echo\",\"execv-ok\"])'"
t "python os.execvp"        "execvp-ok"             /bin/sh -c "python -c 'import os; os.execvp(\"echo\",[\"echo\",\"execvp-ok\"])'"
t "env -i keeps working"    "clean-env"             /bin/sh -c "$P/bin/env -i PATH=$P/bin CLOUDIDE_PREFIX=$P CLOUDIDE_EXEC_ROOTS=$T CLOUDIDE_LINKER=$LD echo clean-env"
t "static ET_EXEC (launcher)" "static hello"        /bin/sh -c "$P/bin/hello-static"
[ -f "$P/bin/hello-spie" ] && t "static-PIE (launcher)" "static hello" /bin/sh -c "$P/bin/hello-spie"
t "launcher direct"         "via-launcher"          "$B/libcloudexec.so" "$P/bin/echo" via-launcher
t "launcher PATH+shebang"   "script ran: L"         "$B/libcloudexec.so" hello.sh L
t "launcher --check"        "cloudexec ok"          "$B/libcloudexec.so" --check
t "system program untouched" "sysok"                /bin/sh -c "/bin/echo sysok"
t "missing program"         "not found"             /bin/sh -c "nonexistent-cmd-xyz"
echo "passed $pass failed $fail"; rm -rf "$T"; [ "$fail" = 0 ]
