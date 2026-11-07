package com.fahim.cloudide;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AI assistant in the editor's bottom panel. The panel only draws the conversation and talks to {@link AiClient}; what the
 * assistant may see and how code is applied to the editor is decided by the {@link Host} (EditorActivity), so every edit goes
 * through the editor's normal undo history.
 *
 * Built in code, not XML, so it adds no layout files or resource ids that could fail to link.
 */
final class AiPanel extends LinearLayout {
    interface Host {
        /** What the assistant may see right now. The panel sets the task and decides which parts to ask for. */
        AiProtocol.Context context(boolean file, boolean selection, boolean problems, boolean tree, boolean project);

        boolean hasSelection();

        boolean hasFile();

        void insertAtCursor(String code);

        void replaceSelection(String code);

        void replaceFile(String code);

        void openSettings();

        /** Opens the full-screen AI website (Claude, DeepSeek, ChatGPT...) that works on the project's files. */
        void openWebAi();

        /** Current text of a project file, unsaved editor text included; null when it does not exist; {@link AiEdits#UNREADABLE} when it cannot be read. */
        String readFile(String rel);

        /** Writes a project file (created when new) and saves it. Returns null on success, else why it could not be written. */
        String writeFile(String rel, String text);

        /** Deletes a file the assistant created, for Undo. Returns null on success, else why not. */
        String deleteFile(String rel);

        /** Moves a file inside the project (open tabs follow it). Returns null on success, else why not. */
        String renameFile(String from, String to);

        /** What the assistant's agent actions run on: this host's project, files and screens. */
        AgentTools tools();

        /** Opens another project in the editor. The conversation continues there through {@link AiSession}. */
        void openProject(File dir);
    }

    /** One question and its answer. */
    private static final class Turn {
        String label, task, prompt, error, note;
        View userView;
        LinearLayout holder;
        final StringBuilder reply = new StringBuilder();
        boolean thinking, done, auto, proj;
        /** Agent actions of this reply: what was asked, whether it ran, and what each came to. */
        AiActions.Parsed parsed;
        boolean ran, running, agent;
        final List<AiActions.Result> results = new ArrayList<>();
        /** Changes applied for this reply, in order (Undo goes backwards), and the reasons some were not. */
        final List<AiEdits.Change> edits = new ArrayList<>();
        final List<String> skips = new ArrayList<>();
    }

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final int RENDER_EVERY_MS = 90;

    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Turn> turns = new ArrayList<>();
    private final LinearLayout transcript;
    private final ScrollView scroll;
    private final EditText input;
    private final TextView send, title, mode, agentPill;
    private final TextView[] pills = new TextView[5];
    private static final String[] PILL_NAMES = {"File", "Selection", "Problems", "File list", "Project code"};
    /** File, Selection, Problems, File list, Project code. The first two default on; the whole project is only sent when asked for. */
    private final boolean[] on = {true, true, false, false, false};
    private AiClient client;
    private Turn current;
    private boolean renderQueued;
    private View introView, setupView, quickRow, ctxRow;
    /** Agent actions are running (on the worker thread) for {@link #actingTurn}. {@link #epoch} changes when they are stopped or the chat is cleared. */
    private volatile boolean acting;
    private volatile int epoch;
    private Turn actingTurn;
    /** Replies since the person last wrote, so an assistant that keeps going cannot loop for ever. */
    private int agentSteps;
    /** The conversation so far, brought over when the assistant opened another project (which starts a new editor). */
    private List<AiProtocol.Msg> seed;

