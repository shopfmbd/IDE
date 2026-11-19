package com.fahim.cloudide;

import java.util.*;
import java.util.regex.Pattern;

/** Language definitions: one combined regex per language, one capture group per token kind. */
final class Lang {
    static final int C = 0, S = 1, K = 2, T = 3, N = 4, M = 5, F = 6, G = 7, A = 8;

    static final String BLK = "/\\*[\\s\\S]*?(?:\\*/|\\z)";
    static final String SL = "//[^\\n]*";
    static final String HASH = "#[^\\n]*";
    static final String DQ = "\"(?:\\\\.|[^\"\\\\\\n])*\"?";
    static final String SQ = "'(?:\\\\.|[^'\\\\\\n])*'?";
    static final String TRI = "(?:\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|'''[\\s\\S]*?(?:'''|\\z))";
    static final String NUM = "\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFdDlLuU]*)\\b";
    static final String ANN = "@[A-Za-z_][\\w.]*";
    static final String TYPE = "\\b[A-Z][A-Za-z0-9_]*\\b";
    static final String FUNC = "\\b[a-zA-Z_]\\w*(?=\\s*\\()";

    static final String JAVA = "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while var record sealed permits yield true false null";
    static final String KOTLIN = "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by catch constructor finally get import init set where abstract annotation companion const crossinline data enum external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg";
    static final String GROOVY = "def class interface enum extends implements import package if else for while do switch case default break continue return new try catch finally throw throws in as instanceof true false null this super static final void plugins id apply implementation api compileOnly runtimeOnly testImplementation androidTestImplementation kapt ksp classpath repositories dependencies android buildscript";
    static final String JS = "break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new return super switch this throw try typeof var void while with yield async await of static get set true false null undefined interface type enum implements readonly public private protected namespace declare";
    static final String PY = "and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield self";
    static final String CC = "auto break case char const continue default do double else enum extern float for goto if inline int long register return short signed sizeof static struct switch typedef union unsigned void volatile while class namespace template typename using new delete public private protected virtual override nullptr true false bool this throw try catch";
    static final String SH = "if then else elif fi for while do done case esac function in return exit export local readonly echo cd set unset source alias";
    static final String RSQ = "'(?:\\\\.[^'\\n]*|[^'\\\\\\n])'";
    static final String RUST = "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while";
    static final String GO = "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil iota";
    static final String DART = "abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for Function get hide if implements import in interface is late library mixin new null on operator part required rethrow return set show static super switch sync this throw true try typedef var void while with yield";
    static final String SWIFT = "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as Any catch false is nil super self Self throw throws true try async await";
    static final String CS = "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using virtual void volatile while var async await record";
    static final String PHP = "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty extends final finally fn for foreach function global goto if implements include include_once instanceof interface isset list namespace new or print private protected public require require_once return static switch throw trait try unset use var while xor yield true false null";
    static final String RUBY = "alias and begin break case class def do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield puts require";
    static final String LUA = "and break do else elseif end false for function goto if in local nil not or repeat return then true until while";
    static final String SQL = "~select from where insert into values update set delete create table drop alter add join left right inner outer on group by order having limit offset and or not null as distinct union all primary key foreign references index view unique default check constraint like in is between exists case when then else end asc desc count sum avg min max";
    static final String CMAKE = "cmake_minimum_required project add_executable add_library target_link_libraries target_include_directories find_package find_library set option if else elseif endif foreach endforeach function endfunction macro endmacro include add_subdirectory message file list string install";
    static final String EXTRA = "Activity AppCompatActivity Bundle Context Intent View TextView Button EditText ImageView RecyclerView LinearLayout FrameLayout Toast Log String Integer Boolean List ArrayList HashMap Map Set Math Thread Runnable Handler Looper File Uri Override onCreate onResume onPause onDestroy findViewById setContentView setOnClickListener getSystemService startActivity";

    static final Map<String, Lang> MAP = new HashMap<>();
    static final Lang PLAIN = new Lang("text", null, new Object[0]);

