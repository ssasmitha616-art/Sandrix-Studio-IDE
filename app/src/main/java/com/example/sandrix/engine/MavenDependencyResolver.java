package com.example.sandrix.engine;

import com.example.sandrix.config.BuildSdParser;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Resolves and caches Maven/Google/JitPack JAR and AAR dependencies declared in {@code build.sd},
 * including transitive POM traversal, BOM imports, version conflict resolution, and AAR unpacking.
 */
public final class MavenDependencyResolver {

    public static final String REPO_GOOGLE = "https://dl.google.com/dl/android/maven2/";
    public static final String REPO_MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    public static final String REPO_JITPACK = "https://jitpack.io/";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_ARTIFACTS = 220;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private final File cacheDir;
    private final List<String> customRepositories;

    public MavenDependencyResolver(File cacheDir, List<String> customRepositories) {
        this.cacheDir = cacheDir;
        if (!this.cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            this.cacheDir.mkdirs();
        }
        List<String> repos = new ArrayList<>();
        if (customRepositories != null) {
            for (String repo : customRepositories) {
                String normalized = normalizeRepoUrl(repo);
                if (!normalized.isEmpty() && !repos.contains(normalized)) {
                    repos.add(normalized);
                }
            }
        }
        this.customRepositories = Collections.unmodifiableList(repos);
    }

