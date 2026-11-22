package com.example.sandrix.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.example.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom Android IDE Code Editor supporting:
 * <ul>
 *     <li>Syntax highlighting for {@code .java}, {@code .xml}, and {@code .sd} ({@code build.sd}).</li>
 *     <li>Line-number gutter with synchronized vertical scrolling and error-line markers.</li>
 *     <li>Context-aware IntelliSense auto-completion overlay for Java keywords, Android classes
 *     ({@code Activity}, {@code Intent}, {@code Toast}, {@code View}), XML layout tags/attributes,
 *     and {@code build.sd} DSL directives.</li>
 * </ul>
 */
public class CodeEditorView extends FrameLayout {

    public enum Language {
        JAVA,
        XML,
        SANDRIX_SD,
        PLAIN;

        public static Language fromFileName(String fileName) {
            if (fileName == null) return PLAIN;
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java")) return JAVA;
            if (lower.endsWith(".xml")) return XML;
            if (lower.endsWith(".sd")) return SANDRIX_SD;
            return PLAIN;
        }
    }

    public interface OnCodeChangedListener {
        void onCodeChanged(String newCode);
    }

    public interface ContextCompletionProvider {
        @Nullable
        List<SuggestionItem> provide(Language language, String code, int cursorOffset, boolean manualTrigger);
    }

    public interface OnBreakpointToggleListener {
        void onToggleBreakpoint(int lineNumber);
        default void onBreakpointLongPress(int lineNumber) {}
    }

    public static final class SuggestionItem {
        public final String kind;
        public final String label;
        public final String insertText;
        public final int cursorBackOffset;
        public final int tokenStartOverride;

        public SuggestionItem(String kind, String label, String insertText, int cursorBackOffset) {
            this(kind, label, insertText, cursorBackOffset, -1);
        }

        public SuggestionItem(String kind, String label, String insertText, int cursorBackOffset, int tokenStartOverride) {
            this.kind = kind;
            this.label = label;
            this.insertText = insertText;
            this.cursorBackOffset = cursorBackOffset;
            this.tokenStartOverride = tokenStartOverride;
        }
    }

    // Syntax palette (Dark & Light)
    private static final int DARK_BG = 0xFF0D1117;
    private static final int DARK_GUTTER_BG = 0xFF121821;
    private static final int DARK_GUTTER_TEXT = 0xFF6E7681;
    private static final int DARK_TEXT = 0xFFE6EDF3;
    private static final int DARK_KEYWORD = 0xFFFF7B72;
    private static final int DARK_TYPE = 0xFF79C0FF;
    private static final int DARK_METHOD = 0xFF56D4DD;
    private static final int DARK_ANDROID_RES = 0xFF7EE787;
    private static final int DARK_STRING = 0xFFA5D6FF;
    private static final int DARK_COMMENT = 0xFF8B949E;
    private static final int DARK_NUMBER = 0xFFF2CC60;
    private static final int DARK_ANNOTATION = 0xFFD2A8FF;
    private static final int DARK_XML_TAG = 0xFF7EE787;
    private static final int DARK_XML_ANDROID_TAG = 0xFF34D399;
    private static final int DARK_XML_ATTR = 0xFF79C0FF;
    private static final int DARK_XML_NS = 0xFFD2A8FF;
    private static final int DARK_XML_BRACKET = 0xFF8B949E;
    private static final int DARK_ERROR_BG = 0x44F85149;

    private static final int LIGHT_BG = 0xFFFAFBFD;
    private static final int LIGHT_GUTTER_BG = 0xFFF1F5F9;
    private static final int LIGHT_GUTTER_TEXT = 0xFF64748B;
    private static final int LIGHT_TEXT = 0xFF0F172A;
    private static final int LIGHT_KEYWORD = 0xFFCF222E;
    private static final int LIGHT_TYPE = 0xFF0550AE;
    private static final int LIGHT_METHOD = 0xFF0E7490;
    private static final int LIGHT_ANDROID_RES = 0xFF116329;
    private static final int LIGHT_STRING = 0xFF0A3069;
    private static final int LIGHT_COMMENT = 0xFF6E7781;
    private static final int LIGHT_NUMBER = 0xFF953800;
    private static final int LIGHT_ANNOTATION = 0xFF8250DF;
    private static final int LIGHT_XML_TAG = 0xFF1A7F37;
    private static final int LIGHT_XML_ANDROID_TAG = 0xFF0F766E;
    private static final int LIGHT_XML_ATTR = 0xFF0550AE;
    private static final int LIGHT_XML_NS = 0xFF8250DF;
    private static final int LIGHT_XML_BRACKET = 0xFF64748B;
    private static final int LIGHT_ERROR_BG = 0x33DC2626;

    // Combined lexers (comments & strings/text-blocks matched left-to-right to avoid overlap bleed)
    private static final Pattern CODE_COMMENT_OR_STRING = Pattern.compile(
            "(/\\*.*?\\*/|//[^\\n]*)|(\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')",
            Pattern.DOTALL);
    private static final Pattern XML_COMMENT_OR_STRING = Pattern.compile(
            "(<!--.*?-->)|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')",
            Pattern.DOTALL);

