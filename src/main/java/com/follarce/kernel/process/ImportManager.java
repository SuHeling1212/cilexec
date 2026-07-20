package com.follarce.kernel.process;

import com.follarce.extension.pack.PackageManager;
import com.follarce.kernel.Constants;
import com.follarce.kernel.function.FunctionRegistry;
import com.follarce.kernel.log.Logger;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模块导入管理器 —— 处理 import/include 语句。
 * <p>
 * import: 将单个脚本或包目录中的所有 FCL 脚本合并到当前进程
 * include: 在当前位置嵌入外部脚本的代码
 */
public class ImportManager {

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^import\\s+(?:\"([^\"]+)\"|(\\S+))"
                    + "(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*$");
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^include\\s+\"([^\"]+)\"\\s*$");
    private static final String PACKAGE_WILDCARD = ".*";

    private final List<String> importedFiles = new ArrayList<>();
    private final Map<String, String> packageDataByFunction = new LinkedHashMap<>();
    private final Supplier<String> effectiveUserSupplier;
    private final Supplier<Map<String, String>> aliasesSupplier;
    private final Supplier<String> currentScriptPathSupplier;

    public ImportManager() {
        this(() -> Constants.DEFAULT_USER_LOCAL, Collections::emptyMap, () -> "/");
    }

    public ImportManager(Supplier<String> effectiveUserSupplier,
                         Supplier<Map<String, String>> aliasesSupplier) {
        this(effectiveUserSupplier, aliasesSupplier, () -> "/");
    }

    public ImportManager(Supplier<String> effectiveUserSupplier,
                         Supplier<Map<String, String>> aliasesSupplier,
                         Supplier<String> currentScriptPathSupplier) {
        this.effectiveUserSupplier = effectiveUserSupplier;
        this.aliasesSupplier = aliasesSupplier;
        this.currentScriptPathSupplier = currentScriptPathSupplier;
    }

    /**
     * 处理 import 语句。
     *
     * @param line 原始 import 行
     * @param codeLines 当前进程的代码行列表（import 返回的函数定义会追加到此处）
     * @return 导入的文件路径列表
     */
    public List<String> handleImport(String line, List<String> codeLines) {
        Matcher matcher = IMPORT_PATTERN.matcher(line);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid import syntax: " + line);
        }

        String quotedPath = matcher.group(1);
        String unquotedPath = matcher.group(2);
        String namespace = matcher.group(3);
        String importPath = quotedPath != null ? quotedPath : unquotedPath;
        if (importPath.endsWith(PACKAGE_WILDCARD)) {
            return handlePackageImport(importPath, namespace, codeLines);
        }
        if (namespace != null) {
            throw new IllegalArgumentException("Only package wildcard imports support aliases: " + line);
        }
        if (quotedPath == null) {
            throw new IllegalArgumentException(
                    "Single-file imports must be quoted; package imports must end with .*: " + line);
        }

        String resolvedPath = PathUtil.resolvePath(importPath,
                effectiveUserSupplier.get(), aliasesSupplier.get());

        if (!FileUtil.exists(resolvedPath)) {
            Logger.warn("Import file not found: " + importPath);
            return new ArrayList<>();
        }

        List<String> imported = new ArrayList<>();
        if (!importedFiles.contains(importPath)
                && appendFile(resolvedPath, importPath, codeLines)) {
            imported.add(importPath);
        }

        return imported;
    }

    private List<String> handlePackageImport(String importPath,
                                             String namespace,
                                             List<String> codeLines) {
        validateNamespace(namespace);
        String packagePath = importPath.substring(0, importPath.length() - PACKAGE_WILDCARD.length());
        if (packagePath.isBlank()) packagePath = ".";

        PackageManager.PackageImport installed = PackageManager.getInstance()
                .loadForImport(effectiveUserSupplier.get(), packagePath);
        if (installed != null) {
            return appendInstalledPackage(installed, importPath, namespace, codeLines);
        }

        String resolvedDirectory = resolvePackageDirectory(packagePath);

        if (!FileUtil.exists(resolvedDirectory)) {
            Logger.warn("Import package not found: " + importPath);
            return new ArrayList<>();
        }
        if (!FileUtil.isDirectory(resolvedDirectory)) {
            throw new IllegalArgumentException("Import package is not a directory: " + importPath);
        }

        String importDirectory = packagePayloadDirectory(resolvedDirectory);
        List<String> packageFiles = new ArrayList<>();
        collectPackageFiles(importDirectory, packageFiles);
        List<String> imported = namespace == null
                ? appendDirectoryPackage(packageFiles, codeLines)
                : appendAliasedDirectoryPackage(packageFiles, resolvedDirectory, namespace, codeLines);

        if (packageFiles.isEmpty()) {
            Logger.warn("Import package contains no .fcl files: " + resolvedDirectory);
        } else {
            Logger.info("Import package: " + importPath
                    + (namespace == null ? "" : " as " + namespace) + " → " + resolvedDirectory
                    + " (" + imported.size() + " files)");
        }
        return imported;
    }

    private List<String> appendInstalledPackage(PackageManager.PackageImport installed,
                                                String displayPath,
                                                String namespace,
                                                List<String> codeLines) {
        if (namespace != null) {
            return appendAliasedInstalledPackage(installed, displayPath, namespace, codeLines);
        }
        List<String> imported = new ArrayList<>();
        for (PackageManager.ImportModule module : installed.modules()) {
            if (importedFiles.contains(module.id())) continue;
            if (appendContent(module.source(), module.id(), codeLines)) {
                registerPackageFunctions(module.source(), null, module.packageDataPath());
                imported.add(module.id());
            }
        }
        Logger.info("Import installed package: " + displayPath + " → sha256:"
                + installed.rootHash() + " (" + imported.size() + " modules)");
        return imported;
    }

    private List<String> appendAliasedInstalledPackage(PackageManager.PackageImport installed,
                                                       String displayPath,
                                                       String namespace,
                                                       List<String> codeLines) {
        String rootIdentity = "sha256:" + installed.rootHash();
        validateNamespaceBinding(namespace, rootIdentity);

        Map<String, Set<String>> functionsByHash = new LinkedHashMap<>();
        Map<String, String> namespacesByHash = new LinkedHashMap<>();
        for (PackageManager.ImportModule module : installed.modules()) {
            functionsByHash.computeIfAbsent(module.packageHash(), ignored -> new LinkedHashSet<>())
                    .addAll(FclNamespaceRewriter.declaredFunctions(module.source()));
            if (module.rootPackage()) {
                namespacesByHash.put(module.packageHash(), namespace);
            } else {
                namespacesByHash.putIfAbsent(module.packageHash(),
                        internalNamespace(namespace, module.packageHash()));
            }
        }

        Map<String, String> rewrittenModules = new LinkedHashMap<>();
        for (PackageManager.ImportModule module : installed.modules()) {
            String importId = aliasedImportId(module.id(), namespace, rootIdentity);
            if (importedFiles.contains(importId)) continue;

            Map<String, String> dependencyNamespaces = new LinkedHashMap<>();
            Map<String, Set<String>> dependencyFunctions = new LinkedHashMap<>();
            for (Map.Entry<String, String> dependency : module.dependencies().entrySet()) {
                String dependencyNamespace = namespacesByHash.get(dependency.getValue());
                if (dependencyNamespace == null) {
                    throw new IllegalArgumentException("Missing imported dependency object: sha256:"
                            + dependency.getValue());
                }
                dependencyNamespaces.put(dependency.getKey(), dependencyNamespace);
                dependencyFunctions.put(dependency.getKey(),
                        functionsByHash.getOrDefault(dependency.getValue(), Set.of()));
            }

            String ownerNamespace = namespacesByHash.get(module.packageHash());
            String rewritten = FclNamespaceRewriter.rewrite(module.source(), ownerNamespace,
                    functionsByHash.getOrDefault(module.packageHash(), Set.of()),
                    dependencyNamespaces, dependencyFunctions);
            rewrittenModules.put(importId, rewritten);
        }

        List<String> imported = new ArrayList<>();
        for (Map.Entry<String, String> module : rewrittenModules.entrySet()) {
            if (appendContent(module.getValue(), module.getKey(), codeLines)) {
                PackageManager.ImportModule owner = installed.modules().stream()
                        .filter(candidate -> aliasedImportId(candidate.id(), namespace, rootIdentity)
                                .equals(module.getKey()))
                        .findFirst()
                        .orElseThrow();
                registerPackageFunctions(owner.source(), namespacesByHash.get(owner.packageHash()),
                        owner.packageDataPath());
                imported.add(module.getKey());
            }
        }
        Logger.info("Import installed package: " + displayPath + " as " + namespace
                + " → sha256:" + installed.rootHash() + " (" + imported.size() + " modules)");
        return imported;
    }

    private List<String> appendDirectoryPackage(List<String> packageFiles,
                                                List<String> codeLines) {
        List<String> imported = new ArrayList<>();
        for (String filePath : packageFiles) {
            if (importedFiles.contains(filePath)) continue;
            if (appendFile(filePath, filePath, codeLines)) imported.add(filePath);
        }
        return imported;
    }

    private List<String> appendAliasedDirectoryPackage(List<String> packageFiles,
                                                       String resolvedDirectory,
                                                       String namespace,
                                                       List<String> codeLines) {
        String rootIdentity = "vfs:" + resolvedDirectory;
        validateNamespaceBinding(namespace, rootIdentity);
        Map<String, String> contents = new LinkedHashMap<>();
        LinkedHashSet<String> functions = new LinkedHashSet<>();
        for (String filePath : packageFiles) {
            checkReadPermission(filePath);
            String content = FileUtil.read(filePath);
            contents.put(filePath, content);
            functions.addAll(FclNamespaceRewriter.declaredFunctions(content));
        }

        List<String> imported = new ArrayList<>();
        for (Map.Entry<String, String> file : contents.entrySet()) {
            String importId = aliasedImportId(file.getKey(), namespace, rootIdentity);
            if (importedFiles.contains(importId)) continue;
            String rewritten = FclNamespaceRewriter.rewrite(file.getValue(), namespace,
                    functions, Map.of(), Map.of());
            if (appendContent(rewritten, importId, codeLines)) imported.add(importId);
        }
        return imported;
    }

    private void validateNamespaceBinding(String namespace, String rootIdentity) {
        String prefix = "#fcl-as=" + namespace + ";root=";
        String expected = prefix + rootIdentity;
        for (String imported : importedFiles) {
            int marker = imported.indexOf(prefix);
            if (marker >= 0 && !imported.substring(marker).equals(expected)) {
                throw new IllegalArgumentException("Import namespace '" + namespace
                        + "' is already bound to another package in this process");
            }
        }
    }

    private static String aliasedImportId(String id, String namespace, String rootIdentity) {
        return id + "#fcl-as=" + namespace + ";root=" + rootIdentity;
    }

    private static String internalNamespace(String rootNamespace, String hash) {
        return rootNamespace + "__" + hash;
    }

    private static void validateNamespace(String namespace) {
        if (namespace != null && FunctionRegistry.hasProviderNamespace(namespace)) {
            throw new IllegalArgumentException("Import namespace is reserved by a built-in provider: "
                    + namespace);
        }
    }

    private String packagePayloadDirectory(String packageDirectory) {
        String manifestPath = PathUtil.normalizePath(packageDirectory + "/manifest.json");
        if (!FileUtil.exists(manifestPath)) return packageDirectory;

        String payloadPath = PathUtil.normalizePath(packageDirectory + "/payload");
        if (!FileUtil.exists(payloadPath) || !FileUtil.isDirectory(payloadPath)) {
            throw new IllegalArgumentException(
                    "Package manifest requires a payload directory: " + packageDirectory);
        }
        return payloadPath;
    }

    private String resolvePackageDirectory(String packagePath) {
        if (packagePath.startsWith("/") || packagePath.startsWith("~")
                || packagePath.startsWith("$") || packagePath.startsWith("@")) {
            return PathUtil.resolvePath(packagePath,
                    effectiveUserSupplier.get(), aliasesSupplier.get());
        }

        String scriptPath = currentScriptPathSupplier.get();
        String resolvedScript = scriptPath == null || scriptPath.isBlank()
                ? "/"
                : PathUtil.resolvePath(scriptPath,
                        effectiveUserSupplier.get(), aliasesSupplier.get());
        String baseDirectory = PathUtil.getParentPath(resolvedScript);
        return PathUtil.normalizePath(baseDirectory + "/" + packagePath);
    }

    private void collectPackageFiles(String directoryPath, List<String> files) {
        checkReadPermission(directoryPath);
        List<Map<String, Object>> entries = FileUtil.getListOfFileAndDirectory(directoryPath);
        entries.sort(Comparator.comparing(entry -> String.valueOf(entry.get("name"))));

        for (Map<String, Object> entry : entries) {
            String name = String.valueOf(entry.get("name"));
            if (name.startsWith(".")) continue;
            if (!PathUtil.isValidPathComponent(name)) {
                throw new IllegalArgumentException("Invalid package entry name: " + name);
            }

            String childPath = PathUtil.normalizePath(directoryPath + "/" + name);
            Path realChild = Path.of(PathUtil.toRealPath(childPath));
            if (Files.isSymbolicLink(realChild)) {
                throw new SecurityException("Package import does not allow symbolic links: " + childPath);
            }

            if (Boolean.TRUE.equals(entry.get("isDirectory"))) {
                collectPackageFiles(childPath, files);
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".fcl")) {
                files.add(childPath);
            }
        }
    }

    private boolean appendFile(String resolvedPath, String displayPath, List<String> codeLines) {
        checkReadPermission(resolvedPath);
        String content = FileUtil.read(resolvedPath);
        return appendContent(content, displayPath, codeLines);
    }

    private boolean appendContent(String content, String displayPath, List<String> codeLines) {
        if (content == null || content.trim().isEmpty()) {
            Logger.warn("Import file empty: " + displayPath);
            return false;
        }

        int importedLineCount = 0;
        for (String importLine : content.split("\n")) {
            String trimmed = importLine.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("#")) {
                codeLines.add(trimmed);
                importedLineCount++;
            }
        }
        Logger.info("Import: " + displayPath + " (" + importedLineCount + " lines)");
        return true;
    }

    private void registerPackageFunctions(String source, String namespace, String dataPath) {
        for (String function : FclNamespaceRewriter.declaredFunctions(source)) {
            String runtimeName = namespace == null ? function : namespace + "." + function;
            packageDataByFunction.put(runtimeName, dataPath);
        }
    }

    public Map<String, String> getPackageDataByFunction() {
        return new LinkedHashMap<>(packageDataByFunction);
    }

    public void setPackageDataByFunction(Map<String, String> mappings) {
        packageDataByFunction.clear();
        if (mappings != null) packageDataByFunction.putAll(mappings);
    }

    private void checkReadPermission(String path) {
        if (!FileUtil.checkFilePermission(path, Constants.PERM_READ, effectiveUserSupplier.get())) {
            throw new SecurityException("Permission denied: read " + path);
        }
    }

    /**
     * 处理 include 语句。
     *
     * @param line 原始 include 行
     * @param codeLines 当前进程的代码行列表（include 的内容会在当前位置插入）
     * @param currentLine 当前执行行号
     * @return 新插入代码后的总行数（用于更新 currentLine）
     */
    public int handleInclude(String line, List<String> codeLines, int currentLine) {
        Matcher matcher = INCLUDE_PATTERN.matcher(line);
        if (!matcher.matches()) return currentLine;

        String includePath = matcher.group(1);
        String resolvedPath = PathUtil.resolvePath(includePath,
                effectiveUserSupplier.get(), aliasesSupplier.get());

        if (!FileUtil.exists(resolvedPath)) {
            Logger.warn("Include file not found: " + includePath);
            return currentLine + 1;
        }
        if (!FileUtil.checkFilePermission(resolvedPath, Constants.PERM_READ, effectiveUserSupplier.get())) {
            throw new SecurityException("Permission denied: read " + resolvedPath);
        }

        String content = FileUtil.read(resolvedPath);
        if (content == null || content.trim().isEmpty()) {
            Logger.warn("Include file empty: " + includePath);
            return currentLine + 1;
        }

        // 在当前位置插入文件内容，替换 include 行
        codeLines.remove(currentLine); // 移除 include 行
        List<String> includeLines = new ArrayList<>();
        for (String l : content.split("\n")) {
            String trimmed = l.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("#")) {
                includeLines.add(trimmed);
            }
        }
        codeLines.addAll(currentLine, includeLines);

        Logger.info("Include: " + includePath + " → " + resolvedPath
                + " (" + includeLines.size() + " lines)");

        // 返回插入后仍然指向当前行（重新执行当前位置的新代码）
        return currentLine;
    }

    /**
     * 获取已导入的文件列表。
     */
    public List<String> getImportedFiles() {
        return new ArrayList<>(importedFiles);
    }

    /**
     * 设置已导入的文件列表（从进程数据恢复时使用）。
     */
    public void setImportedFiles(List<String> files) {
        importedFiles.clear();
        if (files != null) {
            importedFiles.addAll(files);
        }
    }

    /**
     * 添加已导入的文件记录。
     */
    public void addImportedFile(String path) {
        if (!importedFiles.contains(path)) {
            importedFiles.add(path);
        }
    }

    /**
     * 清除导入记录。
     */
    public void clear() {
        importedFiles.clear();
    }
}
