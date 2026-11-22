package com.example.sandrix.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Sandrix project configuration files ({@code build.sd}) and Groovy {@code build.gradle}
 * fallback files into a typed {@link BuildSdConfig}.
 *
 * <p>Supports both classic flat {@code build.sd} syntax and standard Android Gradle blocks
 * ({@code android { ... }}, {@code defaultConfig { ... }}, {@code buildTypes { ... }},
 * {@code dependencies { ... }}, {@code repositories { ... }}), plus {@code buildConfigField},
 * {@code multiDexEnabled}, and variant suffixes.</p>
 */
public final class BuildSdParser {

    private static final Pattern KEY_INT_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:=|\\s)\\s*(\\d+)\\s*;?$");

    private static final Pattern KEY_STRING_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:=|\\s)\\s*[\"']([^\"']*)[\"']\\s*;?$");

    private static final Pattern KEY_BOOL_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:=|\\s)\\s*(true|false)\\s*;?$");

    private static final Pattern IMPLEMENTATION_PATTERN =
            Pattern.compile("^(implementation|api|compileOnly|runtimeOnly|compile)\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?\\s*;?$");

    private static final Pattern FILES_BLOCK_PATTERN =
            Pattern.compile("^(implementation|api|compileOnly|runtimeOnly|compile)\\s*\\(?\\s*files\\s*\\(([^)]+)\\)\\s*\\)?\\s*;?$");

    private static final Pattern FILE_TREE_PATTERN =
            Pattern.compile("^(implementation|api|compileOnly|runtimeOnly|compile)\\s*\\(?\\s*fileTree\\s*\\(([^)]+)\\)\\s*\\)?\\s*;?$");

    private static final Pattern BUILD_CONFIG_FIELD_PATTERN =
            Pattern.compile("^buildConfigField\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*,\\s*[\"']([^\"']+)[\"']\\s*,\\s*(.+?)\\)?\\s*;?$");

    private static final Pattern JAVA_VERSION_DIRECTIVE_PATTERN =
            Pattern.compile("^(?:javaVersion|sourceCompatibility|targetCompatibility)\\s*(?:=|\\s)\\s*(.+?)\\s*;?$");

    private static final Pattern PROGUARD_FILES_PATTERN =
            Pattern.compile("^(?:proguardFiles|proguardFile|consumerProguardFiles)\\b\\s*(.+?)\\s*;?$");

    private static final Pattern QUOTED_TOKEN_PATTERN =
            Pattern.compile("[\"']([^\"']+)[\"']");

    private static final Pattern MAVEN_URL_PATTERN =
            Pattern.compile("^(?:maven|url)\\s*(?:\\(|\\{)?\\s*(?:url\\s*=?)?\\s*[\"']([^\"']+)[\"']\\s*(?:\\)|\\})?\\s*;?$");

    private BuildSdParser() {}

    public static BuildSdConfig parseProjectConfig(File projectRoot) throws IOException {
        if (projectRoot == null) {
            return parseString("");
        }
        File sdFile = new File(projectRoot, "build.sd");
        if (sdFile.exists() && sdFile.isFile()) {
            return parse(sdFile);
        }
        File gradleFile = new File(projectRoot, "build.gradle");
        if (gradleFile.exists() && gradleFile.isFile()) {
            return parse(gradleFile);
        }
        File appGradleFile = new File(projectRoot, "app/build.gradle");
        if (appGradleFile.exists() && appGradleFile.isFile()) {
            return parse(appGradleFile);
        }
        return parseString("");
    }

    public static BuildSdConfig parse(File buildSdFile) throws IOException {
        if (buildSdFile == null || !buildSdFile.exists()) {
            throw new IOException("build.sd not found: " + (buildSdFile != null ? buildSdFile.getAbsolutePath() : "null"));
        }
        return parseString(readUtf8(buildSdFile));
    }

    public static BuildSdConfig parseString(String rawContent) {
        int compileSdk = 36;
        int minSdk = 24;
        int targetSdk = 34;
        int versionCode = 1;
        String versionName = "1.0";
        String namespace = "";
        String applicationId = "";
        boolean multiDexEnabled = true;
        String javaVersion = "1.8";
        String debugAppIdSuffix = "";
        String debugVersionSuffix = "";
        String releaseAppIdSuffix = "";
        String releaseVersionSuffix = "";
        boolean debugMinifyEnabled = false;
        boolean releaseMinifyEnabled = false;
        boolean releaseShrinkResources = false;
        boolean optimizeEnabled = true;
        List<String> proguardFiles = new ArrayList<>();
        List<DependencySpec> dependencies = new ArrayList<>();
        List<LocalFileSpec> localFiles = new ArrayList<>();
        List<String> repositories = new ArrayList<>();
        List<BuildConfigFieldSpec> buildConfigFields = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();

        if (rawContent == null || rawContent.trim().isEmpty()) {
            warnings.add(new ParseWarning(1, "build.sd is empty; using Sandrix default SDK configuration."));
            return new BuildSdConfig(compileSdk, minSdk, targetSdk, versionCode, versionName,
                    namespace, applicationId, multiDexEnabled, javaVersion,
                    debugAppIdSuffix, debugVersionSuffix, releaseAppIdSuffix, releaseVersionSuffix,
                    debugMinifyEnabled, releaseMinifyEnabled, releaseShrinkResources, optimizeEnabled,
                    proguardFiles, dependencies, localFiles, repositories, buildConfigFields, warnings);
        }

        String sanitized = stripBlockComments(rawContent);
        String[] lines = sanitized.split("\\r?\\n");
        List<String> blockStack = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = stripLineComment(lines[i]).trim();
            if (line.isEmpty()) {
                continue;
            }

            // Skip Groovy/Gradle plugin boilerplate lines gracefully
            if (line.startsWith("id ") || line.startsWith("id(")
                    || line.startsWith("apply ")
                    || line.startsWith("testInstrumentationRunner")
                    || line.startsWith("excludes ")
                    || line.startsWith("isCrunchPngs")) {
                continue;
            }

            Matcher javaVerMatcher = JAVA_VERSION_DIRECTIVE_PATTERN.matcher(line);
            if (javaVerMatcher.matches()) {
                String parsedVer = normalizeJavaVersionToken(javaVerMatcher.group(1));
                if (parsedVer != null) {
                    javaVersion = parsedVer;
                } else {
                    warnings.add(new ParseWarning(lineNumber, "Unsupported Java version directive: " + line));
                }
                continue;
            }

            Matcher proguardMatcher = PROGUARD_FILES_PATTERN.matcher(line);
            if (proguardMatcher.matches()) {
                String args = proguardMatcher.group(1);
                if (args != null && args.contains("proguard-android.txt") && !args.contains("proguard-android-optimize.txt")) {
                    optimizeEnabled = false;
                } else if (args != null && args.contains("proguard-android-optimize.txt")) {
                    optimizeEnabled = true;
                }
                Matcher qm = QUOTED_TOKEN_PATTERN.matcher(args != null ? args : "");
                while (qm.find()) {
                    String path = qm.group(1);
                    if (path != null && !path.trim().isEmpty()) {
                        String trimmed = path.trim();
                        if (!trimmed.startsWith("proguard-android")) {
                            if (!proguardFiles.contains(trimmed)) {
                                proguardFiles.add(trimmed);
                            }
                        }
                    }
                }
                continue;
            }

            if (line.endsWith("{")) {
                String blockName = line.substring(0, line.length() - 1).trim();
                int spaceIdx = blockName.indexOf('(');
                if (spaceIdx > 0) blockName = blockName.substring(0, spaceIdx).trim();
                blockStack.add(blockName.toLowerCase(Locale.ROOT));
                continue;
            }

            if (line.equals("}")) {
                if (!blockStack.isEmpty()) {
                    blockStack.remove(blockStack.size() - 1);
                }
                continue;
            }

            String currentBlock = blockStack.isEmpty() ? "" : blockStack.get(blockStack.size() - 1);
            boolean insideDependencies = blockStack.contains("dependencies");
            boolean insideRepositories = blockStack.contains("repositories");

            if (insideRepositories) {
                Matcher urlMatcher = MAVEN_URL_PATTERN.matcher(line);
                if (urlMatcher.matches()) {
                    String url = urlMatcher.group(1);
                    if (url != null && !url.trim().isEmpty()) {
                        repositories.add(url.trim());
                    }
                } else if (!line.startsWith("google") && !line.startsWith("mavenCentral") && !line.startsWith("jitpack")) {
                    warnings.add(new ParseWarning(lineNumber, "Unrecognized repository entry: " + line));
                }
                continue;
            }

            if (insideDependencies) {
                if (line.startsWith("testImplementation") || line.startsWith("androidTestImplementation")) {
                    continue;
                }
                Matcher filesMatcher = FILES_BLOCK_PATTERN.matcher(line);
                if (filesMatcher.matches()) {
                    String configType = filesMatcher.group(1);
                    String args = filesMatcher.group(2);
                    Matcher qm = QUOTED_TOKEN_PATTERN.matcher(args != null ? args : "");
                    int added = 0;
                    while (qm.find()) {
                        String path = qm.group(1);
                        if (path != null && !path.trim().isEmpty()) {
                            localFiles.add(new LocalFileSpec(path.trim(), configType, lineNumber, false));
                            added++;
                        }
                    }
                    if (added == 0) {
                        warnings.add(new ParseWarning(lineNumber, "Empty files(...) dependency declaration."));
                    }
                    continue;
                }

                Matcher treeMatcher = FILE_TREE_PATTERN.matcher(line);
                if (treeMatcher.matches()) {
                    String configType = treeMatcher.group(1);
                    String args = treeMatcher.group(2);
                    String dir = extractFileTreeDir(args);
                    if (dir != null && !dir.isEmpty()) {
                        localFiles.add(new LocalFileSpec(dir, configType, lineNumber, true));
                    } else {
                        warnings.add(new ParseWarning(lineNumber, "Could not parse directory in fileTree(...): " + line));
                    }
                    continue;
                }

                Matcher depMatcher = IMPLEMENTATION_PATTERN.matcher(line);
                if (depMatcher.matches()) {
                    String configType = depMatcher.group(1);
                    String coordinate = depMatcher.group(2);
                    if (coordinate != null) {
                        String trimmedCoord = coordinate.trim();
                        String lowerCoord = trimmedCoord.toLowerCase(Locale.ROOT);
                        if (lowerCoord.endsWith(".jar") || lowerCoord.endsWith(".aar")) {
                            localFiles.add(new LocalFileSpec(trimmedCoord, configType, lineNumber, false));
                        } else {
                            DependencySpec spec = DependencySpec.parse(trimmedCoord, lineNumber, configType);
                            if (spec != null) {
                                dependencies.add(spec);
                            } else {
                                warnings.add(new ParseWarning(lineNumber,
                                        "Malformed Maven coordinate '" + coordinate + "'. Expected group:artifact:version."));
                            }
                        }
                    }
                } else {
                    warnings.add(new ParseWarning(lineNumber,
                            "Unrecognized dependency entry: " + line));
                }
                continue;
            }

            Matcher bcfMatcher = BUILD_CONFIG_FIELD_PATTERN.matcher(line);
            if (bcfMatcher.matches()) {
                String type = bcfMatcher.group(1).trim();
                String name = bcfMatcher.group(2).trim();
                String value = bcfMatcher.group(3).trim();
                buildConfigFields.add(new BuildConfigFieldSpec(type, name, value));
                continue;
            }

            Matcher boolMatcher = KEY_BOOL_PATTERN.matcher(line);
            if (boolMatcher.matches()) {
                String key = boolMatcher.group(1);
                boolean val = Boolean.parseBoolean(boolMatcher.group(2));
                if ("multiDexEnabled".equals(key)) {
                    multiDexEnabled = val;
                    continue;
                } else if ("minifyEnabled".equals(key) || "isMinifyEnabled".equals(key)) {
                    if ("debug".equals(currentBlock)) {
                        debugMinifyEnabled = val;
                    } else {
                        releaseMinifyEnabled = val;
                    }
                    continue;
                } else if ("shrinkResources".equals(key) || "isShrinkResources".equals(key)) {
                    if ("release".equals(currentBlock) || currentBlock.isEmpty()) {
                        releaseShrinkResources = val;
                    }
                    continue;
                } else if ("optimizeEnabled".equals(key) || "r8FullMode".equals(key)) {
                    optimizeEnabled = val;
                    continue;
                } else if ("viewBinding".equals(key) || "buildConfig".equals(key)
                        || "useAndroidX".equals(key) || "includeInApk".equals(key)) {
                    continue;
                }
            }

            Matcher intMatcher = KEY_INT_PATTERN.matcher(line);
            if (intMatcher.matches()) {
                String key = intMatcher.group(1);
                String val = intMatcher.group(2);
                if (key != null && val != null) {
                    int parsedInt = Integer.parseInt(val);
                    switch (key) {
                        case "compileSdk":
                        case "compileSdkVersion":
                            compileSdk = parsedInt;
                            break;
                        case "minSdk":
                        case "minSdkVersion":
                            minSdk = parsedInt;
                            break;
                        case "targetSdk":
                        case "targetSdkVersion":
                            targetSdk = parsedInt;
                            break;
                        case "versionCode":
                            versionCode = parsedInt;
                            break;
                        default:
                            warnings.add(new ParseWarning(lineNumber, "Unknown numeric property '" + key + "'"));
                            break;
                    }
                }
                continue;
            }

            Matcher strMatcher = KEY_STRING_PATTERN.matcher(line);
            if (strMatcher.matches()) {
                String key = strMatcher.group(1);
                String val = strMatcher.group(2);
                if (key != null && val != null) {
                    switch (key) {
                        case "versionName":
                            versionName = val;
                            break;
                        case "namespace":
                            namespace = val;
                            break;
                        case "applicationId":
                            applicationId = val;
                            break;
                        case "applicationIdSuffix":
                            if ("debug".equals(currentBlock)) debugAppIdSuffix = val;
                            else if ("release".equals(currentBlock)) releaseAppIdSuffix = val;
                            break;
                        case "versionNameSuffix":
                            if ("debug".equals(currentBlock)) debugVersionSuffix = val;
                            else if ("release".equals(currentBlock)) releaseVersionSuffix = val;
                            break;
                        default:
                            warnings.add(new ParseWarning(lineNumber, "Unknown string property '" + key + "'"));
                            break;
                    }
                }
                continue;
            }

            // Do not warn on harmless Gradle packaging/resources/compileOptions lines inside blocks
            if (!blockStack.isEmpty() && ("packaging".equals(currentBlock)
                    || "packagingoptions".equals(currentBlock)
                    || "resources".equals(currentBlock)
                    || "signingconfigs".equals(currentBlock)
                    || "compileoptions".equals(currentBlock)
                    || "lintoptions".equals(currentBlock))) {
                continue;
            }

            warnings.add(new ParseWarning(lineNumber, "Unrecognized build.sd directive: " + line));
        }

        if (minSdk > targetSdk) {
            warnings.add(new ParseWarning(1,
                    "minSdk (" + minSdk + ") is greater than targetSdk (" + targetSdk + ")."));
        }
        if (targetSdk > compileSdk) {
            warnings.add(new ParseWarning(1,
                    "targetSdk (" + targetSdk + ") exceeds compileSdk (" + compileSdk + ")."));
        }

        return new BuildSdConfig(
                compileSdk,
                minSdk,
                targetSdk,
                versionCode,
                versionName,
                namespace,
                applicationId,
                multiDexEnabled,
                javaVersion,
                debugAppIdSuffix,
                debugVersionSuffix,
                releaseAppIdSuffix,
                releaseVersionSuffix,
                debugMinifyEnabled,
                releaseMinifyEnabled,
                releaseShrinkResources,
                optimizeEnabled,
                proguardFiles,
                dependencies,
                localFiles,
                repositories,
                buildConfigFields,
                warnings
        );
    }

    public static String normalizeJavaVersionToken(String raw) {
        if (raw == null) return null;
        String token = raw.trim()
                .replace("\"", "")
                .replace("'", "")
                .trim();
        if (token.isEmpty()) return null;
        String upper = token.toUpperCase(Locale.ROOT);
        if (upper.equals("1.8") || upper.equals("8") || upper.endsWith("VERSION_1_8") || upper.endsWith("VERSION_8")) {
            return "1.8";
        }
        if (upper.equals("11") || upper.endsWith("VERSION_11")) {
            return "11";
        }
        if (upper.equals("17") || upper.endsWith("VERSION_17")) {
            return "17";
        }
        if (upper.startsWith("JAVAVERSION.VERSION_")) {
            String suffix = upper.substring("JAVAVERSION.VERSION_".length()).replace('_', '.');
            return normalizeJavaVersionToken(suffix);
        }
        if (upper.startsWith("VERSION_")) {
            String suffix = upper.substring("VERSION_".length()).replace('_', '.');
            return normalizeJavaVersionToken(suffix);
        }
        try {
            int ver = Integer.parseInt(token);
            if (ver >= 9 && ver <= 17) {
                return String.valueOf(ver);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /**
     * Adds or updates a dependency entry inside {@code build.sd} for Phase 4 Dependency Management.
     */
    public static void addOrUpdateDependency(File buildSdFile, String configuration, String coordinate) throws IOException {
        String existing = buildSdFile.exists() ? readUtf8(buildSdFile) : "";
        String config = (configuration == null || configuration.trim().isEmpty()) ? "implementation" : configuration.trim();
        String newLine = "    " + config + "(\"" + coordinate.trim() + "\")";

        String[] ga = coordinate.trim().split(":");
        String gaPrefix = (ga.length >= 2) ? (ga[0] + ":" + ga[1] + ":") : coordinate.trim();

        String[] lines = existing.split("\\r?\\n", -1);
        List<String> out = new ArrayList<>();
        boolean inDeps = false;
        boolean replaced = false;
        int depsEndIdx = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("dependencies") && trimmed.contains("{")) {
                inDeps = true;
            } else if (inDeps && trimmed.equals("}")) {
                depsEndIdx = out.size();
                inDeps = false;
            } else if (inDeps && trimmed.contains(gaPrefix)) {
                out.add(newLine);
                replaced = true;
                continue;
            }
            out.add(lines[i]);
        }

        if (!replaced) {
            if (depsEndIdx >= 0) {
                out.add(depsEndIdx, newLine);
            } else {
                if (!existing.isEmpty() && !existing.endsWith("\n")) {
                    out.add("");
                }
                out.add("dependencies {");
                out.add(newLine);
                out.add("}");
            }
        }
        writeUtf8(buildSdFile, String.join("\n", out) + "\n");
    }

    /**
     * Removes a matching Maven coordinate from {@code build.sd}.
     */
    public static boolean removeDependency(File buildSdFile, String coordinate) throws IOException {
        if (!buildSdFile.exists() || coordinate == null) return false;
        String[] lines = readUtf8(buildSdFile).split("\\r?\\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean removed = false;
        for (String line : lines) {
            if (!removed && line.contains(coordinate)) {
                removed = true;
                continue;
            }
            out.add(line);
        }
        if (removed) {
            writeUtf8(buildSdFile, String.join("\n", out) + "\n");
        }
        return removed;
    }

    private static String extractFileTreeDir(String args) {
        if (args == null) return null;
        Matcher dirNamed = Pattern.compile("dir\\s*[:=]\\s*[\"']([^\"']+)[\"']").matcher(args);
        if (dirNamed.find()) {
            return dirNamed.group(1).trim();
        }
        Matcher firstQuoted = QUOTED_TOKEN_PATTERN.matcher(args);
        if (firstQuoted.find()) {
            return firstQuoted.group(1).trim();
        }
        return null;
    }

    private static String stripBlockComments(String input) {
        StringBuilder out = new StringBuilder(input.length());
        boolean inBlock = false;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char next = (i + 1 < input.length()) ? input.charAt(i + 1) : '\0';

            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append('\n');
                }
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                out.append(c);
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                out.append(c);
            } else if (!inDoubleQuote && !inSingleQuote && c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripLineComment(String line) {
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = (i + 1 < line.length()) ? line.charAt(i + 1) : '\0';
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (!inDoubleQuote && !inSingleQuote) {
                if ((c == '/' && next == '/') || c == '#') {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    private static String readUtf8(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static void writeUtf8(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class BuildConfigFieldSpec {
        public final String type;
        public final String name;
        public final String value;

        public BuildConfigFieldSpec(String type, String name, String value) {
            this.type = type;
            this.name = name;
            this.value = value;
        }
    }

    public static final class LocalFileSpec {
        public final String path;
        public final String configuration;
        public final int lineNumber;
        public final boolean isDirectoryTree;

        public LocalFileSpec(String path, String configuration, int lineNumber, boolean isDirectoryTree) {
            this.path = path;
            this.configuration = configuration != null ? configuration : "implementation";
            this.lineNumber = lineNumber;
            this.isDirectoryTree = isDirectoryTree;
        }

        public boolean isCompileOnly() {
            return "compileOnly".equals(configuration);
        }
    }

    public static final class DependencySpec {
        public final String group;
        public final String artifact;
        public final String version;
        public final String rawCoordinate;
        public final int lineNumber;
        public final String configuration;
        public final String packagingOverride;

        public DependencySpec(String group, String artifact, String version, String rawCoordinate, int lineNumber) {
            this(group, artifact, version, rawCoordinate, lineNumber, "implementation", null);
        }

        public DependencySpec(
                String group,
                String artifact,
                String version,
                String rawCoordinate,
                int lineNumber,
                String configuration,
                String packagingOverride) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.rawCoordinate = rawCoordinate;
            this.lineNumber = lineNumber;
            this.configuration = configuration != null ? configuration : "implementation";
            this.packagingOverride = packagingOverride;
        }

        public boolean isCompileOnly() {
            return "compileOnly".equals(configuration);
        }

        public static DependencySpec parse(String coordinate, int lineNumber) {
            return parse(coordinate, lineNumber, "implementation");
        }

        public static DependencySpec parse(String coordinate, int lineNumber, String configuration) {
            String trimmed = coordinate.trim();
            String pkgOverride = null;
            int atIdx = trimmed.indexOf('@');
            if (atIdx > 0) {
                pkgOverride = trimmed.substring(atIdx + 1).trim();
                trimmed = trimmed.substring(0, atIdx).trim();
            }
            String[] parts = trimmed.split(":");
            if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                return null;
            }
            String ver = parts.length >= 3 && !parts[2].trim().isEmpty() ? parts[2].trim() : "latest";
            return new DependencySpec(parts[0].trim(), parts[1].trim(), ver, coordinate, lineNumber, configuration, pkgOverride);
        }

        public String toJarFileName() {
            return group.replace('.', '_') + "-" + artifact + "-" + version + ".jar";
        }
    }

    public static final class ParseWarning {
        public final int lineNumber;
        public final String message;

        public ParseWarning(int lineNumber, String message) {
            this.lineNumber = lineNumber;
            this.message = message;
        }
    }

    public static final class BuildSdConfig {
        public final int compileSdk;
        public final int minSdk;
        public final int targetSdk;
        public final int versionCode;
        public final String versionName;
        public final String namespace;
        public final String applicationId;
        public final boolean multiDexEnabled;
        public final String javaVersion;
        public final String debugApplicationIdSuffix;
        public final String debugVersionNameSuffix;
        public final String releaseApplicationIdSuffix;
        public final String releaseVersionNameSuffix;
        public final boolean debugMinifyEnabled;
        public final boolean releaseMinifyEnabled;
        public final boolean releaseShrinkResources;
        public final boolean optimizeEnabled;
        public final List<String> proguardFiles;
        public final List<DependencySpec> dependencies;
        public final List<LocalFileSpec> localFiles;
        public final List<String> repositories;
        public final List<BuildConfigFieldSpec> buildConfigFields;
        public final List<ParseWarning> warnings;

        public BuildSdConfig(
                int compileSdk,
                int minSdk,
                int targetSdk,
                int versionCode,
                String versionName,
                String namespace,
                String applicationId,
                List<DependencySpec> dependencies,
                List<ParseWarning> warnings) {
            this(compileSdk, minSdk, targetSdk, versionCode, versionName, namespace, applicationId,
                    true, "17", "", "", "", "", false, false, false, true, Collections.emptyList(),
                    dependencies, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), warnings);
        }

        public BuildSdConfig(
                int compileSdk,
                int minSdk,
                int targetSdk,
                int versionCode,
                String versionName,
                String namespace,
                String applicationId,
                List<DependencySpec> dependencies,
                List<String> repositories,
                List<ParseWarning> warnings) {
            this(compileSdk, minSdk, targetSdk, versionCode, versionName, namespace, applicationId,
                    true, "17", "", "", "", "", false, false, false, true, Collections.emptyList(),
                    dependencies, Collections.emptyList(), repositories, Collections.emptyList(), warnings);
        }

        public BuildSdConfig(
                int compileSdk,
                int minSdk,
                int targetSdk,
                int versionCode,
                String versionName,
                String namespace,
                String applicationId,
                List<DependencySpec> dependencies,
                List<LocalFileSpec> localFiles,
                List<String> repositories,
                List<ParseWarning> warnings) {
            this(compileSdk, minSdk, targetSdk, versionCode, versionName, namespace, applicationId,
                    true, "17", "", "", "", "", false, false, false, true, Collections.emptyList(),
                    dependencies, localFiles, repositories, Collections.emptyList(), warnings);
        }

        public BuildSdConfig(
                int compileSdk,
                int minSdk,
                int targetSdk,
                int versionCode,
                String versionName,
                String namespace,
                String applicationId,
                boolean multiDexEnabled,
                String debugApplicationIdSuffix,
                String debugVersionNameSuffix,
                String releaseApplicationIdSuffix,
                String releaseVersionNameSuffix,
                boolean releaseMinifyEnabled,
                List<DependencySpec> dependencies,
                List<LocalFileSpec> localFiles,
                List<String> repositories,
                List<BuildConfigFieldSpec> buildConfigFields,
                List<ParseWarning> warnings) {
            this(compileSdk, minSdk, targetSdk, versionCode, versionName, namespace, applicationId,
                    multiDexEnabled, "17", debugApplicationIdSuffix, debugVersionNameSuffix,
                    releaseApplicationIdSuffix, releaseVersionNameSuffix,
                    false, releaseMinifyEnabled, false, true, Collections.emptyList(),
                    dependencies, localFiles, repositories, buildConfigFields, warnings);
        }

        public BuildSdConfig(
                int compileSdk,
                int minSdk,
                int targetSdk,
                int versionCode,
                String versionName,
                String namespace,
                String applicationId,
                boolean multiDexEnabled,
                String javaVersion,
                String debugApplicationIdSuffix,
                String debugVersionNameSuffix,
                String releaseApplicationIdSuffix,
                String releaseVersionNameSuffix,
                boolean debugMinifyEnabled,
                boolean releaseMinifyEnabled,
                boolean releaseShrinkResources,
                boolean optimizeEnabled,
                List<String> proguardFiles,
                List<DependencySpec> dependencies,
                List<LocalFileSpec> localFiles,
                List<String> repositories,
                List<BuildConfigFieldSpec> buildConfigFields,
                List<ParseWarning> warnings) {
            this.compileSdk = compileSdk;
            this.minSdk = minSdk;
            this.targetSdk = targetSdk;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.namespace = namespace;
            this.applicationId = applicationId;
            this.multiDexEnabled = multiDexEnabled;
            this.javaVersion = (javaVersion != null && !javaVersion.isEmpty()) ? javaVersion : "17";
            this.debugApplicationIdSuffix = debugApplicationIdSuffix != null ? debugApplicationIdSuffix : "";
            this.debugVersionNameSuffix = debugVersionNameSuffix != null ? debugVersionNameSuffix : "";
            this.releaseApplicationIdSuffix = releaseApplicationIdSuffix != null ? releaseApplicationIdSuffix : "";
            this.releaseVersionNameSuffix = releaseVersionNameSuffix != null ? releaseVersionNameSuffix : "";
            this.debugMinifyEnabled = debugMinifyEnabled;
            this.releaseMinifyEnabled = releaseMinifyEnabled;
            this.releaseShrinkResources = releaseShrinkResources;
            this.optimizeEnabled = optimizeEnabled;
            this.proguardFiles = Collections.unmodifiableList(new ArrayList<>(
                    proguardFiles != null ? proguardFiles : Collections.emptyList()));
            this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
            this.localFiles = Collections.unmodifiableList(new ArrayList<>(localFiles));
            this.repositories = Collections.unmodifiableList(new ArrayList<>(repositories));
            this.buildConfigFields = Collections.unmodifiableList(new ArrayList<>(
                    buildConfigFields != null ? buildConfigFields : Collections.emptyList()));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public boolean isMinifyEnabledForVariant(boolean releaseBuild) {
            return releaseBuild ? releaseMinifyEnabled : debugMinifyEnabled;
        }

        public String effectiveApplicationId(String manifestPackage, boolean releaseBuild) {
            String base = (applicationId != null && !applicationId.isEmpty())
                    ? applicationId
                    : ((namespace != null && !namespace.isEmpty()) ? namespace : manifestPackage);
            if (base == null) base = "";
            String suffix = releaseBuild ? releaseApplicationIdSuffix : debugApplicationIdSuffix;
            return (suffix != null && !suffix.isEmpty()) ? base + suffix : base;
        }

        public String effectiveVersionName(boolean releaseBuild) {
            String base = versionName != null ? versionName : "1.0";
            String suffix = releaseBuild ? releaseVersionNameSuffix : debugVersionNameSuffix;
            return (suffix != null && !suffix.isEmpty()) ? base + suffix : base;
        }

        public String toSummary() {
            return "compileSdk=" + compileSdk
                    + ", minSdk=" + minSdk
                    + ", targetSdk=" + targetSdk
                    + ", java=" + javaVersion
                    + ", version=" + versionName + " (" + versionCode + ")"
                    + (releaseMinifyEnabled ? ", R8" : "")
                    + ", deps=" + (dependencies.size() + localFiles.size());
        }
    }
}
