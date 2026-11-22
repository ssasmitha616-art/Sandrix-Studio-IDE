package com.example.sandrix;

import com.example.sandrix.engine.BuildEngineTask;
import com.example.sandrix.intel.JavaProjectIndexer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Phase2IdeQualityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void writeUtf8(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private File createSampleProject() throws IOException {
        File proj = tmp.newFolder("sample_ide_proj");
        writeUtf8(new File(proj, "src/com/demo/Helper.java"),
                "package com.demo;\n\n"
                        + "public class Helper {\n"
                        + "    public int magicValue = 42;\n"
                        + "    public int computeSum(int a, int b) {\n"
                        + "        return a + b;\n"
                        + "    }\n"
                        + "}\n");
        writeUtf8(new File(proj, "src/com/demo/MainActivity.java"),
                "package com.demo;\n\n"
                        + "import android.app.Activity;\n"
                        + "import android.os.Bundle;\n"
                        + "import android.widget.TextView;\n\n"
                        + "public class MainActivity extends Activity {\n"
                        + "    @Override\n"
                        + "    protected void onCreate(Bundle savedInstanceState) {\n"
                        + "        super.onCreate(savedInstanceState);\n"
                        + "        setContentView(R.layout.activity_main);\n"
                        + "        Helper helper = new Helper();\n"
                        + "        int total = helper.computeSum(1, 2);\n"
                        + "        TextView tv = findViewById(R.id.tvTitle);\n"
                        + "        String msg = getString(R.string.greeting);\n"
                        + "    }\n"
                        + "}\n");
        writeUtf8(new File(proj, "res/values/strings.xml"),
                "<resources>\n"
                        + "    <string name=\"app_name\">Demo</string>\n"
                        + "    <string name=\"greeting\">Hello Sandrix</string>\n"
                        + "</resources>\n");
        writeUtf8(new File(proj, "res/values/colors.xml"),
                "<resources>\n"
                        + "    <color name=\"accent_cyan\">#38BDF8</color>\n"
                        + "</resources>\n");
        writeUtf8(new File(proj, "res/layout/activity_main.xml"),
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:layout_width=\"match_parent\"\n"
                        + "    android:layout_height=\"match_parent\"\n"
                        + "    android:orientation=\"vertical\">\n"
                        + "    <TextView\n"
                        + "        android:id=\"@+id/tvTitle\"\n"
                        + "        android:layout_width=\"wrap_content\"\n"
                        + "        android:layout_height=\"wrap_content\"\n"
                        + "        android:text=\"@string/greeting\" />\n"
                        + "</LinearLayout>\n");
        return proj;
    }

    @Test
    public void javaAutocompleteSuggestsMembersAndResources() throws Exception {
        File proj = createSampleProject();
        JavaProjectIndexer.ProjectIndex idx = JavaProjectIndexer.buildIndex(proj, Collections.emptyMap());
        File mainFile = new File(proj, "src/com/demo/MainActivity.java");

        String snippetMember = "package com.demo;\npublic class MainActivity {\n    void test() {\n        Helper helper = new Helper();\n        helper.comp";
        List<JavaProjectIndexer.CompletionCandidate> memberSugs = JavaProjectIndexer.suggestJava(
                idx, mainFile, snippetMember, snippetMember.length());
        assertTrue(memberSugs.stream().anyMatch(c -> "MTH".equals(c.kind) && c.label.contains("computeSum")));

        String snippetRes = "package com.demo;\npublic class MainActivity {\n    void test() { getString(R.string.g";
        List<JavaProjectIndexer.CompletionCandidate> resSugs = JavaProjectIndexer.suggestJava(
                idx, mainFile, snippetRes, snippetRes.length());
        assertTrue(resSugs.stream().anyMatch(c -> "RES".equals(c.kind) && c.label.equals("R.string.greeting")));
    }

    @Test
    public void goToDefinitionAndFindReferencesWorkAcrossJavaAndXml() throws Exception {
        File proj = createSampleProject();
        JavaProjectIndexer.ProjectIndex idx = JavaProjectIndexer.buildIndex(proj, Collections.emptyMap());
        File mainFile = new File(proj, "src/com/demo/MainActivity.java");
        File helperFile = new File(proj, "src/com/demo/Helper.java");
        File stringsFile = new File(proj, "res/values/strings.xml");

        String mainCode = new String(java.nio.file.Files.readAllBytes(mainFile.toPath()), StandardCharsets.UTF_8);

        int callOffset = mainCode.indexOf("computeSum") + 3;
        JavaProjectIndexer.NavigationTarget methodTarget =
                JavaProjectIndexer.resolveDefinition(idx, mainFile, mainCode, callOffset);
        assertNotNull(methodTarget);
        assertEquals(helperFile.getAbsolutePath(), methodTarget.file.getAbsolutePath());
        assertEquals(5, methodTarget.lineNumber);

        int resOffset = mainCode.indexOf("R.string.greeting") + 10;
        JavaProjectIndexer.NavigationTarget resTarget =
                JavaProjectIndexer.resolveDefinition(idx, mainFile, mainCode, resOffset);
        assertNotNull(resTarget);
        assertEquals(stringsFile.getAbsolutePath(), resTarget.file.getAbsolutePath());
        assertEquals(3, resTarget.lineNumber);

        List<JavaProjectIndexer.ReferenceLocation> refs =
                JavaProjectIndexer.findReferences(proj, Collections.emptyMap(), "greeting");
        assertTrue("Expected declaration + Java + layout references", refs.size() >= 3);
    }

    @Test
    public void renameRefactorAndOrganizeImportsWorkCleanly() throws Exception {
        File proj = createSampleProject();
        JavaProjectIndexer.RenameResult res = JavaProjectIndexer.renameSymbolInProject(
                proj, Collections.emptyMap(), "Helper", "MathUtils");
        assertTrue(res.success);
        assertTrue(res.occurrencesReplaced >= 3);
        File renamedFile = new File(proj, "src/com/demo/MathUtils.java");
        assertTrue(renamedFile.exists());
        assertFalse(new File(proj, "src/com/demo/Helper.java").exists());

        String updatedMain = new String(
                java.nio.file.Files.readAllBytes(new File(proj, "src/com/demo/MainActivity.java").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(updatedMain.contains("MathUtils helper = new MathUtils();"));

        JavaProjectIndexer.ProjectIndex idx = JavaProjectIndexer.buildIndex(proj, new HashMap<>());
        String messyJava = "package com.demo;\n\nimport android.widget.Toast;\n\npublic class Runner {\n"
                + "    void go() { Intent i = new Intent(); Log.d(\"Tag\", \"hi\"); }\n"
                + "}\n";
        String organized = JavaProjectIndexer.organizeImports(idx, messyJava);
        assertTrue(organized.contains("import android.content.Intent;"));
        assertTrue(organized.contains("import android.util.Log;"));
        assertFalse("Unused Toast import should be removed", organized.contains("import android.widget.Toast;"));
    }

    @Test
    public void liveJavaDiagnosticsAndXmlAutocompleteValidationWork() throws Exception {
        File proj = createSampleProject();
        JavaProjectIndexer.ProjectIndex idx = JavaProjectIndexer.buildIndex(proj, Collections.emptyMap());

        // Broken Java: missing semicolon + unresolved symbol + unclosed brace
        File javaFile = new File(proj, "src/com/demo/Broken.java");
        String brokenJava = "package com.demo;\n"
                + "public class Broken {\n"
                + "    void f() {\n"
                + "        Intent i = null\n"
                + "        int x = 1;\n"
                + "}\n";
        List<BuildEngineTask.BuildDiagnostic> javaDiags = JavaProjectIndexer.analyzeFile(idx, javaFile, brokenJava);
        assertTrue(javaDiags.stream().anyMatch(d -> d.message.contains("semicolon") && d.lineNumber == 4));
        assertTrue(javaDiags.stream().anyMatch(d -> d.message.contains("Unresolved symbol 'Intent'")));
        assertTrue(javaDiags.stream().anyMatch(d -> d.message.contains("Unclosed opening brace")));

        // XML autocomplete: value inside layout_width & @string/
        File layoutFile = new File(proj, "res/layout/activity_main.xml");
        String xmlCtxWidth = "<TextView android:layout_width=\"m";
        List<JavaProjectIndexer.CompletionCandidate> widthSugs =
                JavaProjectIndexer.suggestXml(idx, layoutFile, xmlCtxWidth, xmlCtxWidth.length());
        assertTrue(widthSugs.stream().anyMatch(c -> "match_parent".equals(c.insertText)));

        String xmlCtxStr = "<TextView android:text=\"@string/g";
        List<JavaProjectIndexer.CompletionCandidate> strSugs =
                JavaProjectIndexer.suggestXml(idx, layoutFile, xmlCtxStr, xmlCtxStr.length());
        assertTrue(strSugs.stream().anyMatch(c -> "@string/greeting".equals(c.insertText)));

        // XML validation: missing xmlns:android, missing layout_height, unresolved @string/nope
        String badLayout = "<LinearLayout android:layout_width=\"match_parent\">\n"
                + "    <TextView android:layout_width=\"wrap_content\" android:text=\"@string/nope\" />\n"
                + "</LinearLayout>\n";
        List<BuildEngineTask.BuildDiagnostic> xmlDiags = JavaProjectIndexer.analyzeFile(idx, layoutFile, badLayout);
        assertTrue(xmlDiags.stream().anyMatch(d -> d.message.contains("xmlns:android")));
        assertTrue(xmlDiags.stream().anyMatch(d -> d.message.contains("layout_height")));
        assertTrue(xmlDiags.stream().anyMatch(d -> d.message.contains("@string/nope")));
    }
}
