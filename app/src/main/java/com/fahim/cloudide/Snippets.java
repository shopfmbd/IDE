package com.fahim.cloudide;

/** Code snippets offered by autocomplete (trigger, body; $0 marks the caret). */
final class Snippets {
    private Snippets() { }

    static String[][] get(String id) {
        switch (id) {
            case "java":
                return new String[][]{
                        {"sout", "System.out.println($0);"},
                        {"fori", "for (int i = 0; i < $0; i++) {\n    \n}"},
                        {"psvm", "public static void main(String[] args) {\n    $0\n}"},
                        {"onCreate", "@Override\nprotected void onCreate(Bundle savedInstanceState) {\n    super.onCreate(savedInstanceState);\n    $0\n}"},
                        {"trycatch", "try {\n    $0\n} catch (Exception e) {\n    e.printStackTrace();\n}"},
                        {"toast", "Toast.makeText(this, \"$0\", Toast.LENGTH_SHORT).show();"},
                        {"logd", "Log.d(\"TAG\", \"$0\");"}};
            case "kotlin":
                return new String[][]{
                        {"main", "fun main() {\n    $0\n}"},
                        {"fori", "for (i in 0 until $0) {\n    \n}"},
                        {"onCreate", "override fun onCreate(savedInstanceState: Bundle?) {\n    super.onCreate(savedInstanceState)\n    $0\n}"},
                        {"toast", "Toast.makeText(this, \"$0\", Toast.LENGTH_SHORT).show()"},
                        {"logd", "Log.d(\"TAG\", \"$0\")"}};
            case "xml":
                return new String[][]{
                        {"linear", "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\"\n    android:orientation=\"vertical\">\n    $0\n</LinearLayout>"},
                        {"textview", "<TextView\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"$0\" />"},
                        {"button", "<Button\n    android:id=\"@+id/$0\"\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Button\" />"},
                        {"edittext", "<EditText\n    android:id=\"@+id/$0\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:hint=\"Hint\" />"}};
            default:
                return new String[0][];
        }
    }
}
