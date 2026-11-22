package com.example.sandrix.intel;

import com.example.sandrix.engine.BuildEngineTask;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Pure-Java IDE intelligence engine for Sandrix Studio (Phase 2 – IDE Quality).
 *
 * <p>Provides:
 * <ul>
 *     <li>Project-wide indexing of Java classes, methods, fields, and Android XML resources</li>
 *     <li>Context-aware Java &amp; XML autocomplete suggestions</li>
 *     <li>Go to Definition across Java symbols and {@code R.*} / {@code @.../} resources</li>
 *     <li>Project-wide Find References</li>
 *     <li>Safe symbol Rename refactoring &amp; Organize Imports</li>
 *     <li>Live static diagnostics &amp; XML validation</li>
 * </ul>
 */
public final class JavaProjectIndexer {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([a-zA-Z_][a-zA-Z0-9_.*]*)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_DECL_PATTERN =
            Pattern.compile("\\b(?:public|protected|private|abstract|final|static|sealed|non-sealed|\\s)*\\b(class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+extends\\s+([A-Za-z_][A-Za-z0-9_.]*))?");
    private static final Pattern METHOD_DECL_PATTERN =
            Pattern.compile("(?m)^[ \\t]*(?:@[A-Za-z_][A-Za-z0-9_]*(?:\\([^)]*\\))?\\s*)*(?:(?:public|protected|private|static|final|synchronized|abstract|default)\\s+)+([A-Za-z_][A-Za-z0-9_<>.\\[\\]]*)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[A-Za-z0-9_.,\\s]+)?\\s*\\{");
    private static final Pattern FIELD_DECL_PATTERN =
            Pattern.compile("(?m)^[ \\t]*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)+([A-Za-z_][A-Za-z0-9_<>.\\[\\]]*)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*(?:=|;)");
    private static final Pattern LOCAL_VAR_PATTERN =
            Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_<>.\\[\\]]*)\\s+([a-z_][A-Za-z0-9_]*)\\s*(?:=|;|,|\\))");
    private static final Pattern XML_ID_DEF_PATTERN = Pattern.compile("@\\+id/([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern XML_RES_VALUE_PATTERN = Pattern.compile("<(string|color|dimen)\\s+[^>]*name\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final Pattern JAVA_R_TOKEN_PATTERN =
            Pattern.compile("(?:android\\.)?R\\.(id|layout|string|color|drawable|dimen)\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern XML_RES_REF_PATTERN =
            Pattern.compile("@(\\+?id|layout|string|color|drawable|dimen)/([A-Za-z_][A-Za-z0-9_.]*)");

    private static final Set<String> JAVA_NON_TYPE_KEYWORDS = new HashSet<>(Arrays.asList(
            "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
            "return", "try", "catch", "finally", "throw", "throws", "new", "class", "interface",
            "enum", "record", "sealed", "non-sealed", "permits", "yield",
            "extends", "implements", "import", "package", "public", "private", "protected",
            "static", "final", "abstract", "synchronized", "volatile", "transient", "native",
            "void", "boolean", "byte", "char", "short", "int", "long", "float", "double",
            "true", "false", "null", "this", "super", "instanceof", "var", "assert", "strictfp",
            "Override", "SuppressWarnings", "Deprecated", "FunctionalInterface", "SafeVarargs"
    ));

    private static final Map<String, String> SDK_IMPORT_MAP = new LinkedHashMap<>();
    private static final Map<String, List<MemberCandidate>> SDK_MEMBERS = new LinkedHashMap<>();

    static {
        // Core Android & JDK auto-import map
        SDK_IMPORT_MAP.put("Manifest", "android.Manifest");
        SDK_IMPORT_MAP.put("Activity", "android.app.Activity");
        SDK_IMPORT_MAP.put("AlertDialog", "android.app.AlertDialog");
        SDK_IMPORT_MAP.put("Application", "android.app.Application");
        SDK_IMPORT_MAP.put("Dialog", "android.app.Dialog");
        SDK_IMPORT_MAP.put("NotificationManager", "android.app.NotificationManager");
        SDK_IMPORT_MAP.put("PendingIntent", "android.app.PendingIntent");
        SDK_IMPORT_MAP.put("Service", "android.app.Service");
        SDK_IMPORT_MAP.put("Context", "android.content.Context");
        SDK_IMPORT_MAP.put("DialogInterface", "android.content.DialogInterface");
        SDK_IMPORT_MAP.put("Intent", "android.content.Intent");
        SDK_IMPORT_MAP.put("SharedPreferences", "android.content.SharedPreferences");
        SDK_IMPORT_MAP.put("Color", "android.graphics.Color");
        SDK_IMPORT_MAP.put("Bitmap", "android.graphics.Bitmap");
        SDK_IMPORT_MAP.put("Canvas", "android.graphics.Canvas");
        SDK_IMPORT_MAP.put("Paint", "android.graphics.Paint");
        SDK_IMPORT_MAP.put("Typeface", "android.graphics.Typeface");
        SDK_IMPORT_MAP.put("Uri", "android.net.Uri");
        SDK_IMPORT_MAP.put("Build", "android.os.Build");
        SDK_IMPORT_MAP.put("Bundle", "android.os.Bundle");
        SDK_IMPORT_MAP.put("Handler", "android.os.Handler");
        SDK_IMPORT_MAP.put("Looper", "android.os.Looper");
        SDK_IMPORT_MAP.put("Log", "android.util.Log");
        SDK_IMPORT_MAP.put("Gravity", "android.view.Gravity");
        SDK_IMPORT_MAP.put("LayoutInflater", "android.view.LayoutInflater");
        SDK_IMPORT_MAP.put("Menu", "android.view.Menu");
        SDK_IMPORT_MAP.put("MenuItem", "android.view.MenuItem");
        SDK_IMPORT_MAP.put("View", "android.view.View");
        SDK_IMPORT_MAP.put("ViewGroup", "android.view.ViewGroup");
        SDK_IMPORT_MAP.put("Button", "android.widget.Button");
        SDK_IMPORT_MAP.put("CheckBox", "android.widget.CheckBox");
        SDK_IMPORT_MAP.put("EditText", "android.widget.EditText");
        SDK_IMPORT_MAP.put("FrameLayout", "android.widget.FrameLayout");
        SDK_IMPORT_MAP.put("ImageButton", "android.widget.ImageButton");
        SDK_IMPORT_MAP.put("ImageView", "android.widget.ImageView");
        SDK_IMPORT_MAP.put("LinearLayout", "android.widget.LinearLayout");
        SDK_IMPORT_MAP.put("ListView", "android.widget.ListView");
        SDK_IMPORT_MAP.put("ProgressBar", "android.widget.ProgressBar");
        SDK_IMPORT_MAP.put("ScrollView", "android.widget.ScrollView");
        SDK_IMPORT_MAP.put("Switch", "android.widget.Switch");
        SDK_IMPORT_MAP.put("TextView", "android.widget.TextView");
        SDK_IMPORT_MAP.put("Toast", "android.widget.Toast");
        SDK_IMPORT_MAP.put("AppCompatActivity", "androidx.appcompat.app.AppCompatActivity");
        SDK_IMPORT_MAP.put("Fragment", "androidx.fragment.app.Fragment");
        SDK_IMPORT_MAP.put("LinearLayoutManager", "androidx.recyclerview.widget.LinearLayoutManager");
        SDK_IMPORT_MAP.put("RecyclerView", "androidx.recyclerview.widget.RecyclerView");
        SDK_IMPORT_MAP.put("MaterialButton", "com.google.android.material.button.MaterialButton");
        SDK_IMPORT_MAP.put("Snackbar", "com.google.android.material.snackbar.Snackbar");
        SDK_IMPORT_MAP.put("File", "java.io.File");
        SDK_IMPORT_MAP.put("IOException", "java.io.IOException");
        SDK_IMPORT_MAP.put("ArrayList", "java.util.ArrayList");
        SDK_IMPORT_MAP.put("Arrays", "java.util.Arrays");
        SDK_IMPORT_MAP.put("Collections", "java.util.Collections");
        SDK_IMPORT_MAP.put("HashMap", "java.util.HashMap");
        SDK_IMPORT_MAP.put("HashSet", "java.util.HashSet");
        SDK_IMPORT_MAP.put("List", "java.util.List");
        SDK_IMPORT_MAP.put("Locale", "java.util.Locale");
        SDK_IMPORT_MAP.put("Map", "java.util.Map");
        SDK_IMPORT_MAP.put("Objects", "java.util.Objects");
        SDK_IMPORT_MAP.put("Set", "java.util.Set");
        SDK_IMPORT_MAP.put("ExecutorService", "java.util.concurrent.ExecutorService");
        SDK_IMPORT_MAP.put("Executors", "java.util.concurrent.Executors");

        // SDK type member completion catalog
        addSdkMembers("View",
                mc("MTH", "setVisibility(int)", "setVisibility(View.VISIBLE);", 0),
                mc("MTH", "getVisibility()", "getVisibility()", 0),
                mc("MTH", "setOnClickListener(...)", "setOnClickListener(v -> {\n    \n});", 4),
                mc("MTH", "setEnabled(boolean)", "setEnabled(true);", 0),
                mc("MTH", "setBackgroundColor(int)", "setBackgroundColor(Color.TRANSPARENT);", 0),
                mc("MTH", "findViewById(int)", "findViewById(R.id.)", 0),
                mc("FLD", "VISIBLE", "VISIBLE", 0),
                mc("FLD", "INVISIBLE", "INVISIBLE", 0),
                mc("FLD", "GONE", "GONE", 0));

        addSdkMembers("TextView",
                mc("MTH", "setText(CharSequence)", "setText(\"\");", 3),
                mc("MTH", "getText()", "getText().toString()", 0),
                mc("MTH", "setTextColor(int)", "setTextColor(Color.WHITE);", 0),
                mc("MTH", "setTextSize(float)", "setTextSize(16f);", 0),
                mc("MTH", "setVisibility(int)", "setVisibility(View.VISIBLE);", 0),
                mc("MTH", "setOnClickListener(...)", "setOnClickListener(v -> {\n    \n});", 4));

        addSdkMembers("Button",
                mc("MTH", "setText(CharSequence)", "setText(\"\");", 3),
                mc("MTH", "setOnClickListener(...)", "setOnClickListener(v -> {\n    \n});", 4),
                mc("MTH", "setEnabled(boolean)", "setEnabled(true);", 0));

        addSdkMembers("MaterialButton", SDK_MEMBERS.get("Button").toArray(new MemberCandidate[0]));

        addSdkMembers("EditText",
                mc("MTH", "getText()", "getText().toString().trim()", 0),
                mc("MTH", "setText(CharSequence)", "setText(\"\");", 3),
                mc("MTH", "setHint(CharSequence)", "setHint(\"\");", 3),
                mc("MTH", "setError(CharSequence)", "setError(\"\");", 3),
                mc("MTH", "addTextChangedListener(...)", "addTextChangedListener(textWatcher);", 0));

        addSdkMembers("ImageView",
                mc("MTH", "setImageResource(int)", "setImageResource(R.drawable.);", 2),
                mc("MTH", "setColorFilter(int)", "setColorFilter(Color.WHITE);", 0),
                mc("MTH", "setVisibility(int)", "setVisibility(View.VISIBLE);", 0));

        addSdkMembers("Activity",
                mc("MTH", "setContentView(int)", "setContentView(R.layout.activity_main);", 0),
                mc("MTH", "findViewById(int)", "findViewById(R.id.);", 2),
                mc("MTH", "startActivity(Intent)", "startActivity(new Intent(this, MainActivity.class));", 0),
                mc("MTH", "getString(int)", "getString(R.string.);", 2),
                mc("MTH", "getSharedPreferences(...)", "getSharedPreferences(\"app_prefs\", MODE_PRIVATE)", 0),
                mc("MTH", "runOnUiThread(Runnable)", "runOnUiThread(() -> {\n    \n});", 4),
                mc("MTH", "finish()", "finish();", 0));

        addSdkMembers("AppCompatActivity", SDK_MEMBERS.get("Activity").toArray(new MemberCandidate[0]));

        addSdkMembers("Intent",
                mc("MTH", "putExtra(String, String)", "putExtra(\"key\", \"value\");", 0),
                mc("MTH", "getStringExtra(String)", "getStringExtra(\"key\")", 0),
                mc("MTH", "getIntExtra(String, int)", "getIntExtra(\"key\", 0)", 0),
                mc("MTH", "setData(Uri)", "setData(uri);", 0),
                mc("MTH", "addFlags(int)", "addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);", 0),
                mc("FLD", "ACTION_VIEW", "ACTION_VIEW", 0),
                mc("FLD", "FLAG_ACTIVITY_NEW_TASK", "FLAG_ACTIVITY_NEW_TASK", 0));

        addSdkMembers("Toast",
                mc("MTH", "makeText(Context, text, dur)", "makeText(this, \"\", Toast.LENGTH_SHORT).show();", 27),
                mc("FLD", "LENGTH_SHORT", "LENGTH_SHORT", 0),
                mc("FLD", "LENGTH_LONG", "LENGTH_LONG", 0));

        addSdkMembers("Snackbar",
                mc("MTH", "make(View, text, dur)", "make(view, \"\", Snackbar.LENGTH_SHORT).show();", 29),
                mc("FLD", "LENGTH_SHORT", "LENGTH_SHORT", 0),
                mc("FLD", "LENGTH_LONG", "LENGTH_LONG", 0));

        addSdkMembers("Log",
                mc("MTH", "d(String tag, String msg)", "d(\"Sandrix\", \"\");", 3),
                mc("MTH", "i(String tag, String msg)", "i(\"Sandrix\", \"\");", 3),
                mc("MTH", "w(String tag, String msg)", "w(\"Sandrix\", \"\");", 3),
                mc("MTH", "e(String tag, String msg, Throwable)", "e(\"Sandrix\", \"\", e);", 6));

        addSdkMembers("SharedPreferences",
                mc("MTH", "getString(String, String)", "getString(\"key\", \"\")", 0),
                mc("MTH", "getInt(String, int)", "getInt(\"key\", 0)", 0),
                mc("MTH", "getBoolean(String, boolean)", "getBoolean(\"key\", false)", 0),
                mc("MTH", "edit()", "edit()", 0));

        addSdkMembers("String",
                mc("MTH", "length()", "length()", 0),
                mc("MTH", "isEmpty()", "isEmpty()", 0),
                mc("MTH", "trim()", "trim()", 0),
                mc("MTH", "equals(Object)", "equals()", 1),
                mc("MTH", "contains(CharSequence)", "contains(\"\")", 2),
                mc("MTH", "startsWith(String)", "startsWith(\"\")", 2),
                mc("MTH", "substring(int, int)", "substring(0, )", 1),
                mc("MTH", "split(String)", "split(\"\\s+\")", 0),
                mc("MTH", "valueOf(...)", "valueOf()", 1));

        addSdkMembers("List",
                mc("MTH", "add(E)", "add();", 2),
                mc("MTH", "get(int)", "get(0)", 0),
                mc("MTH", "size()", "size()", 0),
                mc("MTH", "isEmpty()", "isEmpty()", 0),
                mc("MTH", "clear()", "clear();", 0));
        addSdkMembers("ArrayList", SDK_MEMBERS.get("List").toArray(new MemberCandidate[0]));

        addSdkMembers("Map",
                mc("MTH", "put(K, V)", "put(key, value);", 0),
                mc("MTH", "get(Object)", "get(key)", 0),
                mc("MTH", "containsKey(Object)", "containsKey(key)", 0),
                mc("MTH", "keySet()", "keySet()", 0),
                mc("MTH", "size()", "size()", 0));
        addSdkMembers("HashMap", SDK_MEMBERS.get("Map").toArray(new MemberCandidate[0]));

        addSdkMembers("Math",
                mc("MTH", "max(a, b)", "max(a, b)", 0),
                mc("MTH", "min(a, b)", "min(a, b)", 0),
                mc("MTH", "abs(x)", "abs(x)", 0),
                mc("MTH", "round(x)", "round(x)", 0),
                mc("FLD", "PI", "PI", 0));

        addSdkMembers("System",
                mc("MTH", "currentTimeMillis()", "currentTimeMillis()", 0),
                mc("FLD", "out", "out", 0));
    }

    private JavaProjectIndexer() {}

    // ==================== Data Types ====================

    public static final class MethodSymbol {
        public final String name;
        public final String returnType;
        public final String paramsRaw;
        public final boolean isStatic;
        public final int lineNumber;

        public MethodSymbol(String name, String returnType, String paramsRaw, boolean isStatic, int lineNumber) {
            this.name = name;
            this.returnType = returnType;
            this.paramsRaw = paramsRaw != null ? paramsRaw.trim() : "";
            this.isStatic = isStatic;
            this.lineNumber = lineNumber;
        }
    }

    public static final class FieldSymbol {
        public final String name;
        public final String type;
        public final boolean isStatic;
        public final int lineNumber;

        public FieldSymbol(String name, String type, boolean isStatic, int lineNumber) {
            this.name = name;
            this.type = type;
            this.isStatic = isStatic;
            this.lineNumber = lineNumber;
        }
    }

    public static final class ClassSymbol {
        public final String packageName;
        public final String name;
        public final String fqn;
        public final String kind;
        public final String superClass;
        public final File file;
        public final int lineNumber;
        public final List<MethodSymbol> methods;
        public final List<FieldSymbol> fields;

        public ClassSymbol(
                String packageName,
                String name,
                String fqn,
                String kind,
                String superClass,
                File file,
                int lineNumber,
                List<MethodSymbol> methods,
                List<FieldSymbol> fields) {
            this.packageName = packageName != null ? packageName : "";
            this.name = name;
            this.fqn = fqn;
            this.kind = kind;
            this.superClass = superClass != null ? superClass : "";
            this.file = file;
            this.lineNumber = lineNumber;
            this.methods = Collections.unmodifiableList(new ArrayList<>(methods));
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        }
    }

    public static final class ResourceTarget {
        public final String type;
        public final String name;
        public final File file;
        public final int lineNumber;

        public ResourceTarget(String type, String name, File file, int lineNumber) {
            this.type = type;
            this.name = name;
            this.file = file;
            this.lineNumber = lineNumber;
        }
    }

    public static final class ResourceCatalog {
        public final Map<String, ResourceTarget> strings = new LinkedHashMap<>();
        public final Map<String, ResourceTarget> colors = new LinkedHashMap<>();
        public final Map<String, ResourceTarget> dimens = new LinkedHashMap<>();
        public final Map<String, ResourceTarget> layouts = new LinkedHashMap<>();
        public final Map<String, ResourceTarget> drawables = new LinkedHashMap<>();
        public final Map<String, List<ResourceTarget>> ids = new LinkedHashMap<>();
    }

    public static final class ProjectIndex {
        public final File projectDir;
        public final Map<String, ClassSymbol> classesByFqn = new LinkedHashMap<>();
        public final Map<String, List<ClassSymbol>> classesBySimpleName = new LinkedHashMap<>();
        public final ResourceCatalog resources = new ResourceCatalog();

        public ProjectIndex(File projectDir) {
            this.projectDir = projectDir;
        }

        public ClassSymbol findClass(String nameOrFqn) {
            if (nameOrFqn == null || nameOrFqn.isEmpty()) return null;
            ClassSymbol direct = classesByFqn.get(nameOrFqn);
            if (direct != null) return direct;
            List<ClassSymbol> list = classesBySimpleName.get(nameOrFqn);
            return (list != null && !list.isEmpty()) ? list.get(0) : null;
        }
    }

    public static final class CompletionCandidate {
        public final String kind;
        public final String label;
        public final String insertText;
        public final int cursorBackOffset;
        public final int tokenStartOverride; // -1 if default editor token start

        public CompletionCandidate(String kind, String label, String insertText, int cursorBackOffset) {
            this(kind, label, insertText, cursorBackOffset, -1);
        }

        public CompletionCandidate(String kind, String label, String insertText, int cursorBackOffset, int tokenStartOverride) {
            this.kind = kind;
            this.label = label;
            this.insertText = insertText;
            this.cursorBackOffset = cursorBackOffset;
            this.tokenStartOverride = tokenStartOverride;
        }
    }

    private static final class MemberCandidate {
        final String kind;
        final String label;
        final String insertText;
        final int cursorBackOffset;

        MemberCandidate(String kind, String label, String insertText, int cursorBackOffset) {
            this.kind = kind;
            this.label = label;
            this.insertText = insertText;
            this.cursorBackOffset = cursorBackOffset;
        }
    }

    public static final class NavigationTarget {
        public final File file;
        public final int lineNumber;
        public final int column;
        public final String displayLabel;

        public NavigationTarget(File file, int lineNumber, int column, String displayLabel) {
            this.file = file;
            this.lineNumber = Math.max(1, lineNumber);
            this.column = Math.max(1, column);
            this.displayLabel = displayLabel;
        }
    }

    public static final class ReferenceLocation {
        public final File file;
        public final int lineNumber;
        public final int column;
        public final String linePreview;
        public final boolean isDeclaration;

        public ReferenceLocation(File file, int lineNumber, int column, String linePreview, boolean isDeclaration) {
            this.file = file;
            this.lineNumber = Math.max(1, lineNumber);
            this.column = Math.max(1, column);
            this.linePreview = linePreview != null ? linePreview : "";
            this.isDeclaration = isDeclaration;
        }
    }

    public static final class RenameResult {
        public final boolean success;
        public final String message;
        public final int filesModified;
        public final int occurrencesReplaced;
        public final File renamedFromFile;
        public final File renamedToFile;
        public final Map<String, String> updatedContentsByPath;

        public RenameResult(
                boolean success,
                String message,
                int filesModified,
                int occurrencesReplaced,
                File renamedFromFile,
                File renamedToFile,
                Map<String, String> updatedContentsByPath) {
            this.success = success;
            this.message = message;
            this.filesModified = filesModified;
            this.occurrencesReplaced = occurrencesReplaced;
            this.renamedFromFile = renamedFromFile;
            this.renamedToFile = renamedToFile;
            this.updatedContentsByPath = Collections.unmodifiableMap(updatedContentsByPath);
        }
    }

    // ==================== Project Indexing ====================

    public static ProjectIndex buildIndex(File projectDir, Map<String, String> openContentByPath) {
        ProjectIndex index = new ProjectIndex(projectDir);
        if (projectDir == null || !projectDir.exists()) {
            return index;
        }
        List<File> files = collectProjectSourceFiles(projectDir);
        for (File f : files) {
            String content = readEffectiveContent(f, openContentByPath);
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".java")) {
                indexJavaFile(index, f, content);
            } else if (name.endsWith(".xml")) {
                indexXmlFile(index, f, content);
            } else if (isDrawableBinaryOrXml(f)) {
                String resName = stripExtension(f.getName());
                index.resources.drawables.putIfAbsent(resName, new ResourceTarget("drawable", resName, f, 1));
            }
        }
        return index;
    }

    private static void indexJavaFile(ProjectIndex index, File file, String content) {
        String pkg = "";
        Matcher pm = PACKAGE_PATTERN.matcher(content);
        if (pm.find()) {
            pkg = pm.group(1);
        }

        Matcher cm = CLASS_DECL_PATTERN.matcher(content);
        ClassSymbol primary = null;
        while (cm.find()) {
            String kind = cm.group(1);
            String name = cm.group(2);
            String superClass = cm.group(3);
            if (name == null || JAVA_NON_TYPE_KEYWORDS.contains(name)) continue;
            int line = lineNumberAtOffset(content, cm.start(2));
            String fqn = pkg.isEmpty() ? name : (pkg + "." + name);
            List<MethodSymbol> methods = new ArrayList<>();
            List<FieldSymbol> fields = new ArrayList<>();
            if (primary == null) {
                extractMembers(content, methods, fields);
            }
            ClassSymbol cls = new ClassSymbol(pkg, name, fqn, kind, superClass, file, line, methods, fields);
            if (primary == null) {
                primary = cls;
            }
            index.classesByFqn.put(fqn, cls);
            index.classesBySimpleName.computeIfAbsent(name, k -> new ArrayList<>()).add(cls);
        }
    }

    private static void extractMembers(String content, List<MethodSymbol> methods, List<FieldSymbol> fields) {
        Matcher mm = METHOD_DECL_PATTERN.matcher(content);
        while (mm.find()) {
            String header = mm.group(0);
            String ret = mm.group(1);
            String name = mm.group(2);
            String params = mm.group(3);
            if (JAVA_NON_TYPE_KEYWORDS.contains(name)) continue;
            boolean isStatic = header.contains("static ");
            methods.add(new MethodSymbol(name, ret, params, isStatic, lineNumberAtOffset(content, mm.start(2))));
        }

        Matcher fm = FIELD_DECL_PATTERN.matcher(content);
        while (fm.find()) {
            String header = fm.group(0);
            String type = fm.group(1);
            String name = fm.group(2);
            if (JAVA_NON_TYPE_KEYWORDS.contains(name) || "class".equals(type)) continue;
            boolean isStatic = header.contains("static ");
            fields.add(new FieldSymbol(name, type, isStatic, lineNumberAtOffset(content, fm.start(2))));
        }
    }

    private static void indexXmlFile(ProjectIndex index, File file, String content) {
        File parent = file.getParentFile();
        String folder = parent != null ? parent.getName().toLowerCase(Locale.ROOT) : "";
        String baseName = stripExtension(file.getName());

        if (folder.startsWith("layout")) {
            index.resources.layouts.putIfAbsent(baseName, new ResourceTarget("layout", baseName, file, 1));
        } else if (folder.startsWith("drawable") || folder.startsWith("mipmap")) {
            index.resources.drawables.putIfAbsent(baseName, new ResourceTarget("drawable", baseName, file, 1));
        }

        if (folder.startsWith("values")) {
            Matcher valM = XML_RES_VALUE_PATTERN.matcher(content);
            while (valM.find()) {
                String tag = valM.group(1);
                String name = valM.group(2);
                int line = lineNumberAtOffset(content, valM.start(2));
                if ("string".equals(tag)) {
                    index.resources.strings.put(name, new ResourceTarget("string", name, file, line));
                } else if ("color".equals(tag)) {
                    index.resources.colors.put(name, new ResourceTarget("color", name, file, line));
                } else if ("dimen".equals(tag)) {
                    index.resources.dimens.put(name, new ResourceTarget("dimen", name, file, line));
                }
            }
        }

        Matcher idM = XML_ID_DEF_PATTERN.matcher(content);
        while (idM.find()) {
            String idName = idM.group(1);
            int line = lineNumberAtOffset(content, idM.start(1));
            index.resources.ids.computeIfAbsent(idName, k -> new ArrayList<>())
                    .add(new ResourceTarget("id", idName, file, line));
        }
    }

    // ==================== Context-Aware Java Autocomplete ====================

    public static List<CompletionCandidate> suggestJava(
            ProjectIndex index,
            File currentFile,
            String code,
            int cursorOffset) {
        if (code == null) return Collections.emptyList();
        int cursor = Math.max(0, Math.min(cursorOffset, code.length()));
        String before = code.substring(0, cursor);

        // 1. Check R.<resType>.<prefix>
        Matcher rResMatcher = Pattern.compile("(?:android\\.)?R\\.(id|layout|string|color|drawable|dimen)\\.([A-Za-z0-9_]*)$")
                .matcher(before);
        if (rResMatcher.find()) {
            String resType = rResMatcher.group(1);
            String prefix = rResMatcher.group(2);
            int tokenStart = cursor - prefix.length();
            return suggestRMembers(index, resType, prefix, tokenStart);
        }

        // 2. Check R.<prefix>
        Matcher rRootMatcher = Pattern.compile("(?:android\\.)?R\\.([A-Za-z0-9_]*)$").matcher(before);
        if (rRootMatcher.find()) {
            String prefix = rRootMatcher.group(1);
            int tokenStart = cursor - prefix.length();
            List<CompletionCandidate> out = new ArrayList<>();
            for (String bucket : Arrays.asList("id", "layout", "string", "color", "drawable", "dimen", "mipmap", "style")) {
                if (bucket.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    out.add(new CompletionCandidate("RES", "R." + bucket, bucket, 0, tokenStart));
                }
            }
            return out;
        }

        // 3. Check <receiver>.<memberPrefix>
        Matcher memberMatcher = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z0-9_]*)$").matcher(before);
        if (memberMatcher.find()) {
            String receiver = memberMatcher.group(1);
            String memberPrefix = memberMatcher.group(2);
            int tokenStart = cursor - memberPrefix.length();
            return suggestMembersForReceiver(index, code, cursor, receiver, memberPrefix, tokenStart);
        }

        // 4. Plain identifier / type / method completion in Java file
        int idStart = cursor;
        while (idStart > 0 && isJavaIdentPart(before.charAt(idStart - 1))) {
            idStart--;
        }
        String prefix = before.substring(idStart);
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);

        Map<String, CompletionCandidate> dedup = new LinkedHashMap<>();

        // Local variables & parameters above cursor
        Map<String, String> locals = extractLocalAndParamTypes(code, cursor);
        for (Map.Entry<String, String> entry : locals.entrySet()) {
            String name = entry.getKey();
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                dedup.put("VAR|" + name, new CompletionCandidate(
                        "VAR", name + " : " + entry.getValue(), name, 0, idStart));
            }
        }

        // Current class fields & methods
        ClassSymbol currentCls = findEnclosingClass(index, currentFile, code);
        if (currentCls != null) {
            for (FieldSymbol f : currentCls.fields) {
                if (f.name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                    dedup.put("FLD|" + f.name, new CompletionCandidate(
                            "FLD", f.name + " : " + f.type, f.name, 0, idStart));
                }
            }
            for (MethodSymbol m : currentCls.methods) {
                if (m.name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                    String call = m.name + "(" + (m.paramsRaw.isEmpty() ? ")" : "");
                    int back = m.paramsRaw.isEmpty() ? 0 : 0;
                    dedup.put("MTH|" + m.name, new CompletionCandidate(
                            "MTH", m.name + "(" + m.paramsRaw + ")", m.name + "()", m.paramsRaw.isEmpty() ? 0 : 1, idStart));
                }
            }
        }

        // Project classes
        if (index != null) {
            for (ClassSymbol cls : index.classesByFqn.values()) {
                if (cls.name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                    dedup.put("CLS|" + cls.name, new CompletionCandidate(
                            "CLS", cls.name + " (" + cls.packageName + ")", cls.name, 0, idStart));
                }
            }
        }

        // Common SDK classes from SDK_IMPORT_MAP
        for (Map.Entry<String, String> sdk : SDK_IMPORT_MAP.entrySet()) {
            String clsName = sdk.getKey();
            if (clsName.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                dedup.putIfAbsent("CLS|" + clsName, new CompletionCandidate(
                        "CLS", clsName, clsName, 0, idStart));
            }
        }

        return new ArrayList<>(dedup.values());
    }

    private static List<CompletionCandidate> suggestRMembers(
            ProjectIndex index, String resType, String prefix, int tokenStart) {
        if (index == null) return Collections.emptyList();
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> keys = new ArrayList<>();
        switch (resType) {
            case "string":
                keys.addAll(index.resources.strings.keySet());
                break;
            case "color":
                keys.addAll(index.resources.colors.keySet());
                break;
            case "dimen":
                keys.addAll(index.resources.dimens.keySet());
                break;
            case "layout":
                keys.addAll(index.resources.layouts.keySet());
                break;
            case "drawable":
                keys.addAll(index.resources.drawables.keySet());
                break;
            case "id":
                keys.addAll(index.resources.ids.keySet());
                break;
            default:
                break;
        }
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        List<CompletionCandidate> out = new ArrayList<>();
        for (String key : keys) {
            if (key.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(new CompletionCandidate("RES", "R." + resType + "." + key, key, 0, tokenStart));
            }
        }
        return out;
    }

    private static List<CompletionCandidate> suggestMembersForReceiver(
            ProjectIndex index,
            String code,
            int cursor,
            String receiver,
            String memberPrefix,
            int tokenStart) {
        String lowerPrefix = memberPrefix.toLowerCase(Locale.ROOT);
        boolean isStaticAccess = false;
        String resolvedType = null;

        if ("this".equals(receiver)) {
            ClassSymbol cls = findEnclosingClass(index, null, code);
            if (cls != null) resolvedType = cls.name;
        } else if ("super".equals(receiver)) {
            ClassSymbol cls = findEnclosingClass(index, null, code);
            if (cls != null && !cls.superClass.isEmpty()) resolvedType = stripPackage(cls.superClass);
        } else {
            // Check locals/params/fields first
            Map<String, String> locals = extractLocalAndParamTypes(code, cursor);
            resolvedType = locals.get(receiver);
            if (resolvedType == null) {
                resolvedType = extractFieldTypeInCode(code, receiver);
            }
            if (resolvedType == null && Character.isUpperCase(receiver.charAt(0))) {
                resolvedType = receiver;
                isStaticAccess = true;
            }
        }

        if (resolvedType == null) {
            return Collections.emptyList();
        }
        String simpleType = stripGenericsAndPackage(resolvedType);
        Map<String, CompletionCandidate> results = new LinkedHashMap<>();

        // Project class members
        if (index != null) {
            ClassSymbol targetCls = index.findClass(simpleType);
            int guard = 0;
            while (targetCls != null && guard++ < 5) {
                for (FieldSymbol f : targetCls.fields) {
                    if (isStaticAccess && !f.isStatic) continue;
                    if (f.name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                        results.putIfAbsent("FLD|" + f.name, new CompletionCandidate(
                                "FLD", f.name + " : " + f.type, f.name, 0, tokenStart));
                    }
                }
                for (MethodSymbol m : targetCls.methods) {
                    if (isStaticAccess && !m.isStatic) continue;
                    if (m.name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                        results.putIfAbsent("MTH|" + m.name, new CompletionCandidate(
                                "MTH",
                                m.name + "(" + m.paramsRaw + ") : " + m.returnType,
                                m.name + "()",
                                m.paramsRaw.isEmpty() ? 0 : 1,
                                tokenStart));
                    }
                }
                if (!targetCls.superClass.isEmpty()) {
                    String supSimple = stripPackage(targetCls.superClass);
                    ClassSymbol next = index.findClass(supSimple);
                    if (next == null) {
                        addSdkClassMembers(supSimple, lowerPrefix, tokenStart, results);
                    }
                    targetCls = next;
                } else {
                    targetCls = null;
                }
            }
        }

        // SDK class members
        addSdkClassMembers(simpleType, lowerPrefix, tokenStart, results);
        return new ArrayList<>(results.values());
    }

    private static void addSdkClassMembers(
            String simpleType,
            String lowerPrefix,
            int tokenStart,
            Map<String, CompletionCandidate> out) {
        List<MemberCandidate> members = SDK_MEMBERS.get(simpleType);
        if (members == null) return;
        for (MemberCandidate mc : members) {
            if (mc.label.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)
                    || mc.insertText.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                out.putIfAbsent(mc.kind + "|" + mc.label,
                        new CompletionCandidate(mc.kind, mc.label, mc.insertText, mc.cursorBackOffset, tokenStart));
            }
        }
    }

    // ==================== Context-Aware XML Autocomplete ====================

    public static List<CompletionCandidate> suggestXml(
            ProjectIndex index,
            File currentFile,
            String code,
            int cursorOffset) {
        if (code == null) return Collections.emptyList();
        int cursor = Math.max(0, Math.min(cursorOffset, code.length()));
        String before = code.substring(0, cursor);
        String fileName = currentFile != null ? currentFile.getName().toLowerCase(Locale.ROOT) : "";
        boolean isManifest = "androidmanifest.xml".equals(fileName);

        // 1. Inside an attribute value: ...attrName="prefix
        int lastQuote = before.lastIndexOf('"');
        int lastEq = before.lastIndexOf('=');
        int lastTagOpen = before.lastIndexOf('<');
        int lastTagClose = before.lastIndexOf('>');
        if (lastTagOpen > lastTagClose && lastQuote > lastEq && lastEq > lastTagOpen) {
            // Ensure quote count inside current tag is odd (meaning cursor is inside "...")
            String insideTag = before.substring(lastTagOpen);
            if (countChar(insideTag, '"') % 2 == 1) {
                String attrName = extractAttrNameBeforeEq(before, lastEq);
                String valPrefix = before.substring(lastQuote + 1);
                String tagName = extractOpenTagName(insideTag);
                int valStart = lastQuote + 1;
                return suggestXmlAttributeValues(index, isManifest, tagName, attrName, valPrefix, valStart);
            }
        }

        // 2. Inside an open tag awaiting an attribute name: <LinearLayout android:or
        if (lastTagOpen > lastTagClose) {
            String insideTag = before.substring(lastTagOpen);
            String tagName = extractOpenTagName(insideTag);
            if (!tagName.isEmpty() && insideTag.length() > 1 + tagName.length()) {
                int attrStart = cursor;
                while (attrStart > lastTagOpen) {
                    char ch = before.charAt(attrStart - 1);
                    if (Character.isLetterOrDigit(ch) || ch == ':' || ch == '_') {
                        attrStart--;
                    } else {
                        break;
                    }
                }
                String attrPrefix = before.substring(attrStart);
                return suggestXmlAttributesForTag(tagName, attrPrefix, isManifest, attrStart);
            }
        }

        // 3. Tag start completion: <...
        int ltIdx = before.lastIndexOf('<');
        if (ltIdx >= 0 && ltIdx > before.lastIndexOf('>') && !before.substring(ltIdx).contains(" ")) {
            String tagPrefix = before.substring(ltIdx + 1).toLowerCase(Locale.ROOT);
            return suggestXmlTagsForContext(fileName, isManifest, tagPrefix, ltIdx);
        }

        return Collections.emptyList();
    }

    private static List<CompletionCandidate> suggestXmlAttributeValues(
            ProjectIndex index,
            boolean isManifest,
            String tagName,
            String attrName,
            String prefix,
            int valStart) {
        String lowerAttr = attrName.toLowerCase(Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<CompletionCandidate> out = new ArrayList<>();

        if (isManifest && "uses-permission".equals(tagName) && lowerAttr.endsWith("name")) {
            List<String> perms = Arrays.asList(
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.CAMERA",
                    "android.permission.RECORD_AUDIO",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.VIBRATE",
                    "android.permission.WAKE_LOCK",
                    "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.BLUETOOTH_SCAN"
            );
            for (String p : perms) {
                if (p.toLowerCase(Locale.ROOT).contains(lowerPrefix)) {
                    out.add(new CompletionCandidate("VAL", p, p, 0, valStart));
                }
            }
            return out;
        }

        if (lowerAttr.endsWith("layout_width") || lowerAttr.endsWith("layout_height")) {
            for (String v : Arrays.asList("match_parent", "wrap_content", "0dp", "48dp", "120dp")) {
                if (v.startsWith(lowerPrefix)) {
                    out.add(new CompletionCandidate("VAL", v, v, 0, valStart));
                }
            }
        } else if (lowerAttr.endsWith("orientation")) {
            for (String v : Arrays.asList("vertical", "horizontal")) {
                if (v.startsWith(lowerPrefix)) {
                    out.add(new CompletionCandidate("VAL", v, v, 0, valStart));
                }
            }
        } else if (lowerAttr.endsWith("visibility")) {
            for (String v : Arrays.asList("visible", "invisible", "gone")) {
                if (v.startsWith(lowerPrefix)) {
                    out.add(new CompletionCandidate("VAL", v, v, 0, valStart));
                }
            }
        } else if (lowerAttr.endsWith("gravity")) {
            for (String v : Arrays.asList("center", "center_horizontal", "center_vertical", "start", "end", "top", "bottom")) {
                if (v.startsWith(lowerPrefix)) {
                    out.add(new CompletionCandidate("VAL", v, v, 0, valStart));
                }
            }
        } else if (lowerAttr.endsWith("textstyle")) {
            for (String v : Arrays.asList("normal", "bold", "italic", "bold|italic")) {
                if (v.startsWith(lowerPrefix)) {
                    out.add(new CompletionCandidate("VAL", v, v, 0, valStart));
                }
            }
        } else if (lowerAttr.endsWith("scaletype")) {
            for (String v : Arrays.asList("centerCrop", "fitCenter", "centerInside", "fitXY", "center")) {
                if (v.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                    out.add(new CompletionCandidate("VAL", v, v, 0, valStart));
                }
            }
        } else if (lowerAttr.endsWith("exported") || lowerAttr.endsWith("allowbackup")
                || lowerAttr.endsWith("supportsrtl") || lowerAttr.endsWith("clickable")
                || lowerAttr.endsWith("enabled")) {
            for (String v : Arrays.asList("true", "false")) {
                if (v.startsWith(lowerPrefix)) {
                    out.add(new CompletionCandidate("VAL", v, v, 0, valStart));
                }
            }
        }

        // Dynamic resource completions
        if (index != null && (prefix.startsWith("@") || prefix.isEmpty())) {
            if (prefix.startsWith("@string/") || lowerAttr.endsWith("text") || lowerAttr.endsWith("hint") || lowerAttr.endsWith("label")) {
                for (String key : index.resources.strings.keySet()) {
                    String ref = "@string/" + key;
                    if (ref.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                        out.add(new CompletionCandidate("RES", ref, ref, 0, valStart));
                    }
                }
            }
            if (prefix.startsWith("@color/") || lowerAttr.endsWith("color") || lowerAttr.endsWith("background") || lowerAttr.endsWith("tint")) {
                for (String key : index.resources.colors.keySet()) {
                    String ref = "@color/" + key;
                    if (ref.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                        out.add(new CompletionCandidate("RES", ref, ref, 0, valStart));
                    }
                }
            }
            if (prefix.startsWith("@drawable/") || lowerAttr.endsWith("src") || lowerAttr.endsWith("background") || lowerAttr.endsWith("icon")) {
                for (String key : index.resources.drawables.keySet()) {
                    String ref = "@drawable/" + key;
                    if (ref.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                        out.add(new CompletionCandidate("RES", ref, ref, 0, valStart));
                    }
                }
            }
            if (prefix.startsWith("@dimen/") || lowerAttr.endsWith("padding") || lowerAttr.contains("margin") || lowerAttr.endsWith("size")) {
                for (String key : index.resources.dimens.keySet()) {
                    String ref = "@dimen/" + key;
                    if (ref.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                        out.add(new CompletionCandidate("RES", ref, ref, 0, valStart));
                    }
                }
            }
            if (prefix.startsWith("@id/") || prefix.startsWith("@+id/") || lowerAttr.contains("to") || lowerAttr.contains("below") || lowerAttr.contains("above")) {
                for (String key : index.resources.ids.keySet()) {
                    String ref = "@id/" + key;
                    if (ref.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                        out.add(new CompletionCandidate("RES", ref, ref, 0, valStart));
                    }
                }
            }
        }
        return out;
    }

    private static List<CompletionCandidate> suggestXmlAttributesForTag(
            String tagName, String attrPrefix, boolean isManifest, int attrStart) {
        List<String> attrs = new ArrayList<>();
        if (isManifest) {
            if ("manifest".equals(tagName)) {
                attrs.addAll(Arrays.asList("xmlns:android", "package", "android:versionCode", "android:versionName"));
            } else if ("application".equals(tagName)) {
                attrs.addAll(Arrays.asList("android:label", "android:icon", "android:roundIcon",
                        "android:theme", "android:allowBackup", "android:supportsRtl"));
            } else if ("activity".equals(tagName) || "service".equals(tagName) || "receiver".equals(tagName)) {
                attrs.addAll(Arrays.asList("android:name", "android:exported", "android:label", "android:theme"));
            } else if ("uses-permission".equals(tagName)) {
                attrs.add("android:name");
            } else {
                attrs.add("android:name");
            }
        } else {
            attrs.addAll(Arrays.asList(
                    "android:id", "android:layout_width", "android:layout_height",
                    "android:layout_margin", "android:layout_marginTop", "android:layout_marginBottom",
                    "android:layout_gravity", "android:padding", "android:background",
                    "android:visibility", "android:gravity"));
            String simpleTag = stripPackage(tagName);
            if ("LinearLayout".equals(simpleTag)) {
                attrs.add(0, "android:orientation");
                attrs.add("android:weightSum");
            } else if ("TextView".equals(simpleTag) || "Button".equals(simpleTag)
                    || "MaterialButton".equals(simpleTag) || "EditText".equals(simpleTag)) {
                attrs.add(0, "android:text");
                attrs.add("android:textColor");
                attrs.add("android:textSize");
                attrs.add("android:textStyle");
                if ("EditText".equals(simpleTag)) {
                    attrs.add("android:hint");
                    attrs.add("android:inputType");
                }
            } else if ("ImageView".equals(simpleTag) || "ImageButton".equals(simpleTag)) {
                attrs.add(0, "android:src");
                attrs.add("android:scaleType");
                attrs.add("android:contentDescription");
                attrs.add("app:tint");
            } else if ("ConstraintLayout".equals(simpleTag)) {
                attrs.add("app:layout_constraintTop_toTopOf");
                attrs.add("app:layout_constraintStart_toStartOf");
                attrs.add("app:layout_constraintEnd_toEndOf");
                attrs.add("app:layout_constraintBottom_toBottomOf");
            }
        }

        String lower = attrPrefix.toLowerCase(Locale.ROOT);
        List<CompletionCandidate> out = new ArrayList<>();
        for (String attr : attrs) {
            if (attr.toLowerCase(Locale.ROOT).contains(lower)) {
                String defaultVal = defaultValueForAttr(attr);
                String insert = attr + "=\"" + defaultVal + "\"";
                int back = 1;
                out.add(new CompletionCandidate("ATTR", attr, insert, back, attrStart));
            }
        }
        return out;
    }

    private static List<CompletionCandidate> suggestXmlTagsForContext(
            String fileName, boolean isManifest, String tagPrefix, int ltIdx) {
        List<CompletionCandidate> out = new ArrayList<>();
        if (isManifest) {
            addTagCandidate(out, tagPrefix, ltIdx, "uses-permission", "<uses-permission android:name=\"\" />", 4);
            addTagCandidate(out, tagPrefix, ltIdx, "activity", "<activity\n    android:name=\"\"\n    android:exported=\"false\" />", 32);
            addTagCandidate(out, tagPrefix, ltIdx, "service", "<service\n    android:name=\"\"\n    android:exported=\"false\" />", 32);
            addTagCandidate(out, tagPrefix, ltIdx, "receiver", "<receiver\n    android:name=\"\"\n    android:exported=\"false\" />", 32);
            addTagCandidate(out, tagPrefix, ltIdx, "intent-filter", "<intent-filter>\n    <action android:name=\"android.intent.action.MAIN\" />\n</intent-filter>", 0);
            addTagCandidate(out, tagPrefix, ltIdx, "meta-data", "<meta-data\n    android:name=\"\"\n    android:value=\"\" />", 25);
        } else if ("strings.xml".equals(fileName)) {
            addTagCandidate(out, tagPrefix, ltIdx, "string", "<string name=\"\"></string>", 11);
        } else if ("colors.xml".equals(fileName)) {
            addTagCandidate(out, tagPrefix, ltIdx, "color", "<color name=\"\">#38BDF8</color>", 18);
        } else if ("dimens.xml".equals(fileName)) {
            addTagCandidate(out, tagPrefix, ltIdx, "dimen", "<dimen name=\"\">16dp</dimen>", 15);
        } else {
            addTagCandidate(out, tagPrefix, ltIdx, "TextView", "<TextView\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"\" />", 4);
            addTagCandidate(out, tagPrefix, ltIdx, "Button", "<Button\n    android:id=\"@+id/btnAction\"\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Action\" />", 0);
            addTagCandidate(out, tagPrefix, ltIdx, "EditText", "<EditText\n    android:id=\"@+id/etInput\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:hint=\"Enter text\" />", 0);
            addTagCandidate(out, tagPrefix, ltIdx, "ImageView", "<ImageView\n    android:id=\"@+id/ivPreview\"\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:contentDescription=\"Image\" />", 0);
            addTagCandidate(out, tagPrefix, ltIdx, "LinearLayout", "<LinearLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:orientation=\"vertical\">\n\n</LinearLayout>", 17);
            addTagCandidate(out, tagPrefix, ltIdx, "FrameLayout", "<FrameLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\">\n\n</FrameLayout>", 16);
            addTagCandidate(out, tagPrefix, ltIdx, "ScrollView", "<ScrollView\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n\n</ScrollView>", 15);
            addTagCandidate(out, tagPrefix, ltIdx, "MaterialButton", "<com.google.android.material.button.MaterialButton\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Action\" />", 0);
        }
        return out;
    }

    private static void addTagCandidate(
            List<CompletionCandidate> out, String prefix, int ltIdx, String tagName, String snippet, int back) {
        if (tagName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            out.add(new CompletionCandidate("TAG", "<" + tagName + ">", snippet, back, ltIdx));
        }
    }

    private static String defaultValueForAttr(String attr) {
        if (attr.endsWith("layout_width")) return "match_parent";
        if (attr.endsWith("layout_height")) return "wrap_content";
        if (attr.endsWith("orientation")) return "vertical";
        if (attr.endsWith("id")) return "@+id/";
        if (attr.endsWith("visibility")) return "visible";
        if (attr.endsWith("textSize")) return "16sp";
        if (attr.endsWith("padding")) return "16dp";
        if (attr.endsWith("exported")) return "false";
        return "";
    }

    // ==================== Go to Definition ====================

    public static NavigationTarget resolveDefinition(
            ProjectIndex index,
            File currentFile,
            String code,
            int cursorOffset) {
        if (code == null || code.isEmpty()) return null;
        int cursor = Math.max(0, Math.min(cursorOffset, code.length()));

        // 1. Check for Java R.<type>.<name> or XML @<type>/<name> at cursor
        String line = extractLineAtOffset(code, cursor);
        int lineStart = code.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        int colInLine = cursor - lineStart;

        Matcher rm = JAVA_R_TOKEN_PATTERN.matcher(line);
        while (rm.find()) {
            if (colInLine >= rm.start() && colInLine <= rm.end()) {
                ResourceTarget target = findResourceTarget(index, rm.group(1), rm.group(2));
                if (target != null) {
                    return new NavigationTarget(target.file, target.lineNumber, 1,
                            "@" + target.type + "/" + target.name);
                }
            }
        }

        Matcher xm = XML_RES_REF_PATTERN.matcher(line);
        while (xm.find()) {
            if (colInLine >= xm.start() && colInLine <= xm.end()) {
                String rawType = xm.group(1).replace("+", "");
                ResourceTarget target = findResourceTarget(index, rawType, xm.group(2));
                if (target != null) {
                    return new NavigationTarget(target.file, target.lineNumber, 1,
                            "@" + target.type + "/" + target.name);
                }
            }
        }

        // 2. Manifest class reference e.g. android:name=".MainActivity" or "com.demo.Foo"
        if (currentFile != null && currentFile.getName().endsWith(".xml") && index != null) {
            String quoted = extractQuotedTokenAt(line, colInLine);
            if (quoted != null) {
                String candidateName = quoted.startsWith(".") ? quoted.substring(1) : stripPackage(quoted);
                ClassSymbol cls = index.findClass(quoted);
                if (cls == null) cls = index.findClass(candidateName);
                if (cls != null) {
                    return new NavigationTarget(cls.file, cls.lineNumber, 1, cls.fqn);
                }
            }
        }

        // 3. Java symbol under cursor
        TokenRange token = extractIdentifierRangeAt(code, cursor);
        if (token == null) return null;
        String symbol = token.text;

        // Check qualified receiver.symbol
        if (token.start > 1 && code.charAt(token.start - 1) == '.') {
            String beforeDot = code.substring(0, token.start - 1);
            Matcher recvM = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)$").matcher(beforeDot);
            if (recvM.find()) {
                String recv = recvM.group(1);
                String typeName = resolveReceiverSimpleType(index, code, cursor, recv);
                if (typeName != null && index != null) {
                    ClassSymbol targetCls = index.findClass(typeName);
                    if (targetCls != null) {
                        NavigationTarget memberTarget = findMemberInClassHierarchy(index, targetCls, symbol);
                        if (memberTarget != null) return memberTarget;
                    }
                }
            }
        }

        // Search enclosing file locals/params first (unless cursor is on the declaration itself)
        ClassSymbol currentCls = findEnclosingClass(index, currentFile, code);
        if (currentCls != null) {
            NavigationTarget inCurrent = findMemberInClassHierarchy(index, currentCls, symbol);
            if (inCurrent != null && !JAVA_NON_TYPE_KEYWORDS.contains(symbol)) {
                int cursorLine = lineNumberAtOffset(code, cursor);
                if (inCurrent.lineNumber != cursorLine || (currentFile != null && !currentFile.equals(inCurrent.file))) {
                    return inCurrent;
                }
            }
        }

        // Project class matching symbol
        if (index != null) {
            ClassSymbol cls = index.findClass(symbol);
            if (cls != null) {
                return new NavigationTarget(cls.file, cls.lineNumber, 1, cls.fqn);
            }
            // Fallback: check local var/param declaration in current file
            if (currentFile != null) {
                Integer localLine = findLocalDeclarationLine(code, cursor, symbol);
                if (localLine != null) {
                    return new NavigationTarget(currentFile, localLine, 1, "local " + symbol);
                }
            }
            // Fallback: any method/field with matching name across project
            for (ClassSymbol c : index.classesByFqn.values()) {
                NavigationTarget m = findMemberInClass(c, symbol);
                if (m != null) return m;
            }
        }
        return null;
    }

    private static ResourceTarget findResourceTarget(ProjectIndex index, String type, String name) {
        if (index == null || type == null || name == null) return null;
        switch (type) {
            case "string":
                return index.resources.strings.get(name);
            case "color":
                return index.resources.colors.get(name);
            case "dimen":
                return index.resources.dimens.get(name);
            case "layout":
                return index.resources.layouts.get(name);
            case "drawable":
                return index.resources.drawables.get(name);
            case "id": {
                List<ResourceTarget> list = index.resources.ids.get(name);
                return (list != null && !list.isEmpty()) ? list.get(0) : null;
            }
            default:
                return null;
        }
    }

    private static NavigationTarget findMemberInClassHierarchy(ProjectIndex index, ClassSymbol cls, String symbol) {
        ClassSymbol cur = cls;
        int guard = 0;
        while (cur != null && guard++ < 5) {
            NavigationTarget hit = findMemberInClass(cur, symbol);
            if (hit != null) return hit;
            if (!cur.superClass.isEmpty() && index != null) {
                cur = index.findClass(stripPackage(cur.superClass));
            } else {
                cur = null;
            }
        }
        return null;
    }

    private static NavigationTarget findMemberInClass(ClassSymbol cls, String symbol) {
        for (MethodSymbol m : cls.methods) {
            if (m.name.equals(symbol)) {
                return new NavigationTarget(cls.file, m.lineNumber, 1, cls.name + "." + m.name + "()");
            }
        }
        for (FieldSymbol f : cls.fields) {
            if (f.name.equals(symbol)) {
                return new NavigationTarget(cls.file, f.lineNumber, 1, cls.name + "." + f.name);
            }
        }
        return null;
    }

    // ==================== Find References ====================

    public static String extractSymbolAtCursor(String code, int cursorOffset) {
        if (code == null || code.isEmpty()) return "";
        int cursor = Math.max(0, Math.min(cursorOffset, code.length()));
        TokenRange tr = extractIdentifierRangeAt(code, cursor);
        return tr != null ? tr.text : "";
    }

    public static List<ReferenceLocation> findReferences(
            File projectDir,
            Map<String, String> openContentByPath,
            String symbol) {
        if (projectDir == null || !projectDir.exists() || symbol == null || symbol.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String clean = symbol.trim();
        Pattern wordPattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(clean) + "(?![A-Za-z0-9_])");
        Pattern declCheck = Pattern.compile("\\b(class|interface|enum|void|int|long|float|double|boolean|String|[A-Z][A-Za-z0-9_]*)\\s+"
                + Pattern.quote(clean) + "\\b|@\\+id/" + Pattern.quote(clean)
                + "\\b|<[^>]*name\\s*=\\s*[\"']" + Pattern.quote(clean) + "[\"']");

        List<ReferenceLocation> refs = new ArrayList<>();
        List<File> files = collectProjectSourceFiles(projectDir);
        for (File f : files) {
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".java") && !lower.endsWith(".xml") && !lower.endsWith(".sd") && !lower.endsWith(".gradle")) {
                continue;
            }
            String content = readEffectiveContent(f, openContentByPath);
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                Matcher m = wordPattern.matcher(line);
                if (m.find()) {
                    boolean isDecl = declCheck.matcher(line).find();
                    refs.add(new ReferenceLocation(
                            f,
                            i + 1,
                            m.start() + 1,
                            line.trim(),
                            isDecl));
                }
            }
        }
        refs.sort(Comparator.comparing((ReferenceLocation r) -> !r.isDeclaration)
                .thenComparing(r -> r.file.getName())
                .thenComparingInt(r -> r.lineNumber));
        return refs;
    }

    // ==================== Refactoring: Rename Symbol & Organize Imports ====================

    public static boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty() || JAVA_NON_TYPE_KEYWORDS.contains(name)) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }

    public static RenameResult renameSymbolInProject(
            File projectDir,
            Map<String, String> openContentByPath,
            String oldName,
            String newName) throws IOException {
        if (projectDir == null || !projectDir.exists()) {
            return new RenameResult(false, "No active project.", 0, 0, null, null, Collections.emptyMap());
        }
        String cleanOld = oldName != null ? oldName.trim() : "";
        String cleanNew = newName != null ? newName.trim() : "";
        if (cleanOld.isEmpty() || cleanOld.equals(cleanNew)) {
            return new RenameResult(false, "Enter a different target identifier.", 0, 0, null, null, Collections.emptyMap());
        }
        if (!isValidJavaIdentifier(cleanNew)) {
            return new RenameResult(false, "Invalid Java identifier: " + cleanNew, 0, 0, null, null, Collections.emptyMap());
        }

        List<File> files = collectProjectSourceFiles(projectDir);
        int filesChanged = 0;
        int totalReplacements = 0;
        Map<String, String> updatedContents = new LinkedHashMap<>();
        File renamedFrom = null;
        File renamedTo = null;

        Pattern javaIdentPattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(cleanOld) + "(?![A-Za-z0-9_])");
        Pattern xmlSymbolPattern = Pattern.compile(
                "(@\\+?id/|@string/|@color/|@dimen/|@layout/|@drawable/|name\\s*=\\s*[\"']\\.?|android:name\\s*=\\s*[\"'](?:[A-Za-z0-9_.]+\\.)?)"
                        + Pattern.quote(cleanOld) + "(?![A-Za-z0-9_])");

        for (File f : files) {
            String lower = f.getName().toLowerCase(Locale.ROOT);
            boolean isJava = lower.endsWith(".java");
            boolean isXml = lower.endsWith(".xml");
            if (!isJava && !isXml) continue;

            String original = readEffectiveContent(f, openContentByPath);
            int[] countHolder = new int[]{0};
            String rewritten;

            if (isJava) {
                rewritten = replaceOutsideJavaCommentsAndStrings(original, javaIdentPattern, cleanNew, countHolder);
            } else {
                Matcher xm = xmlSymbolPattern.matcher(original);
                StringBuffer sb = new StringBuffer();
                while (xm.find()) {
                    countHolder[0]++;
                    xm.appendReplacement(sb, Matcher.quoteReplacement(xm.group(1) + cleanNew));
                }
                xm.appendTail(sb);
                rewritten = sb.toString();
            }

            if (countHolder[0] > 0 && !rewritten.equals(original)) {
                writeUtf8(f, rewritten);
                filesChanged++;
                totalReplacements += countHolder[0];
                updatedContents.put(f.getAbsolutePath(), rewritten);
            }

            if (isJava && f.getName().equals(cleanOld + ".java")) {
                File target = new File(f.getParentFile(), cleanNew + ".java");
                if (!target.exists() && f.renameTo(target)) {
                    renamedFrom = f;
                    renamedTo = target;
                    String movedContent = updatedContents.remove(f.getAbsolutePath());
                    if (movedContent != null) {
                        updatedContents.put(target.getAbsolutePath(), movedContent);
                    }
                }
            }
        }

        if (filesChanged == 0 && renamedTo == null) {
            return new RenameResult(false, "No occurrences of '" + cleanOld + "' found.",
                    0, 0, null, null, Collections.emptyMap());
        }
        String msg = "Renamed '" + cleanOld + "' -> '" + cleanNew + "' ("
                + totalReplacements + " edits across " + filesChanged + " file(s))";
        return new RenameResult(true, msg, filesChanged, totalReplacements,
                renamedFrom, renamedTo, updatedContents);
    }

    public static String organizeImports(ProjectIndex index, String javaCode) {
        if (javaCode == null || javaCode.trim().isEmpty()) return javaCode;

        String[] lines = javaCode.split("\n", -1);
        int packageLineIdx = -1;
        String currentPkg = "";
        int firstImportIdx = -1;
        int lastImportIdx = -1;

        Set<String> existingImports = new LinkedHashSet<>();
        Map<String, String> simpleToImport = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (packageLineIdx < 0 && trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                packageLineIdx = i;
                currentPkg = trimmed.substring(8, trimmed.length() - 1).trim();
            } else if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                if (firstImportIdx < 0) firstImportIdx = i;
                lastImportIdx = i;
                String imp = trimmed.substring(7, trimmed.length() - 1).trim();
                existingImports.add(imp);
                if (!imp.startsWith("static ") && !imp.endsWith(".*")) {
                    simpleToImport.put(stripPackage(imp), imp);
                }
            }
        }

        // Collect code body excluding package & import lines
        StringBuilder bodyBuilder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if ((trimmed.startsWith("package ") || trimmed.startsWith("import ")) && trimmed.endsWith(";")) {
                continue;
            }
            bodyBuilder.append(lines[i]).append('\n');
        }
        String strippedBody = stripCommentsAndStrings(bodyBuilder.toString());

        // Types declared inside this file shouldn't be imported
        Set<String> declaredInFile = new HashSet<>();
        Matcher declM = CLASS_DECL_PATTERN.matcher(strippedBody);
        while (declM.find()) {
            declaredInFile.add(declM.group(2));
        }

        // Find referenced simple capitalized identifiers
        Set<String> usedTypes = new LinkedHashSet<>();
        Matcher typeM = Pattern.compile("(?<![.\\w])([A-Z][A-Za-z0-9_]*)(?![A-Za-z0-9_])").matcher(strippedBody);
        while (typeM.find()) {
            String t = typeM.group(1);
            if (!JAVA_NON_TYPE_KEYWORDS.contains(t) && !declaredInFile.contains(t) && !t.matches("[A-Z0-9_]+")) {
                usedTypes.add(t);
            }
        }

        // Keep used existing imports (plus static / wildcard imports)
        Set<String> finalImports = new LinkedHashSet<>();
        for (String imp : existingImports) {
            if (imp.startsWith("static ") || imp.endsWith(".*")) {
                finalImports.add(imp);
            } else {
                String simple = stripPackage(imp);
                if (usedTypes.contains(simple) && !isSamePackage(imp, currentPkg)) {
                    finalImports.add(imp);
                }
            }
        }

        // Auto-add missing imports for used unresolved types
        for (String used : usedTypes) {
            if (simpleToImport.containsKey(used)) continue;
            if ("R".equals(used) || "BuildConfig".equals(used)) continue;

            // Check project classes
            if (index != null) {
                List<ClassSymbol> matches = index.classesBySimpleName.get(used);
                if (matches != null && !matches.isEmpty()) {
                    ClassSymbol cls = matches.get(0);
                    if (!cls.packageName.isEmpty() && !cls.packageName.equals(currentPkg)) {
                        finalImports.add(cls.fqn);
                    }
                    continue;
                }
            }
            // Check SDK catalog
            String sdkFqn = SDK_IMPORT_MAP.get(used);
            if (sdkFqn != null) {
                finalImports.add(sdkFqn);
            }
        }

        List<String> androidGroup = new ArrayList<>();
        List<String> androidxGroup = new ArrayList<>();
        List<String> projectOr3pGroup = new ArrayList<>();
        List<String> javaGroup = new ArrayList<>();
        List<String> staticGroup = new ArrayList<>();

        for (String imp : finalImports) {
            if (imp.startsWith("static ")) {
                staticGroup.add(imp);
            } else if (imp.startsWith("android.")) {
                androidGroup.add(imp);
            } else if (imp.startsWith("androidx.")) {
                androidxGroup.add(imp);
            } else if (imp.startsWith("java.") || imp.startsWith("javax.")) {
                javaGroup.add(imp);
            } else {
                projectOr3pGroup.add(imp);
            }
        }
        Collections.sort(androidGroup);
        Collections.sort(androidxGroup);
        Collections.sort(projectOr3pGroup);
        Collections.sort(javaGroup);
        Collections.sort(staticGroup);

        List<String> outLines = new ArrayList<>();
        int startBody = 0;
        if (packageLineIdx >= 0) {
            for (int i = 0; i <= packageLineIdx; i++) {
                outLines.add(lines[i]);
            }
            startBody = packageLineIdx + 1;
        }
        if (lastImportIdx >= startBody) {
            startBody = lastImportIdx + 1;
        }
        while (startBody < lines.length && lines[startBody].trim().isEmpty()) {
            startBody++;
        }

        List<List<String>> groups = Arrays.asList(androidGroup, androidxGroup, projectOr3pGroup, javaGroup, staticGroup);
        boolean anyImports = false;
        for (List<String> g : groups) {
            if (!g.isEmpty()) {
                if (!outLines.isEmpty() && !outLines.get(outLines.size() - 1).trim().isEmpty()) {
                    outLines.add("");
                }
                for (String imp : g) {
                    outLines.add("import " + imp + ";");
                }
                anyImports = true;
            }
        }
        if (anyImports || packageLineIdx >= 0) {
            outLines.add("");
        }

        for (int i = startBody; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import ") && trimmed.endsWith(";")) continue;
            outLines.add(lines[i]);
        }
        return String.join("\n", outLines);
    }

    // ==================== Live Error Highlighting & XML Validation ====================

    public static List<BuildEngineTask.BuildDiagnostic> analyzeFile(
            ProjectIndex index,
            File file,
            String content) {
        if (file == null || content == null) return Collections.emptyList();
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return analyzeJava(index, file, content);
        } else if (name.endsWith(".xml")) {
            return analyzeXml(index, file, content);
        }
        return Collections.emptyList();
    }

    private static List<BuildEngineTask.BuildDiagnostic> analyzeJava(
            ProjectIndex index, File file, String code) {
        List<BuildEngineTask.BuildDiagnostic> diags = new ArrayList<>();
        String path = file.getAbsolutePath();

        // 1. Check balanced delimiters {}, (), [], and unclosed strings/block comments
        List<int[]> braceStack = new ArrayList<>();
        List<int[]> parenStack = new ArrayList<>();
        boolean inBlockComment = false;
        int blockCommentStartLine = 1;
        int lineNo = 1;
        String[] lines = code.split("\n", -1);

        for (int idx = 0; idx < lines.length; idx++) {
            lineNo = idx + 1;
            String line = lines[idx];
            boolean inString = false;
            boolean inChar = false;
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                char next = (c + 1 < line.length()) ? line.charAt(c + 1) : '\0';
                if (inBlockComment) {
                    if (ch == '*' && next == '/') {
                        inBlockComment = false;
                        c++;
                    }
                    continue;
                }
                if (inString) {
                    if (ch == '\\') {
                        c++;
                    } else if (ch == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (inChar) {
                    if (ch == '\\') {
                        c++;
                    } else if (ch == '\'') {
                        inChar = false;
                    }
                    continue;
                }
                if (ch == '/' && next == '*') {
                    inBlockComment = true;
                    blockCommentStartLine = lineNo;
                    c++;
                    continue;
                }
                if (ch == '/' && next == '/') {
                    break;
                }
                if (ch == '"') {
                    inString = true;
                } else if (ch == '\'') {
                    inChar = true;
                } else if (ch == '{') {
                    braceStack.add(new int[]{lineNo, c + 1});
                } else if (ch == '}') {
                    if (braceStack.isEmpty()) {
                        diags.add(new BuildEngineTask.BuildDiagnostic(
                                BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                                path, lineNo, "Unexpected closing brace '}'", "JavaAnalyzer"));
                    } else {
                        braceStack.remove(braceStack.size() - 1);
                    }
                } else if (ch == '(') {
                    parenStack.add(new int[]{lineNo, c + 1});
                } else if (ch == ')') {
                    if (parenStack.isEmpty()) {
                        diags.add(new BuildEngineTask.BuildDiagnostic(
                                BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                                path, lineNo, "Unexpected closing parenthesis ')'", "JavaAnalyzer"));
                    } else {
                        parenStack.remove(parenStack.size() - 1);
                    }
                }
            }
            if (inString) {
                diags.add(new BuildEngineTask.BuildDiagnostic(
                        BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                        path, lineNo, "Unclosed string literal", "JavaAnalyzer"));
            }
        }
        if (inBlockComment) {
            diags.add(new BuildEngineTask.BuildDiagnostic(
                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                    path, blockCommentStartLine, "Unclosed block comment '/* ... */'", "JavaAnalyzer"));
        }
        for (int[] unclosed : braceStack) {
            diags.add(new BuildEngineTask.BuildDiagnostic(
                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                    path, unclosed[0], "Unclosed opening brace '{'", "JavaAnalyzer"));
        }
        for (int[] unclosed : parenStack) {
            diags.add(new BuildEngineTask.BuildDiagnostic(
                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                    path, unclosed[0], "Unclosed parenthesis '('", "JavaAnalyzer"));
        }

        // 2. Check missing semicolons on obvious complete statements
        checkMissingSemicolons(path, lines, diags);

        // 3. Check unresolved simple capitalized types (warning with quick hint)
        checkUnresolvedJavaTypes(index, path, code, diags);

        return diags;
    }

    private static void checkMissingSemicolons(
            String path, String[] lines, List<BuildEngineTask.BuildDiagnostic> diags) {
        int parenDepth = 0;
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String line = stripLineCommentRespectingStrings(lines[i]).trim();
            if (inBlockComment) {
                if (line.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (line.startsWith("/*")) {
                if (!line.contains("*/")) inBlockComment = true;
                continue;
            }
            if (line.isEmpty()) continue;

            // Update paren depth; skip lines that span multi-line call arguments
            int prevDepth = parenDepth;
            parenDepth = Math.max(0, parenDepth + netParenDelta(line));
            if (prevDepth > 0 || parenDepth > 0) continue;

            if (line.startsWith("@") || line.startsWith("case ") || line.startsWith("default:")
                    || line.startsWith("else") || line.startsWith("try") || line.startsWith("catch")
                    || line.startsWith("finally") || line.startsWith("do")
                    || line.startsWith("if ") || line.startsWith("if(")
                    || line.startsWith("for ") || line.startsWith("for(")
                    || line.startsWith("while ") || line.startsWith("while(")
                    || line.startsWith("switch ") || line.startsWith("switch(")) {
                continue;
            }
            if (line.contains(" class ") || line.startsWith("class ")
                    || line.contains(" interface ") || line.startsWith("interface ")
                    || line.contains(" enum ") || line.startsWith("enum ")) {
                continue;
            }
            char last = line.charAt(line.length() - 1);
            if (last == ';' || last == '{' || last == '}' || last == ':'
                    || last == ',' || last == '+' || last == '-' || last == '*'
                    || last == '&' || last == '|' || last == '?' || last == '.') {
                continue;
            }
            // Look ahead at next non-empty, non-comment line
            String nextLine = findNextCodeLine(lines, i + 1);
            if (nextLine != null && (nextLine.startsWith(".") || nextLine.startsWith("?")
                    || nextLine.startsWith(":") || nextLine.startsWith("{")
                    || nextLine.startsWith("+") || nextLine.startsWith("||")
                    || nextLine.startsWith("&&"))) {
                continue;
            }
            boolean looksLikeStmt = line.startsWith("package ") || line.startsWith("import ")
                    || line.startsWith("return") || line.startsWith("throw ")
                    || line.startsWith("break") || line.startsWith("continue")
                    || last == ')' || last == '"' || last == '\'' || Character.isLetterOrDigit(last)
                    || last == ']';
            if (looksLikeStmt) {
                diags.add(new BuildEngineTask.BuildDiagnostic(
                        BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                        path, i + 1, "Missing semicolon ';' at end of statement", "JavaAnalyzer"));
            }
        }
    }

    private static void checkUnresolvedJavaTypes(
            ProjectIndex index, String path, String code, List<BuildEngineTask.BuildDiagnostic> diags) {
        String currentPkg = "";
        Matcher pm = PACKAGE_PATTERN.matcher(code);
        if (pm.find()) currentPkg = pm.group(1);

        Set<String> importedSimple = new HashSet<>();
        boolean hasWildcard = false;
        Matcher im = IMPORT_PATTERN.matcher(code);
        while (im.find()) {
            String imp = im.group(1);
            if (imp.endsWith(".*")) {
                hasWildcard = true;
            } else {
                importedSimple.add(stripPackage(imp));
            }
        }

        // Declared in same file or same package
        Set<String> knownLocal = new HashSet<>();
        Matcher decl = CLASS_DECL_PATTERN.matcher(code);
        while (decl.find()) {
            knownLocal.add(decl.group(2));
        }
        if (index != null) {
            for (ClassSymbol cls : index.classesByFqn.values()) {
                if (cls.packageName.equals(currentPkg)) {
                    knownLocal.add(cls.name);
                }
            }
        }

        // Standard java.lang + R + BuildConfig + common annotations
        Set<String> javaLang = new HashSet<>(Arrays.asList(
                "Object", "String", "StringBuilder", "StringBuffer", "CharSequence",
                "Integer", "Long", "Float", "Double", "Boolean", "Byte", "Short", "Character", "Void",
                "Number", "Math", "System", "Thread", "Runnable", "Throwable", "Exception",
                "RuntimeException", "Error", "IllegalArgumentException", "IllegalStateException",
                "NullPointerException", "Class", "Enum", "Iterable", "Comparable", "R", "BuildConfig",
                "Override", "SuppressWarnings", "Deprecated", "NonNull", "Nullable", "FunctionalInterface"
        ));

        if (hasWildcard) return;

        String[] lines = code.split("\n", -1);
        Set<String> reportedTypes = new HashSet<>();
        Pattern typeUsePattern = Pattern.compile("\\b(new\\s+|extends\\s+|implements\\s+|:\\s*|throws\\s+)?([A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*)\\b");
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (inBlockComment) {
                if (raw.contains("*/")) inBlockComment = false;
                continue;
            }
            if (raw.trim().startsWith("/*")) {
                if (!raw.contains("*/")) inBlockComment = true;
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                continue;
            }
            String clean = stripCommentsAndStrings(raw);
            Matcher m = typeUsePattern.matcher(clean);
            while (m.find()) {
                int start = m.start(2);
                if (start > 0 && clean.charAt(start - 1) == '.') continue;
                String name = m.group(2);
                if (importedSimple.contains(name) || knownLocal.contains(name)
                        || javaLang.contains(name) || JAVA_NON_TYPE_KEYWORDS.contains(name)) {
                    continue;
                }
                if (reportedTypes.add(name) && diags.size() < 20) {
                    diags.add(new BuildEngineTask.BuildDiagnostic(
                            BuildEngineTask.BuildDiagnostic.Severity.WARNING,
                            path, i + 1,
                            "Unresolved symbol '" + name + "' — add import or organize imports",
                            "JavaAnalyzer"));
                }
            }
        }
    }

    private static List<BuildEngineTask.BuildDiagnostic> analyzeXml(
            ProjectIndex index, File file, String xml) {
        List<BuildEngineTask.BuildDiagnostic> diags = new ArrayList<>();
        String path = file.getAbsolutePath();
        if (xml.trim().isEmpty()) {
            diags.add(new BuildEngineTask.BuildDiagnostic(
                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                    path, 1, "Empty XML file", "XmlValidator"));
            return diags;
        }

        File parent = file.getParentFile();
        String folder = parent != null ? parent.getName().toLowerCase(Locale.ROOT) : "";
        boolean isLayout = folder.startsWith("layout");
        boolean isValues = folder.startsWith("values");
        boolean isManifest = "androidmanifest.xml".equalsIgnoreCase(file.getName());

        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            SAXParser parser = factory.newSAXParser();
            parser.parse(new InputSource(new StringReader(xml)), new DefaultHandler() {
                Locator locator;
                boolean isRoot = true;

                @Override
                public void setDocumentLocator(Locator locator) {
                    this.locator = locator;
                }

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    int line = locator != null ? Math.max(1, locator.getLineNumber()) : 1;
                    if (isRoot) {
                        isRoot = false;
                        if ((isLayout || isManifest) && attributes.getValue("xmlns:android") == null) {
                            diags.add(new BuildEngineTask.BuildDiagnostic(
                                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                                    path, line,
                                    "Missing xmlns:android=\"http://schemas.android.com/apk/res/android\" on <" + qName + ">",
                                    "XmlValidator"));
                        }
                    }

                    if (isLayout && !"merge".equals(qName) && !"include".equals(qName)
                            && !"requestFocus".equals(qName) && !"tag".equals(qName)) {
                        if (attributes.getValue("android:layout_width") == null) {
                            diags.add(new BuildEngineTask.BuildDiagnostic(
                                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                                    path, line,
                                    "<" + qName + "> is missing required attribute android:layout_width",
                                    "XmlValidator"));
                        }
                        if (attributes.getValue("android:layout_height") == null) {
                            diags.add(new BuildEngineTask.BuildDiagnostic(
                                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                                    path, line,
                                    "<" + qName + "> is missing required attribute android:layout_height",
                                    "XmlValidator"));
                        }
                    }

                    // Validate dimension & color values + broken @res references
                    for (int i = 0; i < attributes.getLength(); i++) {
                        String attrName = attributes.getQName(i);
                        String attrVal = attributes.getValue(i);
                        if (attrVal == null) continue;

                        if (index != null && !isValues && attrVal.startsWith("@") && !attrVal.startsWith("@android:") && !attrVal.startsWith("@+id/")) {
                            Matcher refM = XML_RES_REF_PATTERN.matcher(attrVal);
                            if (refM.matches()) {
                                String rType = refM.group(1).replace("+", "");
                                String rName = refM.group(2);
                                if (!"id".equals(rType) && findResourceTarget(index, rType, rName) == null) {
                                    diags.add(new BuildEngineTask.BuildDiagnostic(
                                            BuildEngineTask.BuildDiagnostic.Severity.WARNING,
                                            path, line,
                                            "Unresolved resource reference " + attrVal,
                                            "XmlValidator"));
                                }
                            }
                        }

                        if ((attrName.endsWith("padding") || attrName.contains("margin") || attrName.endsWith("textSize"))
                                && attrVal.matches("\\d+")) {
                            diags.add(new BuildEngineTask.BuildDiagnostic(
                                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                                    path, line,
                                    attrName + "=\"" + attrVal + "\" requires a unit (dp, sp, or px)",
                                    "XmlValidator"));
                        }

                        if (attrVal.startsWith("#") && !attrVal.matches("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) {
                            diags.add(new BuildEngineTask.BuildDiagnostic(
                                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                                    path, line,
                                    "Invalid hex color format '" + attrVal + "' on " + attrName,
                                    "XmlValidator"));
                        }
                    }
                }
            });
        } catch (SAXParseException spe) {
            diags.add(new BuildEngineTask.BuildDiagnostic(
                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                    path, Math.max(1, spe.getLineNumber()),
                    spe.getMessage() != null ? spe.getMessage() : "Malformed XML",
                    "XmlValidator"));
        } catch (Exception e) {
            diags.add(new BuildEngineTask.BuildDiagnostic(
                    BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                    path, 1,
                    e.getMessage() != null ? e.getMessage() : "XML validation failed",
                    "XmlValidator"));
        }

        return diags;
    }

    // ==================== Internal Helpers ====================

    private static void addSdkMembers(String className, MemberCandidate... items) {
        SDK_MEMBERS.put(className, Arrays.asList(items));
    }

    private static MemberCandidate mc(String kind, String label, String insertText, int back) {
        return new MemberCandidate(kind, label, insertText, back);
    }

    private static List<File> collectProjectSourceFiles(File root) {
        List<File> out = new ArrayList<>();
        collectRecursive(root, out);
        return out;
    }

    private static void collectRecursive(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (!"build".equals(n) && !".git".equals(n) && !".gradle".equals(n)) {
                    collectRecursive(f, out);
                }
            } else {
                out.add(f);
            }
        }
    }

    private static String readEffectiveContent(File f, Map<String, String> openContentByPath) {
        if (openContentByPath != null) {
            String cached = openContentByPath.get(f.getAbsolutePath());
            if (cached != null) return cached;
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(1024 * 1024, Math.max(0, f.length()))];
            int total = 0;
            int n;
            while (total < buf.length && (n = fis.read(buf, total, buf.length - total)) != -1) {
                total += n;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeUtf8(File f, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean isDrawableBinaryOrXml(File f) {
        File parent = f.getParentFile();
        if (parent == null) return false;
        String folder = parent.getName().toLowerCase(Locale.ROOT);
        if (!folder.startsWith("drawable") && !folder.startsWith("mipmap")) return false;
        String lower = f.getName().toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".xml");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String stripPackage(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }

    private static String stripGenericsAndPackage(String type) {
        if (type == null) return "";
        int lt = type.indexOf('<');
        String base = lt > 0 ? type.substring(0, lt) : type;
        base = base.replace("[]", "").trim();
        return stripPackage(base);
    }

    private static boolean isSamePackage(String fqn, String currentPkg) {
        if (currentPkg == null || currentPkg.isEmpty()) return false;
        int dot = fqn.lastIndexOf('.');
        if (dot <= 0) return false;
        return currentPkg.equals(fqn.substring(0, dot));
    }

    private static int lineNumberAtOffset(String content, int offset) {
        int line = 1;
        int limit = Math.min(Math.max(0, offset), content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static boolean isJavaIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static final class TokenRange {
        final int start;
        final int end;
        final String text;

        TokenRange(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private static TokenRange extractIdentifierRangeAt(String code, int cursor) {
        int start = cursor;
        int end = cursor;
        if (start > 0 && (start == code.length() || !isJavaIdentPart(code.charAt(start)))) {
            if (isJavaIdentPart(code.charAt(start - 1))) {
                start--;
                end--;
            }
        }
        if (start < 0 || start >= code.length() || !isJavaIdentPart(code.charAt(start))) {
            return null;
        }
        while (start > 0 && isJavaIdentPart(code.charAt(start - 1))) {
            start--;
        }
        while (end < code.length() && isJavaIdentPart(code.charAt(end))) {
            end++;
        }
        return new TokenRange(start, end, code.substring(start, end));
    }

    private static String extractLineAtOffset(String code, int cursor) {
        int start = code.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        int end = code.indexOf('\n', cursor);
        if (end < 0) end = code.length();
        return code.substring(start, end);
    }

    private static String extractQuotedTokenAt(String line, int col) {
        int q1 = line.lastIndexOf('"', Math.max(0, Math.min(col, line.length() - 1)));
        if (q1 < 0) return null;
        int q2 = line.indexOf('"', q1 + 1);
        if (q2 < 0 || col > q2) return null;
        return line.substring(q1 + 1, q2);
    }

    private static ClassSymbol findEnclosingClass(ProjectIndex index, File file, String code) {
        if (index != null && file != null) {
            for (ClassSymbol cls : index.classesByFqn.values()) {
                if (file.equals(cls.file)) return cls;
            }
        }
        Matcher cm = CLASS_DECL_PATTERN.matcher(code);
        if (cm.find()) {
            String name = cm.group(2);
            if (index != null) {
                ClassSymbol hit = index.findClass(name);
                if (hit != null) return hit;
            }
            List<MethodSymbol> methods = new ArrayList<>();
            List<FieldSymbol> fields = new ArrayList<>();
            extractMembers(code, methods, fields);
            return new ClassSymbol("", name, name, cm.group(1), cm.group(3), file, 1, methods, fields);
        }
        return null;
    }

    private static Map<String, String> extractLocalAndParamTypes(String code, int cursor) {
        Map<String, String> map = new LinkedHashMap<>();
        String slice = code.substring(0, Math.min(cursor, code.length()));
        Matcher m = LOCAL_VAR_PATTERN.matcher(slice);
        while (m.find()) {
            String type = m.group(1);
            String var = m.group(2);
            if (JAVA_NON_TYPE_KEYWORDS.contains(type) && !isPrimitiveType(type)) continue;
            if ("return".equals(type) || "new".equals(type) || "else".equals(type)
                    || JAVA_NON_TYPE_KEYWORDS.contains(var)) {
                continue;
            }
            map.put(var, stripGenericsAndPackage(type));
        }
        return map;
    }

    private static boolean isPrimitiveType(String t) {
        return "int".equals(t) || "long".equals(t) || "float".equals(t) || "double".equals(t)
                || "boolean".equals(t) || "char".equals(t) || "byte".equals(t) || "short".equals(t);
    }

    private static String extractFieldTypeInCode(String code, String fieldName) {
        Matcher fm = FIELD_DECL_PATTERN.matcher(code);
        while (fm.find()) {
            if (fieldName.equals(fm.group(2))) {
                return stripGenericsAndPackage(fm.group(1));
            }
        }
        return null;
    }

    private static String resolveReceiverSimpleType(ProjectIndex index, String code, int cursor, String receiver) {
        if ("this".equals(receiver)) {
            ClassSymbol cls = findEnclosingClass(index, null, code);
            return cls != null ? cls.name : null;
        }
        if ("super".equals(receiver)) {
            ClassSymbol cls = findEnclosingClass(index, null, code);
            return (cls != null && !cls.superClass.isEmpty()) ? stripPackage(cls.superClass) : null;
        }
        String local = extractLocalAndParamTypes(code, cursor).get(receiver);
        if (local != null) return local;
        String field = extractFieldTypeInCode(code, receiver);
        if (field != null) return field;
        return Character.isUpperCase(receiver.charAt(0)) ? receiver : null;
    }

    private static Integer findLocalDeclarationLine(String code, int cursor, String varName) {
        String slice = code.substring(0, Math.min(cursor, code.length()));
        Matcher m = LOCAL_VAR_PATTERN.matcher(slice);
        Integer best = null;
        while (m.find()) {
            if (varName.equals(m.group(2))) {
                best = lineNumberAtOffset(slice, m.start(2));
            }
        }
        return best;
    }

    private static int countChar(String s, char target) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) c++;
        }
        return c;
    }

    private static String extractAttrNameBeforeEq(String before, int eqIdx) {
        int i = eqIdx - 1;
        while (i >= 0 && Character.isWhitespace(before.charAt(i))) i--;
        int end = i + 1;
        while (i >= 0 && (Character.isLetterOrDigit(before.charAt(i)) || before.charAt(i) == ':' || before.charAt(i) == '_')) {
            i--;
        }
        return before.substring(i + 1, end);
    }

    private static String extractOpenTagName(String insideTag) {
        if (insideTag.length() < 2 || insideTag.charAt(0) != '<') return "";
        int i = 1;
        while (i < insideTag.length() && !Character.isWhitespace(insideTag.charAt(i))
                && insideTag.charAt(i) != '>' && insideTag.charAt(i) != '/') {
            i++;
        }
        return insideTag.substring(1, i);
    }

    private static String replaceOutsideJavaCommentsAndStrings(
            String code, Pattern targetPattern, String replacement, int[] counter) {
        Pattern commentOrStr = Pattern.compile(
                "(/\\*.*?\\*/|//[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')",
                Pattern.DOTALL);
        Matcher skipM = commentOrStr.matcher(code);
        StringBuilder out = new StringBuilder(code.length());
        int lastEnd = 0;
        while (skipM.find()) {
            String segment = code.substring(lastEnd, skipM.start());
            out.append(replaceInCodeSegment(segment, targetPattern, replacement, counter));
            out.append(skipM.group(1));
            lastEnd = skipM.end();
        }
        out.append(replaceInCodeSegment(code.substring(lastEnd), targetPattern, replacement, counter));
        return out.toString();
    }

    private static String replaceInCodeSegment(
            String segment, Pattern targetPattern, String replacement, int[] counter) {
        Matcher m = targetPattern.matcher(segment);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            counter[0]++;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripCommentsAndStrings(String code) {
        Pattern p = Pattern.compile(
                "(/\\*.*?\\*/|//[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')",
                Pattern.DOTALL);
        Matcher m = p.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripLineCommentRespectingStrings(String line) {
        boolean inStr = false;
        boolean inChr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = (i + 1 < line.length()) ? line.charAt(i + 1) : '\0';
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (inChr) {
                if (c == '\\') i++;
                else if (c == '\'') inChr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '\'') inChr = true;
                else if (c == '/' && next == '/') return line.substring(0, i);
            }
        }
        return line;
    }

    private static int netParenDelta(String line) {
        String clean = stripCommentsAndStrings(line);
        int delta = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '(') delta++;
            else if (c == ')') delta--;
        }
        return delta;
    }

    private static String findNextCodeLine(String[] lines, int startIdx) {
        for (int i = startIdx; i < lines.length; i++) {
            String trimmed = stripLineCommentRespectingStrings(lines[i]).trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return null;
    }
}
