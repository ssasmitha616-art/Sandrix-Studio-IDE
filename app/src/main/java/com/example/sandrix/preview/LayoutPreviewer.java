package com.example.sandrix.preview;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Dynamic XML-to-View rendering module for Sandrix Studio IDE.
 *
 * <p>Parses Android layout XML tags ({@code LinearLayout}, {@code RelativeLayout},
 * {@code FrameLayout}, {@code ScrollView}, {@code TextView}, {@code Button}, {@code EditText},
 * {@code ImageView}, {@code CheckBox}, {@code Switch}, {@code ProgressBar}, {@code RecyclerView})
 * into live interactive Android {@link View} nodes inside a simulated phone viewport.
 */
public class LayoutPreviewer extends FrameLayout {

    public interface OnNodeSelectedListener {
        void onNodeSelected(String tag, String idName);
    }

    public interface OnInspectNodeTapListener {
        void onInspectNodeTapped(InspectedNode node);
    }

    public static final class InspectedNode {
        public final int index;
        public final int depth;
        public final String tagName;
        public final String idName;
        public final int lineNumber;
        public final Map<String, String> attributes;
        @Nullable public View renderedView;
        public final List<InspectedNode> children = new ArrayList<>();

        InspectedNode(int index, int depth, String tagName, String idName, int lineNumber, Map<String, String> attributes) {
            this.index = index;
            this.depth = depth;
            this.tagName = tagName;
            this.idName = idName;
            this.lineNumber = lineNumber;
            this.attributes = attributes;
        }
    }

    private FrameLayout deviceFrame;
    private LinearLayout deviceScreen;
    private FrameLayout canvasHost;
    private View selectionOverlay;
    private TextView statusLabel;
    private LinearLayout errorBanner;
    private TextView errorTitle;
    private TextView errorDetail;

    private final Map<String, String> stringResources = new HashMap<>();
    private final Map<String, String> colorResources = new HashMap<>();
    private final List<InspectedNode> flatInspectedNodes = new ArrayList<>();
    private boolean previewLightCanvas = true;
    private boolean inspectMode = false;
    private int selectedNodeIndex = -1;

    @Nullable
    private OnNodeSelectedListener onNodeSelectedListener;
    @Nullable
    private OnInspectNodeTapListener onInspectNodeTapListener;

    public LayoutPreviewer(@NonNull Context context) {
        super(context);
        init(context);
    }