    AiPanel(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(4), dp(8), dp(4));
        ImageView spark = new ImageView(c);
        spark.setImageResource(R.drawable.ic_t_ai);
        spark.setColorFilter(onPrimaryContainer());
        spark.setBackground(rounded(primaryContainer(), 14));
        spark.setPadding(dp(5), dp(5), dp(5), dp(5));
        head.addView(spark, new LayoutParams(dp(28), dp(28)));
        title = label(12.5f, onSurface());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(10), 0, dp(8), 0);
        title.setOnClickListener(v -> host.openSettings());
        head.addView(title, new LayoutParams(0, -2, 1f));
        agentPill = action("", v -> toggleAgent());
        agentPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        showAgent();
        LayoutParams ap = new LayoutParams(-2, dp(30));
        ap.rightMargin = dp(6);
        head.addView(agentPill, ap);
        mode = action("", v -> toggleMode());
        mode.setPadding(dp(10), dp(4), dp(10), dp(4));
        showMode();
        LayoutParams mp = new LayoutParams(-2, dp(30));
        mp.rightMargin = dp(6);
        head.addView(mode, mp);
        TextView fresh = action("New chat", v -> clearChat());
        fresh.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), outlined(), rounded(0xFFFFFFFF, 14)));
        fresh.setPadding(dp(12), dp(4), dp(12), dp(4));
        head.addView(fresh, new LayoutParams(-2, dp(30)));
        addView(head, new LayoutParams(-1, -2));

        scroll = new ScrollView(c);
        transcript = new LinearLayout(c);
        transcript.setOrientation(VERTICAL);
        transcript.setPadding(dp(14), dp(2), dp(14), dp(8));
        scroll.addView(transcript);
        addView(scroll, new LayoutParams(-1, 0, 1f));

        View rule = new View(c);
        rule.setBackgroundColor(outline());
        addView(rule, new LayoutParams(-1, dp(1)));

        // one tap actions
        HorizontalScrollView quick = new HorizontalScrollView(c);
        quick.setHorizontalScrollBarEnabled(false);
        LinearLayout qrow = new LinearLayout(c);
        qrow.setPadding(dp(10), dp(6), dp(10), dp(2));
        TextView web = pill("AI website \u2197");
        style(web, false, true);
        web.setOnClickListener(v -> host.openWebAi());
        qrow.addView(web, pillParams());
        for (int i = 0; i < AiProtocol.QUICK.length; i++) {
            final int idx = i;
            TextView p = pill(AiProtocol.QUICK[i][0]);
            style(p, false, true);
            p.setOnClickListener(v -> quick(idx));
            qrow.addView(p, pillParams());
        }
        quick.addView(qrow);
        addView(quick, new LayoutParams(-1, -2));
        quickRow = quick;

        // what is attached
        HorizontalScrollView ctx = new HorizontalScrollView(c);
        ctx.setHorizontalScrollBarEnabled(false);
        LinearLayout crow = new LinearLayout(c);
        crow.setGravity(Gravity.CENTER_VERTICAL);
        crow.setPadding(dp(10), dp(2), dp(10), dp(2));
        TextView attach = label(12, dim());
        attach.setText("Attach");
        attach.setPadding(dp(4), 0, dp(8), 0);
        crow.addView(attach);
        for (int i = 0; i < pills.length; i++) {
            final int idx = i;
            pills[i] = pill(PILL_NAMES[i]);
            style(pills[i], on[i], false);
            showToggle(idx);
            pills[i].setOnClickListener(v -> {
                on[idx] = !on[idx];
                showToggle(idx);
                if (idx == 4 && on[4]) Ui.toast(getContext(), "The source of the whole project is sent with your next question (up to about "
                        + AiProtocol.estimateTokens(AiProtocol.MAX_PROJECT_CHARS) / 1000 + "k tokens).");
            });
            crow.addView(pills[i], pillParams());
        }
        ctx.addView(crow);
        addView(ctx, new LayoutParams(-1, -2));
        ctxRow = ctx;

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(dp(10), dp(4), dp(10), dp(8));
        input = new EditText(c);
        input.setHint("Ask about your code");
        input.setHintTextColor(dim());
        input.setTextColor(onSurface());
        input.setTextSize(14);
        input.setMaxLines(5);
        input.setMinHeight(dp(40));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable box = rounded(container(), 20);
        box.setStroke(dp(1), outline());
        input.setBackground(box);
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        input.setOnKeyListener((v, keyCode, ev) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN && ev.isCtrlPressed()) {
                sendClicked();
                return true;
            }
            return false;
        });
        bar.addView(input, new LayoutParams(0, -2, 1f));
        send = pill("Send");
        send.setTypeface(null, Typeface.BOLD);
        styleSend(false);
        send.setOnClickListener(v -> sendClicked());
        LayoutParams sp = new LayoutParams(-2, dp(40));
        sp.leftMargin = dp(8);
        bar.addView(send, sp);
        addView(bar, new LayoutParams(-1, -2));

        refreshTitle();
        updateIntro();
    }

    // ------------------------------------------------------------ for the host
    /** Call when the panel is brought to the front: settings may have changed. */
    void onShown() {
        refreshTitle();
        updateIntro();
    }

    boolean isRunning() { return client != null || acting; }

    /** For the assistant screen that has no editor: no file shortcuts and no attachments, just the conversation. */
    void setStandalone() {
        quickRow.setVisibility(GONE);
        ctxRow.setVisibility(GONE);
        input.setHint("Say what to build or change");
        updateIntro();
    }

    /**
     * Carries on a conversation that the previous editor handed over (the assistant opened or created a project there):
     * sends the results of its last actions, so it can continue in this project.
     */
    void resumeAgent() {
        AiSession.Carry cr = AiSession.take();
        if (cr == null || agentMode().isEmpty()) return;
        Context c = getContext();
        if (AiConfig.missing(c) != null || !AiConfig.consented(c, AiConfig.provider(c))) return;
        seed = cr.history;
        agentSteps = cr.steps;
        submitAgent(cr.results, "Agent: continued in this project");
    }

    /** Switches "Project code" on, so the next question carries the whole project's source. */
    void attachProject() {
        on[4] = true;
        showToggle(4);
    }

    /** Puts the cursor in the message box and opens the keyboard. */
    void focusInput() {
        input.requestFocus();
        // one frame later: the panel was made visible a moment ago and the keyboard needs the view to be laid out
        input.post(new Runnable() {
            @Override
            public void run() {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, 0);
            }
        });
    }

    /** Stops any request. Call from the activity's onDestroy. */
    void shutdown() {
        cancelRunning();
        worker.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    /** Sends a question from outside the panel (the selection menu, the editor menu). */
    void ask(String task, String label, boolean autoProblems) { submit(task, label, autoProblems, false); }

    void quick(int i) {
        String[] q = AiProtocol.QUICK[i];
        boolean wholeProject = q.length > 3 && "p".equals(q[3]); // these work on the project, so no open file is needed
        if (!wholeProject && !host.hasFile()) {
            Ui.toast(getContext(), "Open a file first.");
            return;
        }
        submit(q[1], q[0], "1".equals(q[2]), wholeProject);
    }

    // ------------------------------------------------------------ colors and small views
    private int dp(float v) { return Ui.dp(getContext(), v); }

    private int col(int attr, int def) { return MaterialColors.getColor(getContext(), attr, def); }

    private int onSurface() { return col(com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1F); }

    private int dim() { return col(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF5F6368); }

    private int primary() { return col(androidx.appcompat.R.attr.colorPrimary, 0xFF006A6A); }

    private int container() { return col(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0x22888888); }

    private int primaryContainer() { return col(com.google.android.material.R.attr.colorPrimaryContainer, 0x3300BFA5); }

    private int onPrimaryContainer() { return col(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF003730); }

    private int outline() { return col(com.google.android.material.R.attr.colorOutlineVariant, 0x55888888); }

    private int tertiaryContainer() { return col(com.google.android.material.R.attr.colorTertiaryContainer, 0xFFFFDDB0); }

    private int onTertiaryContainer() { return col(com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF2A1800); }

    private boolean direct() { return Prefs.bool(getContext(), "ai_direct", false); }

    /** The Manual / Direct switch in the header: outlined when manual, amber when the assistant writes files by itself. */
    private void showMode() {
        boolean d = direct();
        mode.setText(d ? "Edit: direct" : "Edit: manual");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        if (d) {
            g.setColor(tertiaryContainer());
            mode.setTextColor(onTertiaryContainer());
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(1), outline());
            mode.setTextColor(dim());
        }
        mode.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), g, rounded(0xFFFFFFFF, 14)));
    }

    /** Manual to direct asks first; direct to manual is immediate. */
    void toggleMode() {
        final Context c = getContext();
        if (direct()) {
            Prefs.put(c, "ai_direct", false);
            showMode();
            Ui.toast(c, "Manual editing: you tap Apply for each file.");
            return;
        }
        new MaterialAlertDialogBuilder(c)
                .setTitle("Turn on direct editing?")
                .setMessage("When a reply contains File, Edit or Rename steps, CloudIDE applies them to your project as soon as the reply "
                        + "finishes, with no extra tap: it can create or replace files, change part of a file, and rename files.\n\nEach reply shows what was written, with Undo for every file and Undo all, until you start "
                        + "a new chat. Steps that cannot be matched to your file exactly, look incomplete, contain masked keys, or are outside the project are skipped.\n\n"
                        + "Manual mode shows an Apply button for each file instead.")
                .setPositiveButton("Direct", (d, w) -> {
                    Prefs.put(c, "ai_direct", true);
                    showMode();
                    Ui.toast(c, "Direct editing on. Replies write files; Undo is on each reply.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "", "ask" (the person confirms each batch of actions) or "full" (actions run by themselves). */
    private String agentMode() {
        String m = Prefs.str(getContext(), "ai_agent");
        return "full".equals(m) || "ask".equals(m) ? m : "";
    }

    private void showAgent() {
        String m = agentMode();
        agentPill.setText(m.isEmpty() ? "Agent off" : "Agent " + m);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        if (m.equals("full")) {
            g.setColor(tertiaryContainer());
            agentPill.setTextColor(onTertiaryContainer());
        } else if (m.equals("ask")) {
            g.setColor(primaryContainer());
            agentPill.setTextColor(onPrimaryContainer());
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(1), outline());
            agentPill.setTextColor(dim());
        }
        agentPill.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), g, rounded(0xFFFFFFFF, 14)));
    }

    /** Off, then ask, then full (which explains what it allows first), then off again. */
    void toggleAgent() {
        final Context c = getContext();
        if (acting) {
            Ui.toast(c, "Stop the running actions first.");
            return;
        }
        String m = agentMode();
        if (m.equals("full")) {
            Prefs.put(c, "ai_agent", "");
            showAgent();
            Ui.toast(c, "Agent off: the assistant only answers and edits files.");
        } else if (m.isEmpty()) {
            Prefs.put(c, "ai_agent", "ask");
            showAgent();
            Ui.toast(c, "Agent ask: the assistant can propose actions; you confirm each batch.");
        } else {
            new MaterialAlertDialogBuilder(c)
                    .setTitle("Give the assistant full access?")
                    .setMessage("The assistant can then, without asking each time: create and open projects, change this app's settings and a project's settings, "
                            + "read, write, rename and delete files, install language plugins, run any shell command and start builds. It keeps going, reading each result, "
                            + "for up to " + AiActions.MAX_STEPS + " steps per message. Stop ends it at once.\n\n"
                            + "Commands run with this app's own permissions, so a wrong command can delete project files. What it reads (files, command output) is sent to the AI provider, "
                            + "and text inside files or web pages it reads could try to steer it. Its own provider, key, the GitHub tokens and this switch cannot be changed by it. "
                            + "Deleted projects go to a trash folder.\n\nUse Ask to confirm each batch instead.")
                    .setPositiveButton("Full access", (d, w) -> {
                        Prefs.put(c, "ai_agent", "full");
                        showAgent();
                        Ui.toast(c, "Agent full: actions run by themselves.");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private String agentRules() {
        String m = agentMode();
        return m.isEmpty() ? "" : AiActions.rules(m);
    }

    private int onPrimary() { return col(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF); }

    private int errorFill() { return col(androidx.appcompat.R.attr.colorError, 0xFFB3261E); }

    private int onErrorFill() { return col(com.google.android.material.R.attr.colorOnError, 0xFFFFFFFF); }

    private GradientDrawable outlined() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0x00000000);
        g.setStroke(dp(1), outline());
        return g;
    }

    /** The attach button: a check mark and a filled look when on, an outline when off. */
    private void showToggle(int i) {
        pills[i].setText(on[i] ? "\u2713 " + PILL_NAMES[i] : PILL_NAMES[i]);
        style(pills[i], on[i], false);
    }

    /** Send is the accent color with readable text in light and dark; Stop turns red so it is not mistaken for Send. */
    private void styleSend(boolean busy) {
        send.setTextColor(busy ? onErrorFill() : onPrimary());
        send.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(busy ? errorFill() : primary(), 20), rounded(0xFFFFFFFF, 20)));
    }

    private int errorColor() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xFFF2B8B5 : 0xFFB3261E;
    }

    private GradientDrawable rounded(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView label(float sp, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView pill(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(14), 0, dp(14), 0);
        return t;
    }

    private LayoutParams pillParams() {
        LayoutParams lp = new LayoutParams(-2, dp(32));
        lp.rightMargin = dp(6);
        return lp;
    }

    /** A toggle (filled when on) or an action pill (outlined, in the accent color). */
    private void style(TextView t, boolean checked, boolean actionPill) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        if (checked) {
            g.setColor(primaryContainer());
            t.setTextColor(onPrimaryContainer());
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(1), outline());
            t.setTextColor(actionPill ? primary() : dim());
        }
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), g, rounded(0xFFFFFFFF, 16)));
    }

    private TextView action(String text, View.OnClickListener l) {
        TextView t = label(13, primary());
        t.setText(text);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), null, rounded(0xFFFFFFFF, 8)));
        t.setOnClickListener(l);
        return t;
    }

    private TextView note(String text, boolean error) {
        TextView t = label(13, error ? errorColor() : dim());
        t.setText(text);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void refreshTitle() {
        Context c = getContext();
        String miss = AiConfig.missing(c);
        AiProtocol.Provider p = AiConfig.provider(c);
        title.setText(miss != null ? "AI assistant - not set up" : AiConfig.shortName(p) + " - " + AiConfig.model(c, p));
    }

    private void updateIntro() {
        if (!turns.isEmpty() || setupView != null) {
            if (introView != null) {
                transcript.removeView(introView);
                introView = null;
            }
            return;
        }
        if (introView != null) transcript.removeView(introView);
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(0, dp(10), 0, 0);
        TextView t = label(13, dim());
        t.setText("Ask about your code. Select code to focus the question; the open file is attached unless you switch it off below. "
                + "Turn on Project code to send every source file, so the assistant can answer about the whole app. "
                + "Replies with code can be inserted or applied to the editor, and Undo reverts them. "
                + "Files, edits and renames in a reply get an Apply button; switch Edit to direct and they are applied to the project by themselves, with Undo on the reply. "
                + "Agent (ask or full) also lets it create and open projects, change settings and run commands.");
        box.addView(t);
        String miss = AiConfig.missing(getContext());
        if (miss != null) {
            TextView m = label(13, onSurface());
            m.setText(miss);
            m.setPadding(0, dp(10), 0, 0);
            box.addView(m);
            box.addView(action("Set up the assistant", v -> host.openSettings()), new LayoutParams(-2, -2));
        }
        introView = box;
        transcript.addView(box, 0);
    }

    private void clearChat() {
        cancelRunning();
        epoch++;
        seed = null;
        agentSteps = 0;
        turns.clear();
        transcript.removeAllViews();
        introView = null;
        setupView = null;
        updateIntro();
    }

    // ------------------------------------------------------------ sending
    private void sendClicked() {
        if (client != null || acting) {
            stop();
            return;
        }
        String s = input.getText().toString().trim();
        if (s.isEmpty()) return;
        submit(s, s, false); // the box is cleared once the request really starts, so a setup or consent step does not lose the question
    }

    private void submit(final String task, final String label, final boolean auto) { submit(task, label, auto, false); }

    private void submit(final String task, final String label, final boolean auto, final boolean wholeProject) {
        if (client != null || acting) return;
        agentSteps = 0;
        final Context c = getContext();
        String miss = AiConfig.missing(c);
        if (miss != null) {
            showSetup(miss);
            return;
        }
        final AiProtocol.Provider p = AiConfig.provider(c);
        if (!AiConfig.consented(c, p)) {
            askConsent(p, new Runnable() {
                @Override
                public void run() { submit(task, label, auto, wholeProject); }
            });
            return;
        }
        if (setupView != null) {
            transcript.removeView(setupView);
            setupView = null;
        }
        if (task.equals(input.getText().toString().trim())) input.setText("");
        AiProtocol.Context ctx = host.context(on[0], on[1], on[2] || auto, on[3], on[4] || wholeProject);
        ctx.task = task;
        final Turn t = new Turn();
        t.label = label;
        t.task = task;
        t.auto = auto;
        t.proj = wholeProject;
        t.prompt = AiProtocol.compose(ctx);
        turns.add(t);
        updateIntro();
        addUser(t, caption(ctx, t.prompt.length()));
        launch(t);
    }

    /** Starts the request for a turn that is in the list with its question drawn. */
    private void launch(final Turn t) {
        final Context c = getContext();
        final AiProtocol.Provider p = AiConfig.provider(c);
        t.holder = new LinearLayout(c);
        t.holder.setOrientation(VERTICAL);
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        transcript.addView(t.holder, lp);
        current = t;
        render(t, true);
        scrollToEnd();

        final AiProtocol.Request req = AiProtocol.chat(p, AiConfig.baseTyped(c, p), AiConfig.key(c, p), AiConfig.model(c, p),
                AiConfig.system(c) + AiProtocol.editRules(direct()) + agentRules(), historyFor(t), AiConfig.maxTokens(c));
        final AiClient cl = new AiClient();
        client = cl;
        setBusy(true);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                cl.stream(p.kind, req, new AiClient.Listener() {
                    @Override
                    public void onText(final String d) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (client != cl) return;
                                t.reply.append(d);
                                t.thinking = false;
                                queueRender(t);
                            }
                        });
                    }

                    @Override
                    public void onThinking() {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (client != cl || t.reply.length() > 0 || t.thinking) return;
                                t.thinking = true;
                                queueRender(t);
                            }
                        });
                    }

                    @Override
                    public void onDone(final String stop) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() { if (client == cl) finish(t, stop, null); }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() { if (client == cl) finish(t, null, message); }
                        });
                    }
                });
            }
        });
    }

    private void finish(Turn t, String stop, String error) {
        client = null;
        current = null;
        t.done = true;
        t.thinking = false;
        t.error = error;
        t.note = AiProtocol.stopNote(stop);
        if ("incomplete".equals(stop)) t.note = "The connection ended before the reply was complete.";
        setBusy(false);
        // direct editing: write the complete files of a reply that ended normally (never one that was cut off or stopped)
        if (error == null && t.note == null && direct()) applyOps(t, AiEdits.ops(t.reply.toString()));
        boolean down = nearBottom();
        render(t, false);
        if (down) scrollToEnd();
        if (error == null && t.note == null && !agentMode().isEmpty()) afterReply(t);
    }

    // ------------------------------------------------------------ agent actions
    /** A finished reply may ask for actions: run them (full), offer them (ask), or tell the model what was wrong with its block. */
    private void afterReply(Turn t) {
        AiActions.Parsed ps = AiActions.parse(t.reply.toString());
        if (ps.empty()) return;
        t.parsed = ps;
        if (ps.actions.isEmpty()) { // only problems: nothing to run, but the model must hear about them
            agentSteps++;
            feedBack(t, new ArrayList<AiActions.Result>(), ps, null);
            return;
        }
        if (agentSteps >= AiActions.MAX_STEPS) {
            t.note = "Stopped after " + AiActions.MAX_STEPS + " steps. Send a message to let the assistant carry on.";
            render(t, false);
            return;
        }
        if ("full".equals(agentMode())) startActions(t);
        else render(t, false); // ask: the reply now shows a Run button
    }

    private void confirmRun(final Turn t) {
        if (acting || client != null || t.parsed == null) return;
        StringBuilder sb = new StringBuilder();
        for (AiActions.Action a : t.parsed.actions) sb.append("\u2022 ").append(a.describe()).append('\n');
        int n = t.parsed.actions.size();
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(n == 1 ? "Run this action?" : "Run " + n + " actions?")
                .setMessage(sb.toString().trim() + "\n\nCommands run in the app's shell with the app's permissions and can change or delete files.")
                .setPositiveButton("Run", (d, w) -> startActions(t))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startActions(final Turn t) {
        if (acting || t.parsed == null || t.parsed.actions.isEmpty()) return;
        final AgentTools tools = host.tools();
        tools.reset();
        acting = true;
        actingTurn = t;
        t.ran = true;
        t.running = true;
        final int ep = ++epoch;
        setBusy(true);
        render(t, false);
        final List<AiActions.Action> acts = new ArrayList<>(t.parsed.actions);
        worker.execute(() -> {
            final List<AiActions.Result> rs = new ArrayList<>();
            File sw = null;
            for (AiActions.Action a : acts) {
                if (ep != epoch) return; // stopped, or the chat was cleared
                AiActions.Result r;
                if (sw != null) r = AiActions.Result.fail(a, "not run: the project was switched. Send this action again once you are in the new project.");
                else r = tools.run(a);
                if (ep != epoch) return;
                if (r.switchTo != null && r.ok) sw = r.switchTo;
                rs.add(r);
                final AiActions.Result fr = r;
                ui.post(() -> {
                    if (ep != epoch || !acting) return;
                    t.results.add(fr);
                    boolean down = nearBottom();
                    render(t, false);
                    if (down) scrollToEnd();
                });
            }
            final File fsw = sw;
            ui.post(() -> actionsDone(t, rs, fsw, ep));
        });
    }

    private void actionsDone(Turn t, List<AiActions.Result> rs, File switchTo, int ep) {
        if (ep != epoch || !acting) return;
        acting = false;
        actingTurn = null;
        t.running = false;
        setBusy(false);
        agentSteps++;
        feedBack(t, rs, t.parsed, switchTo);
    }

    /** Sends the results back so the model can continue, or hands the conversation to the next editor when the project changed. */
    private void feedBack(Turn t, List<AiActions.Result> rs, AiActions.Parsed ps, File switchTo) {
        String results = AiActions.formatResults(rs, ps);
        if (switchTo != null) {
            AiSession.put(new AiSession.Carry(historyFor(null), results, agentSteps));
            render(t, false);
            host.openProject(switchTo);
            return;
        }
        if (agentSteps >= AiActions.MAX_STEPS) {
            t.note = "Stopped after " + AiActions.MAX_STEPS + " steps. Send a message to let the assistant carry on.";
            render(t, false);
            return;
        }
        int n = rs.size();
        submitAgent(results, n == 0 ? "Agent: a problem with the last reply" : "Agent: " + n + (n == 1 ? " action" : " actions") + " done");
    }

    /** The next request of an agent loop: the results of the actions, as a question the person did not type. */
    private void submitAgent(String results, String label) {
        if (client != null) return;
        Context c = getContext();
        if (AiConfig.missing(c) != null) {
            showSetup(AiConfig.missing(c));
            return;
        }
        AiProtocol.Context ctx = host.context(false, false, false, false, false);
        ctx.task = AiActions.followUp(results);
        Turn t = new Turn();
        t.label = label;
        t.task = ctx.task;
        t.agent = true;
        t.auto = true;
        t.prompt = AiProtocol.compose(ctx);
        turns.add(t);
        updateIntro();
        addUser(t, "results sent back - about " + AiProtocol.estimateTokens(t.prompt.length()) + " tokens");
        launch(t);
    }

    private void stop() {
        if (acting) { // actions are running: end the command, forget the rest, and let the person write again
            epoch++;
            host.tools().cancel();
            acting = false;
            Turn at = actingTurn;
            actingTurn = null;
            if (at != null) {
                at.running = false;
                at.note = "Stopped.";
                render(at, false);
            }
            setBusy(false);
            return;
        }
        AiClient cl = client;
        Turn t = current;
        client = null; // late callbacks from the stopped request see a different client and are ignored
        current = null;
        if (cl != null) cl.cancel();
        if (t != null) {
            t.done = true;
            t.thinking = false;
            t.note = "Stopped.";
            render(t, false);
        }
        setBusy(false);
    }

    private void cancelRunning() {
        if (client != null || acting) stop();
    }

    private void setBusy(boolean busy) {
        send.setText(busy ? "Stop" : "Send");
        styleSend(busy);
    }

    /** The turns the model sees: finished question/answer pairs as plain questions, and the new question with its full context. */
    private List<AiProtocol.Msg> historyFor(Turn cur) {
        List<AiProtocol.Msg> m = new ArrayList<>();
        if (seed != null) m.addAll(seed);
        for (Turn t : turns) {
            if (t == cur) {
                m.add(new AiProtocol.Msg("user", t.prompt));
                break;
            }
            if (t.done && t.error == null && t.reply.length() > 0) {
                m.add(new AiProtocol.Msg("user", t.task));
                m.add(new AiProtocol.Msg("assistant", t.reply.toString()));
            }
        }
        return AiProtocol.trimHistory(m, agentMode().isEmpty() ? 12 : 60, 160000);
    }

    private String caption(AiProtocol.Context c, int promptChars) {
        List<String> parts = new ArrayList<>();
        if (c.selection != null && !c.selection.isEmpty()) {
            int lines = 1;
            for (int i = 0; i < c.selection.length(); i++) if (c.selection.charAt(i) == '\n') lines++;
            parts.add("selection, " + lines + (lines == 1 ? " line" : " lines"));
        }
        if (c.fileText != null && !c.fileText.isEmpty()) parts.add(c.filePath == null ? "file" : c.filePath.substring(c.filePath.lastIndexOf('/') + 1));
        if (!c.problems.isEmpty()) parts.add(c.problems.size() + (c.problems.size() == 1 ? " problem" : " problems"));
        if (!c.tree.isEmpty()) parts.add("file list");
        if (!c.code.isEmpty()) parts.add("project code, " + c.code.size() + (c.codeTotal > c.code.size() ? " of " + c.codeTotal : "") + " files");
        if (c.skipped != null) parts.add(c.skipped);
        int tok = AiProtocol.estimateTokens(promptChars);
        parts.add(tok >= 1000 ? String.format(Locale.US, "about %.1fk tokens", tok / 1000.0) : "about " + tok + " tokens");
        return TextUtils.join(" - ", parts);
    }

    private void showSetup(String miss) {
        if (setupView != null) transcript.removeView(setupView);
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        box.addView(note(miss, false));
        box.addView(action("Open AI settings", v -> host.openSettings()), new LayoutParams(-2, -2));
        setupView = box;
        transcript.addView(box);
        scrollToEnd();
    }

    private void askConsent(final AiProtocol.Provider p, final Runnable then) {
        final Context c = getContext();
        String where = AiConfig.base(c, p);
        new MaterialAlertDialogBuilder(c)
                .setTitle("Send your code to " + AiConfig.shortName(p) + "?")
                .setMessage("When you ask the assistant, CloudIDE sends your question and the parts you attach (the open file, the selection, "
                        + "problems, file names and, if you switch it on, the source of the whole project) to " + where + ".\n\nKey files such as .env or keystores are never attached and key-like text is masked. "
                        + "Switch parts on or off with the Attach buttons above the message box. Usage is billed by the provider.")
                .setPositiveButton("Allow", (d, w) -> {
                    AiConfig.setConsented(c, p, true);
                    then.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ drawing the conversation
    private void addUser(Turn t, String caption) {
        Context c = getContext();
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(VERTICAL);
        wrap.setGravity(Gravity.END);
        TextView b = label(14, onPrimaryContainer());
        b.setText(t.label);
        b.setBackground(rounded(primaryContainer(), 16));
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.86f));
        wrap.addView(b, new LayoutParams(-2, -2));
        if (caption != null) {
            TextView cap = label(11.5f, dim());
            cap.setText(caption);
            cap.setPadding(dp(4), dp(3), dp(4), 0);
            wrap.addView(cap, new LayoutParams(-2, -2));
        }
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        transcript.addView(wrap, lp);
        t.userView = wrap;
    }

    private void queueRender(final Turn t) {
        if (renderQueued) return;
        renderQueued = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderQueued = false;
                if (current != t) return;
                boolean down = nearBottom();
                render(t, true);
                if (down) scrollToEnd();
            }
        }, RENDER_EVERY_MS);
    }

    private void render(final Turn t, boolean streaming) {
        LinearLayout h = t.holder;
        h.removeAllViews();
        String md = t.reply.toString();
        if (md.isEmpty()) {
            if (streaming) h.addView(note(t.thinking ? "Thinking..." : "Working...", false));
        } else {
            for (AiProtocol.Block b : AiProtocol.blocks(md)) h.addView(b.code ? codeCard(t, b, streaming) : prose(b.text, !streaming));
        }
        agentLog(h, t);
        if (t.note != null) h.addView(note(t.note, false));
        if (t.error != null) {
            h.addView(note(t.error, true));
            h.addView(action("Try again", v -> retry(t)), new LayoutParams(-2, -2));
        } else if (!streaming && !md.isEmpty()) {
            editBar(h, t);
            LinearLayout row = new LinearLayout(getContext());
            row.addView(action("Copy reply", v -> copy(md)));
            if (t.edits.isEmpty() && !t.ran) row.addView(action("Retry", v -> retry(t))); // after files were written, undo first, then retry
            h.addView(row);
        }
    }

    // ------------------------------------------------------------ applying a reply to the project
    /** Has this exact request already been done for this reply? */
    private AiEdits.Change changeFor(Turn t, AiEdits.Op op) {
        String sig = op.sig();
        for (AiEdits.Change c : t.edits) if (c.sigs.contains(sig)) return c;
        return null;
    }

    /** What the reply asks for that is not done yet: whole files, partial edits and renames, in the order written. */
    private List<AiEdits.Op> pendingOps(Turn t) {
        List<AiEdits.Op> l = new ArrayList<>();
        for (AiEdits.Op op : AiEdits.ops(t.reply.toString())) if (changeFor(t, op) == null) l.add(op);
        return l;
    }

    private List<AiEdits.Change> planOps(List<AiEdits.Op> ops) { return AiEdits.plan(ops, host::readFile); }

    /** Does one checked change. Returns null on success, else why not. A failed rename-then-write puts the file back. */
    private String applyChange(Turn t, AiEdits.Change c) {
        String err;
        if (c.isRename()) {
            err = host.renameFile(c.from, c.rel);
            if (err == null && !c.text.equals(c.old)) {
                err = host.writeFile(c.rel, c.text);
                if (err != null) host.renameFile(c.rel, c.from);
            }
        } else {
            err = host.writeFile(c.rel, c.text);
        }
        if (err == null) t.edits.add(c);
        return err;
    }

    /** Applies the requests that check out; the rest are listed with the reason. Returns how many files were changed. */
    private int applyOps(Turn t, List<AiEdits.Op> ops) {
        int done = 0;
        for (AiEdits.Change c : planOps(ops)) {
            if (c.problem != null) {
                t.skips.add(c.label() + " was not applied: " + c.problem + ".");
                continue;
            }
            if (done >= AiProtocol.MAX_EDIT_FILES) {
                t.skips.add("Stopped after " + done + " files.");
                break;
            }
            String err = applyChange(t, c);
            if (err != null) t.skips.add(c.label() + " was not applied: " + err + ".");
            else done++;
        }
        return done;
    }

    private String undoChange(AiEdits.Change c) {
        if (c.isRename()) {
            String err = host.renameFile(c.rel, c.from);
            if (err == null && !c.text.equals(c.old)) err = host.writeFile(c.from, c.old);
            return err;
        }
        return c.old == null ? host.deleteFile(c.rel) : host.writeFile(c.rel, c.old);
    }

    private static boolean touches(AiEdits.Change a, AiEdits.Change b) {
        return a.rel.equals(b.rel) || (a.from != null && (a.from.equals(b.rel) || a.from.equals(b.from))) || (b.from != null && b.from.equals(a.rel));
    }

    /** Undo for one change, only when no later change of this reply touched the same files; otherwise the later one has to go first. */
    private void undoOne(Turn t, AiEdits.Change c) {
        for (int i = t.edits.indexOf(c) + 1; i < t.edits.size(); i++) {
            if (touches(t.edits.get(i), c)) {
                Ui.toast(getContext(), "A later change touched " + c.rel + ". Use Undo all, or undo that one first.");
                return;
            }
        }
        String err = undoChange(c);
        if (err == null) t.edits.remove(c);
        else Ui.toast(getContext(), "Could not undo: " + err);
        render(t, false);
    }

    private void undoAll(Turn t) {
        List<String> failed = new ArrayList<>();
        for (int i = t.edits.size() - 1; i >= 0; i--) {
            AiEdits.Change c = t.edits.get(i);
            String err = undoChange(c);
            if (err == null) t.edits.remove(i);
            else failed.add(c.label() + ": " + err);
        }
        t.skips.clear();
        for (String f : failed) t.skips.add("Could not undo " + f + ".");
        Ui.toast(getContext(), failed.isEmpty() ? "Restored the project." : "Some changes could not be undone.");
        render(t, false);
    }

    /** Shows what would happen, then does it on OK. Used for one block, one rename, or Apply all. */
    private void confirmApplyOps(final Turn t, final List<AiEdits.Op> ops) {
        List<AiEdits.Change> plan = planOps(ops);
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        for (AiEdits.Change c : plan) if (c.problem == null) {
            sb.append(c.describe()).append('\n');
            ok++;
        }
        for (AiEdits.Change c : plan) if (c.problem != null) sb.append("\n\u26a0 ").append(c.label()).append(": ").append(c.problem).append('.');
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(getContext());
        if (plan.isEmpty()) {
            b.setTitle("Nothing to change").setMessage("The project already matches this.").setPositiveButton("OK", null).show();
            return;
        }
        if (ok == 0) {
            b.setTitle("Cannot apply").setMessage(sb.toString().trim() + "\n\nAsk the AI to fix it, or change the file by hand.").setPositiveButton("OK", null).show();
            return;
        }
        sb.append("\nUndo on this reply puts everything back.");
        b.setTitle(ok == 1 ? "Apply this change?" : "Apply " + ok + " changes?")
                .setMessage(sb.toString())
                .setPositiveButton("Apply", (d, w) -> {
                    t.skips.clear();
                    int n = applyOps(t, ops);
                    Ui.toast(getContext(), n > 0 ? "Applied. Undo restores it." : "Nothing was applied.");
                    render(t, false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String base(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    /** Under a reply: what was applied, what was skipped and why, rename buttons, and the all-at-once buttons. */
    private void editBar(LinearLayout h, final Turn t) {
        for (String s : t.skips) h.addView(note(s, true));
        if (!t.edits.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (AiEdits.Change c : t.edits) names.add(c.isRename() ? base(c.from) + " \u2192 " + base(c.rel) : base(c.rel) + (c.old == null ? " (new)" : ""));
            h.addView(note("Applied to the project: " + TextUtils.join(", ", names), false));
        }
        final List<AiEdits.Op> pending = pendingOps(t);
        int changes = pending.isEmpty() ? 0 : planOps(pending).size();
        HorizontalScrollView hs = new HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        for (final AiEdits.Op op : pending) {
            if (op.kind == AiEdits.Kind.RENAME) {
                row.addView(action("Rename " + base(op.path) + " \u2192 " + base(op.to), v -> confirmApplyOps(t, java.util.Collections.singletonList(op))));
            }
        }
        if (t.parsed != null && !t.ran && !t.parsed.actions.isEmpty()) {
            int n = t.parsed.actions.size();
            row.addView(action("Run " + n + (n == 1 ? " action" : " actions"), v -> confirmRun(t)));
        }
        if (changes >= 2) row.addView(action("Apply all (" + changes + ")", v -> confirmApplyOps(t, pending)));
        if (t.edits.size() >= 2) row.addView(action("Undo all", v -> undoAll(t)));
        if (row.getChildCount() > 0) {
            hs.addView(row);
            h.addView(hs);
        }
    }

    /** Under a reply that asked for actions: each action with its outcome and a short look at its output (tap to see it all). */
    private void agentLog(LinearLayout h, Turn t) {
        if (t.parsed == null) return;
        int n = t.results.size();
        boolean next = t.running && n < t.parsed.actions.size();
        for (int i = 0; i < n; i++) {
            final AiActions.Result r = t.results.get(i);
            TextView line = label(12.5f, r.ok ? primary() : errorColor());
            line.setTypeface(Typeface.MONOSPACE);
            line.setText((r.ok ? "\u2713 " : "\u2717 ") + r.action.describe());
            line.setPadding(0, dp(3), 0, 0);
            h.addView(line);
            if (!r.text.trim().isEmpty()) {
                final TextView out = label(11.5f, dim());
                out.setTypeface(Typeface.MONOSPACE);
                out.setText(preview(r.text, false));
                out.setPadding(dp(14), 0, 0, dp(2));
                final boolean[] open = {false};
                out.setOnClickListener(v -> {
                    open[0] = !open[0];
                    out.setText(preview(r.text, open[0]));
                });
                h.addView(out);
            }
        }
        if (next) h.addView(note("Running: " + t.parsed.actions.get(n).describe() + "...", false));
    }

    private static String preview(String text, boolean full) {
        String t = text.trim();
        if (full) return AiActions.clipMiddle(t, 6000) + "\n(tap to fold)";
        String[] ls = t.split("\n");
        StringBuilder b = new StringBuilder();
        boolean more = ls.length > 5;
        for (int i = 0; i < Math.min(5, ls.length); i++) {
            String l = ls[i];
            if (l.length() > 110) {
                l = l.substring(0, 110) + "...";
                more = true;
            }
            b.append(i > 0 ? "\n" : "").append(l);
        }
        return more ? b + "\n... (tap to see all)" : b.toString();
    }

    private TextView prose(String text, boolean selectable) {
        TextView t = label(14, onSurface());
        t.setLineSpacing(0, 1.12f);
        t.setText(spans(text));
        t.setTextIsSelectable(selectable);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private CharSequence spans(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        int codeBg = container();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = sb.length();
            boolean heading = false;
            Matcher hm = HEADING.matcher(line);
            if (hm.matches()) {
                line = hm.group(1);
                heading = true;
            } else {
                Matcher bm = BULLET.matcher(line);
                if (bm.matches()) line = bm.group(1) + "\u2022 " + bm.group(2);
            }
            for (AiProtocol.Seg s : AiProtocol.inline(line)) {
                int a = sb.length();
                sb.append(s.text);
                int b = sb.length();
                if (s.bold) sb.setSpan(new StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (s.code) {
                    sb.setSpan(new TypefaceSpan("monospace"), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(codeBg), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.93f), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (heading && sb.length() > start) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.12f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb;
    }

    private View codeCard(final Turn t, final AiProtocol.Block b, boolean streaming) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setBackground(rounded(container(), 10));
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(2), dp(4), 0);
        TextView lang = label(12, dim());
        lang.setText(b.lang.isEmpty() ? "code" : b.lang);
        if (AiActions.isActionLang(b.lang)) lang.setText("agent actions");
        if (b.path != null) {
            lang.setText(b.edit ? "edit  " + b.path : b.path);
            lang.setTextColor(primary());
            lang.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            lang.setSingleLine(true);
            lang.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        }
        bar.addView(lang, new LayoutParams(0, -2, 1f));
        if (b.path != null) {
            if (!b.open && !streaming) {
                bar.addView(action("Copy", v -> copy(b.text)));
                final AiEdits.Op op = AiEdits.of(b);
                final AiEdits.Change e = changeFor(t, op);
                if (e != null) {
                    TextView ok = label(12, primary());
                    ok.setText("\u2713 Applied");
                    ok.setPadding(dp(6), 0, dp(6), 0);
                    bar.addView(ok);
                    bar.addView(action("Undo", v -> undoOne(t, e)));
                } else {
                    bar.addView(action("Apply", v -> confirmApplyOps(t, java.util.Collections.singletonList(op))));
                }
            }
        } else if (!b.open && AiActions.isActionLang(b.lang)) {
            bar.addView(action("Copy", v -> copy(b.text))); // actions are run by the Agent switch, never by Insert or Replace
        } else if (!b.open) {
            bar.addView(action("Copy", v -> copy(b.text)));
            bar.addView(action("Insert", v -> {
                if (!host.hasFile()) {
                    Ui.toast(getContext(), "Open a file first.");
                    return;
                }
                host.insertAtCursor(b.text);
                Ui.toast(getContext(), "Inserted at the cursor. Undo reverts it.");
            }));
            bar.addView(action("Replace", v -> replace(b.text)));
        }
        card.addView(bar, new LayoutParams(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        TextView code = label(12.5f, onSurface());
        Fonts.apply(code, c);
        code.setText(b.text);
        code.setPadding(dp(12), dp(2), dp(12), dp(10));
        hs.addView(code);
        card.addView(hs, new LayoutParams(-1, -2));
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);
        return card;
    }

    private void replace(final String code) {
        if (!host.hasFile()) {
            Ui.toast(getContext(), "Open a file first.");
            return;
        }
        if (host.hasSelection()) {
            host.replaceSelection(code);
            Ui.toast(getContext(), "Replaced the selection. Undo reverts it.");
            return;
        }
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Replace the whole file?")
                .setMessage("Nothing is selected, so this replaces everything in the open file with this code. Undo reverts it.")
                .setPositiveButton("Replace", (d, w) -> {
                    host.replaceFile(code);
                    Ui.toast(getContext(), "Replaced the file. Undo reverts it.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("CloudIDE", text));
        Ui.toast(getContext(), "Copied");
    }

    private void retry(Turn t) {
        if (client != null || acting) return;
        int i = turns.indexOf(t);
        if (i < 0) return;
        // drop this question and everything after it, then ask again with fresh context
        for (int k = turns.size() - 1; k >= i; k--) {
            Turn x = turns.remove(k);
            transcript.removeView(x.userView);
            transcript.removeView(x.holder);
        }
        if (t.agent) { // an agent request: send the same results again
            Turn n = new Turn();
            n.label = t.label;
            n.task = t.task;
            n.prompt = t.prompt;
            n.agent = true;
            n.auto = true;
            turns.add(n);
            addUser(n, null);
            launch(n);
            return;
        }
        submit(t.task, t.label, t.auto, t.proj);
    }

    private boolean nearBottom() {
        View ch = scroll.getChildAt(0);
        return ch == null || ch.getBottom() - (scroll.getHeight() + scroll.getScrollY()) < dp(96);
    }

    private void scrollToEnd() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                View ch = scroll.getChildAt(0);
                if (ch != null) scroll.scrollTo(0, ch.getBottom());
            }
        });
    }
}