    public ResolvedDependencies resolve(
            File projectDir,
            BuildSdParser.BuildSdConfig config,
            ProgressListener listener) throws ResolutionException, IOException {

        List<File> compileJars = new ArrayList<>();
        List<File> dexJars = new ArrayList<>();
        List<File> aarResDirs = new ArrayList<>();
        List<File> aarAssetsDirs = new ArrayList<>();
        List<File> jniDirs = new ArrayList<>();
        List<File> aarManifests = new ArrayList<>();
        List<File> proguardRuleFiles = new ArrayList<>();
        Set<String> aarPackages = new LinkedHashSet<>();
        Set<String> seenCompilePaths = new HashSet<>();
        Set<String> seenDexPaths = new HashSet<>();
        Set<String> seenJniDirs = new HashSet<>();
        Set<String> seenProguardPaths = new HashSet<>();
        int[] stats = new int[2]; // [0] = downloaded, [1] = cached

        File localAarsRoot = new File(projectDir, "build/intermediates/local_aars");

        // 1. Scan manual libs/*.jar, libs/*.aar, src/main/libs/*, and project jniLibs/
        collectLocalArchiveDirectory(new File(projectDir, "libs"), false, localAarsRoot,
                compileJars, dexJars, aarResDirs, aarAssetsDirs, jniDirs, aarManifests, proguardRuleFiles,
                aarPackages, seenCompilePaths, seenDexPaths, seenJniDirs, seenProguardPaths);
        collectLocalArchiveDirectory(new File(projectDir, "src/main/libs"), false, localAarsRoot,
                compileJars, dexJars, aarResDirs, aarAssetsDirs, jniDirs, aarManifests, proguardRuleFiles,
                aarPackages, seenCompilePaths, seenDexPaths, seenJniDirs, seenProguardPaths);
        collectJniDirIfNotEmpty(new File(projectDir, "jniLibs"), jniDirs, seenJniDirs);
        collectJniDirIfNotEmpty(new File(projectDir, "src/main/jniLibs"), jniDirs, seenJniDirs);

        // 2. Process explicit files(...) / fileTree(...) / direct .jar/.aar paths from build.sd
        List<BuildSdParser.LocalFileSpec> explicitLocalFiles =
                config != null ? config.localFiles : Collections.emptyList();
        for (BuildSdParser.LocalFileSpec localSpec : explicitLocalFiles) {
            if (isCancelled(listener)) {
                throw new ResolutionException("Dependency resolution cancelled.", null, localSpec.lineNumber);
            }
            File target = resolveLocalDependencyTarget(projectDir, localSpec.path);
            if (localSpec.isDirectoryTree) {
                if (target.exists() && target.isDirectory()) {
                    collectLocalArchiveDirectory(target, localSpec.isCompileOnly(), localAarsRoot,
                            compileJars, dexJars, aarResDirs, aarAssetsDirs, jniDirs, aarManifests, proguardRuleFiles,
                            aarPackages, seenCompilePaths, seenDexPaths, seenJniDirs, seenProguardPaths);
                }
            } else {
                if (!target.exists() || !target.isFile()) {
                    throw new ResolutionException("Local dependency file not found: " + localSpec.path, null, localSpec.lineNumber);
                }
                addLocalArchiveFile(target, localSpec.isCompileOnly(), localAarsRoot,
                        compileJars, dexJars, aarResDirs, aarAssetsDirs, jniDirs, aarManifests, proguardRuleFiles,
                        aarPackages, seenCompilePaths, seenDexPaths, seenJniDirs, seenProguardPaths);
            }
        }

        List<BuildSdParser.DependencySpec> rootSpecs = config != null ? config.dependencies : Collections.emptyList();
        if (rootSpecs.isEmpty()) {
            log(listener,
                    "[DEPS] Ready: " + compileJars.size() + " JAR(s), "
                            + aarResDirs.size() + " AAR resource bundle(s).",
                    false);
            return new ResolvedDependencies(compileJars, dexJars, aarResDirs, aarAssetsDirs,
                    jniDirs, aarManifests, proguardRuleFiles, new ArrayList<>(aarPackages), stats[0], stats[1]);
        }

        log(listener, "[DEPS] Resolving " + rootSpecs.size() + " dependency(ies) from build.sd...", false);

        Map<String, SelectedArtifact> selectedByGa = new LinkedHashMap<>();
        Set<String> visitedGav = new HashSet<>();
        Deque<QueueItem> queue = new ArrayDeque<>();

        for (BuildSdParser.DependencySpec spec : rootSpecs) {
            if (isCancelled(listener)) {
                throw new ResolutionException("Dependency resolution cancelled.", spec);
            }
            String resolvedVersion = resolveRequestedVersion(spec.group, spec.artifact, spec.version, spec, listener);
            queue.add(new QueueItem(
                    spec.group,
                    spec.artifact,
                    resolvedVersion,
                    spec.isCompileOnly(),
                    spec.packagingOverride,
                    Collections.emptySet(),
                    0,
                    spec
            ));
        }

        while (!queue.isEmpty()) {
            if (isCancelled(listener)) {
                throw new ResolutionException("Dependency resolution cancelled.", null);
            }
            QueueItem item = queue.poll();
            if (item == null || item.depth > MAX_DEPTH) {
                continue;
            }
            String ga = item.group + ":" + item.artifact;
            if (isExcluded(item.exclusions, item.group, item.artifact)) {
                continue;
            }

            SelectedArtifact existing = selectedByGa.get(ga);
            if (existing != null) {
                int cmp = compareVersions(item.version, existing.version);
                if (cmp < 0) {
                    if (!item.compileOnly && existing.compileOnly) {
                        existing.compileOnly = false;
                    }
                    continue;
                } else if (cmp == 0) {
                    if (!item.compileOnly && existing.compileOnly) {
                        existing.compileOnly = false;
                    }
                    continue;
                }
            } else if (selectedByGa.size() >= MAX_ARTIFACTS) {
                log(listener, "[DEPS WARN] Reached dependency limit (" + MAX_ARTIFACTS + "); skipping " + ga, false);
                continue;
            }

            String gav = ga + ":" + item.version;
            if (!visitedGav.add(gav)) {
                continue;
            }

            PomModel pom = fetchAndParsePom(item.group, item.artifact, item.version, new HashSet<>(), stats, listener, item.originSpec);

            // Handle Maven POM relocation if present
            if (pom != null && pom.relocatedGroup != null && pom.relocatedArtifact != null) {
                String relocVer = pom.relocatedVersion != null ? pom.relocatedVersion : item.version;
                queue.addFirst(new QueueItem(
                        pom.relocatedGroup,
                        pom.relocatedArtifact,
                        relocVer,
                        item.compileOnly,
                        item.packagingOverride,
                        item.exclusions,
                        item.depth,
                        item.originSpec
                ));
                continue;
            }

            String packaging = item.packagingOverride != null
                    ? item.packagingOverride
                    : (pom != null && pom.packaging != null ? pom.packaging : "jar");

            boolean compileOnly = existing != null ? (existing.compileOnly && item.compileOnly) : item.compileOnly;
            SelectedArtifact selected = new SelectedArtifact(item.group, item.artifact, item.version, packaging, compileOnly, item.originSpec);
            selectedByGa.put(ga, selected);

            if (pom != null) {
                for (PomDependency dep : pom.dependencies) {
                    if (dep.optional) continue;
                    if (!isTransitiveScope(dep.scope)) continue;
                    if (isExcluded(item.exclusions, dep.group, dep.artifact)) continue;

                    String childGa = dep.group + ":" + dep.artifact;
                    String childVer = dep.version;
                    if (childVer == null || childVer.isEmpty()) {
                        childVer = pom.managedVersions.get(childGa);
                    }
                    if (childVer == null || childVer.isEmpty()) {
                        continue;
                    }
                    childVer = resolveVersionConstraint(dep.group, dep.artifact, childVer, listener);
                    if (childVer == null || childVer.isEmpty()) {
                        continue;
                    }

                    Set<String> childExclusions = new HashSet<>(item.exclusions);
                    childExclusions.addAll(dep.exclusions);

                    queue.add(new QueueItem(
                            dep.group,
                            dep.artifact,
                            childVer,
                            item.compileOnly,
                            dep.type != null && ("aar".equals(dep.type) || "jar".equals(dep.type)) ? dep.type : null,
                            childExclusions,
                            item.depth + 1,
                            item.originSpec
                    ));
                }
            }
        }

        pruneSupersededArtifacts(selectedByGa, listener);

        // Download / unpack all selected artifacts
        for (SelectedArtifact artifact : selectedByGa.values()) {
            if (isCancelled(listener)) {
                throw new ResolutionException("Dependency resolution cancelled.", artifact.originSpec);
            }
            if ("pom".equalsIgnoreCase(artifact.packaging)) {
                continue;
            }

            ArtifactBinary binary = fetchArtifactBinary(artifact, stats, listener);
            if (binary == null || binary.file == null || !binary.file.exists()) {
                throw new ResolutionException(
                        "Unable to download " + artifact.group + ":" + artifact.artifact + ":" + artifact.version,
                        artifact.originSpec);
            }

            if (binary.isAar) {
                File unpackDir = new File(binary.file.getParentFile(), "aar_unpacked");
                unpackAar(binary.file, unpackDir);
                collectUnpackedAar(unpackDir, artifact.compileOnly, compileJars, dexJars,
                        aarResDirs, aarAssetsDirs, jniDirs, aarManifests, proguardRuleFiles,
                        aarPackages, seenCompilePaths, seenDexPaths, seenJniDirs, seenProguardPaths);
            } else {
                addUniqueFile(compileJars, seenCompilePaths, binary.file);
                if (!artifact.compileOnly) {
                    addUniqueFile(dexJars, seenDexPaths, binary.file);
                }
            }
        }

        log(listener,
                "[DEPS] Ready: " + compileJars.size() + " JAR(s), "
                        + aarResDirs.size() + " AAR resource bundle(s) ("
                        + stats[0] + " downloaded, " + stats[1] + " cached).",
                false);

        return new ResolvedDependencies(
                compileJars,
                dexJars,
                aarResDirs,
                aarAssetsDirs,
                jniDirs,
                aarManifests,
                proguardRuleFiles,
                new ArrayList<>(aarPackages),
                stats[0],
                stats[1]
        );
    }