    // Java 17 & Android SDK patterns
    private static final Pattern JAVA_KEYWORDS = Pattern.compile(
            "(?:\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|permits|private|protected|public|record|return|sealed|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|var|void|volatile|while|yield|true|false|null)\\b|(?<![\\w-])non-sealed(?![\\w-]))");
    private static final Set<String> JAVA_KEYWORD_SET = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "non-sealed", "package", "permits", "private",
            "protected", "public", "record", "return", "sealed", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "var", "void", "volatile", "while", "yield", "true", "false", "null"
    ));
    private static final Pattern JAVA_TYPES = Pattern.compile(
            "\\b(Activity|AppCompatActivity|Fragment|FragmentActivity|Dialog|AlertDialog|DialogFragment|Service|IntentService|BroadcastReceiver|ContentProvider|Application|Context|Bundle|Intent|PendingIntent|Uri|Toast|Snackbar|View|ViewGroup|TextView|Button|ImageButton|EditText|ImageView|CheckBox|RadioButton|RadioGroup|Switch|ToggleButton|SeekBar|ProgressBar|Spinner|ListView|GridView|WebView|LinearLayout|RelativeLayout|FrameLayout|ConstraintLayout|CoordinatorLayout|ScrollView|NestedScrollView|HorizontalScrollView|RecyclerView|CardView|DrawerLayout|Toolbar|ActionBar|FloatingActionButton|BottomNavigationView|TabLayout|ViewPager2|LayoutInflater|Menu|MenuItem|SharedPreferences|Handler|Looper|Resources|Drawable|Bitmap|Canvas|Paint|Color|Log|R|Build|Manifest|PackageManager|Notification|NotificationManager|NotificationCompat|OnClickListener|TextWatcher|Editable|Spannable|SpannableString|String|StringBuilder|CharSequence|Integer|Long|Float|Double|Boolean|Object|Class|System|Math|Thread|Runnable|List|ArrayList|Map|HashMap|Set|HashSet|Collections|Arrays|Objects|File|Exception|RuntimeException|IOException|Override|SuppressWarnings|NonNull|Nullable|[A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*)\\b");
    private static final Pattern JAVA_CONSTANTS = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,}\\b");
    private static final Pattern JAVA_METHOD_CALL = Pattern.compile("\\b([a-z_][A-Za-z0-9_]*)(?=\\s*\\()");
    private static final Pattern JAVA_ANDROID_R_REF = Pattern.compile(
            "\\b(?:android\\.)?R\\.(?:id|layout|string|color|drawable|mipmap|style|dimen|anim|menu|raw|xml|attr)\\.[A-Za-z_][A-Za-z0-9_]*\\b");
    private static final Pattern JAVA_ANNOTATION = Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern NUMBER_LITERAL = Pattern.compile(
            "\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?[fFdDlL]?)\\b");

    // XML & Android Layout / Manifest / Resource patterns
    private static final Pattern XML_DECL_OR_BRACKET = Pattern.compile("<\\?xml\\b|\\?>|</?|/?>");
    private static final Pattern XML_TAG_NAME = Pattern.compile("</?([A-Za-z_][A-Za-z0-9_.:-]*)");
    private static final Pattern XML_ATTR_PREFIXED = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_-]*):([a-zA-Z_][a-zA-Z0-9_-]*)(?=\\s*=)");
    private static final Pattern XML_ATTR_UNPREFIXED = Pattern.compile(
            "(?<![a-zA-Z0-9_:-])([a-zA-Z_][a-zA-Z0-9_-]*)(?=\\s*=)");
    private static final Pattern XML_RESOURCE_REF = Pattern.compile(
            "@\\+?(?:[a-zA-Z0-9_.]+:)?[a-zA-Z_][a-zA-Z0-9_]*/[A-Za-z0-9_.-]+|(?<=[\"']|\\s)\\?(?:[a-zA-Z0-9_.]+:)?(?:attr/)?[A-Za-z_][A-Za-z0-9_.-]*|\\?(?:[a-zA-Z0-9_.]+:)?attr/[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern XML_HEX_COLOR = Pattern.compile(
            "#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})\\b");
    private static final Pattern XML_DIMEN = Pattern.compile("\\b\\d+(?:\\.\\d+)?(?:dp|dip|sp|px|pt|in|mm)\\b");
    private static final Pattern XML_VALUE_KEYWORDS = Pattern.compile(
            "\\b(match_parent|wrap_content|fill_parent|vertical|horizontal|center|center_horizontal|center_vertical|top|bottom|start|end|left|right|bold|italic|normal|visible|invisible|gone|true|false)\\b");
    private static final Pattern XML_ENTITY = Pattern.compile("&(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);");

    private static final Set<String> ANDROID_XML_TAGS = new HashSet<>(Arrays.asList(
            // Layouts & Containers
            "LinearLayout", "RelativeLayout", "FrameLayout", "ConstraintLayout", "CoordinatorLayout",
            "MotionLayout", "ScrollView", "NestedScrollView", "HorizontalScrollView", "GridLayout",
            "TableLayout", "TableRow", "SwipeRefreshLayout", "DrawerLayout", "AppBarLayout",
            "CollapsingToolbarLayout", "ViewPager", "ViewPager2", "RecyclerView", "CardView",
            "MaterialCardView", "ViewStub", "FragmentContainerView", "include", "merge", "fragment",
            "requestFocus",
            // Widgets & Views
            "TextView", "Button", "MaterialButton", "ImageButton", "FloatingActionButton",
            "ExtendedFloatingActionButton", "EditText", "TextInputLayout", "TextInputEditText",
            "AutoCompleteTextView", "ImageView", "ShapeableImageView", "CheckBox", "MaterialCheckBox",
            "RadioButton", "RadioGroup", "Switch", "SwitchMaterial", "MaterialSwitch", "ToggleButton",
            "Chip", "ChipGroup", "SeekBar", "Slider", "ProgressBar", "CircularProgressIndicator",
            "LinearProgressIndicator", "Spinner", "ListView", "GridView", "WebView", "VideoView",
            "SurfaceView", "TextureView", "Toolbar", "MaterialToolbar", "TabLayout", "TabItem",
            "BottomNavigationView", "NavigationView", "SearchView", "RatingBar", "Chronometer",
            "CalendarView", "DatePicker", "TimePicker", "View", "Space",
            // Manifest Tags
            "manifest", "application", "activity", "activity-alias", "service", "receiver",
            "provider", "intent-filter", "action", "category", "data", "uses-permission",
            "uses-permission-sdk-23", "permission", "uses-sdk", "uses-feature", "uses-library",
            "meta-data", "queries", "package", "intent", "supports-screens",
            // Resource & Drawable Tags
            "resources", "string", "string-array", "plurals", "color", "dimen", "style", "item",
            "bool", "integer", "integer-array", "array", "declare-styleable", "attr", "selector",
            "layer-list", "level-list", "transition", "ripple", "shape", "solid", "stroke",
            "corners", "gradient", "padding", "size", "bitmap", "inset", "clip", "scale", "rotate",
            "vector", "path", "group", "clip-path", "menu", "adaptive-icon", "background",
            "foreground", "preference", "PreferenceScreen", "paths", "files-path", "cache-path",
            "external-path"
    ));

    private static final Set<String> ANDROID_XML_ATTRS = new HashSet<>(Arrays.asList(
            "id", "layout_width", "layout_height", "layout_margin", "layout_marginTop",
            "layout_marginBottom", "layout_marginStart", "layout_marginEnd", "layout_marginLeft",
            "layout_marginRight", "layout_marginHorizontal", "layout_marginVertical", "layout_gravity",
            "layout_weight", "layout_alignParentTop", "layout_alignParentBottom",
            "layout_alignParentStart", "layout_alignParentEnd", "layout_centerInParent",
            "layout_centerHorizontal", "layout_centerVertical", "layout_below", "layout_above",
            "layout_toStartOf", "layout_toEndOf", "layout_behavior", "layout_scrollFlags",
            "orientation", "gravity", "padding", "paddingTop", "paddingBottom", "paddingStart",
            "paddingEnd", "paddingLeft", "paddingRight", "paddingHorizontal", "paddingVertical",
            "text", "textColor", "textSize", "textStyle", "textAlignment", "fontFamily", "hint",
            "textColorHint", "maxLines", "minLines", "singleLine", "ellipsize", "inputType",
            "imeOptions", "background", "backgroundTint", "foreground", "src", "srcCompat", "tint",
            "scaleType", "contentDescription", "visibility", "alpha", "elevation", "clickable",
            "focusable", "enabled", "checked", "style", "theme", "name", "package", "exported",
            "label", "icon", "roundIcon", "allowBackup", "supportsRtl", "minSdkVersion",
            "targetSdkVersion", "compileSdkVersion", "versionCode", "versionName", "parent",
            "value", "resource", "context", "showIn", "layout", "android", "app", "tools"
    ));

    private static final Pattern SD_KEYWORDS = Pattern.compile(
            "\\b(compileSdk|minSdk|targetSdk|versionCode|versionName|namespace|applicationId|dependencies|implementation|api|compileOnly|android)\\b");

    private static final List<SuggestionItem> JAVA_SUGGESTIONS = Arrays.asList(
            new SuggestionItem("CLS", "AppCompatActivity", "AppCompatActivity", 0),
            new SuggestionItem("CLS", "Activity", "Activity", 0),
            new SuggestionItem("CLS", "Intent", "Intent", 0),
            new SuggestionItem("CLS", "Toast.makeText(...)", "Toast.makeText(this, \"\", Toast.LENGTH_SHORT).show();", 27),
            new SuggestionItem("CLS", "Snackbar.make(...)", "Snackbar.make(view, \"\", Snackbar.LENGTH_SHORT).show();", 29),
            new SuggestionItem("CLS", "Log.d(...)", "Log.d(\"Sandrix\", \"\");", 3),
            new SuggestionItem("CLS", "View", "View", 0),
            new SuggestionItem("CLS", "TextView", "TextView", 0),
            new SuggestionItem("CLS", "Button", "Button", 0),
            new SuggestionItem("CLS", "MaterialButton", "MaterialButton", 0),
            new SuggestionItem("CLS", "EditText", "EditText", 0),
            new SuggestionItem("CLS", "ImageView", "ImageView", 0),
            new SuggestionItem("CLS", "LinearLayout", "LinearLayout", 0),
            new SuggestionItem("CLS", "RecyclerView", "RecyclerView", 0),
            new SuggestionItem("CLS", "AlertDialog.Builder", "new AlertDialog.Builder(this)\n    .setTitle(\"\")\n    .setMessage(\"\")\n    .setPositiveButton(\"OK\", null)\n    .show();", 65),
            new SuggestionItem("CLS", "SharedPreferences", "SharedPreferences", 0),
            new SuggestionItem("CLS", "Bundle", "Bundle", 0),
            new SuggestionItem("CLS", "ArrayList<>", "ArrayList<>()", 3),
            new SuggestionItem("CLS", "HashMap<>", "HashMap<>()", 3),
            new SuggestionItem("MTH", "findViewById(R.id.)", "findViewById(R.id.)", 0),
            new SuggestionItem("MTH", "setContentView(R.layout.)", "setContentView(R.layout.);", 2),
            new SuggestionItem("MTH", "startActivity(new Intent(...))", "startActivity(new Intent(this, MainActivity.class));", 0),
            new SuggestionItem("MTH", "getString(R.string.)", "getString(R.string.)", 0),
            new SuggestionItem("MTH", "getColor(R.color.)", "getColor(R.color.)", 0),
            new SuggestionItem("MTH", "onCreate(Bundle)", "@Override\nprotected void onCreate(Bundle savedInstanceState) {\n    super.onCreate(savedInstanceState);\n}", 2),
            new SuggestionItem("MTH", "setOnClickListener", "setOnClickListener(v -> {\n    \n});", 4),
            new SuggestionItem("MTH", "runOnUiThread", "runOnUiThread(() -> {\n    \n});", 4),
            new SuggestionItem("KW", "public class", "public class ", 0),
            new SuggestionItem("KW", "public record (Java 17)", "public record () {\n}", 6),
            new SuggestionItem("KW", "sealed interface (Java 17)", "public sealed interface  permits  {\n}", 12),
            new SuggestionItem("KW", "switch expression (Java 17)", "var result = switch () {\n    case 0 -> \"Zero\";\n    default -> \"Other\";\n};", 54),
            new SuggestionItem("KW", "private", "private ", 0),
            new SuggestionItem("KW", "protected", "protected ", 0),
            new SuggestionItem("KW", "return", "return ", 0),
            new SuggestionItem("KW", "if (...) { ... }", "if () {\n    \n}", 8),
            new SuggestionItem("KW", "for (int i = 0; ...)", "for (int i = 0; i < ; i++) {\n    \n}", 12),
            new SuggestionItem("KW", "try { ... } catch", "try {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}", 42),
            new SuggestionItem("KW", "import android.os.Bundle;", "import android.os.Bundle;", 0),
            new SuggestionItem("KW", "import android.widget.Toast;", "import android.widget.Toast;", 0),
            new SuggestionItem("KW", "import android.content.Intent;", "import android.content.Intent;", 0),
            new SuggestionItem("KW", "import android.view.View;", "import android.view.View;", 0)
    );

    private static final List<SuggestionItem> XML_SUGGESTIONS = Arrays.asList(
            new SuggestionItem("ATTR", "android:layout_width", "android:layout_width=\"match_parent\"", 1),
            new SuggestionItem("ATTR", "android:layout_height", "android:layout_height=\"wrap_content\"", 1),
            new SuggestionItem("ATTR", "android:id", "android:id=\"@+id/\"", 1),
            new SuggestionItem("ATTR", "android:orientation", "android:orientation=\"vertical\"", 1),
            new SuggestionItem("ATTR", "android:text", "android:text=\"\"", 1),
            new SuggestionItem("ATTR", "android:textSize", "android:textSize=\"16sp\"", 1),
            new SuggestionItem("ATTR", "android:textColor", "android:textColor=\"#FFFFFF\"", 1),
            new SuggestionItem("ATTR", "android:textStyle", "android:textStyle=\"bold\"", 1),
            new SuggestionItem("ATTR", "android:background", "android:background=\"#161B22\"", 1),
            new SuggestionItem("ATTR", "android:padding", "android:padding=\"16dp\"", 1),
            new SuggestionItem("ATTR", "android:layout_margin", "android:layout_margin=\"8dp\"", 1),
            new SuggestionItem("ATTR", "android:layout_marginTop", "android:layout_marginTop=\"8dp\"", 1),
            new SuggestionItem("ATTR", "android:layout_weight", "android:layout_weight=\"1\"", 1),
            new SuggestionItem("ATTR", "android:gravity", "android:gravity=\"center\"", 1),
            new SuggestionItem("ATTR", "android:layout_gravity", "android:layout_gravity=\"center\"", 1),
            new SuggestionItem("ATTR", "android:hint", "android:hint=\"\"", 1),
            new SuggestionItem("ATTR", "android:visibility", "android:visibility=\"visible\"", 1),
            new SuggestionItem("ATTR", "android:src", "android:src=\"@drawable/\"", 1),
            new SuggestionItem("ATTR", "android:contentDescription", "android:contentDescription=\"\"", 1),
            new SuggestionItem("ATTR", "app:cornerRadius", "app:cornerRadius=\"12dp\"", 1),
            new SuggestionItem("ATTR", "app:tint", "app:tint=\"@color/\"", 1),
            new SuggestionItem("VAL", "match_parent", "match_parent", 0),
            new SuggestionItem("VAL", "wrap_content", "wrap_content", 0),
            new SuggestionItem("VAL", "@+id/", "@+id/", 0),
            new SuggestionItem("VAL", "@string/", "@string/", 0),
            new SuggestionItem("VAL", "@color/", "@color/", 0),
            new SuggestionItem("VAL", "@drawable/", "@drawable/", 0),
            new SuggestionItem("TAG", "<LinearLayout>", "<LinearLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:orientation=\"vertical\">\n\n</LinearLayout>", 17),
            new SuggestionItem("TAG", "<FrameLayout>", "<FrameLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\">\n\n</FrameLayout>", 16),
            new SuggestionItem("TAG", "<ConstraintLayout>", "<androidx.constraintlayout.widget.ConstraintLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n\n</androidx.constraintlayout.widget.ConstraintLayout>", 51),
            new SuggestionItem("TAG", "<TextView />", "<TextView\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Hello Sandrix\" />", 0),
            new SuggestionItem("TAG", "<Button />", "<Button\n    android:id=\"@+id/btnAction\"\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Click Me\" />", 0),
            new SuggestionItem("TAG", "<MaterialButton />", "<com.google.android.material.button.MaterialButton\n    android:id=\"@+id/btnMaterial\"\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Action\" />", 0),
            new SuggestionItem("TAG", "<EditText />", "<EditText\n    android:id=\"@+id/inputField\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:hint=\"Enter text\" />", 0),
            new SuggestionItem("TAG", "<ImageView />", "<ImageView\n    android:id=\"@+id/imgPreview\"\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:contentDescription=\"Image\" />", 0),
            new SuggestionItem("TAG", "<RecyclerView />", "<androidx.recyclerview.widget.RecyclerView\n    android:id=\"@+id/recyclerView\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\" />", 0),
            new SuggestionItem("TAG", "<ScrollView>", "<ScrollView\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n\n</ScrollView>", 15),
            new SuggestionItem("TAG", "<shape>", "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:shape=\"rectangle\">\n    <solid android:color=\"#1E293B\" />\n    <corners android:radius=\"12dp\" />\n</shape>", 0),
            new SuggestionItem("TAG", "<string>", "<string name=\"\"></string>", 11),
            new SuggestionItem("TAG", "<color>", "<color name=\"\">#38BDF8</color>", 18)
    );

    private static final List<SuggestionItem> SD_SUGGESTIONS = Arrays.asList(
            new SuggestionItem("SD", "compileSdk = 36", "compileSdk = 36", 0),
            new SuggestionItem("SD", "minSdk = 24", "minSdk = 24", 0),
            new SuggestionItem("SD", "targetSdk = 34", "targetSdk = 34", 0),
            new SuggestionItem("SD", "javaVersion = 17", "javaVersion = 17", 0),
            new SuggestionItem("SD", "versionCode = 1", "versionCode = 1", 0),
            new SuggestionItem("SD", "versionName = \"1.0\"", "versionName = \"1.0\"", 0),
            new SuggestionItem("SD", "namespace = \"com.sandrix.app\"", "namespace = \"com.sandrix.app\"", 1),
            new SuggestionItem("SD", "applicationId = \"com.sandrix.app\"", "applicationId = \"com.sandrix.app\"", 1),
            new SuggestionItem("SD", "minifyEnabled = true", "minifyEnabled = true", 0),
            new SuggestionItem("SD", "shrinkResources = true", "shrinkResources = true", 0),
            new SuggestionItem("SD", "proguardFiles(...)", "proguardFiles(\"proguard-rules.pro\")", 0),
            new SuggestionItem("SD", "repositories { ... }", "repositories {\n    google()\n    mavenCentral()\n    jitpack()\n}", 2),
            new SuggestionItem("SD", "dependencies { ... }", "dependencies {\n    implementation(\"androidx.appcompat:appcompat:1.6.1\")\n}", 2),
            new SuggestionItem("SD", "implementation(...)", "implementation(\"\")", 2),
            new SuggestionItem("SD", "compileOnly(...)", "compileOnly(\"\")", 2),
            new SuggestionItem("SD", "implementation files(...)", "implementation files(\"libs/\")", 2)
    );

    private ScrollView verticalScroll;
    private HorizontalScrollView horizontalScroll;
    private LineNumberGutter gutterView;
    private EditText codeEditText;
    private LinearLayout suggestionOverlay;
    private TextView suggestionHeaderTitle;
    private TextView suggestionDismissButton;
    private ListView suggestionListView;
    private SuggestionAdapter suggestionAdapter;
    private final List<SuggestionItem> dynamicSuggestions = new ArrayList<>();

    private Language language = Language.JAVA;
    private boolean darkMode = true;
    private boolean isProgrammaticChange = false;
    private boolean suppressAutocompleteOnce = false;
    private final Set<Integer> errorLines = new HashSet<>();
    private final Set<Integer> warningLines = new HashSet<>();
    private final Set<Integer> breakpointLines = new HashSet<>();
    private final Set<Integer> conditionalBreakpointLines = new HashSet<>();
    private int activeErrorHighlightLine = -1;
    private int activeDebugLine = -1;
    private int currentTokenStart = 0;

    private final Handler highlightHandler = new Handler(Looper.getMainLooper());
    private final Runnable highlightRunnable = this::applySyntaxHighlighting;

    @Nullable
    private OnCodeChangedListener onCodeChangedListener;
    @Nullable
    private ContextCompletionProvider contextCompletionProvider;
    @Nullable
    private OnBreakpointToggleListener onBreakpointToggleListener;

    public CodeEditorView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public CodeEditorView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CodeEditorView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        Typeface mono = loadMonoTypeface(context);

        verticalScroll = new ScrollView(context);
        verticalScroll.setFillViewport(true);
        verticalScroll.setVerticalScrollBarEnabled(true);

        LinearLayout editorRow = new LinearLayout(context);
        editorRow.setOrientation(LinearLayout.HORIZONTAL);
        editorRow.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        gutterView = new LineNumberGutter(context, mono);
        LinearLayout.LayoutParams gutterParams = new LinearLayout.LayoutParams(
                dp(46), ViewGroup.LayoutParams.MATCH_PARENT);
        gutterView.setLayoutParams(gutterParams);

        horizontalScroll = new HorizontalScrollView(context);
        horizontalScroll.setFillViewport(true);
        horizontalScroll.setHorizontalScrollBarEnabled(true);
        LinearLayout.LayoutParams hParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        horizontalScroll.setLayoutParams(hParams);

        codeEditText = new EditText(context);
        codeEditText.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        codeEditText.setMinWidth(dp(900));
        codeEditText.setBackground(null);
        codeEditText.setTypeface(mono);
        codeEditText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        codeEditText.setGravity(Gravity.TOP | Gravity.START);
        codeEditText.setPadding(dp(12), dp(12), dp(24), dp(120));
        codeEditText.setHorizontallyScrolling(true);
        codeEditText.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeEditText.setLineSpacing(dp(4), 1.0f);
        codeEditText.setFilters(new InputFilter[]{new IndentTabFilter()});

        horizontalScroll.addView(codeEditText);
        editorRow.addView(gutterView);
        editorRow.addView(horizontalScroll);
        verticalScroll.addView(editorRow);
        addView(verticalScroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating IntelliSense Auto-Completion Overlay
        suggestionOverlay = new LinearLayout(context);
        suggestionOverlay.setOrientation(LinearLayout.VERTICAL);
        suggestionOverlay.setVisibility(GONE);
        suggestionOverlay.setElevation(dp(8));

        LinearLayout overlayHeaderRow = new LinearLayout(context);
        overlayHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        overlayHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        overlayHeaderRow.setPadding(dp(10), dp(4), dp(4), dp(2));

        suggestionHeaderTitle = new TextView(context);
        suggestionHeaderTitle.setText("IntelliSense");
        suggestionHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        suggestionHeaderTitle.setTypeface(mono, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        overlayHeaderRow.addView(suggestionHeaderTitle, titleLp);

        suggestionDismissButton = new TextView(context);
        suggestionDismissButton.setText("×");
        suggestionDismissButton.setContentDescription("Dismiss suggestions");
        suggestionDismissButton.setGravity(Gravity.CENTER);
        suggestionDismissButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        suggestionDismissButton.setTypeface(mono, Typeface.BOLD);
        suggestionDismissButton.setMinWidth(dp(36));
        suggestionDismissButton.setMinHeight(dp(32));
        suggestionDismissButton.setOnClickListener(v -> hideSuggestions());
        overlayHeaderRow.addView(suggestionDismissButton);

        suggestionOverlay.addView(overlayHeaderRow);

        suggestionListView = new ListView(context);
        suggestionListView.setDividerHeight(1);
        suggestionAdapter = new SuggestionAdapter(context, mono);
        suggestionListView.setAdapter(suggestionAdapter);
        suggestionListView.setOnItemClickListener(this::onSuggestionClicked);
        suggestionOverlay.addView(suggestionListView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(176)));

        LayoutParams overlayParams = new LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        overlayParams.gravity = Gravity.BOTTOM | Gravity.END;
        overlayParams.setMargins(dp(12), dp(12), dp(12), dp(12));
        addView(suggestionOverlay, overlayParams);

        codeEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                gutterView.invalidate();
                if (!isProgrammaticChange) {
                    if (onCodeChangedListener != null) {
                        onCodeChangedListener.onCodeChanged(s.toString());
                    }
                    scheduleHighlight();
                    if (suppressAutocompleteOnce) {
                        suppressAutocompleteOnce = false;
                        hideSuggestions();
                    } else {
                        updateSuggestions(false);
                    }
                }
            }
        });

        applyThemeColors();
    }

    public void setDarkMode(boolean darkMode) {
        if (this.darkMode != darkMode) {
            this.darkMode = darkMode;
            applyThemeColors();
            applySyntaxHighlighting();
        }
    }

    public void setLanguage(Language language) {
        if (this.language != language) {
            this.language = language;
            applySyntaxHighlighting();
            hideSuggestions();
        }
    }

    public void setCode(String code) {
        String safe = code == null ? "" : code;
        if (safe.equals(codeEditText.getText().toString())) {
            return;
        }
        isProgrammaticChange = true;
        codeEditText.setText(safe);
        isProgrammaticChange = false;
        hideSuggestions();
        applySyntaxHighlighting();
        gutterView.invalidate();
    }

    public String getCode() {
        return codeEditText.getText() != null ? codeEditText.getText().toString() : "";
    }

    public int getCursorOffset() {
        return Math.max(0, codeEditText.getSelectionStart());
    }

    public void setOnCodeChangedListener(@Nullable OnCodeChangedListener listener) {
        this.onCodeChangedListener = listener;
    }

    public void setContextCompletionProvider(@Nullable ContextCompletionProvider provider) {
        this.contextCompletionProvider = provider;
    }

    public void setOnBreakpointToggleListener(@Nullable OnBreakpointToggleListener listener) {
        this.onBreakpointToggleListener = listener;
    }

    public void setErrorLines(@Nullable Set<Integer> lines) {
        Set<Integer> normalized = lines == null ? Collections.emptySet() : lines;
        if (errorLines.equals(normalized)) {
            return;
        }
        errorLines.clear();
        errorLines.addAll(normalized);
        applySyntaxHighlighting();
        gutterView.invalidate();
    }

    public void setWarningLines(@Nullable Set<Integer> lines) {
        Set<Integer> normalized = lines == null ? Collections.emptySet() : lines;
        if (warningLines.equals(normalized)) {
            return;
        }
        warningLines.clear();
        warningLines.addAll(normalized);
        applySyntaxHighlighting();
        gutterView.invalidate();
    }

    public void setBreakpointLines(@Nullable Set<Integer> lines) {
        setBreakpointLines(lines, Collections.emptySet());
    }

    public void setBreakpointLines(@Nullable Set<Integer> lines, @Nullable Set<Integer> conditionalLines) {
        Set<Integer> normalized = lines == null ? Collections.emptySet() : lines;
        Set<Integer> normalizedCond = conditionalLines == null ? Collections.emptySet() : conditionalLines;
        if (breakpointLines.equals(normalized) && conditionalBreakpointLines.equals(normalizedCond)) {
            return;
        }
        breakpointLines.clear();
        breakpointLines.addAll(normalized);
        conditionalBreakpointLines.clear();
        conditionalBreakpointLines.addAll(normalizedCond);
        gutterView.invalidate();
    }

    public void setActiveDebugLine(int lineNumber) {
        int normalized = lineNumber > 0 ? lineNumber : -1;
        if (this.activeDebugLine == normalized) {
            return;
        }
        this.activeDebugLine = normalized;
        applySyntaxHighlighting();
        gutterView.invalidate();
        if (normalized > 0) {
            scrollToLine(normalized);
        }
    }

    public void scrollToLine(int lineNumber) {
        int line = Math.max(1, lineNumber);
        codeEditText.post(() -> {
            Layout layout = codeEditText.getLayout();
            if (layout != null && layout.getLineCount() > 0 && codeEditText.getText() != null) {
                int offset = findLineStartOffset(codeEditText.getText(), line);
                int visualLine = layout.getLineForOffset(Math.min(offset, codeEditText.getText().length()));
                int y = Math.max(0, layout.getLineTop(visualLine) - dp(48));
                verticalScroll.smoothScrollTo(0, y);
            }
        });
    }

    private static int findLineStartOffset(CharSequence text, int targetLine) {
        int current = 1;
        for (int i = 0; i < text.length(); i++) {
            if (current == targetLine) return i;
            if (text.charAt(i) == '\n') current++;
        }
        return Math.max(0, text.length() - 1);
    }

    @Nullable
    public Spanned getHighlightedText() {
        return codeEditText.getText();
    }

    public void jumpToLine(int lineNumber) {
        int line = Math.max(1, lineNumber);
        activeErrorHighlightLine = line;
        errorLines.add(line);
        applySyntaxHighlighting();
        gutterView.invalidate();

        codeEditText.post(() -> {
            Layout layout = codeEditText.getLayout();
            if (layout != null && layout.getLineCount() > 0) {
                int targetLineIdx = Math.min(line - 1, layout.getLineCount() - 1);
                int offset = layout.getLineStart(targetLineIdx);
                codeEditText.requestFocus();
                codeEditText.setSelection(Math.min(offset, codeEditText.getText().length()));
                int y = Math.max(0, layout.getLineTop(targetLineIdx) - dp(40));
                verticalScroll.smoothScrollTo(0, y);
            }
        });
    }

    public void insertTextAtCursor(String snippet) {
        if (snippet == null) return;
        Editable editable = codeEditText.getText();
        if (editable == null) return;
        int start = Math.max(0, codeEditText.getSelectionStart());
        int end = Math.max(0, codeEditText.getSelectionEnd());
        suppressAutocompleteOnce = true;
        editable.replace(Math.min(start, end), Math.max(start, end), snippet);
    }

    public void hideSuggestions() {
        suggestionOverlay.setVisibility(GONE);
    }

    public void setDynamicSuggestions(@Nullable List<SuggestionItem> suggestions) {
        dynamicSuggestions.clear();
        if (suggestions != null) {
            dynamicSuggestions.addAll(suggestions);
        }
    }

    public boolean acceptTopSuggestionOrTrigger() {
        if (suggestionOverlay.getVisibility() == VISIBLE && suggestionAdapter.getCount() > 0) {
            SuggestionItem top = suggestionAdapter.getItem(0);
            if (top != null) {
                applySuggestionItem(top);
                return true;
            }
        }
        updateSuggestions(true);
        return suggestionOverlay.getVisibility() == VISIBLE;
    }

    private void applyThemeColors() {
        setBackgroundColor(darkMode ? DARK_BG : LIGHT_BG);
        codeEditText.setTextColor(darkMode ? DARK_TEXT : LIGHT_TEXT);
        gutterView.setBackgroundColor(darkMode ? DARK_GUTTER_BG : LIGHT_GUTTER_BG);
        gutterView.invalidate();

        GradientDrawable overlayBg = new GradientDrawable();
        overlayBg.setColor(darkMode ? 0xFF161B22 : 0xFFFFFFFF);
        overlayBg.setCornerRadius(dp(10));
        overlayBg.setStroke(dp(1), darkMode ? 0xFF38BDF8 : 0xFF0284C7);
        suggestionOverlay.setBackground(overlayBg);
        suggestionHeaderTitle.setTextColor(darkMode ? 0xFF38BDF8 : 0xFF0284C7);
        suggestionDismissButton.setTextColor(darkMode ? 0xFF8B949E : 0xFF64748B);
        if (suggestionAdapter != null) {
            suggestionAdapter.notifyDataSetChanged();
        }
    }

    private void scheduleHighlight() {
        highlightHandler.removeCallbacks(highlightRunnable);
        int docLen = codeEditText.length();
        highlightHandler.postDelayed(highlightRunnable,
                docLen > 60_000 ? 320L : (docLen > 15_000 ? 160L : 90L));
    }

    private void applySyntaxHighlighting() {
        Editable editable = codeEditText.getText();
        if (editable == null) return;
        String text = editable.toString();

        // Clear existing non-composing highlight spans
        ForegroundColorSpan[] fgSpans = editable.getSpans(0, editable.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : fgSpans) {
            if ((editable.getSpanFlags(span) & Spanned.SPAN_COMPOSING) == 0) {
                editable.removeSpan(span);
            }
        }
        StyleSpan[] styleSpans = editable.getSpans(0, editable.length(), StyleSpan.class);
        for (StyleSpan span : styleSpans) {
            if ((editable.getSpanFlags(span) & Spanned.SPAN_COMPOSING) == 0) {
                editable.removeSpan(span);
            }
        }
        BackgroundColorSpan[] bgSpans = editable.getSpans(0, editable.length(), BackgroundColorSpan.class);
        for (BackgroundColorSpan span : bgSpans) {
            if ((editable.getSpanFlags(span) & Spanned.SPAN_COMPOSING) == 0) {
                editable.removeSpan(span);
            }
        }
        UnderlineSpan[] ulSpans = editable.getSpans(0, editable.length(), UnderlineSpan.class);
        for (UnderlineSpan span : ulSpans) {
            if ((editable.getSpanFlags(span) & Spanned.SPAN_COMPOSING) == 0) {
                editable.removeSpan(span);
            }
        }

        // Highlight error, debug active step line, & warning lines first
        if (!errorLines.isEmpty() || !warningLines.isEmpty() || activeErrorHighlightLine > 0 || activeDebugLine > 0) {
            int errBg = darkMode ? DARK_ERROR_BG : LIGHT_ERROR_BG;
            int warnBg = darkMode ? 0x30F59E0B : 0x26D97706;
            int dbgBg = darkMode ? 0x3D38BDF8 : 0x330284C7;
            String[] lines = text.split("\n", -1);
            int pos = 0;
            for (int i = 0; i < lines.length; i++) {
                int lineNo = i + 1;
                int lineLen = lines[i].length();
                if (lineLen > 0) {
                    if (lineNo == activeDebugLine) {
                        editable.setSpan(new BackgroundColorSpan(dbgBg),
                                pos, pos + lineLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (errorLines.contains(lineNo) || lineNo == activeErrorHighlightLine) {
                        editable.setSpan(new BackgroundColorSpan(errBg),
                                pos, pos + lineLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        editable.setSpan(new UnderlineSpan(),
                                pos, pos + lineLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (warningLines.contains(lineNo)) {
                        editable.setSpan(new BackgroundColorSpan(warnBg),
                                pos, pos + lineLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                pos += lineLen + 1;
            }
        }

        if (text.isEmpty() || language == Language.PLAIN) {
            return;
        }

        int kwColor = darkMode ? DARK_KEYWORD : LIGHT_KEYWORD;
        int typeColor = darkMode ? DARK_TYPE : LIGHT_TYPE;
        int methodColor = darkMode ? DARK_METHOD : LIGHT_METHOD;
        int androidResColor = darkMode ? DARK_ANDROID_RES : LIGHT_ANDROID_RES;
        int strColor = darkMode ? DARK_STRING : LIGHT_STRING;
        int cmtColor = darkMode ? DARK_COMMENT : LIGHT_COMMENT;
        int numColor = darkMode ? DARK_NUMBER : LIGHT_NUMBER;
        int annColor = darkMode ? DARK_ANNOTATION : LIGHT_ANNOTATION;
        int xmlTagColor = darkMode ? DARK_XML_TAG : LIGHT_XML_TAG;
        int androidXmlTagColor = darkMode ? DARK_XML_ANDROID_TAG : LIGHT_XML_ANDROID_TAG;
        int xmlAttrColor = darkMode ? DARK_XML_ATTR : LIGHT_XML_ATTR;
        int xmlNsColor = darkMode ? DARK_XML_NS : LIGHT_XML_NS;
        int xmlBracketColor = darkMode ? DARK_XML_BRACKET : LIGHT_XML_BRACKET;

        // 0 = code, 1 = string literal, 2 = comment
        byte[] tokenMask = new byte[text.length()];

        if (language == Language.JAVA) {
            applyCommentsAndStrings(editable, text, CODE_COMMENT_OR_STRING, tokenMask, strColor, cmtColor);
            colorizeUnmasked(editable, text, NUMBER_LITERAL, numColor, Typeface.NORMAL, tokenMask);
            colorizeUnmasked(editable, text, JAVA_CONSTANTS, numColor, Typeface.NORMAL, tokenMask);
            colorizeUnmasked(editable, text, JAVA_TYPES, typeColor, Typeface.NORMAL, tokenMask);
            colorizeUnmasked(editable, text, JAVA_ANDROID_R_REF, androidResColor, Typeface.BOLD, tokenMask);
            colorizeJavaMethods(editable, text, methodColor, tokenMask);
            colorizeUnmasked(editable, text, JAVA_KEYWORDS, kwColor, Typeface.BOLD, tokenMask);
            colorizeUnmasked(editable, text, JAVA_ANNOTATION, annColor, Typeface.NORMAL, tokenMask);
        } else if (language == Language.XML) {
            applyCommentsAndStrings(editable, text, XML_COMMENT_OR_STRING, tokenMask, strColor, cmtColor);
            colorizeUnmasked(editable, text, XML_DECL_OR_BRACKET, xmlBracketColor, Typeface.NORMAL, tokenMask);
            colorizeXmlTags(editable, text, xmlTagColor, androidXmlTagColor, tokenMask);
            colorizeXmlAttributes(editable, text, xmlNsColor, xmlAttrColor, tokenMask);
            colorizeInStrings(editable, text, XML_VALUE_KEYWORDS, kwColor, Typeface.BOLD, tokenMask);
            colorizeInStrings(editable, text, XML_DIMEN, numColor, Typeface.NORMAL, tokenMask);
            colorizeInStrings(editable, text, XML_HEX_COLOR, numColor, Typeface.NORMAL, tokenMask);
            colorizeInStrings(editable, text, XML_RESOURCE_REF, androidResColor, Typeface.BOLD, tokenMask);
            colorizeOutsideComments(editable, text, XML_ENTITY, annColor, Typeface.NORMAL, tokenMask);
        } else if (language == Language.SANDRIX_SD) {
            applyCommentsAndStrings(editable, text, CODE_COMMENT_OR_STRING, tokenMask, strColor, cmtColor);
            colorizeUnmasked(editable, text, NUMBER_LITERAL, numColor, Typeface.NORMAL, tokenMask);
            colorizeUnmasked(editable, text, SD_KEYWORDS, kwColor, Typeface.BOLD, tokenMask);
        }
    }

    private static void applyCommentsAndStrings(
            Editable editable,
            String text,
            Pattern lexerPattern,
            byte[] tokenMask,
            int strColor,
            int cmtColor
    ) {
        Matcher m = lexerPattern.matcher(text);
        while (m.find()) {
            int cmtStart = m.start(1);
            int cmtEnd = m.end(1);
            if (cmtStart >= 0 && cmtEnd > cmtStart && cmtEnd <= editable.length()) {
                Arrays.fill(tokenMask, cmtStart, cmtEnd, (byte) 2);
                applySpan(editable, cmtStart, cmtEnd, cmtColor, Typeface.ITALIC);
                continue;
            }
            int strStart = m.start(2);
            int strEnd = m.end(2);
            if (strStart >= 0 && strEnd > strStart && strEnd <= editable.length()) {
                Arrays.fill(tokenMask, strStart, strEnd, (byte) 1);
                applySpan(editable, strStart, strEnd, strColor, Typeface.NORMAL);
            }
        }
    }

    private static void colorizeUnmasked(
            Editable editable,
            String text,
            Pattern pattern,
            int color,
            int style,
            byte[] tokenMask
    ) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (isMaskMatch(tokenMask, start, end, (byte) 0)) {
                applySpan(editable, start, end, color, style);
            }
        }
    }

    private static void colorizeInStrings(
            Editable editable,
            String text,
            Pattern pattern,
            int color,
            int style,
            byte[] tokenMask
    ) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (isMaskMatch(tokenMask, start, end, (byte) 1)) {
                applySpan(editable, start, end, color, style);
            }
        }
    }

    private static void colorizeOutsideComments(
            Editable editable,
            String text,
            Pattern pattern,
            int color,
            int style,
            byte[] tokenMask
    ) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (start >= 0 && end <= tokenMask.length && start < end) {
                boolean inComment = false;
                for (int i = start; i < end; i++) {
                    if (tokenMask[i] == 2) {
                        inComment = true;
                        break;
                    }
                }
                if (!inComment) {
                    applySpan(editable, start, end, color, style);
                }
            }
        }
    }

    private static void colorizeJavaMethods(
            Editable editable,
            String text,
            int methodColor,
            byte[] tokenMask
    ) {
        Matcher m = JAVA_METHOD_CALL.matcher(text);
        while (m.find()) {
            int start = m.start(1);
            int end = m.end(1);
            String name = m.group(1);
            if (name != null && !JAVA_KEYWORD_SET.contains(name)
                    && isMaskMatch(tokenMask, start, end, (byte) 0)) {
                applySpan(editable, start, end, methodColor, Typeface.NORMAL);
            }
        }
    }



    private static void colorizeXmlTags(
            Editable editable,
            String text,
            int xmlTagColor,
            int androidXmlTagColor,
            byte[] tokenMask
    ) {
        Matcher m = XML_TAG_NAME.matcher(text);
        while (m.find()) {
            int start = m.start(1);
            int end = m.end(1);
            if (isMaskMatch(tokenMask, start, end, (byte) 0)) {
                String tagName = m.group(1);
                int color = isAndroidXmlTag(tagName) ? androidXmlTagColor : xmlTagColor;
                applySpan(editable, start, end, color, Typeface.BOLD);
            }
        }
    }

    private static void colorizeXmlAttributes(
            Editable editable,
            String text,
            int xmlNsColor,
            int xmlAttrColor,
            byte[] tokenMask
    ) {
        Matcher prefixed = XML_ATTR_PREFIXED.matcher(text);
        while (prefixed.find()) {
            int nsStart = prefixed.start(1);
            int nsEnd = prefixed.end(1);
            int attrStart = prefixed.start(2);
            int attrEnd = prefixed.end(2);
            if (isMaskMatch(tokenMask, nsStart, attrEnd, (byte) 0)) {
                applySpan(editable, nsStart, nsEnd, xmlNsColor, Typeface.NORMAL);
                String attrName = prefixed.group(2);
                int style = isAndroidXmlAttr(attrName) ? Typeface.BOLD : Typeface.NORMAL;
                applySpan(editable, attrStart, attrEnd, xmlAttrColor, style);
            }
        }

        Matcher unprefixed = XML_ATTR_UNPREFIXED.matcher(text);
        while (unprefixed.find()) {
            int attrStart = unprefixed.start(1);
            int attrEnd = unprefixed.end(1);
            if (isMaskMatch(tokenMask, attrStart, attrEnd, (byte) 0)) {
                String attrName = unprefixed.group(1);
                int style = isAndroidXmlAttr(attrName) ? Typeface.BOLD : Typeface.NORMAL;
                applySpan(editable, attrStart, attrEnd, xmlAttrColor, style);
            }
        }
    }

    private static boolean isAndroidXmlTag(@Nullable String tagName) {
        if (tagName == null || tagName.isEmpty()) return false;
        if (ANDROID_XML_TAGS.contains(tagName)) return true;
        int lastDot = tagName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < tagName.length() - 1) {
            String simple = tagName.substring(lastDot + 1);
            return ANDROID_XML_TAGS.contains(simple)
                    || tagName.startsWith("android.")
                    || tagName.startsWith("androidx.")
                    || tagName.startsWith("com.google.android.material.");
        }
        return false;
    }

    private static boolean isAndroidXmlAttr(@Nullable String attrName) {
        if (attrName == null || attrName.isEmpty()) return false;
        return ANDROID_XML_ATTRS.contains(attrName) || attrName.startsWith("layout_constraint");
    }

    private static boolean isMaskMatch(byte[] tokenMask, int start, int end, byte expectedMask) {
        if (start < 0 || end > tokenMask.length || start >= end) {
            return false;
        }
        for (int i = start; i < end; i++) {
            if (tokenMask[i] != expectedMask) {
                return false;
            }
        }
        return true;
    }

    private static void applySpan(Editable editable, int start, int end, int color, int style) {
        if (start >= 0 && end <= editable.length() && start < end) {
            editable.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (style != Typeface.NORMAL) {
                editable.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private void updateSuggestions(boolean manualTrigger) {
        Editable editable = codeEditText.getText();
        if (editable == null) {
            hideSuggestions();
            return;
        }
        int cursor = codeEditText.getSelectionStart();
        if (cursor < 0 || cursor > editable.length()) {
            hideSuggestions();
            return;
        }

        String text = editable.toString();
        int tokenStart = cursor;
        while (tokenStart > 0) {
            char c = text.charAt(tokenStart - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '<'
                    || c == '.' || c == '@' || c == '/' || c == '+') {
                tokenStart--;
            } else {
                break;
            }
        }

        String prefix = text.substring(tokenStart, cursor);
        List<SuggestionItem> contextItems = contextCompletionProvider != null
                ? contextCompletionProvider.provide(language, text, cursor, manualTrigger)
                : Collections.emptyList();

        boolean smartTrigger = false;
        if (language == Language.XML) {
            smartTrigger = prefix.startsWith("<") || prefix.startsWith("@")
                    || (contextItems != null && !contextItems.isEmpty()
                    && cursor > 0 && (text.charAt(cursor - 1) == '"' || !prefix.isEmpty()));
        } else if (language == Language.JAVA) {
            smartTrigger = prefix.contains(".") || prefix.endsWith(".");
        }
        if (!manualTrigger && !smartTrigger && prefix.length() < 2) {
            hideSuggestions();
            return;
        }

        currentTokenStart = tokenStart;
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        int dotIdx = prefix.lastIndexOf('.');
        String memberFilter = (language == Language.JAVA && dotIdx >= 0)
                ? prefix.substring(dotIdx + 1).toLowerCase(Locale.ROOT)
                : lowerPrefix;

        List<SuggestionItem> basePool;
        if (language == Language.JAVA) {
            basePool = JAVA_SUGGESTIONS;
        } else if (language == Language.XML) {
            basePool = XML_SUGGESTIONS;
        } else if (language == Language.SANDRIX_SD) {
            basePool = SD_SUGGESTIONS;
        } else {
            basePool = Collections.emptyList();
        }

        Map<String, SuggestionItem> deduped = new LinkedHashMap<>();
        if (contextItems != null) {
            for (SuggestionItem item : contextItems) {
                deduped.put(item.kind + "|" + item.label, item);
            }
        }
        for (SuggestionItem item : dynamicSuggestions) {
            if (isDynamicItemRelevant(item)) {
                deduped.putIfAbsent(item.kind + "|" + item.label, item);
            }
        }
        if (contextItems == null || contextItems.isEmpty() || !smartTrigger) {
            for (SuggestionItem item : basePool) {
                deduped.putIfAbsent(item.kind + "|" + item.label, item);
            }
        }

        List<SuggestionItem> prefixMatches = new ArrayList<>();
        List<SuggestionItem> containsMatches = new ArrayList<>();
        for (SuggestionItem item : deduped.values()) {
            if (item.insertText.equals(prefix)) {
                continue;
            }
            if (item.tokenStartOverride >= 0) {
                prefixMatches.add(item);
                continue;
            }
            String filter = memberFilter.isEmpty() ? lowerPrefix : memberFilter;
            if (filter.isEmpty()) {
                prefixMatches.add(item);
                continue;
            }
            String lowerLabel = item.label.toLowerCase(Locale.ROOT);
            String lowerInsert = item.insertText.toLowerCase(Locale.ROOT);
            if (lowerLabel.startsWith(filter) || lowerInsert.startsWith(filter)) {
                prefixMatches.add(item);
            } else if (lowerLabel.contains(filter) || lowerInsert.contains(filter)) {
                containsMatches.add(item);
            }
        }

        Comparator<SuggestionItem> byPriorityAndName = Comparator
                .comparingInt((SuggestionItem s) -> s.tokenStartOverride >= 0 ? 0 : 1)
                .thenComparingInt(s -> s.label.length())
                .thenComparing(s -> s.label, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(prefixMatches, byPriorityAndName);
        Collections.sort(containsMatches, byPriorityAndName);

        List<SuggestionItem> matches = new ArrayList<>(Math.min(25, prefixMatches.size() + containsMatches.size()));
        for (SuggestionItem s : prefixMatches) {
            if (matches.size() >= 25) break;
            matches.add(s);
        }
        for (SuggestionItem s : containsMatches) {
            if (matches.size() >= 25) break;
            matches.add(s);
        }

        if (matches.isEmpty()) {
            hideSuggestions();
        } else {
            suggestionAdapter.setItems(matches);
            suggestionOverlay.setVisibility(VISIBLE);
        }
    }

    private boolean isDynamicItemRelevant(SuggestionItem item) {
        if (language == Language.XML) {
            return item.insertText.startsWith("@");
        }
        if (language == Language.JAVA) {
            return item.insertText.startsWith("R.");
        }
        return false;
    }

    private void onSuggestionClicked(AdapterView<?> parent, View view, int position, long id) {
        SuggestionItem item = suggestionAdapter.getItem(position);
        if (item != null) {
            applySuggestionItem(item);
        }
    }

    private void applySuggestionItem(SuggestionItem item) {
        Editable editable = codeEditText.getText();
        if (editable == null) return;

        int cursor = Math.max(0, codeEditText.getSelectionStart());
        int replaceStart = item.tokenStartOverride >= 0
                ? Math.min(cursor, Math.max(0, item.tokenStartOverride))
                : Math.min(cursor, currentTokenStart);
        suppressAutocompleteOnce = true;
        editable.replace(replaceStart, cursor, item.insertText);
        int newCursor = Math.max(0, Math.min(
                editable.length(),
                replaceStart + item.insertText.length() - item.cursorBackOffset));
        codeEditText.setSelection(newCursor);
        hideSuggestions();
    }

    private Typeface loadMonoTypeface(Context context) {
        try {
            Typeface tf = ResourcesCompat.getFont(context, R.font.jetbrains_mono);
            return tf != null ? tf : Typeface.MONOSPACE;
        } catch (Exception e) {
            return Typeface.MONOSPACE;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Auto-indents new lines using the preceding line's leading whitespace.
     */
    private static final class IndentTabFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            if (end - start == 1 && source.charAt(start) == '\n') {
                int lineStart = dstart;
                while (lineStart > 0 && dest.charAt(lineStart - 1) != '\n') {
                    lineStart--;
                }
                StringBuilder indent = new StringBuilder("\n");
                for (int i = lineStart; i < dstart; i++) {
                    char c = dest.charAt(i);
                    if (c == ' ' || c == '\t') {
                        indent.append(c);
                    } else {
                        break;
                    }
                }
                if (dstart > 0 && dest.charAt(dstart - 1) == '{') {
                    indent.append("    ");
                }
                return indent.toString();
            }
            return null;
        }
    }

    /**
     * Draws line numbers, error indicators, breakpoint dots, and active debug execution arrows
     * aligned to {@link EditText}'s text layout.
     */
    private final class LineNumberGutter extends View {
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint();
        private final Paint errorDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint warningDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint breakpointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint conditionalRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint debugArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path arrowPath = new android.graphics.Path();
        private float touchDownY = -1f;
        private long touchDownTimeMs = 0L;

        LineNumberGutter(Context context, Typeface mono) {
            super(context);
            textPaint.setTypeface(mono);
            textPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 11.5f, getResources().getDisplayMetrics()));
            textPaint.setTextAlign(Paint.Align.RIGHT);
            borderPaint.setColor(0xFF30363D);
            borderPaint.setStrokeWidth(dp(1));
            errorDotPaint.setColor(0xFFF87171);
            warningDotPaint.setColor(0xFFF59E0B);
            breakpointPaint.setColor(0xFFEF4444);
            conditionalRingPaint.setColor(0xFFFBBF24);
            conditionalRingPaint.setStyle(Paint.Style.STROKE);
            conditionalRingPaint.setStrokeWidth(dp(1) * 1.6f);
            debugArrowPaint.setColor(0xFF38BDF8);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (onBreakpointToggleListener == null) {
                return super.onTouchEvent(event);
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchDownY = event.getY();
                touchDownTimeMs = System.currentTimeMillis();
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (touchDownY >= 0 && Math.abs(event.getY() - touchDownY) < dp(12)) {
                    int tappedLine = findLogicalLineAtY(event.getY());
                    if (tappedLine > 0) {
                        if (System.currentTimeMillis() - touchDownTimeMs >= 420L) {
                            onBreakpointToggleListener.onBreakpointLongPress(tappedLine);
                        } else {
                            onBreakpointToggleListener.onToggleBreakpoint(tappedLine);
                        }
                        performClick();
                        return true;
                    }
                }
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private int findLogicalLineAtY(float y) {
            Layout layout = codeEditText.getLayout();
            if (layout == null) return -1;
            int localY = Math.round(y) - codeEditText.getPaddingTop();
            int visualLine = layout.getLineForVertical(Math.max(0, localY));
            CharSequence text = codeEditText.getText();
            if (text == null) return -1;
            int offset = Math.min(text.length(), layout.getLineStart(visualLine));
            int logical = 1;
            for (int i = 0; i < offset; i++) {
                if (text.charAt(i) == '\n') logical++;
            }
            return logical;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            textPaint.setColor(darkMode ? DARK_GUTTER_TEXT : LIGHT_GUTTER_TEXT);
            borderPaint.setColor(darkMode ? 0xFF30363D : 0xFFCBD5E1);

            float rightEdge = getWidth() - 1f;
            canvas.drawLine(rightEdge, 0f, rightEdge, getHeight(), borderPaint);

            Layout layout = codeEditText.getLayout();
            if (layout == null) {
                canvas.drawText("1", getWidth() - dp(8), codeEditText.getPaddingTop() + dp(14), textPaint);
                return;
            }

            int paddingTop = codeEditText.getPaddingTop();
            int lineCount = layout.getLineCount();
            CharSequence text = codeEditText.getText();
            int logicalLine = 1;

            for (int i = 0; i < lineCount; i++) {
                boolean isLogicalStart = (i == 0)
                        || (layout.getLineStart(i) > 0 && text.charAt(layout.getLineStart(i) - 1) == '\n');
                if (isLogicalStart) {
                    int baseline = paddingTop + layout.getLineBaseline(i);
                    boolean isDebugHere = logicalLine == activeDebugLine;
                    boolean isBp = breakpointLines.contains(logicalLine);
                    boolean isCondBp = conditionalBreakpointLines.contains(logicalLine);
                    boolean isErr = errorLines.contains(logicalLine) || logicalLine == activeErrorHighlightLine;
                    boolean isWarn = !isErr && warningLines.contains(logicalLine);

                    if (isBp) {
                        canvas.drawCircle(dp(8), baseline - dp(4), dp(4), breakpointPaint);
                        if (isCondBp) {
                            canvas.drawCircle(dp(8), baseline - dp(4), dp(5), conditionalRingPaint);
                        }
                    } else if (isErr) {
                        canvas.drawCircle(dp(8), baseline - dp(4), dp(3), errorDotPaint);
                    } else if (isWarn) {
                        canvas.drawCircle(dp(8), baseline - dp(4), dp(3) * 0.85f, warningDotPaint);
                    }

                    if (isDebugHere) {
                        float cx = dp(15);
                        float cy = baseline - dp(4);
                        float r = dp(4);
                        arrowPath.reset();
                        arrowPath.moveTo(cx - r * 0.6f, cy - r);
                        arrowPath.lineTo(cx + r, cy);
                        arrowPath.lineTo(cx - r * 0.6f, cy + r);
                        arrowPath.close();
                        canvas.drawPath(arrowPath, debugArrowPaint);
                    }

                    if (isDebugHere) {
                        textPaint.setColor(0xFF38BDF8);
                    } else if (isErr) {
                        textPaint.setColor(0xFFF87171);
                    } else if (isWarn) {
                        textPaint.setColor(0xFFF59E0B);
                    } else if (isBp) {
                        textPaint.setColor(0xFFEF4444);
                    } else {
                        textPaint.setColor(darkMode ? DARK_GUTTER_TEXT : LIGHT_GUTTER_TEXT);
                    }
                    canvas.drawText(String.valueOf(logicalLine), getWidth() - dp(8), baseline, textPaint);
                    logicalLine++;
                }
            }
        }
    }

    private final class SuggestionAdapter extends ArrayAdapter<SuggestionItem> {
        private final Typeface mono;

        SuggestionAdapter(Context context, Typeface mono) {
            super(context, 0, new ArrayList<>());
            this.mono = mono;
        }

        void setItems(List<SuggestionItem> items) {
            clear();
            addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            LinearLayout row;
            TextView badge;
            TextView label;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                badge = (TextView) row.getChildAt(0);
                label = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(7), dp(10), dp(7));

                badge = new TextView(getContext());
                badge.setTypeface(mono, Typeface.BOLD);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.setMarginEnd(dp(8));
                row.addView(badge, bp);

                label = new TextView(getContext());
                label.setTypeface(mono);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                row.addView(label);
            }

            label.setTextColor(darkMode ? 0xFFE6EDF3 : 0xFF0F172A);
            SuggestionItem item = getItem(position);
            if (item != null) {
                badge.setText(item.kind);
                int badgeColor;
                switch (item.kind) {
                    case "CLS":
                        badgeColor = 0xFF38BDF8;
                        break;
                    case "MTH":
                        badgeColor = 0xFF56D4DD;
                        break;
                    case "VAR":
                    case "FLD":
                        badgeColor = 0xFFF472B6;
                        break;
                    case "ATTR":
                    case "TAG":
                    case "VAL":
                        badgeColor = 0xFF34D399;
                        break;
                    case "RES":
                        badgeColor = 0xFF2DD4BF;
                        break;
                    case "SD":
                        badgeColor = 0xFFF59E0B;
                        break;
                    default:
                        badgeColor = 0xFFA78BFA;
                        break;
                }
                badge.setTextColor(badgeColor);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor((badgeColor & 0x00FFFFFF) | 0x22000000);
                bg.setCornerRadius(dp(4));
                badge.setBackground(bg);
                label.setText(item.label);
            }
            return row;
        }
    }
}
