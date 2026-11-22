package com.example.sandrix.workspace;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.core.content.ContextCompat;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the hybrid Android Storage + Internal Sandrix Workspace in pure Java.
 */
public final class WorkspaceManager {

    public static final String EXTERNAL_WORKSPACE_PATH = "/storage/emulated/0/App_Project";

    public enum ProjectTemplate {
        HELLO_WORLD,
        COUNTER
    }

    private final Context context;

    public WorkspaceManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean hasExternalWorkspacePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        int read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE);
        int write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED;
    }

    public File getWorkspaceRoot() {
        if (hasExternalWorkspacePermission()) {
            File externalDir = new File(EXTERNAL_WORKSPACE_PATH);
            if (externalDir.exists() || externalDir.mkdirs()) {
                return externalDir;
            }
        }
        File appExternal = context.getExternalFilesDir("App_Project");
        File fallback = appExternal != null ? appExternal : new File(context.getFilesDir(), "App_Project");
        if (!fallback.exists()) {
            //noinspection ResultOfMethodCallIgnored
            fallback.mkdirs();
        }
        return fallback;
    }

    public List<File> ensureSampleProjects() {
        List<File> existing = listProjects();
        if (existing.isEmpty()) {
            createProject("SandrixHelloApp", "com.sandrix.hello", ProjectTemplate.HELLO_WORLD);
            createProject("CounterMaterialApp", "com.sandrix.counter", ProjectTemplate.COUNTER);
        } else {
            for (File p : existing) {
                ensureProjectThemeAndStyles(p);
            }
        }
        return listProjects();
    }

    private void ensureProjectThemeAndStyles(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) return;
        File valuesDir = new File(projectDir, "res/values");
        if (!valuesDir.exists()) {
            valuesDir = new File(projectDir, "src/main/res/values");
        }
        if (!valuesDir.exists()) {
            valuesDir = new File(projectDir, "res/values");
            //noinspection ResultOfMethodCallIgnored
            valuesDir.mkdirs();
        }
        File stylesXml = new File(valuesDir, "styles.xml");
        File themesXml = new File(valuesDir, "themes.xml");
        if (!stylesXml.exists() && !themesXml.exists()) {
            writeFile(stylesXml,
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<resources>\n"
                            + "    <!-- Base application theme inheriting from MaterialComponents / AppCompat -->\n"
                            + "    <style name=\"AppTheme\" parent=\"Theme.MaterialComponents.DayNight.NoActionBar\">\n"
                            + "        <item name=\"colorPrimary\">@color/primary_cyan</item>\n"
                            + "        <item name=\"colorPrimaryDark\">#0369A1</item>\n"
                            + "        <item name=\"colorAccent\">@color/primary_cyan</item>\n"
                            + "        <item name=\"android:windowBackground\">@color/surface_bg</item>\n"
                            + "    </style>\n"
                            + "</resources>\n");
        }
        File colorsXml = new File(valuesDir, "colors.xml");
        if (!colorsXml.exists()) {
            writeFile(colorsXml,
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<resources>\n"
                            + "    <color name=\"primary_cyan\">#0284C7</color>\n"
                            + "    <color name=\"surface_bg\">#F8FAFC</color>\n"
                            + "    <color name=\"text_dark\">#0F172A</color>\n"
                            + "</resources>\n");
        }
        File manifest = new File(projectDir, "AndroidManifest.xml");
        if (!manifest.exists()) {
            manifest = new File(projectDir, "src/main/AndroidManifest.xml");
        }
        if (manifest.exists() && manifest.isFile()) {
            String content = readFile(manifest);
            if (content.contains("<application") && !content.contains("android:theme")) {
                String updated = content.replaceFirst("(<application\\b[^>]*)(>)", "$1\n        android:theme=\"@style/AppTheme\"$2");
                writeFile(manifest, updated);
            }
        }
    }

    public List<File> listProjects() {
        File root = getWorkspaceRoot();
        if (!root.exists()) return Collections.emptyList();
        File[] files = root.listFiles();
        if (files == null) return Collections.emptyList();
        List<File> dirs = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                dirs.add(f);
            }
        }
        Collections.sort(dirs, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return dirs;
    }

    public File createProject(String projectName, String packageName, ProjectTemplate templateType) {
        String cleanName = projectName != null
                ? projectName.trim().replaceAll("[^A-Za-z0-9_-]", "")
                : "";
        if (cleanName.isEmpty()) cleanName = "SandrixApp";

        String cleanPkg = packageName != null ? packageName.trim() : "";
        if (cleanPkg.isEmpty()) cleanPkg = "com.sandrix." + cleanName.toLowerCase(Locale.ROOT);

        File projectDir = new File(getWorkspaceRoot(), cleanName);
        //noinspection ResultOfMethodCallIgnored
        projectDir.mkdirs();

        String pkgPath = cleanPkg.replace('.', '/');
        File javaDir = new File(projectDir, "src/" + pkgPath);
        File layoutDir = new File(projectDir, "res/layout");
        File valuesDir = new File(projectDir, "res/values");
        //noinspection ResultOfMethodCallIgnored
        javaDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        layoutDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        valuesDir.mkdirs();

        writeFile(new File(projectDir, "build.sd"),
                "// Sandrix Studio Project Configuration\n"
                        + "compileSdk = 36\n"
                        + "minSdk = 24\n"
                        + "targetSdk = 34\n"
                        + "versionCode = 1\n"
                        + "versionName = \"1.0\"\n"
                        + "javaVersion = 17\n\n"
                        + "buildTypes {\n"
                        + "    release {\n"
                        + "        minifyEnabled = false\n"
                        + "        shrinkResources = false\n"
                        + "        proguardFiles(\"proguard-rules.pro\")\n"
                        + "    }\n"
                        + "}\n\n"
                        + "dependencies {\n"
                        + "    implementation(\"androidx.appcompat:appcompat:1.6.1\")\n"
                        + "    implementation(\"com.google.android.material:material:1.11.0\")\n"
                        + "    implementation(\"androidx.recyclerview:recyclerview:1.3.2\")\n"
                        + "}\n");

        writeFile(new File(projectDir, "proguard-rules.pro"),
                "# Add project-specific R8 / ProGuard keep rules here.\n"
                        + "# By default Sandrix R8 keeps Android components, @Keep, and Parcelable.CREATOR.\n"
                        + "-keepclassmembers class * {\n"
                        + "    @android.webkit.JavascriptInterface <methods>;\n"
                        + "}\n");

        writeFile(new File(projectDir, "AndroidManifest.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    package=\"" + cleanPkg + "\">\n\n"
                        + "    <uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"34\" />\n\n"
                        + "    <application\n"
                        + "        android:allowBackup=\"true\"\n"
                        + "        android:label=\"@string/app_name\"\n"
                        + "        android:theme=\"@style/AppTheme\">\n"
                        + "        <activity\n"
                        + "            android:name=\".MainActivity\"\n"
                        + "            android:exported=\"true\">\n"
                        + "            <intent-filter>\n"
                        + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                        + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                        + "            </intent-filter>\n"
                        + "        </activity>\n"
                        + "    </application>\n"
                        + "</manifest>\n");

        writeFile(new File(valuesDir, "strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <string name=\"app_name\">" + cleanName + "</string>\n"
                        + "    <string name=\"welcome_title\">Built with Sandrix Engine</string>\n"
                        + "</resources>\n");

        writeFile(new File(valuesDir, "colors.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <color name=\"primary_cyan\">#0284C7</color>\n"
                        + "    <color name=\"surface_bg\">#F8FAFC</color>\n"
                        + "    <color name=\"text_dark\">#0F172A</color>\n"
                        + "</resources>\n");

        writeFile(new File(valuesDir, "styles.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <!-- Base application theme inheriting from MaterialComponents / AppCompat -->\n"
                        + "    <style name=\"AppTheme\" parent=\"Theme.MaterialComponents.DayNight.NoActionBar\">\n"
                        + "        <item name=\"colorPrimary\">@color/primary_cyan</item>\n"
                        + "        <item name=\"colorPrimaryDark\">#0369A1</item>\n"
                        + "        <item name=\"colorAccent\">@color/primary_cyan</item>\n"
                        + "        <item name=\"android:windowBackground\">@color/surface_bg</item>\n"
                        + "    </style>\n"
                        + "</resources>\n");

        if (templateType == ProjectTemplate.COUNTER) {
            writeFile(new File(javaDir, "MainActivity.java"),
                    "package " + cleanPkg + ";\n\n"
                            + "import android.app.Activity;\n"
                            + "import android.os.Bundle;\n"
                            + "import android.view.View;\n"
                            + "import android.widget.Button;\n"
                            + "import android.widget.TextView;\n"
                            + "import android.widget.Toast;\n\n"
                            + "public class MainActivity extends Activity {\n\n"
                            + "    private int count = 0;\n\n"
                            + "    @Override\n"
                            + "    protected void onCreate(Bundle savedInstanceState) {\n"
                            + "        super.onCreate(savedInstanceState);\n"
                            + "        setContentView(R.layout.activity_main);\n\n"
                            + "        TextView tvCounter = findViewById(R.id.tvCounter);\n"
                            + "        Button btnIncrement = findViewById(R.id.btnIncrement);\n\n"
                            + "        btnIncrement.setOnClickListener(new View.OnClickListener() {\n"
                            + "            @Override\n"
                            + "            public void onClick(View v) {\n"
                            + "                count++;\n"
                            + "                tvCounter.setText(\"Count: \" + count);\n"
                            + "                Toast.makeText(MainActivity.this, \"Updated: \" + count, Toast.LENGTH_SHORT).show();\n"
                            + "            }\n"
                            + "        });\n"
                            + "    }\n"
                            + "}\n");

            writeFile(new File(layoutDir, "activity_main.xml"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                            + "    android:layout_width=\"match_parent\"\n"
                            + "    android:layout_height=\"match_parent\"\n"
                            + "    android:background=\"#0F172A\"\n"
                            + "    android:gravity=\"center\"\n"
                            + "    android:orientation=\"vertical\"\n"
                            + "    android:padding=\"24dp\">\n\n"
                            + "    <TextView\n"
                            + "        android:layout_width=\"wrap_content\"\n"
                            + "        android:layout_height=\"wrap_content\"\n"
                            + "        android:text=\"@string/welcome_title\"\n"
                            + "        android:textColor=\"#38BDF8\"\n"
                            + "        android:textSize=\"14sp\"\n"
                            + "        android:textStyle=\"bold\" />\n\n"
                            + "    <TextView\n"
                            + "        android:id=\"@+id/tvCounter\"\n"
                            + "        android:layout_width=\"wrap_content\"\n"
                            + "        android:layout_height=\"wrap_content\"\n"
                            + "        android:layout_marginTop=\"16dp\"\n"
                            + "        android:layout_marginBottom=\"24dp\"\n"
                            + "        android:text=\"Count: 0\"\n"
                            + "        android:textColor=\"#F8FAFC\"\n"
                            + "        android:textSize=\"32sp\"\n"
                            + "        android:textStyle=\"bold\" />\n\n"
                            + "    <Button\n"
                            + "        android:id=\"@+id/btnIncrement\"\n"
                            + "        android:layout_width=\"wrap_content\"\n"
                            + "        android:layout_height=\"wrap_content\"\n"
                            + "        android:background=\"#0284C7\"\n"
                            + "        android:padding=\"14dp\"\n"
                            + "        android:text=\"Increment Counter\"\n"
                            + "        android:textColor=\"#FFFFFF\" />\n\n"
                            + "</LinearLayout>\n");
        } else {
            writeFile(new File(javaDir, "MainActivity.java"),
                    "package " + cleanPkg + ";\n\n"
                            + "import android.app.Activity;\n"
                            + "import android.os.Bundle;\n"
                            + "import android.widget.Button;\n"
                            + "import android.widget.TextView;\n"
                            + "import android.widget.Toast;\n\n"
                            + "public class MainActivity extends Activity {\n\n"
                            + "    @Override\n"
                            + "    protected void onCreate(Bundle savedInstanceState) {\n"
                            + "        super.onCreate(savedInstanceState);\n"
                            + "        setContentView(R.layout.activity_main);\n\n"
                            + "        TextView titleView = findViewById(R.id.tvTitle);\n"
                            + "        Button launchBtn = findViewById(R.id.btnLaunch);\n\n"
                            + "        launchBtn.setOnClickListener(v -> {\n"
                            + "            titleView.setText(\"Sandrix Native Build Active\");\n"
                            + "            Toast.makeText(this, \"Hello from Sandrix Studio!\", Toast.LENGTH_SHORT).show();\n"
                            + "        });\n"
                            + "    }\n"
                            + "}\n");

            writeFile(new File(layoutDir, "activity_main.xml"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                            + "    android:layout_width=\"match_parent\"\n"
                            + "    android:layout_height=\"match_parent\"\n"
                            + "    android:background=\"@color/surface_bg\"\n"
                            + "    android:gravity=\"center_horizontal\"\n"
                            + "    android:orientation=\"vertical\"\n"
                            + "    android:padding=\"24dp\">\n\n"
                            + "    <TextView\n"
                            + "        android:id=\"@+id/tvTitle\"\n"
                            + "        android:layout_width=\"wrap_content\"\n"
                            + "        android:layout_height=\"wrap_content\"\n"
                            + "        android:layout_marginTop=\"24dp\"\n"
                            + "        android:text=\"@string/welcome_title\"\n"
                            + "        android:textColor=\"@color/text_dark\"\n"
                            + "        android:textSize=\"22sp\"\n"
                            + "        android:textStyle=\"bold\" />\n\n"
                            + "    <TextView\n"
                            + "        android:layout_width=\"wrap_content\"\n"
                            + "        android:layout_height=\"wrap_content\"\n"
                            + "        android:layout_marginTop=\"8dp\"\n"
                            + "        android:gravity=\"center\"\n"
                            + "        android:text=\"Edit this XML layout and watch LayoutPreviewer update in real time.\"\n"
                            + "        android:textColor=\"#475569\"\n"
                            + "        android:textSize=\"14sp\" />\n\n"
                            + "    <EditText\n"
                            + "        android:id=\"@+id/etInput\"\n"
                            + "        android:layout_width=\"match_parent\"\n"
                            + "        android:layout_height=\"wrap_content\"\n"
                            + "        android:layout_marginTop=\"20dp\"\n"
                            + "        android:hint=\"Type a message...\"\n"
                            + "        android:padding=\"12dp\" />\n\n"
                            + "    <Button\n"
                            + "        android:id=\"@+id/btnLaunch\"\n"
                            + "        android:layout_width=\"match_parent\"\n"
                            + "        android:layout_height=\"wrap_content\"\n"
                            + "        android:layout_marginTop=\"16dp\"\n"
                            + "        android:background=\"@color/primary_cyan\"\n"
                            + "        android:text=\"Run Action\"\n"
                            + "        android:textColor=\"#FFFFFF\" />\n\n"
                            + "</LinearLayout>\n");
        }
        return projectDir;
    }

    public String readFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return "";
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }

    public void writeFile(File file, String content) {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    public File createNewFile(File projectDir, String relativePath) {
        String cleanRel = relativePath != null ? relativePath.trim() : "";
        while (cleanRel.startsWith("/")) {
            cleanRel = cleanRel.substring(1);
        }
        if (cleanRel.toLowerCase(Locale.ROOT).endsWith(".kt")
                || cleanRel.toLowerCase(Locale.ROOT).endsWith(".kts")) {
            return null;
        }
        File target = new File(projectDir, cleanRel);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        String content;
        if (cleanRel.endsWith(".java")) {
            String name = target.getName();
            int dot = name.lastIndexOf('.');
            String clsName = dot > 0 ? name.substring(0, dot) : name;
            String pkg = inferPackageFromPath(projectDir, target);
            if (pkg == null || pkg.isEmpty()) pkg = "com.sandrix.app";
            content = "package " + pkg + ";\n\n"
                    + "import android.view.View;\n\n"
                    + "public class " + clsName + " {\n"
                    + "    public " + clsName + "() {\n"
                    + "    }\n"
                    + "}\n";
        } else if (cleanRel.endsWith(".xml")) {
            content = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                    + "    android:layout_width=\"match_parent\"\n"
                    + "    android:layout_height=\"match_parent\"\n"
                    + "    android:orientation=\"vertical\"\n"
                    + "    android:padding=\"16dp\">\n\n"
                    + "    <TextView\n"
                    + "        android:layout_width=\"wrap_content\"\n"
                    + "        android:layout_height=\"wrap_content\"\n"
                    + "        android:text=\"New Layout\" />\n\n"
                    + "</LinearLayout>\n";
        } else if (cleanRel.endsWith(".sd")) {
            content = "compileSdk = 36\nminSdk = 24\ntargetSdk = 34\nversionCode = 1\nversionName = \"1.0\"\n";
        } else {
            content = "";
        }
        writeFile(target, content);
        return target;
    }

    private String inferPackageFromPath(File projectDir, File file) {
        File[] candidates = {
                new File(projectDir, "app/src/main/java"),
                new File(projectDir, "src/main/java"),
                new File(projectDir, "src")
        };
        File parent = file.getParentFile();
        if (parent == null) return null;
        String parentPath = parent.getAbsolutePath();
        for (File srcRoot : candidates) {
            String srcPath = srcRoot.getAbsolutePath();
            if (parentPath.startsWith(srcPath)) {
                String rel = parentPath.substring(srcPath.length());
                while (rel.startsWith(File.separator)) {
                    rel = rel.substring(1);
                }
                if (!rel.isEmpty()) {
                    return rel.replace(File.separatorChar, '.');
                }
            }
        }
        return null;
    }

    public File resolveResDir(File projectDir) {
        if (projectDir == null) return null;
        File[] candidates = {
                new File(projectDir, "res"),
                new File(projectDir, "src/main/res"),
                new File(projectDir, "app/src/main/res")
        };
        for (File c : candidates) {
            if (c.exists() && c.isDirectory()) return c;
        }
        return candidates[0];
    }

    public File resolveManifestFile(File projectDir) {
        if (projectDir == null) return null;
        File[] candidates = {
                new File(projectDir, "AndroidManifest.xml"),
                new File(projectDir, "src/main/AndroidManifest.xml"),
                new File(projectDir, "app/src/main/AndroidManifest.xml")
        };
        for (File c : candidates) {
            if (c.exists() && c.isFile()) return c;
        }
        return candidates[0];
    }

    public File resolveBuildConfigFile(File projectDir) {
        if (projectDir == null) return null;
        File[] candidates = {
                new File(projectDir, "build.sd"),
                new File(projectDir, "app/build.sd"),
                new File(projectDir, "build.gradle"),
                new File(projectDir, "app/build.gradle")
        };
        for (File c : candidates) {
            if (c.exists() && c.isFile()) return c;
        }
        return candidates[0];
    }

    public File resolveAssetsDir(File projectDir) {
        if (projectDir == null) return null;
        File[] candidates = {
                new File(projectDir, "assets"),
                new File(projectDir, "src/main/assets"),
                new File(projectDir, "app/src/main/assets")
        };
        for (File c : candidates) {
            if (c.exists() && c.isDirectory()) return c;
        }
        return candidates[0];
    }

    public List<File> findDefaultFilesToOpen(File projectDir) {
        List<File> result = new ArrayList<>();
        File mainActivity = findFirstFileNamed(projectDir, "MainActivity.java");
        File mathHelper = findFirstFileNamed(projectDir, "MathHelper.java");
        File activityMain = findPrimaryLayoutXml(projectDir);
        File buildConfig = resolveBuildConfigFile(projectDir);
        File manifest = resolveManifestFile(projectDir);

        if (mainActivity != null && mainActivity.exists()) result.add(mainActivity);
        if (mathHelper != null && mathHelper.exists()) result.add(mathHelper);
        if (activityMain != null && activityMain.exists()) result.add(activityMain);
        if (buildConfig != null && buildConfig.exists()) result.add(buildConfig);
        if (manifest != null && manifest.exists() && result.isEmpty()) result.add(manifest);
        return result;
    }

    public File findPrimaryLayoutXml(File projectDir) {
        File resDir = resolveResDir(projectDir);
        File candidate = new File(resDir, "layout/activity_main.xml");
        if (candidate.exists()) return candidate;
        File layoutDir = new File(resDir, "layout");
        File[] files = layoutDir.listFiles();
        if (files == null) return null;
        Arrays.sort(files);
        for (File f : files) {
            if (f.getName().endsWith(".xml")) return f;
        }
        return null;
    }

    private File findFirstFileNamed(File dir, String targetName) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory() && !"build".equals(f.getName()) && !".git".equals(f.getName())) {
                File found = findFirstFileNamed(f, targetName);
                if (found != null) return found;
            } else if (targetName.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    public Map<String, String> parseStringResources(File projectDir) {
        return parseValueXml(new File(resolveResDir(projectDir), "values/strings.xml"), "string");
    }

    public Map<String, String> parseColorResources(File projectDir) {
        return parseValueXml(new File(resolveResDir(projectDir), "values/colors.xml"), "color");
    }

    public Map<String, String> parseDimenResources(File projectDir) {
        return parseValueXml(new File(resolveResDir(projectDir), "values/dimens.xml"), "dimen");
    }

    private Map<String, String> parseValueXml(File file, String tagName) {
        if (!file.exists()) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(readFile(file)));
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && tagName.equals(parser.getName())) {
                    String key = parser.getAttributeValue(null, "name");
                    String value = parser.nextText();
                    if (key != null && !key.trim().isEmpty()) {
                        map.put(key, value);
                    }
                }
                event = parser.next();
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    public Map<String, String> saveStringResource(File projectDir, String rawKey, String value) {
        if (projectDir == null) return Collections.emptyMap();
        String key = sanitizeResourceName(rawKey, "string_item");
        Map<String, String> map = new LinkedHashMap<>(parseStringResources(projectDir));
        map.put(key, value != null ? value : "");
        writeValueXml(new File(resolveResDir(projectDir), "values/strings.xml"), "string", map);
        return Collections.unmodifiableMap(map);
    }

    public Map<String, String> deleteStringResource(File projectDir, String key) {
        if (projectDir == null) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>(parseStringResources(projectDir));
        if (key != null) {
            map.remove(key);
        }
        writeValueXml(new File(resolveResDir(projectDir), "values/strings.xml"), "string", map);
        return Collections.unmodifiableMap(map);
    }

    public Map<String, String> saveColorResource(File projectDir, String rawKey, String rawHexColor) {
        if (projectDir == null) return Collections.emptyMap();
        String key = sanitizeResourceName(rawKey, "color_item");
        String hex = normalizeHexColor(rawHexColor);
        if (hex == null) {
            throw new IllegalArgumentException("Color must be #RGB, #ARGB, #RRGGBB, or #AARRGGBB");
        }
        Map<String, String> map = new LinkedHashMap<>(parseColorResources(projectDir));
        map.put(key, hex);
        writeValueXml(new File(resolveResDir(projectDir), "values/colors.xml"), "color", map);
        return Collections.unmodifiableMap(map);
    }

    public Map<String, String> deleteColorResource(File projectDir, String key) {
        if (projectDir == null) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>(parseColorResources(projectDir));
        if (key != null) {
            map.remove(key);
        }
        writeValueXml(new File(resolveResDir(projectDir), "values/colors.xml"), "color", map);
        return Collections.unmodifiableMap(map);
    }

    public Map<String, String> saveDimenResource(File projectDir, String rawKey, String value) {
        if (projectDir == null) return Collections.emptyMap();
        String key = sanitizeResourceName(rawKey, "dimen_item");
        Map<String, String> map = new LinkedHashMap<>(parseDimenResources(projectDir));
        map.put(key, value != null ? value.trim() : "16dp");
        writeValueXml(new File(resolveResDir(projectDir), "values/dimens.xml"), "dimen", map);
        return Collections.unmodifiableMap(map);
    }

    public Map<String, String> deleteDimenResource(File projectDir, String key) {
        if (projectDir == null) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>(parseDimenResources(projectDir));
        if (key != null) {
            map.remove(key);
        }
        writeValueXml(new File(resolveResDir(projectDir), "values/dimens.xml"), "dimen", map);
        return Collections.unmodifiableMap(map);
    }

    public List<File> listDrawableFiles(File projectDir) {
        if (projectDir == null) return Collections.emptyList();
        return listSortedFiles(new File(resolveResDir(projectDir), "drawable"));
    }

    public List<File> listAssetFiles(File projectDir) {
        if (projectDir == null) return Collections.emptyList();
        File assetsDir = resolveAssetsDir(projectDir);
        if (assetsDir == null || !assetsDir.exists() || !assetsDir.isDirectory()) return Collections.emptyList();
        List<File> out = new ArrayList<>();
        collectFilesRecursive(assetsDir, out);
        Collections.sort(out, Comparator.comparing(f -> f.getAbsolutePath().toLowerCase(Locale.ROOT)));
        return out;
    }

    public File createXmlDrawable(File projectDir, String rawName, String templateType) {
        if (projectDir == null) {
            throw new IllegalArgumentException("No active project");
        }
        String resName = sanitizeResourceName(rawName, "bg_custom_shape");
        File drawableDir = new File(resolveResDir(projectDir), "drawable");
        if (!drawableDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            drawableDir.mkdirs();
        }
        File outFile = new File(drawableDir, resName + ".xml");
        String content;
        String type = templateType != null ? templateType.toUpperCase(Locale.ROOT) : "ROUNDED_CARD";
        switch (type) {
            case "GRADIENT":
                content = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:shape=\"rectangle\">\n"
                        + "    <gradient\n"
                        + "        android:angle=\"135\"\n"
                        + "        android:startColor=\"#0284C7\"\n"
                        + "        android:endColor=\"#10B981\" />\n"
                        + "    <corners android:radius=\"16dp\" />\n"
                        + "</shape>\n";
                break;
            case "CIRCLE_BADGE":
                content = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:shape=\"oval\">\n"
                        + "    <solid android:color=\"#38BDF8\" />\n"
                        + "    <size android:width=\"48dp\" android:height=\"48dp\" />\n"
                        + "    <stroke android:width=\"2dp\" android:color=\"#FFFFFF\" />\n"
                        + "</shape>\n";
                break;
            case "STAR_VECTOR":
                content = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:width=\"24dp\"\n"
                        + "    android:height=\"24dp\"\n"
                        + "    android:viewportWidth=\"24\"\n"
                        + "    android:viewportHeight=\"24\">\n"
                        + "    <path\n"
                        + "        android:fillColor=\"#F59E0B\"\n"
                        + "        android:pathData=\"M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2L9.19,8.63L2,9.24l5.46,4.73L5.82,21z\" />\n"
                        + "</vector>\n";
                break;
            case "ROUNDED_CARD":
            default:
                content = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:shape=\"rectangle\">\n"
                        + "    <solid android:color=\"#161B22\" />\n"
                        + "    <corners android:radius=\"14dp\" />\n"
                        + "    <stroke android:width=\"1dp\" android:color=\"#30363D\" />\n"
                        + "</shape>\n";
                break;
        }
        writeFile(outFile, content);
        return outFile;
    }

    public File importDrawableImage(File projectDir, Uri uri, String preferredName) throws IOException {
        if (projectDir == null || uri == null) {
            throw new IOException("Missing project or image URI");
        }
        String displayName = (preferredName != null && !preferredName.trim().isEmpty())
                ? preferredName
                : queryDisplayName(uri);
        String ext = detectImageExtension(uri, displayName);
        String base = stripExtension(displayName);
        String resName = sanitizeResourceName(base, "img_imported_" + (System.currentTimeMillis() % 10000L));

        File drawableDir = new File(resolveResDir(projectDir), "drawable");
        if (!drawableDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            drawableDir.mkdirs();
        }
        File target = uniqueTargetFile(drawableDir, resName, ext);
        copyUriToFile(uri, target);
        return target;
    }

    public File importProjectAsset(File projectDir, Uri uri, String preferredName) throws IOException {
        if (projectDir == null || uri == null) {
            throw new IOException("Missing project or asset URI");
        }
        String displayName = (preferredName != null && !preferredName.trim().isEmpty())
                ? preferredName
                : queryDisplayName(uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "asset_" + (System.currentTimeMillis() % 100000L) + ".bin";
        }
        String safeFileName = displayName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeFileName.startsWith(".")) {
            safeFileName = "asset" + safeFileName;
        }
        File assetsDir = resolveAssetsDir(projectDir);
        if (!assetsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            assetsDir.mkdirs();
        }
        File target = new File(assetsDir, safeFileName);
        if (target.exists()) {
            String ext = "";
            int dot = safeFileName.lastIndexOf('.');
            String base = safeFileName;
            if (dot > 0) {
                base = safeFileName.substring(0, dot);
                ext = safeFileName.substring(dot);
            }
            target = uniqueTargetFile(assetsDir, base, ext);
        }
        copyUriToFile(uri, target);
        return target;
    }

    public boolean deleteProjectResourceFile(File target) {
        return target != null && target.exists() && target.isFile() && target.delete();
    }

    public List<String> collectXmlResourceIds(File projectDir) {
        if (projectDir == null) return Collections.emptyList();
        File layoutDir = new File(resolveResDir(projectDir), "layout");
        File[] files = layoutDir.listFiles();
        if (files == null || files.length == 0) return Collections.emptyList();
        Pattern idPattern = Pattern.compile("@\\+?id/([A-Za-z_][A-Za-z0-9_]*)");
        Set<String> ids = new LinkedHashSet<>();
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".xml")) {
                String content = readFile(f);
                Matcher m = idPattern.matcher(content);
                while (m.find()) {
                    ids.add(m.group(1));
                }
            }
        }
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    public static String sanitizeResourceName(String rawName, String fallback) {
        String s = rawName != null ? rawName.trim().toLowerCase(Locale.ROOT) : "";
        s = s.replaceAll("[^a-z0-9_]", "_");
        s = s.replaceAll("_+", "_");
        while (s.startsWith("_")) s = s.substring(1);
        while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) {
            s = (s.isEmpty() ? fallback : "res_" + s);
        }
        return s;
    }

    public static String normalizeHexColor(String rawHex) {
        if (rawHex == null) return null;
        String trimmed = rawHex.trim().toUpperCase(Locale.ROOT);
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (trimmed.matches("^#([0-9A-F]{3}|[0-9A-F]{4}|[0-9A-F]{6}|[0-9A-F]{8})$")) {
            return trimmed;
        }
        return null;
    }

    private void writeValueXml(File file, String tagName, Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<resources>\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append("    <").append(tagName)
                    .append(" name=\"").append(escapeXmlAttr(entry.getKey())).append("\">")
                    .append(escapeXmlText(entry.getValue()))
                    .append("</").append(tagName).append(">\n");
        }
        sb.append("</resources>\n");
        writeFile(file, sb.toString());
    }

    private static String escapeXmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeXmlText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private List<File> listSortedFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] raw = dir.listFiles();
        if (raw == null || raw.length == 0) {
            return Collections.emptyList();
        }
        List<File> out = new ArrayList<>();
        for (File f : raw) {
            if (f.isFile()) {
                out.add(f);
            }
        }
        Collections.sort(out, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void collectFilesRecursive(File dir, List<File> out) {
        File[] raw = dir.listFiles();
        if (raw == null) return;
        for (File f : raw) {
            if (f.isDirectory()) {
                collectFilesRecursive(f, out);
            } else {
                out.add(f);
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return null;
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver()
                    .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        name = cursor.getString(idx);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    private String detectImageExtension(Uri uri, String displayName) {
        String mime = null;
        try {
            mime = context.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        if (mime != null) {
            if (mime.contains("webp")) return ".webp";
            if (mime.contains("jpeg") || mime.contains("jpg")) return ".jpg";
            if (mime.contains("png")) return ".png";
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null && !ext.isEmpty()) {
                if ("jpeg".equalsIgnoreCase(ext)) return ".jpg";
                return "." + ext.toLowerCase(Locale.ROOT);
            }
        }
        if (displayName != null) {
            String lower = displayName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".webp")) return ".webp";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        }
        return ".png";
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) return "";
        int slash = fileName.lastIndexOf('/');
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static File uniqueTargetFile(File dir, String base, String ext) {
        File candidate = new File(dir, base + ext);
        int idx = 1;
        while (candidate.exists()) {
            candidate = new File(dir, base + "_" + idx + ext);
            idx++;
        }
        return candidate;
    }

    private void copyUriToFile(Uri uri, File target) throws IOException {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("Unable to read selected file");
            }
            try (FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
        }
    }
}