    private static void pruneSupersededArtifacts(
            Map<String, SelectedArtifact> selectedByGa,
            ProgressListener listener) {
        SelectedArtifact kotlinStdlib = selectedByGa.get("org.jetbrains.kotlin:kotlin-stdlib");
        if (kotlinStdlib != null && compareVersions(kotlinStdlib.version, "1.8.0") >= 0) {
            String[] mergedArtifacts = {
                    "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
                    "org.jetbrains.kotlin:kotlin-stdlib-jdk8"
            };
            for (String ga : mergedArtifacts) {
                SelectedArtifact removed = selectedByGa.remove(ga);
                if (removed != null) {
                    log(listener,
                            "[DEPS] Pruned superseded " + ga + ":" + removed.version
                                    + " (merged into kotlin-stdlib:" + kotlinStdlib.version + ")",
                            false);
                }
            }
        }

        if (selectedByGa.containsKey("com.google.guava:guava")) {
            SelectedArtifact listenable = selectedByGa.get("com.google.guava:listenablefuture");
            if (listenable != null) {
                selectedByGa.remove("com.google.guava:listenablefuture");
                log(listener,
                        "[DEPS] Pruned superseded com.google.guava:listenablefuture:"
                                + listenable.version + " (included in guava)",
                        false);
            }
        }
    }

