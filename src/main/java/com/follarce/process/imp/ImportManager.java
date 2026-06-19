package com.follarce.process.imp;

import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.PathUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模块导入管理器 —— 处理 import/include 语句。
 * <p>
 * import: 将外部脚本的函数定义合并到当前进程
 * include: 在当前位置嵌入外部脚本的代码
 */
public class ImportManager {

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^import\\s+\"([^\"]+)\"\\s*$");
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^include\\s+\"([^\"]+)\"\\s*$");

    private final List<String> importedFiles = new ArrayList<>();

    /**
     * 处理 import 语句。
     *
     * @param line 原始 import 行
     * @param codeLines 当前进程的代码行列表（import 返回的函数定义会追加到此处）
     * @return 导入的文件路径列表
     */
    public List<String> handleImport(String line, List<String> codeLines) {
        Matcher matcher = IMPORT_PATTERN.matcher(line);
        if (!matcher.matches()) return new ArrayList<>();

        String importPath = matcher.group(1);
        String resolvedPath = PathUtil.resolvePath(importPath);

        if (!FileUtil.exists(resolvedPath)) {
            Logger.warn("Import file not found: " + importPath);
            return new ArrayList<>();
        }

        String content = FileUtil.read(resolvedPath);
        if (content == null || content.trim().isEmpty()) {
            Logger.warn("Import file empty: " + importPath);
            return new ArrayList<>();
        }

        List<String> imported = new ArrayList<>();
        imported.add(importPath);

        // 将导入文件的内容追加到当前代码行
        for (String importLine : content.split("\n")) {
            String trimmed = importLine.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("#")) {
                codeLines.add(trimmed);
            }
        }

        // 递归处理导入文件中的 import 语句
        // (简单实现：只处理一层嵌套)

        Logger.info("Import: " + importPath + " → " + resolvedPath
                + " (" + content.split("\n").length + " lines)");
        return imported;
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
        String resolvedPath = PathUtil.resolvePath(includePath);

        if (!FileUtil.exists(resolvedPath)) {
            Logger.warn("Include file not found: " + includePath);
            return currentLine + 1;
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
