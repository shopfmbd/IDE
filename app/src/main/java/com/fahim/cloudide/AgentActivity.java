package com.fahim.cloudide;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.File;

/**
 * The AI assistant on its own screen, opened from the project list when no project is open. It has no editor, so the assistant starts with
 * the agent actions (create a project, open one, change settings, run commands); once it creates or opens a project, the editor starts
 * and the conversation carries on there ({@link AiSession}).
 */
public class AgentActivity extends AppCompatActivity implements AiPanel.Host, AgentTools.Link {
    private AiPanel ai;
    private AgentTools tools;

    @Override
    protected void onCreate(Bundle b) {
        Ui.edge(this);
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar tb = new MaterialToolbar(this);
        tb.setTitle(R.string.ai_assistant);
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.setNavigationOnClickListener(v -> finish());
        root.addView(tb, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        ai = new AiPanel(this, this);
        ai.setStandalone();
        root.addView(ai, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        Ui.insets(root, true, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ai.onShown(); // the provider or the access mode may have changed in Settings
    }

    @Override
    protected void onDestroy() {
        if (ai != null) ai.shutdown();
        super.onDestroy();
    }

    // ---- AiPanel.Host: no file is open here
    @Override
    public AiProtocol.Context context(boolean file, boolean selection, boolean problems, boolean tree, boolean project) {
        AiProtocol.Context c = new AiProtocol.Context();
        c.projectNote = "No project is open (this is the assistant screen, not an editor). Projects folder: " + Prefs.root(this).getAbsolutePath()
                + ". Use create_project or open_project to work on one.";
        return c;
    }

    @Override
    public boolean hasSelection() { return false; }

    @Override
    public boolean hasFile() { return false; }

    @Override
    public void insertAtCursor(String code) { }

    @Override
    public void replaceSelection(String code) { }

    @Override
    public void replaceFile(String code) { }

    @Override
    public void openSettings() { startActivity(new Intent(this, SettingsActivity.class)); }

    @Override
    public void openWebAi() { Ui.toast(this, "Open a project to use the AI website."); }

    @Override
    public String readFile(String rel) { return null; }

    @Override
    public String writeFile(String rel, String text) { return "no project is open here; use create_project or open_project first"; }

    @Override
    public String removeFile(String rel) { return "no project is open here"; }

    @Override
    public String renameFile(String from, String to) { return "no project is open here"; }

    @Override
    public AgentTools tools() {
        if (tools == null) tools = new AgentTools(this, this);
        return tools;
    }

    @Override
    public void openProject(File dir) {
        Prefs.touch(this, dir.getName());
        startActivity(new Intent(this, EditorActivity.class).putExtra("dir", dir.getAbsolutePath()).putExtra("ai_resume", true));
        finish();
    }

    // ---- AgentTools.Link: there is no editor, so only what works on disk
    @Override
    public File project() { return null; }

    @Override
    public boolean editor() { return false; }

    @Override
    public ProjectConfig config() { return null; }

    @Override
    public void configChanged() { }

    @Override
    public void settingsChanged() { }

    @Override
    public void pluginsChanged() { }

    @Override
    public String showFile(String rel, int line) { return "no editor is open"; }

    @Override
    public String startBuild(boolean run) { return "a build needs the project open in the editor"; }

    @Override
    public boolean building() { return false; }

    @Override
    public int buildCount() { return 0; }

    @Override
    public boolean buildOk() { return false; }

    @Override
    public String buildReport() { return ""; }
}