    private static boolean isTransitiveScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) return true;
        String s = scope.trim().toLowerCase(Locale.ROOT);
        return "compile".equals(s) || "runtime".equals(s);
    }

    private static boolean isExcluded(Set<String> exclusions, String group, String artifact) {
        if (exclusions == null || exclusions.isEmpty()) return false;
        return exclusions.contains("*:*")
                || exclusions.contains(group + ":*")
                || exclusions.contains("*:" + artifact)
                || exclusions.contains(group + ":" + artifact);
    }

    private String resolveRequestedVersion(
            String group,
            String artifact,
            String requested,
            BuildSdParser.DependencySpec spec,
            ProgressListener listener) throws ResolutionException {
        String resolved = resolveVersionConstraint(group, artifact, requested, listener);
        if (resolved == null || resolved.isEmpty()) {
            throw new ResolutionException("Could not determine latest version for " + group + ":" + artifact, spec);
        }
        return resolved;
    }

    private String resolveVersionConstraint(
            String group,
            String artifact,
            String rawVersion,
            ProgressListener listener) {
        if (rawVersion == null) return null;
        String v = rawVersion.trim();
        if (v.isEmpty()) return null;

        // Single exact pinned version like [1.6.1]
        if (v.startsWith("[") && v.endsWith("]") && !v.contains(",")) {
            return v.substring(1, v.length() - 1).trim();
        }

        // Range like [1.0,2.0) or [1.0,)
        if ((v.startsWith("[") || v.startsWith("(")) && v.contains(",")) {
            String inner = v.substring(1, v.length() - 1);
            String[] bounds = inner.split(",", -1);
            String lower = bounds.length > 0 ? bounds[0].trim() : "";
            String upper = bounds.length > 1 ? bounds[1].trim() : "";
            boolean allowPre = hasQualifier(lower) || hasQualifier(upper);

            List<String> versions = fetchMetadataVersions(group, artifact);
            if (versions.isEmpty()) {
                versions = listCachedVersions(group, artifact);
            }
            if (!versions.isEmpty()) {
                Collections.sort(versions, MavenDependencyResolver::compareVersions);
                String bestPre = null;
                for (int i = versions.size() - 1; i >= 0; i--) {
                    String candidate = versions.get(i);
                    boolean geLower = lower.isEmpty() || (v.startsWith("[") ? compareVersions(candidate, lower) >= 0 : compareVersions(candidate, lower) > 0);
                    boolean leUpper = upper.isEmpty() || (v.endsWith("]") ? compareVersions(candidate, upper) <= 0 : compareVersions(candidate, upper) < 0);
                    if (geLower && leUpper) {
                        if (allowPre || !hasQualifier(candidate)) {
                            return candidate;
                        }
                        if (bestPre == null) {
                            bestPre = candidate;
                        }
                    }
                }
                if (bestPre != null) return bestPre;
            }
            return !lower.isEmpty() ? lower : upper;
        }

        // Dynamic version ("latest", "+", "1.6.+")
        if ("latest".equalsIgnoreCase(v) || "latest.release".equalsIgnoreCase(v) || v.contains("+")) {
            String prefix = v.contains("+") ? v.substring(0, v.indexOf('+')) : null;
            String metaLatest = fetchLatestFromMetadata(group, artifact, prefix);
            if (metaLatest != null) {
                log(listener, "[DEPS] Resolved " + group + ":" + artifact + " -> " + metaLatest, false);
                return metaLatest;
            }
            List<String> cached = listCachedVersions(group, artifact);
            if (!cached.isEmpty()) {
                Collections.sort(cached, MavenDependencyResolver::compareVersions);
                for (int i = cached.size() - 1; i >= 0; i--) {
                    String c = cached.get(i);
                    if (prefix == null || prefix.isEmpty() || c.startsWith(prefix)) {
                        log(listener, "[DEPS] Using cached " + group + ":" + artifact + ":" + c + " (offline)", false);
                        return c;
                    }
                }
            }
            return null;
        }

        return v;
    }

    private static boolean hasQualifier(String v) {
        if (v == null || v.isEmpty()) return false;
        String lower = v.toLowerCase(Locale.ROOT);
        return lower.contains("-alpha") || lower.contains("-beta")
                || lower.contains("-rc") || lower.contains("-snapshot")
                || lower.contains("-m");
    }

    private List<String> listCachedVersions(String group, String artifact) {
        File artifactDir = new File(cacheDir, group.replace('.', '/') + "/" + artifact);
        File[] children = artifactDir.listFiles();
        if (children == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (File c : children) {
            if (c.isDirectory()) {
                out.add(c.getName());
            }
        }
        return out;
    }

    private String fetchLatestFromMetadata(String group, String artifact, String prefix) {
        String relPath = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
        for (String repo : reposForGroup(group)) {
            byte[] bytes = httpGetBytes(repo + relPath);
            if (bytes == null) continue;
            try {
                Document doc = parseXmlBytes(bytes);
                Element root = doc.getDocumentElement();
                if (prefix == null || prefix.isEmpty()) {
                    String release = firstChildText(root, "versioning", "release");
                    if (release != null && !release.isEmpty()) return release;
                    String latest = firstChildText(root, "versioning", "latest");
                    if (latest != null && !latest.isEmpty()) return latest;
                }
                List<String> versions = extractVersionsFromMetadataDoc(root);
                Collections.sort(versions, MavenDependencyResolver::compareVersions);
                for (int i = versions.size() - 1; i >= 0; i--) {
                    String candidate = versions.get(i);
                    if ((prefix == null || prefix.isEmpty() || candidate.startsWith(prefix)) && !hasQualifier(candidate)) {
                        return candidate;
                    }
                }
                for (int i = versions.size() - 1; i >= 0; i--) {
                    String candidate = versions.get(i);
                    if (prefix == null || prefix.isEmpty() || candidate.startsWith(prefix)) {
                        return candidate;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private List<String> fetchMetadataVersions(String group, String artifact) {
        String relPath = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
        for (String repo : reposForGroup(group)) {
            byte[] bytes = httpGetBytes(repo + relPath);
            if (bytes == null) continue;
            try {
                Document doc = parseXmlBytes(bytes);
                List<String> versions = extractVersionsFromMetadataDoc(doc.getDocumentElement());
                if (!versions.isEmpty()) return versions;
            } catch (Exception ignored) {
            }
        }
        return Collections.emptyList();
    }

    private static List<String> extractVersionsFromMetadataDoc(Element root) {
        List<String> out = new ArrayList<>();
        Element versioning = firstChildElement(root, "versioning");
        if (versioning == null) return out;
        Element versionsEl = firstChildElement(versioning, "versions");
        if (versionsEl == null) return out;
        NodeList children = versionsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "version".equals(n.getNodeName())) {
                String txt = n.getTextContent().trim();
                if (!txt.isEmpty()) out.add(txt);
            }
        }
        return out;
    }

    private PomModel fetchAndParsePom(
            String group,
            String artifact,
            String version,
            Set<String> parentChainGuard,
            int[] stats,
            ProgressListener listener,
            BuildSdParser.DependencySpec originSpec) throws ResolutionException {
        String key = group + ":" + artifact + ":" + version;
        if (!parentChainGuard.add(key)) {
            return null;
        }

        File pomFile = getCachedFile(group, artifact, version, artifact + "-" + version + ".pom");
        if (!pomFile.exists() || pomFile.length() == 0) {
            String relPath = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom";
            boolean ok = downloadFromRepos(group, relPath, pomFile, listener, false);
            if (!ok) {
                throw new ResolutionException("POM not found for " + key + " in Google/Maven Central/JitPack.", originSpec);
            }
            stats[0]++;
        } else {
            stats[1]++;
        }

        try {
            Document doc = parseXmlFile(pomFile);
            Element project = doc.getDocumentElement();

            PomModel model = new PomModel();
            Element parentEl = firstChildElement(project, "parent");
            PomModel parentModel = null;
            String parentGroup = parentEl != null ? directChildText(parentEl, "groupId") : null;
            String parentArtifact = parentEl != null ? directChildText(parentEl, "artifactId") : null;
            String parentVersion = parentEl != null ? directChildText(parentEl, "version") : null;
            if (parentGroup != null && parentArtifact != null && parentVersion != null) {
                try {
                    parentModel = fetchAndParsePom(parentGroup, parentArtifact, parentVersion,
                            new HashSet<>(parentChainGuard), stats, listener, originSpec);
                } catch (ResolutionException ignored) {
                    // Best-effort parent POM resolution
                }
            }

            if (parentModel != null) {
                model.properties.putAll(parentModel.properties);
                model.managedVersions.putAll(parentModel.managedVersions);
            }

            String ownGroup = directChildText(project, "groupId");
            String ownArtifact = directChildText(project, "artifactId");
            String ownVersion = directChildText(project, "version");
            String resolvedGroup = (ownGroup != null && !ownGroup.isEmpty()) ? ownGroup : (parentGroup != null ? parentGroup : group);
            String resolvedArtifact = (ownArtifact != null && !ownArtifact.isEmpty()) ? ownArtifact : artifact;
            String resolvedVersion = (ownVersion != null && !ownVersion.isEmpty()) ? ownVersion : (parentVersion != null ? parentVersion : version);

            model.properties.put("project.groupId", resolvedGroup);
            model.properties.put("pom.groupId", resolvedGroup);
            model.properties.put("project.artifactId", resolvedArtifact);
            model.properties.put("pom.artifactId", resolvedArtifact);
            model.properties.put("project.version", resolvedVersion);
            model.properties.put("pom.version", resolvedVersion);
            model.properties.put("version", resolvedVersion);
            if (parentVersion != null) {
                model.properties.put("project.parent.version", parentVersion);
            }
            if (parentGroup != null) {
                model.properties.put("project.parent.groupId", parentGroup);
            }

            Element propsEl = firstChildElement(project, "properties");
            if (propsEl != null) {
                NodeList children = propsEl.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node n = children.item(i);
                    if (n.getNodeType() == Node.ELEMENT_NODE) {
                        model.properties.put(n.getNodeName(), n.getTextContent().trim());
                    }
                }
            }

            String pkg = directChildText(project, "packaging");
            model.packaging = (pkg != null && !pkg.isEmpty()) ? substituteProps(pkg, model.properties) : "jar";

            // Check relocation
            Element distMgmt = firstChildElement(project, "distributionManagement");
            if (distMgmt != null) {
                Element relocation = firstChildElement(distMgmt, "relocation");
                if (relocation != null) {
                    String rg = directChildText(relocation, "groupId");
                    String ra = directChildText(relocation, "artifactId");
                    String rv = directChildText(relocation, "version");
                    model.relocatedGroup = (rg != null && !rg.isEmpty()) ? substituteProps(rg, model.properties) : resolvedGroup;
                    model.relocatedArtifact = (ra != null && !ra.isEmpty()) ? substituteProps(ra, model.properties) : resolvedArtifact;
                    model.relocatedVersion = (rv != null && !rv.isEmpty()) ? substituteProps(rv, model.properties) : resolvedVersion;
                }
            }

            // Parse dependencyManagement (including BOM imports)
            Element depMgmt = firstChildElement(project, "dependencyManagement");
            if (depMgmt != null) {
                Element mgmtDeps = firstChildElement(depMgmt, "dependencies");
                if (mgmtDeps != null) {
                    for (Element d : childElements(mgmtDeps, "dependency")) {
                        String dg = substituteProps(directChildText(d, "groupId"), model.properties);
                        String da = substituteProps(directChildText(d, "artifactId"), model.properties);
                        String dv = substituteProps(directChildText(d, "version"), model.properties);
                        String scope = substituteProps(directChildText(d, "scope"), model.properties);
                        String type = substituteProps(directChildText(d, "type"), model.properties);
                        if (dg == null || da == null || dv == null) continue;
                        if ("import".equalsIgnoreCase(scope) && "pom".equalsIgnoreCase(type)) {
                            try {
                                PomModel bom = fetchAndParsePom(dg, da, dv,
                                        new HashSet<>(parentChainGuard), stats, listener, originSpec);
                                if (bom != null) {
                                    for (Map.Entry<String, String> e : bom.managedVersions.entrySet()) {
                                        if (!model.managedVersions.containsKey(e.getKey())) {
                                            model.managedVersions.put(e.getKey(), e.getValue());
                                        }
                                    }
                                }
                            } catch (ResolutionException ignored) {
                            }
                        } else {
                            String gaKey = dg + ":" + da;
                            if (!model.managedVersions.containsKey(gaKey)) {
                                model.managedVersions.put(gaKey, dv);
                            }
                        }
                    }
                }
            }

            // Parse direct dependencies
            Element depsEl = firstChildElement(project, "dependencies");
            if (depsEl != null) {
                for (Element d : childElements(depsEl, "dependency")) {
                    String dg = substituteProps(directChildText(d, "groupId"), model.properties);
                    String da = substituteProps(directChildText(d, "artifactId"), model.properties);
                    String dv = substituteProps(directChildText(d, "version"), model.properties);
                    String scope = substituteProps(directChildText(d, "scope"), model.properties);
                    String type = substituteProps(directChildText(d, "type"), model.properties);
                    String optStr = substituteProps(directChildText(d, "optional"), model.properties);
                    if (dg == null || da == null || dg.isEmpty() || da.isEmpty()) continue;
                    boolean optional = "true".equalsIgnoreCase(optStr);

                    Set<String> exclusions = new HashSet<>();
                    Element exclsEl = firstChildElement(d, "exclusions");
                    if (exclsEl != null) {
                        for (Element ex : childElements(exclsEl, "exclusion")) {
                            String eg = substituteProps(directChildText(ex, "groupId"), model.properties);
                            String ea = substituteProps(directChildText(ex, "artifactId"), model.properties);
                            if (eg != null && ea != null) {
                                exclusions.add(eg + ":" + ea);
                            }
                        }
                    }
                    model.dependencies.add(new PomDependency(dg, da, dv, scope, type, optional, exclusions));
                }
            }

            return model;
        } catch (ResolutionException re) {
            throw re;
        } catch (Exception e) {
            throw new ResolutionException("Failed parsing POM for " + key + ": " + e.getMessage(), originSpec);
        }
    }

    private ArtifactBinary fetchArtifactBinary(
            SelectedArtifact artifact,
            int[] stats,
            ProgressListener listener) {
        String baseName = artifact.artifact + "-" + artifact.version;
        String baseRel = artifact.group.replace('.', '/') + "/" + artifact.artifact + "/" + artifact.version + "/";

        boolean preferAar = "aar".equalsIgnoreCase(artifact.packaging);
        String primaryExt = preferAar ? "aar" : "jar";
        String secondaryExt = preferAar ? "jar" : "aar";

        // Check local cache first
        File primaryCache = getCachedFile(artifact.group, artifact.artifact, artifact.version, baseName + "." + primaryExt);
        if (primaryCache.exists() && primaryCache.length() > 0) {
            stats[1]++;
            return new ArtifactBinary(primaryCache, preferAar);
        }
        File secondaryCache = getCachedFile(artifact.group, artifact.artifact, artifact.version, baseName + "." + secondaryExt);
        if (secondaryCache.exists() && secondaryCache.length() > 0) {
            stats[1]++;
            return new ArtifactBinary(secondaryCache, !preferAar);
        }

        // Download primary, fallback to secondary
        log(listener, "[DEPS] Downloading " + artifact.group + ":" + artifact.artifact + ":" + artifact.version
                + " (" + primaryExt + ")...", false);
        if (downloadFromRepos(artifact.group, baseRel + baseName + "." + primaryExt, primaryCache, listener, true)) {
            stats[0]++;
            return new ArtifactBinary(primaryCache, preferAar);
        }
        if (downloadFromRepos(artifact.group, baseRel + baseName + "." + secondaryExt, secondaryCache, listener, true)) {
            stats[0]++;
            return new ArtifactBinary(secondaryCache, !preferAar);
        }
        return null;
    }

    private boolean downloadFromRepos(
            String group,
            String relativePath,
            File destination,
            ProgressListener listener,
            boolean deleteOnFail) {
        for (String repo : reposForGroup(group)) {
            if (isCancelled(listener)) return false;
            String urlStr = repo + relativePath;
            if (downloadUrlToFile(urlStr, destination)) {
                return true;
            }
        }
        if (deleteOnFail && destination.exists()) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
        }
        return false;
    }

    private List<String> reposForGroup(String group) {
        List<String> order = new ArrayList<>(customRepositories);
        if (group.startsWith("androidx.") || group.startsWith("com.google.android.") || group.startsWith("com.android.")) {
            addIfMissing(order, REPO_GOOGLE);
            addIfMissing(order, REPO_MAVEN_CENTRAL);
            addIfMissing(order, REPO_JITPACK);
        } else if (group.startsWith("com.github.")) {
            addIfMissing(order, REPO_JITPACK);
            addIfMissing(order, REPO_MAVEN_CENTRAL);
            addIfMissing(order, REPO_GOOGLE);
        } else {
            addIfMissing(order, REPO_MAVEN_CENTRAL);
            addIfMissing(order, REPO_GOOGLE);
            addIfMissing(order, REPO_JITPACK);
        }
        return order;
    }

    private static void addIfMissing(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static String normalizeRepoUrl(String url) {
        if (url == null) return "";
        String t = url.trim();
        if (t.isEmpty()) return "";
        return t.endsWith("/") ? t : t + "/";
    }

    private File getCachedFile(String group, String artifact, String version, String fileName) {
        File dir = new File(cacheDir, group.replace('.', '/') + "/" + artifact + "/" + version);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, fileName);
    }

    private boolean downloadUrlToFile(String urlStr, File dest) {
        return downloadUrlToFile(urlStr, dest, 0);
    }

    private boolean downloadUrlToFile(String urlStr, File dest, int redirectDepth) {
        if (redirectDepth > 5) return false;
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "SandrixStudioIDE/1.0");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) return false;
                URL nextUrl = new URL(url, loc);
                return downloadUrlToFile(nextUrl.toExternalForm(), dest, redirectDepth + 1);
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return false;
            }
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
            if (dest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
            return tmp.renameTo(dest);
        } catch (IOException ignored) {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private byte[] httpGetBytes(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "SandrixStudioIDE/1.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (IOException ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void unpackAar(File aarFile, File destDir) throws IOException {
        File okMarker = new File(destDir, ".unpacked_ok");
        if (okMarker.exists() && destDir.exists()) {
            return;
        }
        deleteRecursively(destDir);
        if (!destDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
        }
        String canonicalRoot = destDir.getCanonicalPath();
        if (!canonicalRoot.endsWith(File.separator)) {
            canonicalRoot += File.separator;
        }
        byte[] buf = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(aarFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                File out = new File(destDir, name);
                String canonicalOut = out.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalRoot)) {
                    throw new IOException("Blocked unsafe AAR entry: " + name);
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        try (FileOutputStream fos = new FileOutputStream(okMarker)) {
            fos.write("ok".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static File resolveLocalDependencyTarget(File projectDir, String rawPath) {
        String normalized = rawPath != null ? rawPath.trim() : "";
        if (normalized.startsWith("file://")) {
            try {
                return new File(new java.net.URI(normalized));
            } catch (Exception ignored) {
                normalized = normalized.substring("file://".length());
            }
        }
        if (normalized.contains("%")) {
            try {
                String decoded = java.net.URLDecoder.decode(
                        normalized.replace("+", "%2B"), StandardCharsets.UTF_8.name());
                File decodedCandidate = new File(decoded);
                File resolvedDecoded = decodedCandidate.isAbsolute()
                        ? decodedCandidate
                        : new File(projectDir, decoded);
                if (resolvedDecoded.exists()) {
                    return resolvedDecoded;
                }
            } catch (Exception ignored) {
            }
        }
        File direct = new File(normalized);
        return direct.isAbsolute() ? direct : new File(projectDir, normalized);
    }

    private static String sanitizeFolderComponent(String raw) {
        if (raw == null || raw.isEmpty()) return "lib";
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void collectLocalArchiveDirectory(
            File dir,
            boolean compileOnly,
            File localAarsRoot,
            List<File> compileJars,
            List<File> dexJars,
            List<File> aarResDirs,
            List<File> aarAssetsDirs,
            List<File> jniDirs,
            List<File> aarManifests,
            List<File> proguardRuleFiles,
            Set<String> aarPackages,
            Set<String> seenCompilePaths,
            Set<String> seenDexPaths,
            Set<String> seenJniDirs,
            Set<String> seenProguardPaths) throws IOException {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isFile()) {
                addLocalArchiveFile(f, compileOnly, localAarsRoot, compileJars, dexJars,
                        aarResDirs, aarAssetsDirs, jniDirs, aarManifests, proguardRuleFiles,
                        aarPackages, seenCompilePaths, seenDexPaths, seenJniDirs, seenProguardPaths);
            }
        }
    }

    private static void addLocalArchiveFile(
            File f,
            boolean compileOnly,
            File localAarsRoot,
            List<File> compileJars,
            List<File> dexJars,
            List<File> aarResDirs,
            List<File> aarAssetsDirs,
            List<File> jniDirs,
            List<File> aarManifests,
            List<File> proguardRuleFiles,
            Set<String> aarPackages,
            Set<String> seenCompilePaths,
            Set<String> seenDexPaths,
            Set<String> seenJniDirs,
            Set<String> seenProguardPaths) throws IOException {
        String lower = f.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) {
            addUniqueFile(compileJars, seenCompilePaths, f);
            if (!compileOnly) {
                addUniqueFile(dexJars, seenDexPaths, f);
            }
        } else if (lower.endsWith(".aar")) {
            String rawBase = f.getName().substring(0, f.getName().length() - 4);
            String folderName = sanitizeFolderComponent(rawBase) + "_" + (f.getAbsolutePath().hashCode() & 0x7fffffff);
            File unpackDir = new File(localAarsRoot, folderName);
            unpackAar(f, unpackDir);
            collectUnpackedAar(unpackDir, compileOnly, compileJars, dexJars,
                    aarResDirs, aarAssetsDirs, jniDirs, aarManifests, proguardRuleFiles,
                    aarPackages, seenCompilePaths, seenDexPaths, seenJniDirs, seenProguardPaths);
        }
    }

    private static void collectJniDirIfNotEmpty(File jniDir, List<File> jniDirs, Set<String> seenJniDirs) {
        if (jniDir != null && jniDir.exists() && jniDir.isDirectory() && hasAnyFile(jniDir)) {
            addUniqueFile(jniDirs, seenJniDirs, jniDir);
        }
    }

    private static void collectUnpackedAar(
            File unpackDir,
            boolean compileOnly,
            List<File> compileJars,
            List<File> dexJars,
            List<File> aarResDirs,
            List<File> aarAssetsDirs,
            List<File> jniDirs,
            List<File> aarManifests,
            List<File> proguardRuleFiles,
            Set<String> aarPackages,
            Set<String> seenCompilePaths,
            Set<String> seenDexPaths,
            Set<String> seenJniDirs,
            Set<String> seenProguardPaths) {
        File classesJar = new File(unpackDir, "classes.jar");
        if (classesJar.exists() && classesJar.length() > 0) {
            addUniqueFile(compileJars, seenCompilePaths, classesJar);
            if (!compileOnly) {
                addUniqueFile(dexJars, seenDexPaths, classesJar);
            }
        } else {
            File apiJar = new File(unpackDir, "api.jar");
            if (apiJar.exists() && apiJar.length() > 0) {
                addUniqueFile(compileJars, seenCompilePaths, apiJar);
            }
        }
        File embeddedLibs = new File(unpackDir, "libs");
        if (embeddedLibs.exists() && embeddedLibs.isDirectory()) {
            File[] jars = embeddedLibs.listFiles();
            if (jars != null) {
                java.util.Arrays.sort(jars);
                for (File jar : jars) {
                    if (jar.isFile() && jar.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        addUniqueFile(compileJars, seenCompilePaths, jar);
                        if (!compileOnly) {
                            addUniqueFile(dexJars, seenDexPaths, jar);
                        }
                    }
                }
            }
        }

        if (!compileOnly) {
            File resDir = new File(unpackDir, "res");
            if (resDir.exists() && resDir.isDirectory() && hasAnyFile(resDir)) {
                aarResDirs.add(resDir);
            }
            File assetsDir = new File(unpackDir, "assets");
            if (assetsDir.exists() && assetsDir.isDirectory() && hasAnyFile(assetsDir)) {
                aarAssetsDirs.add(assetsDir);
            }
            collectJniDirIfNotEmpty(new File(unpackDir, "jni"), jniDirs, seenJniDirs);
            File manifest = new File(unpackDir, "AndroidManifest.xml");
            if (manifest.exists()) {
                aarManifests.add(manifest);
                String pkg = readPackageFromManifest(manifest);
                if (pkg != null && !pkg.isEmpty()) {
                    aarPackages.add(pkg);
                }
            }
            for (String pgName : new String[]{"proguard.txt", "consumer-rules.pro"}) {
                File pg = new File(unpackDir, pgName);
                if (pg.exists() && pg.isFile() && pg.length() > 0) {
                    addUniqueFile(proguardRuleFiles, seenProguardPaths, pg);
                }
            }
        }
    }

    public static String readPackageFromManifest(File manifestFile) {
        try {
            Document doc = parseXmlFile(manifestFile);
            Element root = doc.getDocumentElement();
            if (root != null) {
                String pkg = root.getAttribute("package");
                return pkg != null ? pkg.trim() : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean hasAnyFile(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File c : children) {
            if (c.isFile()) return true;
            if (c.isDirectory() && hasAnyFile(c)) return true;
        }
        return false;
    }

    private static void addUniqueFile(List<File> list, Set<String> seen, File file) {
        String path = file.getAbsolutePath();
        if (seen.add(path)) {
            list.add(file);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static Document parseXmlFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(fis);
        }
    }

    private static Document parseXmlBytes(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new java.io.ByteArrayInputStream(bytes));
    }

    private static Element firstChildElement(Element parent, String tag) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String directChildText(Element parent, String tag) {
        Element child = firstChildElement(parent, tag);
        return child != null ? child.getTextContent().trim() : null;
    }

    private static String firstChildText(Element root, String child1, String child2) {
        Element c1 = firstChildElement(root, child1);
        if (c1 == null) return null;
        return directChildText(c1, child2);
    }

    private static String substituteProps(String input, Map<String, String> props) {
        if (input == null) return null;
        String current = input;
        for (int pass = 0; pass < 5; pass++) {
            Matcher m = PLACEHOLDER_PATTERN.matcher(current);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                String key = m.group(1);
                String val = props.get(key);
                if (val != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(val));
                    changed = true;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);
            current = sb.toString();
            if (!changed) break;
        }
        return current;
    }

    public static int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        if (v1.equals(v2)) return 0;

        String[] p1 = v1.split("[.\\-]");
        String[] p2 = v2.split("[.\\-]");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            String s1 = i < p1.length ? p1[i] : "";
            String s2 = i < p2.length ? p2[i] : "";
            boolean n1 = isDigits(s1);
            boolean n2 = isDigits(s2);
            if (n1 && n2) {
                long l1 = s1.isEmpty() ? 0 : parseLongSafe(s1);
                long l2 = s2.isEmpty() ? 0 : parseLongSafe(s2);
                if (l1 != l2) return Long.compare(l1, l2);
            } else if (n1 != n2) {
                // Numeric segment beats qualifier (e.g. 1.0 > 1.0-alpha01)
                return n1 ? 1 : -1;
            } else {
                int cmp = qualifierRank(s1) - qualifierRank(s2);
                if (cmp != 0) return cmp;
                cmp = s1.compareToIgnoreCase(s2);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private static int qualifierRank(String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return 4;
        if (lower.startsWith("snapshot")) return 0;
        if (lower.startsWith("alpha")) return 1;
        if (lower.startsWith("beta")) return 2;
        if (lower.startsWith("rc")) return 3;
        return 4;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static void log(ProgressListener listener, String msg, boolean err) {
        if (listener != null) {
            listener.onLog(msg, err);
        }
    }

    private static boolean isCancelled(ProgressListener listener) {
        return listener != null && listener.isCancelled();
    }

    public interface ProgressListener {
        void onLog(String message, boolean isError);
        boolean isCancelled();
    }

    public static final class ResolutionException extends Exception {
        public final BuildSdParser.DependencySpec failedSpec;
        public final int lineNumber;

        public ResolutionException(String message, BuildSdParser.DependencySpec failedSpec) {
            this(message, failedSpec, failedSpec != null ? failedSpec.lineNumber : 1);
        }

        public ResolutionException(String message, BuildSdParser.DependencySpec failedSpec, int lineNumber) {
            super(message);
            this.failedSpec = failedSpec;
            this.lineNumber = Math.max(1, lineNumber);
        }
    }

    public static final class ResolvedDependencies {
        public final List<File> compileJars;
        public final List<File> dexJars;
        public final List<File> aarResDirs;
        public final List<File> aarAssetsDirs;
        public final List<File> jniDirs;
        public final List<File> aarManifests;
        public final List<File> proguardRuleFiles;
        public final List<String> aarPackages;
        public final int downloadedCount;
        public final int cachedCount;

        public ResolvedDependencies(
                List<File> compileJars,
                List<File> dexJars,
                List<File> aarResDirs,
                List<File> aarAssetsDirs,
                List<String> aarPackages,
                int downloadedCount,
                int cachedCount) {
            this(compileJars, dexJars, aarResDirs, aarAssetsDirs, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), aarPackages, downloadedCount, cachedCount);
        }

        public ResolvedDependencies(
                List<File> compileJars,
                List<File> dexJars,
                List<File> aarResDirs,
                List<File> aarAssetsDirs,
                List<File> jniDirs,
                List<String> aarPackages,
                int downloadedCount,
                int cachedCount) {
            this(compileJars, dexJars, aarResDirs, aarAssetsDirs, jniDirs,
                    Collections.emptyList(), Collections.emptyList(), aarPackages, downloadedCount, cachedCount);
        }

        public ResolvedDependencies(
                List<File> compileJars,
                List<File> dexJars,
                List<File> aarResDirs,
                List<File> aarAssetsDirs,
                List<File> jniDirs,
                List<File> aarManifests,
                List<String> aarPackages,
                int downloadedCount,
                int cachedCount) {
            this(compileJars, dexJars, aarResDirs, aarAssetsDirs, jniDirs,
                    aarManifests, Collections.emptyList(), aarPackages, downloadedCount, cachedCount);
        }

        public ResolvedDependencies(
                List<File> compileJars,
                List<File> dexJars,
                List<File> aarResDirs,
                List<File> aarAssetsDirs,
                List<File> jniDirs,
                List<File> aarManifests,
                List<File> proguardRuleFiles,
                List<String> aarPackages,
                int downloadedCount,
                int cachedCount) {
            this.compileJars = Collections.unmodifiableList(new ArrayList<>(compileJars));
            this.dexJars = Collections.unmodifiableList(new ArrayList<>(dexJars));
            this.aarResDirs = Collections.unmodifiableList(new ArrayList<>(aarResDirs));
            this.aarAssetsDirs = Collections.unmodifiableList(new ArrayList<>(aarAssetsDirs));
            this.jniDirs = Collections.unmodifiableList(new ArrayList<>(jniDirs));
            this.aarManifests = Collections.unmodifiableList(new ArrayList<>(
                    aarManifests != null ? aarManifests : Collections.emptyList()));
            this.proguardRuleFiles = Collections.unmodifiableList(new ArrayList<>(
                    proguardRuleFiles != null ? proguardRuleFiles : Collections.emptyList()));
            this.aarPackages = Collections.unmodifiableList(new ArrayList<>(aarPackages));
            this.downloadedCount = downloadedCount;
            this.cachedCount = cachedCount;
        }
    }

    private static final class QueueItem {
        final String group;
        final String artifact;
        final String version;
        final boolean compileOnly;
        final String packagingOverride;
        final Set<String> exclusions;
        final int depth;
        final BuildSdParser.DependencySpec originSpec;

        QueueItem(
                String group,
                String artifact,
                String version,
                boolean compileOnly,
                String packagingOverride,
                Set<String> exclusions,
                int depth,
                BuildSdParser.DependencySpec originSpec) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.compileOnly = compileOnly;
            this.packagingOverride = packagingOverride;
            this.exclusions = exclusions;
            this.depth = depth;
            this.originSpec = originSpec;
        }
    }

    private static final class SelectedArtifact {
        final String group;
        final String artifact;
        final String version;
        final String packaging;
        boolean compileOnly;
        final BuildSdParser.DependencySpec originSpec;

        SelectedArtifact(
                String group,
                String artifact,
                String version,
                String packaging,
                boolean compileOnly,
                BuildSdParser.DependencySpec originSpec) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.packaging = packaging;
            this.compileOnly = compileOnly;
            this.originSpec = originSpec;
        }
    }

    private static final class ArtifactBinary {
        final File file;
        final boolean isAar;

        ArtifactBinary(File file, boolean isAar) {
            this.file = file;
            this.isAar = isAar;
        }
    }

    private static final class PomModel {
        String packaging = "jar";
        String relocatedGroup;
        String relocatedArtifact;
        String relocatedVersion;
        final Map<String, String> properties = new HashMap<>();
        final Map<String, String> managedVersions = new HashMap<>();
        final List<PomDependency> dependencies = new ArrayList<>();
    }

    private static final class PomDependency {
        final String group;
        final String artifact;
        final String version;
        final String scope;
        final String type;
        final boolean optional;
        final Set<String> exclusions;

        PomDependency(
                String group,
                String artifact,
                String version,
                String scope,
                String type,
                boolean optional,
                Set<String> exclusions) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.scope = scope;
            this.type = type;
            this.optional = optional;
            this.exclusions = exclusions;
        }
    }
}