    public LayoutPreviewer(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public LayoutPreviewer(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setBackgroundColor(0xFF0A0E14);
        setPadding(dp(12), dp(12), dp(12), dp(12));

        ScrollView outerScroll = new ScrollView(context);
        outerScroll.setFillViewport(true);

        LinearLayout centerWrap = new LinearLayout(context);
        centerWrap.setOrientation(LinearLayout.VERTICAL);
        centerWrap.setGravity(Gravity.CENTER_HORIZONTAL);

        // Simulated Phone Bezel
        deviceFrame = new FrameLayout(context);
        GradientDrawable bezelBg = new GradientDrawable();
        bezelBg.setColor(0xFF161B22);
        bezelBg.setCornerRadius(dp(22));
        bezelBg.setStroke(dp(2), 0xFF30363D);
        deviceFrame.setBackground(bezelBg);
        deviceFrame.setPadding(dp(8), dp(8), dp(8), dp(8));

        deviceScreen = new LinearLayout(context);
        deviceScreen.setOrientation(LinearLayout.VERTICAL);
        applyCanvasSurfaceStyle();

        // Simulated Status + App Bar inside preview device
        LinearLayout mockStatusBar = new LinearLayout(context);
        mockStatusBar.setOrientation(LinearLayout.HORIZONTAL);
        mockStatusBar.setPadding(dp(14), dp(6), dp(14), dp(6));
        mockStatusBar.setBackgroundColor(0xFF0F172A);

        statusLabel = new TextView(context);
        statusLabel.setText("Sandrix Live Render  •  24dp+");
        statusLabel.setTextColor(0xFF94A3B8);
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        mockStatusBar.addView(statusLabel);

        deviceScreen.addView(mockStatusBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Error Banner (visible when XML is malformed)
        errorBanner = new LinearLayout(context);
        errorBanner.setOrientation(LinearLayout.VERTICAL);
        errorBanner.setBackgroundColor(0xFF450A0A);
        errorBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
        errorBanner.setVisibility(GONE);

        errorTitle = new TextView(context);
        errorTitle.setTextColor(0xFFFCA5A5);
        errorTitle.setTypeface(Typeface.DEFAULT_BOLD);
        errorTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        errorBanner.addView(errorTitle);

        errorDetail = new TextView(context);
        errorDetail.setTextColor(0xFFFECACA);
        errorDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        errorBanner.addView(errorDetail);

        deviceScreen.addView(errorBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        canvasHost = new FrameLayout(context);
        canvasHost.setMinimumHeight(dp(420));
        deviceScreen.addView(canvasHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        selectionOverlay = new View(context);
        GradientDrawable overlayBg = new GradientDrawable();
        overlayBg.setColor(0x2238BDF8);
        overlayBg.setStroke(dp(2), 0xFF38BDF8);
        selectionOverlay.setBackground(overlayBg);
        selectionOverlay.setVisibility(GONE);

        deviceFrame.addView(deviceScreen, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        centerWrap.addView(deviceFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        outerScroll.addView(centerWrap);
        addView(outerScroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setResources(@Nullable Map<String, String> strings, @Nullable Map<String, String> colors) {
        stringResources.clear();
        if (strings != null) stringResources.putAll(strings);
        colorResources.clear();
        if (colors != null) colorResources.putAll(colors);
    }

    public void setPreviewLightCanvas(boolean lightCanvas) {
        this.previewLightCanvas = lightCanvas;
        applyCanvasSurfaceStyle();
    }

    public void setInspectMode(boolean inspectMode) {
        this.inspectMode = inspectMode;
        if (!inspectMode) {
            selectionOverlay.setVisibility(GONE);
        }
    }

    public boolean isInspectMode() {
        return inspectMode;
    }

    public void setOnNodeSelectedListener(@Nullable OnNodeSelectedListener listener) {
        this.onNodeSelectedListener = listener;
    }

    public void setOnInspectNodeTapListener(@Nullable OnInspectNodeTapListener listener) {
        this.onInspectNodeTapListener = listener;
    }

    public List<InspectedNode> getInspectedNodes() {
        return java.util.Collections.unmodifiableList(flatInspectedNodes);
    }

    public void selectInspectedNode(int index) {
        this.selectedNodeIndex = index;
        updateSelectionOverlay();
    }

    private void updateSelectionOverlay() {
        if (selectedNodeIndex < 0 || selectedNodeIndex >= flatInspectedNodes.size()) {
            selectionOverlay.setVisibility(GONE);
            return;
        }
        View target = flatInspectedNodes.get(selectedNodeIndex).renderedView;
        if (target == null) {
            selectionOverlay.setVisibility(GONE);
            return;
        }
        canvasHost.post(() -> {
            if (target.getWidth() <= 0 || target.getHeight() <= 0) {
                selectionOverlay.setVisibility(GONE);
                return;
            }
            int[] hostLoc = new int[2];
            int[] targetLoc = new int[2];
            canvasHost.getLocationInWindow(hostLoc);
            target.getLocationInWindow(targetLoc);
            int left = targetLoc[0] - hostLoc[0];
            int top = targetLoc[1] - hostLoc[1];
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(target.getWidth(), target.getHeight());
            lp.leftMargin = Math.max(0, left);
            lp.topMargin = Math.max(0, top);
            selectionOverlay.setLayoutParams(lp);
            selectionOverlay.setVisibility(VISIBLE);
            selectionOverlay.bringToFront();
        });
    }

    private void applyCanvasSurfaceStyle() {
        GradientDrawable screenBg = new GradientDrawable();
        screenBg.setColor(previewLightCanvas ? 0xFFFFFFFF : 0xFF111827);
        screenBg.setCornerRadius(dp(16));
        deviceScreen.setBackground(screenBg);
        deviceScreen.setClipToOutline(true);
    }

    public void renderXml(@Nullable String xmlSource, @Nullable String layoutName) {
        canvasHost.removeAllViews();
        flatInspectedNodes.clear();
        errorBanner.setVisibility(GONE);
        statusLabel.setText((layoutName != null ? layoutName : "layout.xml") + "  •  Live Render");

        if (xmlSource == null || xmlSource.trim().isEmpty()) {
            showPlaceholder("Empty XML layout file.");
            return;
        }

        try {
            java.util.List<Integer> startLines = com.example.sandrix.xml.XmlPatcher.findStartTagLineNumbers(xmlSource);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xmlSource));

            Stack<ViewGroup> containerStack = new Stack<>();
            Stack<InspectedNode> inspectStack = new Stack<>();
            View rootView = null;
            int nodeIdx = 0;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = simplifyTag(parser.getName());
                    Map<String, String> attrs = readAttributes(parser);
                    int depth = inspectStack.size();
                    int lineNo = nodeIdx < startLines.size() ? startLines.get(nodeIdx) : Math.max(1, parser.getLineNumber());
                    String idAttr = attr(attrs, "android:id", "");

                    InspectedNode node = new InspectedNode(nodeIdx, depth, tagName, idAttr, lineNo, attrs);
                    if (!inspectStack.isEmpty()) {
                        inspectStack.peek().children.add(node);
                    }
                    flatInspectedNodes.add(node);
                    nodeIdx++;

                    ViewGroup parent = containerStack.isEmpty() ? null : containerStack.peek();
                    View created = createViewForTag(tagName, attrs, parent, node);
                    node.renderedView = created;

                    if (rootView == null) {
                        rootView = created;
                    } else if (parent != null) {
                        parent.addView(created);
                    }

                    if (created instanceof ViewGroup) {
                        containerStack.push((ViewGroup) created);
                    }
                    inspectStack.push(node);
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (!inspectStack.isEmpty()) {
                        inspectStack.pop();
                    }
                    if (!containerStack.isEmpty()) {
                        String tagName = simplifyTag(parser.getName());
                        if (matchesContainerTag(containerStack.peek(), tagName)) {
                            containerStack.pop();
                        }
                    }
                }
                eventType = parser.next();
            }

            if (rootView != null) {
                canvasHost.addView(rootView);
                canvasHost.addView(selectionOverlay);
                if (selectedNodeIndex >= 0 && selectedNodeIndex < flatInspectedNodes.size()) {
                    updateSelectionOverlay();
                } else {
                    selectionOverlay.setVisibility(GONE);
                }
            } else {
                showPlaceholder("No root layout element found.");
            }
        } catch (Exception e) {
            errorBanner.setVisibility(VISIBLE);
            errorTitle.setText("XML Render Error");
            errorDetail.setText(e.getMessage() != null ? e.getMessage() : "Unable to parse XML layout.");
            showPlaceholder("Fix XML syntax to restore Live Preview.");
        }
    }

    private View createViewForTag(
            String tag,
            Map<String, String> attrs,
            @Nullable ViewGroup parent,
            InspectedNode node) {
        Context ctx = getContext();
        View view;

        switch (tag) {
            case "LinearLayout": {
                LinearLayout ll = new LinearLayout(ctx);
                String orientation = attr(attrs, "android:orientation", "horizontal");
                ll.setOrientation("vertical".equalsIgnoreCase(orientation)
                        ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                ll.setGravity(parseGravity(attr(attrs, "android:gravity", "")));
                view = ll;
                break;
            }
            case "RelativeLayout":
            case "ConstraintLayout": {
                RelativeLayout rl = new RelativeLayout(ctx);
                view = rl;
                break;
            }
            case "FrameLayout":
            case "CoordinatorLayout":
            case "CardView":
            case "MaterialCardView": {
                FrameLayout fl = new FrameLayout(ctx);
                if (tag.contains("CardView")) {
                    GradientDrawable cardBg = new GradientDrawable();
                    cardBg.setColor(previewLightCanvas ? 0xFFF8FAFC : 0xFF1E293B);
                    cardBg.setCornerRadius(dp(12));
                    cardBg.setStroke(dp(1), 0x3394A3B8);
                    fl.setBackground(cardBg);
                    fl.setElevation(dp(3));
                }
                view = fl;
                break;
            }
            case "ScrollView": {
                view = new ScrollView(ctx);
                break;
            }
            case "HorizontalScrollView": {
                view = new HorizontalScrollView(ctx);
                break;
            }
            case "TextView": {
                TextView tv = new TextView(ctx);
                tv.setText(resolveString(attr(attrs, "android:text", "TextView")));
                tv.setTextColor(previewLightCanvas ? 0xFF0F172A : 0xFFE2E8F0);
                tv.setGravity(parseGravity(attr(attrs, "android:gravity", "")));
                applyTextAttributes(tv, attrs);
                view = tv;
                break;
            }
            case "Button":
            case "MaterialButton": {
                Button btn = new Button(ctx);
                btn.setText(resolveString(attr(attrs, "android:text", "Button")));
                applyTextAttributes(btn, attrs);
                String label = btn.getText().toString();
                btn.setOnClickListener(v ->
                        Toast.makeText(ctx, "Sandbox Click: " + label, Toast.LENGTH_SHORT).show());
                view = btn;
                break;
            }
            case "EditText":
            case "TextInputEditText": {
                EditText et = new EditText(ctx);
                et.setText(resolveString(attr(attrs, "android:text", "")));
                et.setHint(resolveString(attr(attrs, "android:hint", "Input text...")));
                et.setTextColor(previewLightCanvas ? 0xFF0F172A : 0xFFE2E8F0);
                et.setHintTextColor(0xFF94A3B8);
                et.setInputType(InputType.TYPE_CLASS_TEXT);
                applyTextAttributes(et, attrs);
                view = et;
                break;
            }
            case "CheckBox": {
                CheckBox cb = new CheckBox(ctx);
                cb.setText(resolveString(attr(attrs, "android:text", "CheckBox")));
                cb.setChecked("true".equalsIgnoreCase(attr(attrs, "android:checked", "false")));
                cb.setTextColor(previewLightCanvas ? 0xFF0F172A : 0xFFE2E8F0);
                view = cb;
                break;
            }
            case "Switch":
            case "SwitchMaterial": {
                Switch sw = new Switch(ctx);
                sw.setText(resolveString(attr(attrs, "android:text", "Switch")));
                sw.setChecked("true".equalsIgnoreCase(attr(attrs, "android:checked", "false")));
                sw.setTextColor(previewLightCanvas ? 0xFF0F172A : 0xFFE2E8F0);
                view = sw;
                break;
            }
            case "ImageView": {
                ImageView iv = new ImageView(ctx);
                iv.setImageResource(android.R.drawable.ic_menu_gallery);
                iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                view = iv;
                break;
            }
            case "ProgressBar": {
                view = new ProgressBar(ctx);
                break;
            }
            case "SeekBar": {
                SeekBar sb = new SeekBar(ctx);
                sb.setProgress(50);
                view = sb;
                break;
            }
            case "RecyclerView": {
                view = buildMockRecyclerPreview(ctx);
                break;
            }
            default: {
                // Unknown or divider <View>
                View fallback = new View(ctx);
                if (!attrs.containsKey("android:background")) {
                    fallback.setBackgroundColor(0x3394A3B8);
                }
                view = fallback;
                break;
            }
        }

        applyCommonAttributes(view, attrs, parent);

        String idAttr = attr(attrs, "android:id", "");
        if (inspectMode) {
            view.setOnClickListener(v -> {
                selectInspectedNode(node.index);
                if (onNodeSelectedListener != null) {
                    onNodeSelectedListener.onNodeSelected(tag, idAttr);
                }
                if (onInspectNodeTapListener != null) {
                    onInspectNodeTapListener.onInspectNodeTapped(node);
                }
            });
        }
        if (inspectMode || !(view instanceof Button || view instanceof EditText || view instanceof CheckBox || view instanceof Switch)) {
            view.setOnLongClickListener(v -> {
                selectInspectedNode(node.index);
                String info = "<" + tag + "> " + (idAttr.isEmpty() ? "(no id)" : idAttr);
                Toast.makeText(ctx, info, Toast.LENGTH_SHORT).show();
                if (onNodeSelectedListener != null) {
                    onNodeSelectedListener.onNodeSelected(tag, idAttr);
                }
                if (onInspectNodeTapListener != null) {
                    onInspectNodeTapListener.onInspectNodeTapped(node);
                }
                return true;
            });
        }
        return view;
    }

    private View buildMockRecyclerPreview(Context ctx) {
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 1; i <= 3; i++) {
            TextView row = new TextView(ctx);
            row.setText("RecyclerView Item #" + i);
            row.setTextColor(previewLightCanvas ? 0xFF334155 : 0xFFCBD5E1);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(previewLightCanvas ? 0xFFF1F5F9 : 0xFF1E293B);
            rowBg.setCornerRadius(dp(8));
            row.setBackground(rowBg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(6));
            list.addView(row, lp);
        }
        return list;
    }

    private void applyTextAttributes(TextView tv, Map<String, String> attrs) {
        String textSize = attr(attrs, "android:textSize", "");
        if (!textSize.isEmpty()) {
            float sp = parseDimenSp(textSize, 14f);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
        String textColor = attr(attrs, "android:textColor", "");
        if (!textColor.isEmpty()) {
            Integer resolved = resolveColor(textColor);
            if (resolved != null) tv.setTextColor(resolved);
        }
        String style = attr(attrs, "android:textStyle", "");
        if ("bold".equalsIgnoreCase(style)) {
            tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        } else if ("italic".equalsIgnoreCase(style)) {
            tv.setTypeface(tv.getTypeface(), Typeface.ITALIC);
        } else if (style.contains("bold") && style.contains("italic")) {
            tv.setTypeface(tv.getTypeface(), Typeface.BOLD_ITALIC);
        }
    }

    private void applyCommonAttributes(View view, Map<String, String> attrs, @Nullable ViewGroup parent) {
        int width = parseLayoutSize(attr(attrs, "android:layout_width", "wrap_content"));
        int height = parseLayoutSize(attr(attrs, "android:layout_height", "wrap_content"));

        ViewGroup.MarginLayoutParams lp;
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(width, height);
            String weight = attr(attrs, "android:layout_weight", "");
            if (!weight.isEmpty()) {
                try {
                    llp.weight = Float.parseFloat(weight);
                } catch (NumberFormatException ignored) {}
            }
            String lg = attr(attrs, "android:layout_gravity", "");
            if (!lg.isEmpty()) {
                llp.gravity = parseGravity(lg);
            }
            lp = llp;
        } else if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(width, height);
            String lg = attr(attrs, "android:layout_gravity", "");
            if (!lg.isEmpty()) {
                flp.gravity = parseGravity(lg);
            }
            lp = flp;
        } else {
            lp = new ViewGroup.MarginLayoutParams(width, height);
        }

        int mAll = parseDimenPx(attr(attrs, "android:layout_margin", ""), 0);
        int mTop = parseDimenPx(attr(attrs, "android:layout_marginTop", ""), mAll);
        int mBottom = parseDimenPx(attr(attrs, "android:layout_marginBottom", ""), mAll);
        int mLeft = parseDimenPx(attr(attrs, "android:layout_marginStart", attr(attrs, "android:layout_marginLeft", "")), mAll);
        int mRight = parseDimenPx(attr(attrs, "android:layout_marginEnd", attr(attrs, "android:layout_marginRight", "")), mAll);
        lp.setMargins(mLeft, mTop, mRight, mBottom);
        view.setLayoutParams(lp);

        int pAll = parseDimenPx(attr(attrs, "android:padding", ""), 0);
        int pTop = parseDimenPx(attr(attrs, "android:paddingTop", ""), pAll);
        int pBottom = parseDimenPx(attr(attrs, "android:paddingBottom", ""), pAll);
        int pLeft = parseDimenPx(attr(attrs, "android:paddingStart", attr(attrs, "android:paddingLeft", "")), pAll);
        int pRight = parseDimenPx(attr(attrs, "android:paddingEnd", attr(attrs, "android:paddingRight", "")), pAll);
        if (pAll > 0 || pTop > 0 || pBottom > 0 || pLeft > 0 || pRight > 0) {
            view.setPadding(pLeft, pTop, pRight, pBottom);
        }

        String bg = attr(attrs, "android:background", "");
        if (!bg.isEmpty()) {
            Integer color = resolveColor(bg);
            if (color != null) {
                view.setBackgroundColor(color);
            }
        }

        String vis = attr(attrs, "android:visibility", "visible");
        if ("gone".equalsIgnoreCase(vis)) {
            view.setVisibility(View.GONE);
        } else if ("invisible".equalsIgnoreCase(vis)) {
            view.setVisibility(View.INVISIBLE);
        } else {
            view.setVisibility(View.VISIBLE);
        }
    }

    private void showPlaceholder(String message) {
        TextView tv = new TextView(getContext());
        tv.setText(message);
        tv.setTextColor(0xFF64748B);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(24), dp(48), dp(24), dp(48));
        canvasHost.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static Map<String, String> readAttributes(XmlPullParser parser) {
        Map<String, String> map = new HashMap<>();
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            map.put(parser.getAttributeName(i), parser.getAttributeValue(i));
        }
        return map;
    }

    private static String attr(Map<String, String> attrs, String key, String fallback) {
        String v = attrs.get(key);
        return v != null ? v : fallback;
    }

    private static String simplifyTag(String raw) {
        if (raw == null) return "View";
        int dot = raw.lastIndexOf('.');
        return dot >= 0 && dot + 1 < raw.length() ? raw.substring(dot + 1) : raw;
    }

    private static boolean matchesContainerTag(ViewGroup group, String tag) {
        if (group instanceof LinearLayout && "LinearLayout".equals(tag)) return true;
        if (group instanceof RelativeLayout && ("RelativeLayout".equals(tag) || "ConstraintLayout".equals(tag))) return true;
        if (group instanceof ScrollView && "ScrollView".equals(tag)) return true;
        if (group instanceof HorizontalScrollView && "HorizontalScrollView".equals(tag)) return true;
        return group instanceof FrameLayout && ("FrameLayout".equals(tag) || tag.contains("CardView") || "CoordinatorLayout".equals(tag));
    }

    private String resolveString(String raw) {
        if (raw.startsWith("@string/")) {
            String key = raw.substring("@string/".length());
            String val = stringResources.get(key);
            return val != null ? val : raw;
        }
        return raw;
    }

    @Nullable
    private Integer resolveColor(String raw) {
        try {
            if (raw.startsWith("#")) {
                return Color.parseColor(raw);
            }
            if (raw.startsWith("@color/")) {
                String key = raw.substring("@color/".length());
                String val = colorResources.get(key);
                if (val != null && val.startsWith("#")) {
                    return Color.parseColor(val);
                }
            }
            if ("@android:color/white".equals(raw)) return Color.WHITE;
            if ("@android:color/black".equals(raw)) return Color.BLACK;
            if ("@android:color/transparent".equals(raw)) return Color.TRANSPARENT;
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private int parseLayoutSize(String value) {
        if ("match_parent".equalsIgnoreCase(value) || "fill_parent".equalsIgnoreCase(value)) {
            return ViewGroup.LayoutParams.MATCH_PARENT;
        }
        if ("wrap_content".equalsIgnoreCase(value)) {
            return ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        return parseDimenPx(value, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int parseDimenPx(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        String clean = raw.trim().toLowerCase();
        try {
            if (clean.endsWith("dp") || clean.endsWith("dip")) {
                float val = Float.parseFloat(clean.replace("dip", "").replace("dp", "").trim());
                return Math.round(val * getResources().getDisplayMetrics().density);
            }
            if (clean.endsWith("sp")) {
                float val = Float.parseFloat(clean.replace("sp", "").trim());
                return Math.round(val * getResources().getDisplayMetrics().scaledDensity);
            }
            if (clean.endsWith("px")) {
                return Math.round(Float.parseFloat(clean.replace("px", "").trim()));
            }
        } catch (NumberFormatException ignored) {}
        return fallback;
    }

    private float parseDimenSp(String raw, float fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            String clean = raw.trim().toLowerCase().replace("sp", "").replace("dp", "").trim();
            return Float.parseFloat(clean);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseGravity(String raw) {
        if (raw == null || raw.isEmpty()) return Gravity.NO_GRAVITY;
        int result = Gravity.NO_GRAVITY;
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            switch (part.trim().toLowerCase()) {
                case "center":
                    result |= Gravity.CENTER;
                    break;
                case "center_horizontal":
                    result |= Gravity.CENTER_HORIZONTAL;
                    break;
                case "center_vertical":
                    result |= Gravity.CENTER_VERTICAL;
                    break;
                case "top":
                    result |= Gravity.TOP;
                    break;
                case "bottom":
                    result |= Gravity.BOTTOM;
                    break;
                case "start":
                case "left":
                    result |= Gravity.START;
                    break;
                case "end":
                case "right":
                    result |= Gravity.END;
                    break;
            }
        }
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
