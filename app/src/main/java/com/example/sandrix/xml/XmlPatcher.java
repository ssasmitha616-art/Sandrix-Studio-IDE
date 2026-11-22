package com.example.sandrix.xml;

import com.example.sandrix.config.BuildSdParser;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Safe auto-fixer for Android layout XML namespaces and AndroidManifest.xml package/SDK sync.
 *
 * <ul>
 *     <li>Extracts the Java {@code package ...;} declaration from {@code MainActivity.java} and
 *     synchronizes {@code package="..."} in {@code AndroidManifest.xml}.</li>
 *     <li>Auto-injects {@code xmlns:android="http://schemas.android.com/apk/res/android"} (and
 *     {@code xmlns:app} / {@code xmlns:tools} if referenced) into layout XML root elements to
 *     prevent AAPT2 unbound prefix compilation errors.</li>
 *     <li>Validates XML well-formedness using {@link XmlPullParser} with exact line numbers.</li>
 * </ul>
 */
public final class XmlPatcher {

    public static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    public static final String APP_NS = "http://schemas.android.com/apk/res-auto";
    public static final String TOOLS_NS = "http://schemas.android.com/tools";

    private static final Pattern JAVA_PACKAGE_PATTERN =
            Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)\\s*;?");

    private static final Pattern MANIFEST_TAG_PATTERN =
            Pattern.compile("(<manifest\\b[^>]*)(>)");

    private static final Pattern MANIFEST_PACKAGE_ATTR_PATTERN =
            Pattern.compile("\\bpackage\\s*=\\s*[\"'][^\"']*[\"']");

    private static final Pattern USES_SDK_TAG_PATTERN =
            Pattern.compile("<uses-sdk\\b[^>]*/?>");

    private XmlPatcher() {}

    /**
     * Extracts the package declaration from Java source code, ignoring comments.
     */
    public static String extractPackageFromJava(String javaSource) {
        if (javaSource == null || javaSource.isEmpty()) {
            return null;
        }
        String stripped = javaSource
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        Matcher matcher = JAVA_PACKAGE_PATTERN.matcher(stripped);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Synchronizes {@code AndroidManifest.xml} with the package name declared in
     * {@code MainActivity.java} and SDK metadata from {@link BuildSdParser.BuildSdConfig}.
     */
    public static PatchReport syncManifestPackage(
            File mainActivityJavaFile,
            File manifestXmlFile,
            BuildSdParser.BuildSdConfig config) throws IOException {
        List<String> actions = new ArrayList<>();
        if (manifestXmlFile == null || !manifestXmlFile.exists()) {
            return new PatchReport(false, "AndroidManifest.xml not found.", actions);
        }

        String packageName = null;
        if (mainActivityJavaFile != null && mainActivityJavaFile.exists()) {
            String javaSource = readFileUtf8(mainActivityJavaFile);
            packageName = extractPackageFromJava(javaSource);
        }
        if ((packageName == null || packageName.isEmpty())
                && config != null && config.namespace != null && !config.namespace.isEmpty()) {
            packageName = config.namespace;
        }

        String manifest = readFileUtf8(manifestXmlFile);
        String updated = manifest;

        if (packageName != null && !packageName.isEmpty()) {
            Matcher manifestMatcher = MANIFEST_TAG_PATTERN.matcher(updated);
            if (manifestMatcher.find()) {
                String tagOpen = manifestMatcher.group(1);
                String tagClose = manifestMatcher.group(2);
                if (tagOpen != null && tagClose != null) {
                    Matcher pkgAttr = MANIFEST_PACKAGE_ATTR_PATTERN.matcher(tagOpen);
                    String newTagOpen;
                    if (pkgAttr.find()) {
                        String existing = pkgAttr.group();
                        String desired = "package=\"" + packageName + "\"";
                        if (!existing.equals(desired)) {
                            newTagOpen = pkgAttr.replaceFirst(Matcher.quoteReplacement(desired));
                            actions.add("Synced manifest package -> " + packageName);
                        } else {
                            newTagOpen = tagOpen;
                        }
                    } else {
                        newTagOpen = tagOpen + " package=\"" + packageName + "\"";
                        actions.add("Injected manifest package=\"" + packageName + "\"");
                    }

                    if (!newTagOpen.contains("xmlns:android")) {
                        newTagOpen = newTagOpen + "\n    xmlns:android=\"" + ANDROID_NS + "\"";
                        actions.add("Injected xmlns:android into <manifest>");
                    }
                    updated = manifestMatcher.replaceFirst(Matcher.quoteReplacement(newTagOpen + tagClose));
                }
            }
        }

        if (config != null) {
            String usesSdkTag = "<uses-sdk android:minSdkVersion=\"" + config.minSdk
                    + "\" android:targetSdkVersion=\"" + config.targetSdk + "\" />";
            Matcher sdkMatcher = USES_SDK_TAG_PATTERN.matcher(updated);
            if (sdkMatcher.find()) {
                if (!sdkMatcher.group().equals(usesSdkTag)) {
                    updated = sdkMatcher.replaceFirst(Matcher.quoteReplacement(usesSdkTag));
                    actions.add("Synced <uses-sdk> minSdk=" + config.minSdk + ", targetSdk=" + config.targetSdk);
                }
            } else {
                int appIdx = updated.indexOf("<application");
                if (appIdx > 0) {
                    updated = updated.substring(0, appIdx)
                            + usesSdkTag + "\n\n    "
                            + updated.substring(appIdx);
                    actions.add("Inserted <uses-sdk> minSdk=" + config.minSdk + ", targetSdk=" + config.targetSdk);
                }
            }
        }

        // Ensure <application> has an android:theme if none is specified, preventing Activity/AppCompat crash on launch
        Pattern appTagPattern = Pattern.compile("(<application\\b[^>]*)(>)");
        Matcher appMatcher = appTagPattern.matcher(updated);
        if (appMatcher.find()) {
            String appOpen = appMatcher.group(1);
            String appClose = appMatcher.group(2);
            if (appOpen != null && !appOpen.contains("android:theme")) {
                String newAppOpen = appOpen + "\n        android:theme=\"@style/AppTheme\"";
                updated = appMatcher.replaceFirst(Matcher.quoteReplacement(newAppOpen + appClose));
                actions.add("Injected android:theme=\"@style/AppTheme\" on <application>");
                ensureAppThemeResources(manifestXmlFile.getParentFile());
            }
        }

        if (packageName != null && !packageName.isEmpty()) {
            updated = qualifyComponentNamesInXml(updated, packageName, actions);
        }

        boolean changed = !updated.equals(manifest);
        if (changed) {
            writeFileUtf8(manifestXmlFile, updated);
        }
        return new PatchReport(changed,
                changed ? "Manifest synchronized." : "Manifest already in sync.",
                actions);
    }

    /**
     * Fully qualifies relative component names (e.g. {@code .MainActivity} -> {@code com.sandrix.myapp.MainActivity})
     * in {@code <activity>}, {@code <service>}, {@code <receiver>}, and {@code <provider>} tags.
     * This prevents Android PackageManager from looking for classes under a renamed applicationId.
     */
    public static PatchReport qualifyComponentNames(File manifestXmlFile, String packageName) throws IOException {
        List<String> actions = new ArrayList<>();
        if (manifestXmlFile == null || !manifestXmlFile.exists() || packageName == null || packageName.isEmpty()) {
            return new PatchReport(false, "Manifest file missing or invalid package.", actions);
        }
        String original = readFileUtf8(manifestXmlFile);
        String updated = qualifyComponentNamesInXml(original, packageName, actions);
        boolean changed = !updated.equals(original);
        if (changed) {
            writeFileUtf8(manifestXmlFile, updated);
        }
        return new PatchReport(changed, changed ? "Component names qualified." : "No component names needed qualification.", actions);
    }

    private static String qualifyComponentNamesInXml(String manifestXml, String packageName, List<String> actions) {
        if (manifestXml == null || packageName == null || packageName.isEmpty()) return manifestXml;
        Pattern compPattern = Pattern.compile("(<(?:activity|service|receiver|provider)\\b[^>]*?\\bandroid:name\\s*=\\s*[\"'])([^\"']+)([\"'][^>]*>)");
        Matcher m = compPattern.matcher(manifestXml);
        StringBuffer sb = new StringBuffer();
        boolean modified = false;
        while (m.find()) {
            String prefix = m.group(1);
            String name = m.group(2);
            String suffix = m.group(3);
            String resolved = resolveComponentName(name, packageName);
            if (!resolved.equals(name)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(prefix + resolved + suffix));
                if (actions != null) {
                    actions.add("Fully qualified " + name + " -> " + resolved);
                }
                modified = true;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return modified ? sb.toString() : manifestXml;
    }

    /**
     * Ensures that {@code styles.xml} and {@code colors.xml} exist with {@code AppTheme}
     * so that projects run without missing resource / theme crashes.
     */
    public static void ensureAppThemeResources(File projectDir) {
        if (projectDir == null) return;
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
            try {
                writeFileUtf8(stylesXml,
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
            } catch (IOException ignored) {}
        }
        File colorsXml = new File(valuesDir, "colors.xml");
        if (!colorsXml.exists()) {
            try {
                writeFileUtf8(colorsXml,
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                                + "<resources>\n"
                                + "    <color name=\"primary_cyan\">#0284C7</color>\n"
                                + "    <color name=\"surface_bg\">#F8FAFC</color>\n"
                                + "    <color name=\"text_dark\">#0F172A</color>\n"
                                + "</resources>\n");
            } catch (IOException ignored) {}
        }
    }

    /**
     * Ensures that a layout XML file declares {@code xmlns:android} (and {@code xmlns:app} /
     * {@code xmlns:tools} if those prefixes appear in the document) on its root element.
     */
    public static PatchReport ensureAndroidNamespace(File layoutXmlFile) throws IOException {
        if (layoutXmlFile == null || !layoutXmlFile.exists()) {
            return new PatchReport(false, "Layout XML file missing.", new ArrayList<>());
        }
        String original = readFileUtf8(layoutXmlFile);
        PatchResultWithContent result = patchLayoutStringWithReport(original);
        if (result.report.modified) {
            writeFileUtf8(layoutXmlFile, result.content);
        }
        return result.report;
    }

    public static String patchLayoutString(String xmlContent) {
        return patchLayoutStringWithReport(xmlContent).content;
    }

    public static PatchResultWithContent patchLayoutStringWithReport(String xmlContent) {
        List<String> actions = new ArrayList<>();
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            return new PatchResultWithContent(xmlContent == null ? "" : xmlContent,
                    new PatchReport(false, "Empty XML content.", actions));
        }

        int rootStart = findRootElementStart(xmlContent);
        if (rootStart < 0) {
            return new PatchResultWithContent(xmlContent,
                    new PatchReport(false, "No root XML element found.", actions));
        }

        int rootEnd = findTagEnd(xmlContent, rootStart);
        if (rootEnd < 0) {
            return new PatchResultWithContent(xmlContent,
                    new PatchReport(false, "Unterminated root XML element.", actions));
        }

        String rootTag = xmlContent.substring(rootStart, rootEnd + 1);
        boolean selfClosing = rootTag.endsWith("/>");
        int insertPos = selfClosing ? rootEnd - 1 : rootEnd;

        StringBuilder injectedAttrs = new StringBuilder();
        if (!rootTag.contains("xmlns:android=")) {
            injectedAttrs.append("\n    xmlns:android=\"").append(ANDROID_NS).append("\"");
            actions.add("Auto-injected xmlns:android on root element");
        }
        if (xmlContent.contains("app:") && !rootTag.contains("xmlns:app=")) {
            injectedAttrs.append("\n    xmlns:app=\"").append(APP_NS).append("\"");
            actions.add("Auto-injected xmlns:app on root element");
        }
        if (xmlContent.contains("tools:") && !rootTag.contains("xmlns:tools=")) {
            injectedAttrs.append("\n    xmlns:tools=\"").append(TOOLS_NS).append("\"");
            actions.add("Auto-injected xmlns:tools on root element");
        }

        if (injectedAttrs.length() == 0) {
            return new PatchResultWithContent(xmlContent,
                    new PatchReport(false, "XML namespaces already valid.", actions));
        }

        String patched = xmlContent.substring(0, insertPos)
                + injectedAttrs
                + xmlContent.substring(insertPos);

        return new PatchResultWithContent(patched,
                new PatchReport(true, "Injected missing XML namespaces.", actions));
    }

    /**
     * Validates XML syntax using {@link XmlPullParser} and returns any parse error with line number.
     */
    public static XmlValidationResult validateXml(String xmlContent) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xmlContent));
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                event = parser.next();
            }
            return new XmlValidationResult(true, -1, null);
        } catch (XmlPullParserException e) {
            int line = Math.max(1, e.getLineNumber());
            return new XmlValidationResult(false, line, e.getMessage());
        } catch (IOException e) {
            return new XmlValidationResult(false, 1, e.getMessage());
        }
    }

    private static int findRootElementStart(String xml) {
        int i = 0;
        int len = xml.length();
        while (i < len) {
            int lt = xml.indexOf('<', i);
            if (lt < 0 || lt + 1 >= len) {
                return -1;
            }
            char next = xml.charAt(lt + 1);
            if (next == '?') {
                int endPi = xml.indexOf("?>", lt + 2);
                if (endPi < 0) return -1;
                i = endPi + 2;
            } else if (next == '!') {
                if (xml.startsWith("<!--", lt)) {
                    int endComment = xml.indexOf("-->", lt + 4);
                    if (endComment < 0) return -1;
                    i = endComment + 3;
                } else {
                    int endBang = xml.indexOf('>', lt + 2);
                    if (endBang < 0) return -1;
                    i = endBang + 1;
                }
            } else {
                return lt;
            }
        }
        return -1;
    }

    private static int findTagEnd(String xml, int start) {
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = start; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '>' && !inDouble && !inSingle) {
                return i;
            }
        }
        return -1;
    }

    public static String readFileUtf8(File file) throws IOException {
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file)) {
            int read;
            while ((read = fis.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void writeFileUtf8(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Merges library/AAR manifests into the app's primary {@code AndroidManifest.xml}, resolving
     * {@code ${applicationId}} / {@code ${packageName}} placeholders, honoring {@code tools:replace}
     * and {@code tools:node="remove"}, and reporting component/attribute/provider conflicts cleanly.
     *
     * <p>When {@code conflicts} is non-empty, {@code outputMergedManifest} is NOT written and
     * {@link ManifestMergeResult#success} is {@code false}.</p>
     */
    public static ManifestMergeResult mergeManifests(
            File mainManifest,
            List<File> libraryManifests,
            File outputMergedManifest,
            String applicationId) {
        List<String> actions = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        if (mainManifest == null || !mainManifest.exists()) {
            conflicts.add("Main AndroidManifest.xml is missing.");
            return new ManifestMergeResult(false, mainManifest, actions, conflicts);
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document mainDoc;
            try (FileInputStream fis = new FileInputStream(mainManifest)) {
                mainDoc = builder.parse(fis);
            }
            Element mainRoot = mainDoc.getDocumentElement();
            if (mainRoot == null || !"manifest".equals(mainRoot.getNodeName())) {
                conflicts.add("Main manifest root element must be <manifest>.");
                return new ManifestMergeResult(false, mainManifest, actions, conflicts);
            }

            String resolvedAppId = (applicationId != null && !applicationId.trim().isEmpty())
                    ? applicationId.trim()
                    : mainRoot.getAttribute("package");
            if (resolvedAppId == null) {
                resolvedAppId = "";
            }
            String mainPkg = mainRoot.getAttribute("package");
            if (mainPkg == null || mainPkg.isEmpty()) {
                mainPkg = resolvedAppId;
            }

            // Ensure xmlns:android on merged root
            if (!mainRoot.hasAttribute("xmlns:android")) {
                mainRoot.setAttribute("xmlns:android", ANDROID_NS);
            }

            replacePlaceholdersRecursively(mainRoot, resolvedAppId, mainPkg);

            Element mainApp = firstChildElementByTag(mainRoot, "application");
            if (mainApp == null) {
                mainApp = mainDoc.createElement("application");
                mainRoot.appendChild(mainApp);
            }

            // Track existing top-level permissions/features and application components
            Map<String, Element> existingTopLevel = new LinkedHashMap<>();
            indexTopLevelElements(mainRoot, existingTopLevel);

            Map<String, Element> existingAppComponents = new LinkedHashMap<>();
            Map<String, Element> providerAuthorities = new LinkedHashMap<>();
            indexAppComponents(mainApp, mainPkg, existingAppComponents, providerAuthorities);

            Set<String> removedKeys = new HashSet<>();
            Iterator<Map.Entry<String, Element>> topIt = existingTopLevel.entrySet().iterator();
            while (topIt.hasNext()) {
                Map.Entry<String, Element> entry = topIt.next();
                if ("remove".equalsIgnoreCase(getToolsAttr(entry.getValue(), "node"))) {
                    removedKeys.add(entry.getKey());
                    if (mainRoot.equals(entry.getValue().getParentNode())) {
                        mainRoot.removeChild(entry.getValue());
                    }
                    topIt.remove();
                    actions.add("Suppressed top-level " + entry.getKey() + " via app tools:node=\"remove\"");
                }
            }
            Iterator<Map.Entry<String, Element>> appCompIt = existingAppComponents.entrySet().iterator();
            while (appCompIt.hasNext()) {
                Map.Entry<String, Element> entry = appCompIt.next();
                if ("remove".equalsIgnoreCase(getToolsAttr(entry.getValue(), "node"))) {
                    removedKeys.add(entry.getKey());
                    if (mainApp.equals(entry.getValue().getParentNode())) {
                        mainApp.removeChild(entry.getValue());
                    }
                    appCompIt.remove();
                    actions.add("Suppressed component " + entry.getKey() + " via app tools:node=\"remove\"");
                }
            }

            if (libraryManifests != null) {
                for (File libFile : libraryManifests) {
                    if (libFile == null || !libFile.exists() || !libFile.isFile()) {
                        continue;
                    }
                    Document libDoc;
                    try (FileInputStream fis = new FileInputStream(libFile)) {
                        libDoc = builder.parse(fis);
                    } catch (Exception parseEx) {
                        conflicts.add("Failed to parse library manifest "
                                + describeLibManifest(libFile) + ": " + parseEx.getMessage());
                        continue;
                    }

                    Element libRoot = libDoc.getDocumentElement();
                    if (libRoot == null) continue;
                    String libPkg = libRoot.getAttribute("package");
                    String libLabel = describeLibManifest(libFile);
                    replacePlaceholdersRecursively(libRoot, resolvedAppId, libPkg != null ? libPkg : mainPkg);

                    NodeList libChildren = libRoot.getChildNodes();
                    for (int i = 0; i < libChildren.getLength(); i++) {
                        Node n = libChildren.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element el = (Element) n;
                        String tag = localOrTagName(el);

                        if ("uses-sdk".equals(tag)) {
                            // Keep app's uses-sdk as primary
                            continue;
                        }
                        if ("queries".equals(tag)) {
                            mergeQueriesElement(mainDoc, mainRoot, el, actions, libLabel);
                            continue;
                        }
                        if ("application".equals(tag)) {
                            mergeApplicationElement(
                                    mainDoc,
                                    mainApp,
                                    el,
                                    (libPkg != null && !libPkg.isEmpty()) ? libPkg : mainPkg,
                                    existingAppComponents,
                                    providerAuthorities,
                                    removedKeys,
                                    actions,
                                    conflicts,
                                    libLabel);
                            continue;
                        }

                        if ("uses-permission".equals(tag)
                                || "uses-permission-sdk-23".equals(tag)
                                || "uses-feature".equals(tag)
                                || "permission".equals(tag)) {
                            String nameAttr = getAndroidOrPlainAttr(el, "name");
                            String key = tag + ":" + (nameAttr != null ? nameAttr : el.getTextContent().trim());
                            String toolsNode = getToolsAttr(el, "node");
                            Element existing = existingTopLevel.get(key);

                            if ("remove".equalsIgnoreCase(toolsNode)) {
                                if (existing != null && mainRoot.equals(existing.getParentNode())) {
                                    mainRoot.removeChild(existing);
                                    existingTopLevel.remove(key);
                                    actions.add("Removed <" + tag + "> (" + nameAttr + ") via tools:node=\"remove\" from " + libLabel);
                                }
                                continue;
                            }
                            if (removedKeys.contains(key)) {
                                actions.add("Skipped <" + tag + "> (" + nameAttr + ") blocked by app tools:node=\"remove\"");
                                continue;
                            }

                            if (existing == null) {
                                Element imported = (Element) mainDoc.importNode(el, true);
                                stripToolsAttributes(imported);
                                mainRoot.insertBefore(imported, mainApp);
                                existingTopLevel.put(key, imported);
                                actions.add("Merged <" + tag + "> " + (nameAttr != null ? nameAttr : "") + " from " + libLabel);
                            } else {
                                mergeAttributesWithConflictCheck(
                                        existing, el, tag + " (" + nameAttr + ")", libLabel, actions, conflicts);
                            }
                        }
                    }
                }
            }

            if (!conflicts.isEmpty()) {
                return new ManifestMergeResult(false, mainManifest, actions, conflicts);
            }

            stripToolsAttributesRecursively(mainRoot);
            String mergedXml = serializeDocument(mainDoc);
            if (outputMergedManifest != null) {
                writeFileUtf8(outputMergedManifest, mergedXml);
                return new ManifestMergeResult(true, outputMergedManifest, actions, conflicts);
            }
            return new ManifestMergeResult(true, mainManifest, actions, conflicts);
        } catch (Exception e) {
            conflicts.add("Manifest merge exception: " + e.getMessage());
            return new ManifestMergeResult(false, mainManifest, actions, conflicts);
        }
    }

    private static void mergeQueriesElement(
            Document mainDoc,
            Element mainRoot,
            Element libQueries,
            List<String> actions,
            String libLabel) {
        Element mainQueries = firstChildElementByTag(mainRoot, "queries");
        if (mainQueries == null) {
            Element imported = (Element) mainDoc.importNode(libQueries, true);
            stripToolsAttributesRecursively(imported);
            Element mainApp = firstChildElementByTag(mainRoot, "application");
            if (mainApp != null) {
                mainRoot.insertBefore(imported, mainApp);
            } else {
                mainRoot.appendChild(imported);
            }
            actions.add("Merged <queries> from " + libLabel);
            return;
        }
        Set<String> existingSignatures = new HashSet<>();
        NodeList existingChildren = mainQueries.getChildNodes();
        for (int i = 0; i < existingChildren.getLength(); i++) {
            Node c = existingChildren.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                existingSignatures.add(elementSignature((Element) c));
            }
        }
        NodeList libChildren = libQueries.getChildNodes();
        for (int i = 0; i < libChildren.getLength(); i++) {
            Node c = libChildren.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) c;
                String sig = elementSignature(el);
                if (existingSignatures.add(sig)) {
                    Element imported = (Element) mainDoc.importNode(el, true);
                    stripToolsAttributesRecursively(imported);
                    mainQueries.appendChild(imported);
                    actions.add("Merged <queries>/<" + localOrTagName(el) + "> from " + libLabel);
                }
            }
        }
    }

    private static void mergeApplicationElement(
            Document mainDoc,
            Element mainApp,
            Element libApp,
            String libPkg,
            Map<String, Element> existingAppComponents,
            Map<String, Element> providerAuthorities,
            Set<String> removedKeys,
            List<String> actions,
            List<String> conflicts,
            String libLabel) {
        mergeAttributesWithConflictCheck(mainApp, libApp, "<application>", libLabel, actions, conflicts);

        NodeList children = libApp.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String tag = localOrTagName(el);
            String nameAttr = resolveComponentName(getAndroidOrPlainAttr(el, "name"), libPkg);
            String toolsNode = getToolsAttr(el, "node");
            String key = tag + ":" + (nameAttr != null ? nameAttr : elementSignature(el));

            Element existing = existingAppComponents.get(key);
            if ("remove".equalsIgnoreCase(toolsNode)) {
                if (existing != null && mainApp.equals(existing.getParentNode())) {
                    mainApp.removeChild(existing);
                    existingAppComponents.remove(key);
                    actions.add("Removed <" + tag + "> " + nameAttr + " via tools:node=\"remove\" from " + libLabel);
                }
                continue;
            }
            if (removedKeys.contains(key)) {
                actions.add("Skipped <" + tag + "> " + nameAttr + " blocked by app tools:node=\"remove\"");
                continue;
            }

            // Check provider authority collisions against distinct providers
            if ("provider".equals(tag)) {
                String authStr = getAndroidOrPlainAttr(el, "authorities");
                if (authStr != null && !authStr.isEmpty()) {
                    for (String auth : authStr.split(";")) {
                        String cleanAuth = auth.trim();
                        if (cleanAuth.isEmpty()) continue;
                        Element conflictingProvider = providerAuthorities.get(cleanAuth);
                        if (conflictingProvider != null && conflictingProvider != existing) {
                            String existingName = getAndroidOrPlainAttr(conflictingProvider, "name");
                            conflicts.add("Provider authority conflict for '" + cleanAuth
                                    + "' between " + existingName + " and " + nameAttr + " (" + libLabel + ").");
                        }
                    }
                }
            }

            if (existing == null) {
                Element imported = (Element) mainDoc.importNode(el, true);
                if (nameAttr != null && hasAndroidOrPlainAttr(imported, "name")) {
                    setAndroidAttr(imported, "name", nameAttr);
                }
                stripToolsAttributesRecursively(imported);
                mainApp.appendChild(imported);
                existingAppComponents.put(key, imported);
                if ("provider".equals(tag)) {
                    String authStr = getAndroidOrPlainAttr(imported, "authorities");
                    if (authStr != null) {
                        for (String auth : authStr.split(";")) {
                            String cleanAuth = auth.trim();
                            if (!cleanAuth.isEmpty()) {
                                providerAuthorities.put(cleanAuth, imported);
                            }
                        }
                    }
                }
                actions.add("Merged <" + tag + "> " + (nameAttr != null ? nameAttr : "") + " from " + libLabel);
            } else {
                mergeAttributesWithConflictCheck(
                        existing, el, "<" + tag + " android:name=\"" + nameAttr + "\">", libLabel, actions, conflicts);
                mergeComponentChildren(
                        mainDoc, existing, el, "<" + tag + " android:name=\"" + nameAttr + "\">", libLabel, actions, conflicts);
            }
        }
    }

    private static void mergeComponentChildren(
            Document mainDoc,
            Element existing,
            Element source,
            String parentDesc,
            String libLabel,
            List<String> actions,
            List<String> conflicts) {
        NodeList srcChildren = source.getChildNodes();
        for (int i = 0; i < srcChildren.getLength(); i++) {
            Node n = srcChildren.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element childEl = (Element) n;
            String childTag = localOrTagName(childEl);
            String childNodeAction = getToolsAttr(childEl, "node");
            String childName = getAndroidOrPlainAttr(childEl, "name");

            Element matchingChild = findMatchingChild(existing, childTag, childName, childEl);

            if ("remove".equals(childNodeAction) || "removeAll".equals(childNodeAction)) {
                if (matchingChild != null) {
                    existing.removeChild(matchingChild);
                    actions.add("Removed <" + childTag + "> " + (childName != null ? childName : "")
                            + " from " + parentDesc + " via tools:node=\"" + childNodeAction + "\"");
                }
                continue;
            }

            if (matchingChild == null) {
                Element importedChild = (Element) mainDoc.importNode(childEl, true);
                stripToolsAttributesRecursively(importedChild);
                existing.appendChild(importedChild);
                actions.add("Merged child <" + childTag + "> " + (childName != null ? childName : "")
                        + " into " + parentDesc + " from " + libLabel);
            } else {
                mergeAttributesWithConflictCheck(
                        matchingChild,
                        childEl,
                        "<" + childTag + (childName != null ? " android:name=\"" + childName + "\"" : "") + "> in " + parentDesc,
                        libLabel,
                        actions,
                        conflicts);
            }
        }
    }

    private static Element findMatchingChild(Element parent, String tag, String name, Element source) {
        NodeList children = parent.getChildNodes();
        String sourceSig = (name == null) ? childSignature(source) : null;
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if (!tag.equals(localOrTagName(el))) continue;

            if (name != null) {
                String existingName = getAndroidOrPlainAttr(el, "name");
                if (name.equals(existingName)) {
                    return el;
                }
            } else {
                if (sourceSig != null && sourceSig.equals(childSignature(el))) {
                    return el;
                }
            }
        }
        return null;
    }

    private static String childSignature(Element el) {
        StringBuilder sb = new StringBuilder(localOrTagName(el));
        NamedNodeMap attrs = el.getAttributes();
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (!a.getNodeName().startsWith("tools:") && !a.getNodeName().startsWith("xmlns")) {
                pairs.add(a.getNodeName() + "=" + a.getNodeValue());
            }
        }
        java.util.Collections.sort(pairs);
        sb.append(pairs);
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                sb.append("/").append(childSignature((Element) n));
            }
        }
        return sb.toString();
    }

    private static void mergeAttributesWithConflictCheck(
            Element target,
            Element source,
            String componentDesc,
            String libLabel,
            List<String> actions,
            List<String> conflicts) {
        Set<String> replaceSet = parseToolsReplace(target);
        Set<String> sourceReplaceSet = parseToolsReplace(source);
        replaceSet.addAll(sourceReplaceSet);

        NamedNodeMap srcAttrs = source.getAttributes();
        for (int i = 0; i < srcAttrs.getLength(); i++) {
            Node a = srcAttrs.item(i);
            if (!(a instanceof Attr)) continue;
            Attr attr = (Attr) a;
            String qName = attr.getName();
            String ns = attr.getNamespaceURI();
            String local = attr.getLocalName() != null ? attr.getLocalName() : qName;

            if (qName.startsWith("xmlns") || TOOLS_NS.equals(ns) || qName.startsWith("tools:")) {
                continue;
            }
            if ("package".equals(qName)) continue;

            String srcVal = attr.getValue();
            Attr existingAttr = findMatchingAttr(target, attr);
            if (existingAttr == null) {
                if (ns != null && !ns.isEmpty()) {
                    target.setAttributeNS(ns, qName, srcVal);
                } else {
                    target.setAttribute(qName, srcVal);
                }
                actions.add("Added attribute " + qName + "=\"" + srcVal + "\" on " + componentDesc + " from " + libLabel);
            } else {
                String targetVal = existingAttr.getValue();
                if (!targetVal.equals(srcVal)) {
                    if (replaceSet.contains(qName) || replaceSet.contains(local)
                            || replaceSet.contains("android:" + local)) {
                        // Target wins when tools:replace is declared on target; if declared on source only, source overrides
                        if (!parseToolsReplace(target).isEmpty()) {
                            actions.add("Kept app attribute " + qName + "=\"" + targetVal
                                    + "\" via tools:replace on " + componentDesc);
                        } else {
                            existingAttr.setValue(srcVal);
                            actions.add("Replaced attribute " + qName + "=\"" + srcVal
                                    + "\" via tools:replace from " + libLabel);
                        }
                    } else {
                        conflicts.add("Manifest attribute conflict on " + componentDesc + " for "
                                + qName + ": app has \"" + targetVal + "\" while " + libLabel
                                + " has \"" + srcVal + "\". Use tools:replace=\"" + qName + "\" to resolve.");
                    }
                }
            }
        }
    }

    private static Attr findMatchingAttr(Element target, Attr srcAttr) {
        String ns = srcAttr.getNamespaceURI();
        String local = srcAttr.getLocalName();
        if (ns != null && local != null) {
            Attr byNs = target.getAttributeNodeNS(ns, local);
            if (byNs != null) return byNs;
        }
        return target.getAttributeNode(srcAttr.getName());
    }

    private static Set<String> parseToolsReplace(Element el) {
        String raw = getToolsAttr(el, "replace");
        Set<String> set = new HashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    set.add(p);
                    int colon = p.indexOf(':');
                    if (colon >= 0 && colon + 1 < p.length()) {
                        set.add(p.substring(colon + 1));
                    }
                }
            }
        }
        return set;
    }

    private static void indexTopLevelElements(Element root, Map<String, Element> map) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String tag = localOrTagName(el);
            if ("uses-permission".equals(tag)
                    || "uses-permission-sdk-23".equals(tag)
                    || "uses-feature".equals(tag)
                    || "permission".equals(tag)) {
                String nameAttr = getAndroidOrPlainAttr(el, "name");
                String key = tag + ":" + (nameAttr != null ? nameAttr : el.getTextContent().trim());
                map.put(key, el);
            }
        }
    }

    private static void indexAppComponents(
            Element app,
            String pkg,
            Map<String, Element> components,
            Map<String, Element> providerAuthorities) {
        NodeList children = app.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String tag = localOrTagName(el);
            String nameAttr = resolveComponentName(getAndroidOrPlainAttr(el, "name"), pkg);
            if (nameAttr != null && hasAndroidOrPlainAttr(el, "name")) {
                setAndroidAttr(el, "name", nameAttr);
            }
            String key = tag + ":" + (nameAttr != null ? nameAttr : elementSignature(el));
            components.put(key, el);

            if ("provider".equals(tag)) {
                String authStr = getAndroidOrPlainAttr(el, "authorities");
                if (authStr != null) {
                    for (String auth : authStr.split(";")) {
                        String cleanAuth = auth.trim();
                        if (!cleanAuth.isEmpty()) {
                            providerAuthorities.put(cleanAuth, el);
                        }
                    }
                }
            }
        }
    }

    private static void replacePlaceholdersRecursively(Element root, String appId, String pkgName) {
        NamedNodeMap attrs = root.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String val = a.getNodeValue();
            if (val != null && val.contains("${")) {
                String next = val
                        .replace("${applicationId}", appId != null ? appId : "")
                        .replace("${packageName}", pkgName != null ? pkgName : "");
                if (!next.equals(val)) {
                    a.setNodeValue(next);
                }
            }
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                replacePlaceholdersRecursively((Element) n, appId, pkgName);
            }
        }
    }

    private static String resolveComponentName(String name, String pkg) {
        if (name == null || name.isEmpty()) return name;
        if (name.startsWith(".") && pkg != null && !pkg.isEmpty()) {
            return pkg + name;
        }
        if (!name.contains(".") && pkg != null && !pkg.isEmpty()) {
            return pkg + "." + name;
        }
        return name;
    }

    private static Element firstChildElementByTag(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(localOrTagName((Element) n))) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String localOrTagName(Element el) {
        String local = el.getLocalName();
        return (local != null && !local.isEmpty()) ? local : el.getTagName();
    }

    private static String getAndroidOrPlainAttr(Element el, String localName) {
        String v = el.getAttributeNS(ANDROID_NS, localName);
        if (v != null && !v.isEmpty()) return v;
        v = el.getAttribute("android:" + localName);
        if (v != null && !v.isEmpty()) return v;
        v = el.getAttribute(localName);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    private static boolean hasAndroidOrPlainAttr(Element el, String localName) {
        return getAndroidOrPlainAttr(el, localName) != null;
    }

    private static void setAndroidAttr(Element el, String localName, String value) {
        if (el.hasAttributeNS(ANDROID_NS, localName)) {
            el.setAttributeNS(ANDROID_NS, "android:" + localName, value);
        } else {
            el.setAttribute("android:" + localName, value);
        }
    }

    private static String getToolsAttr(Element el, String localName) {
        String v = el.getAttributeNS(TOOLS_NS, localName);
        if (v != null && !v.isEmpty()) return v;
        v = el.getAttribute("tools:" + localName);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    private static void stripToolsAttributesRecursively(Element el) {
        stripToolsAttributes(el);
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                stripToolsAttributesRecursively((Element) n);
            }
        }
    }

    private static void stripToolsAttributes(Element el) {
        NamedNodeMap attrs = el.getAttributes();
        List<String> toRemove = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            if (TOOLS_NS.equals(a.getNamespaceURI()) || name.startsWith("tools:")) {
                toRemove.add(name);
            }
        }
        for (String name : toRemove) {
            el.removeAttribute(name);
        }
    }

    private static String elementSignature(Element el) {
        StringBuilder sb = new StringBuilder(localOrTagName(el));
        NamedNodeMap attrs = el.getAttributes();
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (!a.getNodeName().startsWith("tools:") && !a.getNodeName().startsWith("xmlns")) {
                pairs.add(a.getNodeName() + "=" + a.getNodeValue());
            }
        }
        java.util.Collections.sort(pairs);
        sb.append(pairs);
        return sb.toString();
    }

    private static String describeLibManifest(File libFile) {
        File parent = libFile.getParentFile();
        if (parent != null && "aar_unpacked".equals(parent.getName()) && parent.getParentFile() != null) {
            return parent.getParentFile().getName();
        }
        return parent != null ? parent.getName() + "/" + libFile.getName() : libFile.getName();
    }

    private static String serializeDocument(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    public static final class PatchReport {
        public final boolean modified;
        public final String summary;
        public final List<String> actions;

        public PatchReport(boolean modified, String summary, List<String> actions) {
            this.modified = modified;
            this.summary = summary;
            this.actions = actions;
        }
    }

    public static final class PatchResultWithContent {
        public final String content;
        public final PatchReport report;

        public PatchResultWithContent(String content, PatchReport report) {
            this.content = content;
            this.report = report;
        }
    }

    public static final class XmlValidationResult {
        public final boolean valid;
        public final int errorLine;
        public final String errorMessage;

        public XmlValidationResult(boolean valid, int errorLine, String errorMessage) {
            this.valid = valid;
            this.errorLine = errorLine;
            this.errorMessage = errorMessage;
        }
    }

    public static final class ManifestMergeResult {
        public final boolean success;
        public final File mergedManifestFile;
        public final List<String> actions;
        public final List<String> conflicts;

        public ManifestMergeResult(
                boolean success,
                File mergedManifestFile,
                List<String> actions,
                List<String> conflicts) {
            this.success = success;
            this.mergedManifestFile = mergedManifestFile;
            this.actions = actions != null ? actions : new ArrayList<>();
            this.conflicts = conflicts != null ? conflicts : new ArrayList<>();
        }
    }

    // ==================== Phase 3: Layout Inspector Attribute Patching & Visual Manifest Model ====================

    /**
     * Patches (or removes when {@code newValue} is null/empty) an attribute on the N-th XML start
     * element (0-based pre-order index) while preserving existing formatting, comments, and siblings.
     */
    public static String patchElementAttributeByOccurrence(
            String xmlSource,
            int elementIndex,
            String attrName,
            String newValue) {
        if (xmlSource == null || elementIndex < 0 || attrName == null || attrName.trim().isEmpty()) {
            return xmlSource;
        }
        String cleanAttr = attrName.trim();
        int[] bounds = findStartTagBoundsByOccurrence(xmlSource, elementIndex);
        if (bounds == null) {
            return xmlSource;
        }
        int tagStart = bounds[0];
        int tagEnd = bounds[1];
        String tagText = xmlSource.substring(tagStart, tagEnd + 1);
        boolean remove = newValue == null || newValue.trim().isEmpty();

        Pattern attrPattern = Pattern.compile(
                "(\\s+)" + Pattern.quote(cleanAttr) + "\\s*=\\s*(\"[^\"]*\"|'[^']*')");
        Matcher m = attrPattern.matcher(tagText);
        String updatedTag;
        if (m.find()) {
            if (remove) {
                updatedTag = tagText.substring(0, m.start()) + tagText.substring(m.end());
            } else {
                String escaped = escapeAttrValue(newValue);
                updatedTag = tagText.substring(0, m.start())
                        + m.group(1) + cleanAttr + "=\"" + escaped + "\""
                        + tagText.substring(m.end());
            }
        } else {
            if (remove) {
                return xmlSource;
            }
            int insertPos = tagText.endsWith("/>") ? tagText.length() - 2 : tagText.length() - 1;
            String indent = inferChildAttributeIndent(xmlSource, tagStart);
            String escaped = escapeAttrValue(newValue);
            updatedTag = tagText.substring(0, insertPos)
                    + "\n" + indent + cleanAttr + "=\"" + escaped + "\""
                    + tagText.substring(insertPos);
        }

        return xmlSource.substring(0, tagStart) + updatedTag + xmlSource.substring(tagEnd + 1);
    }

    /**
     * Finds the line numbers (1-based) of all XML start elements in pre-order sequence.
     */
    public static List<Integer> findStartTagLineNumbers(String xmlSource) {
        List<Integer> lines = new ArrayList<>();
        if (xmlSource == null || xmlSource.isEmpty()) return lines;
        int i = 0;
        int len = xmlSource.length();
        while (i < len) {
            int lt = xmlSource.indexOf('<', i);
            if (lt < 0 || lt + 1 >= len) break;
            char next = xmlSource.charAt(lt + 1);
            if (next == '?') {
                int end = xmlSource.indexOf("?>", lt + 2);
                if (end < 0) break;
                i = end + 2;
            } else if (next == '!') {
                if (xmlSource.startsWith("<!--", lt)) {
                    int end = xmlSource.indexOf("-->", lt + 4);
                    if (end < 0) break;
                    i = end + 3;
                } else if (xmlSource.startsWith("<![CDATA[", lt)) {
                    int end = xmlSource.indexOf("]]>", lt + 9);
                    if (end < 0) break;
                    i = end + 3;
                } else {
                    int end = xmlSource.indexOf('>', lt + 2);
                    if (end < 0) break;
                    i = end + 1;
                }
            } else if (next == '/') {
                int end = xmlSource.indexOf('>', lt + 2);
                if (end < 0) break;
                i = end + 1;
            } else {
                lines.add(countLineAtOffset(xmlSource, lt));
                int end = findTagEnd(xmlSource, lt);
                if (end < 0) break;
                i = end + 1;
            }
        }
        return lines;
    }

    private static int[] findStartTagBoundsByOccurrence(String xmlSource, int targetIndex) {
        int i = 0;
        int len = xmlSource.length();
        int currentIdx = 0;
        while (i < len) {
            int lt = xmlSource.indexOf('<', i);
            if (lt < 0 || lt + 1 >= len) break;
            char next = xmlSource.charAt(lt + 1);
            if (next == '?') {
                int end = xmlSource.indexOf("?>", lt + 2);
                if (end < 0) break;
                i = end + 2;
            } else if (next == '!') {
                if (xmlSource.startsWith("<!--", lt)) {
                    int end = xmlSource.indexOf("-->", lt + 4);
                    if (end < 0) break;
                    i = end + 3;
                } else if (xmlSource.startsWith("<![CDATA[", lt)) {
                    int end = xmlSource.indexOf("]]>", lt + 9);
                    if (end < 0) break;
                    i = end + 3;
                } else {
                    int end = xmlSource.indexOf('>', lt + 2);
                    if (end < 0) break;
                    i = end + 1;
                }
            } else if (next == '/') {
                int end = xmlSource.indexOf('>', lt + 2);
                if (end < 0) break;
                i = end + 1;
            } else {
                int end = findTagEnd(xmlSource, lt);
                if (end < 0) break;
                if (currentIdx == targetIndex) {
                    return new int[]{lt, end};
                }
                currentIdx++;
                i = end + 1;
            }
        }
        return null;
    }

    private static int countLineAtOffset(String text, int offset) {
        int line = 1;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String inferChildAttributeIndent(String xml, int tagStart) {
        int lineStart = xml.lastIndexOf('\n', tagStart);
        int spaces = 0;
        if (lineStart >= 0) {
            for (int i = lineStart + 1; i < tagStart; i++) {
                char c = xml.charAt(i);
                if (c == ' ' || c == '\t') spaces++;
                else break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spaces + 4; i++) sb.append(' ');
        return sb.toString();
    }

    private static String escapeAttrValue(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static final class ManifestComponent {
        public String tag;
        public String name;
        public boolean exported;
        public String authorities;
        public boolean hasLauncherIntent;

        public ManifestComponent(String tag, String name, boolean exported, String authorities, boolean hasLauncherIntent) {
            this.tag = tag;
            this.name = name;
            this.exported = exported;
            this.authorities = authorities != null ? authorities : "";
            this.hasLauncherIntent = hasLauncherIntent;
        }
    }

    public static final class ManifestModel {
        public String packageName = "com.example.app";
        public String minSdk = "24";
        public String targetSdk = "34";
        public String appLabel = "@string/app_name";
        public String appIcon = "";
        public String appTheme = "";
        public boolean allowBackup = true;
        public boolean supportsRtl = true;
        public final List<String> permissions = new ArrayList<>();
        public final List<ManifestComponent> components = new ArrayList<>();
    }

    public static ManifestModel parseManifestModel(String manifestXml) {
        ManifestModel model = new ManifestModel();
        if (manifestXml == null || manifestXml.trim().isEmpty()) {
            return model;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(manifestXml)));
            Element root = doc.getDocumentElement();
            if (root == null) return model;

            String pkg = root.getAttribute("package");
            if (pkg != null && !pkg.isEmpty()) {
                model.packageName = pkg;
            }

            Element usesSdk = firstChildElementByTag(root, "uses-sdk");
            if (usesSdk != null) {
                String min = getAndroidOrPlainAttr(usesSdk, "minSdkVersion");
                String target = getAndroidOrPlainAttr(usesSdk, "targetSdkVersion");
                if (min != null && !min.isEmpty()) model.minSdk = min;
                if (target != null && !target.isEmpty()) model.targetSdk = target;
            }

            NodeList rootChildren = root.getChildNodes();
            for (int i = 0; i < rootChildren.getLength(); i++) {
                Node n = rootChildren.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element el = (Element) n;
                if ("uses-permission".equals(localOrTagName(el))) {
                    String perm = getAndroidOrPlainAttr(el, "name");
                    if (perm != null && !perm.isEmpty() && !model.permissions.contains(perm)) {
                        model.permissions.add(perm);
                    }
                }
            }

            Element app = firstChildElementByTag(root, "application");
            if (app != null) {
                String lbl = getAndroidOrPlainAttr(app, "label");
                if (lbl != null) model.appLabel = lbl;
                String icon = getAndroidOrPlainAttr(app, "icon");
                if (icon != null) model.appIcon = icon;
                String theme = getAndroidOrPlainAttr(app, "theme");
                if (theme != null) model.appTheme = theme;
                String backup = getAndroidOrPlainAttr(app, "allowBackup");
                model.allowBackup = backup == null || "true".equalsIgnoreCase(backup);
                String rtl = getAndroidOrPlainAttr(app, "supportsRtl");
                model.supportsRtl = rtl == null || "true".equalsIgnoreCase(rtl);

                NodeList appChildren = app.getChildNodes();
                for (int i = 0; i < appChildren.getLength(); i++) {
                    Node n = appChildren.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element el = (Element) n;
                    String tag = localOrTagName(el);
                    if ("activity".equals(tag) || "service".equals(tag)
                            || "receiver".equals(tag) || "provider".equals(tag)) {
                        String name = getAndroidOrPlainAttr(el, "name");
                        String exp = getAndroidOrPlainAttr(el, "exported");
                        String auth = getAndroidOrPlainAttr(el, "authorities");
                        boolean launcher = hasLauncherCategory(el);
                        model.components.add(new ManifestComponent(
                                tag,
                                name != null ? name : "",
                                "true".equalsIgnoreCase(exp),
                                auth != null ? auth : "",
                                launcher));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return model;
    }

    public static String applyManifestModel(String originalManifestXml, ManifestModel model) {
        if (model == null) return originalManifestXml;
        String baseXml = (originalManifestXml != null && !originalManifestXml.trim().isEmpty())
                ? originalManifestXml
                : "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<manifest xmlns:android=\"" + ANDROID_NS + "\"\n"
                        + "    package=\"" + escapeAttrValue(model.packageName) + "\">\n"
                        + "    <application android:label=\"@string/app_name\" />\n"
                        + "</manifest>\n";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(baseXml)));
            Element root = doc.getDocumentElement();
            if (root == null) return baseXml;

            if (!root.hasAttribute("xmlns:android")) {
                root.setAttribute("xmlns:android", ANDROID_NS);
            }
            if (model.packageName != null && !model.packageName.trim().isEmpty()) {
                root.setAttribute("package", model.packageName.trim());
            }

            Element usesSdk = firstChildElementByTag(root, "uses-sdk");
            if (usesSdk == null) {
                usesSdk = doc.createElement("uses-sdk");
                Node first = root.getFirstChild();
                if (first != null) root.insertBefore(usesSdk, first);
                else root.appendChild(usesSdk);
            }
            if (model.minSdk != null && !model.minSdk.trim().isEmpty()) {
                setAndroidAttr(usesSdk, "minSdkVersion", model.minSdk.trim());
            }
            if (model.targetSdk != null && !model.targetSdk.trim().isEmpty()) {
                setAndroidAttr(usesSdk, "targetSdkVersion", model.targetSdk.trim());
            }

            Element app = firstChildElementByTag(root, "application");
            if (app == null) {
                app = doc.createElement("application");
                root.appendChild(app);
            }

            // Replace <uses-permission> children cleanly before <application>
            List<Element> oldPerms = new ArrayList<>();
            NodeList rootChildren = root.getChildNodes();
            for (int i = 0; i < rootChildren.getLength(); i++) {
                Node n = rootChildren.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE
                        && "uses-permission".equals(localOrTagName((Element) n))) {
                    oldPerms.add((Element) n);
                }
            }
            for (Element old : oldPerms) {
                root.removeChild(old);
            }
            for (String perm : model.permissions) {
                if (perm == null || perm.trim().isEmpty()) continue;
                Element pEl = doc.createElement("uses-permission");
                setAndroidAttr(pEl, "name", perm.trim());
                root.insertBefore(pEl, app);
            }

            setOrRemoveAndroidAttr(app, "label", model.appLabel);
            setOrRemoveAndroidAttr(app, "icon", model.appIcon);
            setOrRemoveAndroidAttr(app, "theme", model.appTheme);
            setAndroidAttr(app, "allowBackup", String.valueOf(model.allowBackup));
            setAndroidAttr(app, "supportsRtl", String.valueOf(model.supportsRtl));

            // Match existing components by (tag, name) and update exported; add any missing components
            Map<String, Element> existingComps = new LinkedHashMap<>();
            NodeList appChildren = app.getChildNodes();
            for (int i = 0; i < appChildren.getLength(); i++) {
                Node n = appChildren.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element el = (Element) n;
                String tag = localOrTagName(el);
                if ("activity".equals(tag) || "service".equals(tag)
                        || "receiver".equals(tag) || "provider".equals(tag)) {
                    String name = getAndroidOrPlainAttr(el, "name");
                    existingComps.put(tag + ":" + (name != null ? name : ""), el);
                }
            }
            for (ManifestComponent comp : model.components) {
                if (comp.name == null || comp.name.trim().isEmpty()) continue;
                String key = comp.tag + ":" + comp.name.trim();
                Element el = existingComps.get(key);
                if (el == null) {
                    el = doc.createElement(comp.tag);
                    setAndroidAttr(el, "name", comp.name.trim());
                    if ("provider".equals(comp.tag) && !comp.authorities.isEmpty()) {
                        setAndroidAttr(el, "authorities", comp.authorities);
                    }
                    app.appendChild(el);
                }
                setAndroidAttr(el, "exported", String.valueOf(comp.exported));
            }

            return serializeDocument(doc);
        } catch (Exception e) {
            return baseXml;
        }
    }

    private static void setOrRemoveAndroidAttr(Element el, String localName, String value) {
        if (value == null || value.trim().isEmpty()) {
            el.removeAttributeNS(ANDROID_NS, localName);
            el.removeAttribute("android:" + localName);
        } else {
            setAndroidAttr(el, localName, value.trim());
        }
    }

    private static boolean hasLauncherCategory(Element componentEl) {
        NodeList children = componentEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "intent-filter".equals(localOrTagName((Element) n))) {
                NodeList sub = n.getChildNodes();
                for (int j = 0; j < sub.getLength(); j++) {
                    Node sn = sub.item(j);
                    if (sn.getNodeType() == Node.ELEMENT_NODE && "category".equals(localOrTagName((Element) sn))) {
                        String catName = getAndroidOrPlainAttr((Element) sn, "name");
                        if ("android.intent.category.LAUNCHER".equals(catName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