    static {
        reg(new Lang("java", JAVA, new Object[]{BLK, C, SL, C, DQ, S, SQ, S, ANN, M}).cfg(true, "//", null, null).extra(), "java");
        reg(new Lang("kotlin", KOTLIN, new Object[]{BLK, C, SL, C, TRI, S, DQ, S, SQ, S, ANN, M}).cfg(true, "//", null, null).extra(), "kt,kts");
        reg(new Lang("groovy", GROOVY, new Object[]{BLK, C, SL, C, TRI, S, DQ, S, SQ, S, ANN, M}).cfg(true, "//", null, null), "gradle,groovy");
        reg(new Lang("js", JS, new Object[]{BLK, C, SL, C, "`(?:\\\\.|[^`\\\\])*`?", S, DQ, S, SQ, S}).cfg(true, "//", null, null), "js,ts,jsx,tsx,mjs");
        reg(new Lang("python", PY, new Object[]{HASH, C, TRI, S, DQ, S, SQ, S, "@\\w+", M}).cfg(false, "#", null, null), "py");
        reg(new Lang("c", CC, new Object[]{BLK, C, SL, C, DQ, S, SQ, S, "^[ \\t]*#\\s*\\w+[^\\n]*", M}).cfg(true, "//", null, null), "c,h,cpp,hpp,cc,cxx");
        reg(new Lang("shell", SH, new Object[]{HASH, C, DQ, S, SQ, S, "\\$\\{?\\w+\\}?", M}).cfg(false, "#", null, null), "sh,bash,zsh");
        reg(new Lang("css", null, new Object[]{BLK, C, DQ, S, SQ, S, "#[0-9a-fA-F]{3,8}\\b", N, "[-\\w]+(?=\\s*:)", A,
                "[.#][A-Za-z_-][\\w-]*", T, "-?\\d+(?:\\.\\d+)?(?:px|dp|sp|em|rem|vh|vw|ms|s)?\\b", N}).cfg(true, null, "/*", "*/"), "css");
        reg(new Lang("xml", null, new Object[]{"<!--[\\s\\S]*?(?:-->|\\z)", C, "<\\?[\\s\\S]*?(?:\\?>|\\z)", M,
                "<!\\[CDATA\\[[\\s\\S]*?(?:\\]\\]>|\\z)", S, "\"[^\"]*\"?", S, "'[^']*'?", S, "</?[\\w:.-]+", G, "/?>", G,
                "[\\w:.-]+(?=\\s*=)", A, "&\\w+;", M}).cfg(false, null, "<!--", "-->"), "xml,html,htm,svg,xhtml");
        reg(new Lang("json", null, new Object[]{"\"(?:\\\\.|[^\"\\\\\\n])*\"(?=\\s*:)", A, DQ, S, "-?\\b\\d[\\d.eE+-]*\\b", N,
                "\\b(?:true|false|null)\\b", K}).cfg(true, null, null, null), "json");
        reg(new Lang("yaml", null, new Object[]{HASH, C, "^[ \\t-]*[\\w./\"'-]+(?=\\s*:(?:\\s|$))", A, DQ, S, SQ, S,
                "\\b(?:true|false|null|yes|no)\\b", K, NUM, N}).cfg(false, "#", null, null), "yml,yaml");
        reg(new Lang("props", null, new Object[]{"^[#!][^\\n]*", C, "^[ \\t]*[^\\s=:#!][^=:\\n]*(?=[=:])", A, NUM, N})
                .cfg(false, "#", null, null), "properties,ini,toml,cfg,conf");
        reg(new Lang("md", null, new Object[]{"```[\\s\\S]*?(?:```|\\z)", S, "`[^`\\n]+`", S, "^#{1,6}[^\\n]*", K,
                "\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__", T, "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)", F, "^[ \\t]*(?:[-*+]|\\d+\\.)[ \\t]", M,
                "^>[^\\n]*", C}).cfg(false, null, "<!--", "-->"), "md,markdown");
        reg(new Lang("rust", RUST, new Object[]{BLK, C, SL, C, DQ, S, RSQ, S, "#!?\\[[^\\]\\n]*\\]", M, "\\b\\w+!", M}).cfg(true, "//", null, null), "rs");
        reg(new Lang("go", GO, new Object[]{BLK, C, SL, C, "`[^`]*`?", S, DQ, S, SQ, S}).cfg(true, "//", null, null), "go");
        reg(new Lang("dart", DART, new Object[]{BLK, C, SL, C, TRI, S, DQ, S, SQ, S, ANN, M}).cfg(true, "//", null, null), "dart");
        reg(new Lang("swift", SWIFT, new Object[]{BLK, C, SL, C, TRI, S, DQ, S, ANN, M}).cfg(true, "//", null, null), "swift");
        reg(new Lang("csharp", CS, new Object[]{BLK, C, SL, C, DQ, S, SQ, S, "^[ \\t]*#\\s*\\w+[^\\n]*", M}).cfg(true, "//", null, null), "cs");
        reg(new Lang("php", PHP, new Object[]{BLK, C, SL, C, HASH, C, DQ, S, SQ, S, "\\$\\w+", M}).cfg(true, "//", null, null), "php");
        reg(new Lang("ruby", RUBY, new Object[]{HASH, C, DQ, S, SQ, S, ":[A-Za-z_]\\w*", M, "@{1,2}\\w+", M}).cfg(false, "#", null, null), "rb");
        reg(new Lang("lua", LUA, new Object[]{"--\\[\\[[\\s\\S]*?(?:\\]\\]|\\z)", C, "--[^\\n]*", C, DQ, S, SQ, S}).cfg(false, "--", null, null), "lua");
        reg(new Lang("sql", SQL, new Object[]{BLK, C, "--[^\\n]*", C, SQ, S, DQ, S}).cfg(false, "--", null, null), "sql");
        reg(new Lang("cmake", CMAKE, new Object[]{HASH, C, DQ, S, "\\$\\{[^}\\n]*\\}", M}).cfg(false, "#", null, null), "cmake");
    }

    final String id;
    final Pattern pat;
    final int[] kinds;
    String[] words;
    boolean brace;
    String lineComment, blockOpen, blockClose;

    private Lang(String id, String kw, Object[] defs) {
        this.id = id;
        List<String> rx = new ArrayList<>();
        List<Integer> k = new ArrayList<>();
        for (int i = 0; i + 1 < defs.length; i += 2) {
            rx.add((String) defs[i]);
            k.add((Integer) defs[i + 1]);
        }
        if (kw != null) {
            boolean ci = kw.startsWith("~");
            if (ci) kw = kw.substring(1);
            rx.add((ci ? "(?i:" : "(?:") + "\\b(?:" + kw.replace(' ', '|') + ")\\b)");
            k.add(K);
            rx.add(TYPE);
            k.add(T);
            rx.add(FUNC);
            k.add(F);
            rx.add(NUM);
            k.add(N);
        }
        words = kw == null ? new String[0] : kw.split(" ");
        if (rx.isEmpty()) {
            pat = null;
            kinds = new int[0];
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rx.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append('(').append(rx.get(i)).append(')');
            }
            pat = Pattern.compile(sb.toString(), Pattern.MULTILINE);
            kinds = new int[k.size()];
            for (int i = 0; i < kinds.length; i++) kinds[i] = k.get(i);
        }
    }

    private Lang cfg(boolean brace, String lc, String bo, String bc) {
        this.brace = brace;
        this.lineComment = lc;
        this.blockOpen = bo;
        this.blockClose = bc;
        return this;
    }

    private Lang extra() {
        StringBuilder sb = new StringBuilder();
        for (String w : words) sb.append(w).append(' ');
        words = sb.append(EXTRA).toString().split(" ");
        return this;
    }

    private static void reg(Lang l, String exts) {
        for (String e : exts.split(",")) MAP.put(e, l);
    }

    static Lang forFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        if (n.equals("cmakelists.txt")) return MAP.get("cmake");
        if (n.equals("makefile") || n.equals("dockerfile")) return MAP.get("sh");
        if (i < 0) return PLAIN; // a bare name such as "go" or "md" is not an extension
        Lang l = MAP.get(n.substring(i + 1));
        return l == null ? PLAIN : l;
    }
}
