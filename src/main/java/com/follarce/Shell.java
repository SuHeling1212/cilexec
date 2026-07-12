package com.follarce;

import com.follarce.process.ExpressionEvaluator;
import com.follarce.util.FileUtil;
import com.follarce.process.ProcessRunner;
import com.follarce.util.PathUtil;
import com.follarce.util.UserUtil;
import com.follarce.util.JsonUtil;
import com.follarce.log.Logger;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.File;

/**
 * Cilexec Shell —— 兼容 zsh 常用指令的交互式终端。
 * <p>
 * 启动方式：{@code java com.follarce.Main --shell}
 * <p>
 * 内置命令使用 FCL 内核实现，操作 VFS 而非宿主机文件系统。
 */
public class Shell {

    // ════════════════════════════════════════════
    // 常量
    // ════════════════════════════════════════════

    private static final Pattern ASSIGN_PATTERN =
            Pattern.compile("^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+)\\s*$");

    private static final Pattern EXPORT_PATTERN =
            Pattern.compile("^export\\s+([a-zA-Z_][a-zA-Z0-9_]*)=(.*)$");

    private static final Pattern VAR_REF = Pattern.compile("\\$([a-zA-Z_][a-zA-Z0-9_]*|\\{[^}]+\\})");

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_BOLD = "\u001B[1m";

    // ════════════════════════════════════════════
    // 状态
    // ════════════════════════════════════════════

    private final ExpressionEvaluator evaluator;
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final Scanner scanner;
    private final List<String> history = new ArrayList<>();

    private String cwd = "/user/local";
    private final Map<String, String> env = new LinkedHashMap<>();
    private boolean running = true;
    private int lastExitCode = 0;

    // ════════════════════════════════════════════
    // 构造
    // ════════════════════════════════════════════

    public Shell() {
        this.evaluator = new ExpressionEvaluator(9999, (n, a) -> {});
        this.evaluator.setData(variables);
        this.scanner = new Scanner(System.in);
        UserUtil.setCurrentUser("local");

        env.put("HOME", "/user/local");
        env.put("PWD", cwd);
        env.put("USER", "local");
        env.put("SHELL", "cilexec");
        env.put("VFS_ROOT", PathUtil.toRealPath(""));
    }

    // ════════════════════════════════════════════
    // 主循环
    // ════════════════════════════════════════════

    public void run() {
        System.out.println();
        System.out.println(ANSI_BOLD + "Cilexec Shell" + ANSI_RESET);
        System.out.println("Type 'exit' to quit, 'help' for help");
        System.out.println();

        while (running) {
            String prompt = formatPrompt();
            System.out.print(prompt);

            if (!scanner.hasNextLine()) break;
            String raw = scanner.nextLine();
            if (raw.trim().isEmpty()) continue;

            history.add(raw);
            execute(raw.trim());
        }
    }

    // ════════════════════════════════════════════
    // 命令执行
    // ════════════════════════════════════════════

    private void execute(String line) {
        // 变量赋值
        Matcher am = ASSIGN_PATTERN.matcher(line);
        if (am.matches() && Character.isLowerCase(am.group(1).charAt(0))) {
            try {
                Object val = evaluator.evaluateExpression(am.group(2));
                variables.put(am.group(1), val);
                System.out.println(val);
                return;
            } catch (Exception ignored) {}
        }

        // 解析命令
        String expanded = expandVars(line);
        List<String> tokens = tokenize(expanded);
        if (tokens.isEmpty()) return;

        String cmd = tokens.get(0);
        List<String> args = tokens.subList(1, tokens.size());

        try {
            if (executeBuiltin(cmd, args)) return;
            // fcl/eval 前缀：FCL 表达式（从原始行保留引号提取）
            if (cmd.equals("fcl") || cmd.equals("eval")) {
                int sp = line.indexOf(' ');
                String expr = (sp >= 0) ? line.substring(sp + 1).trim() : "";
                if (expr.isEmpty()) {
                    // FCL 子模式
                    System.out.println("Entering FCL mode. Type 'exit' to return.");
                    while (true) {
                        System.out.print("fcl> ");
                        if (!scanner.hasNextLine()) break;
                        String fclLine = scanner.nextLine().trim();
                        if (fclLine.isEmpty()) continue;
                        if (fclLine.equals("exit") || fclLine.equals("quit")) break;
                        try {
                            Matcher fclAm = ASSIGN_PATTERN.matcher(fclLine);
                            if (fclAm.matches()) {
                                Object val = evaluator.evaluateExpression(fclAm.group(2));
                                variables.put(fclAm.group(1), val);
                                System.out.println(val);
                                continue;
                            }
                            Object result = evaluator.evaluateExpression(fclLine);
                            if (result != null) {
                                String s = (result instanceof Object[])
                                    ? java.util.Arrays.toString((Object[]) result)
                                    : result.toString();
                                if (!s.isEmpty()) System.out.println(s);
                            }
                        } catch (Exception e) {
                            String msg = e.getMessage();
                            System.out.println("Error: " + (msg != null ? msg : e.getClass().getSimpleName()));
                        }
                    }
                } else {
                    try {
                        Matcher m2 = ASSIGN_PATTERN.matcher(expr);
                        if (m2.matches()) {
                            Object val = evaluator.evaluateExpression(m2.group(2));
                            variables.put(m2.group(1), val);
                            System.out.println(val);
                        } else {
                            Object result = evaluator.evaluateExpression(expr);
                            if (result != null) {
                                String s = (result instanceof Object[])
                                    ? java.util.Arrays.toString((Object[]) result)
                                    : result.toString();
                                if (!s.isEmpty()) System.out.println(s);
                            }
                        }
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        System.out.println("Error: " + (msg != null ? msg : e.getClass().getSimpleName()));
                    }
                }
                return;
            }
            // 简单表达式（a+1, 3*4 等）直接走 FCL 求值
            if (executeFcl(line)) return;
            System.out.println(ANSI_RED + "cilexec: command not found: " + cmd + ANSI_RESET);
            lastExitCode = 127;
        } catch (Exception e) {
            System.out.println("cilexec: " + cmd + ": " + e.getMessage());
            lastExitCode = 1;
        }
    }

    private boolean executeBuiltin(String cmd, List<String> args) {
        switch (cmd) {
            case "cd":       return doCd(args);
            case "ls":       return doLs(args);
            case "pwd":      doPwd(); return true;
            case "echo":     doEcho(args); return true;
            case "cat":      return doCat(args);
            case "mkdir":    return doMkdir(args);
            case "rm":       return doRm(args);
            case "touch":    return doTouch(args);
            case "cp":       return doCp(args);
            case "mv":       return doMv(args);
            case "ps":       return doPs();
            case "kill":     return doKill(args);
            case "export":   doExport(args); return true;
            case "source":
            case ".":        return doSource(args);
            case "type":     doType(args); return true;
            case "which":    doWhich(args); return true;
            case "clear":    doClear(); return true;
            case "history":  doHistory(); return true;
            case "env":      doEnv(); return true;
            case "help":     doHelp(); return true;
            case "exit":
            case "quit":     running = false; return true;
            default:         return false;
        }
    }

    private boolean executeFcl(String line) {
        try {
            // 赋值
            Matcher m = ASSIGN_PATTERN.matcher(line);
            if (m.matches()) {
                Object val = evaluator.evaluateExpression(m.group(2));
                variables.put(m.group(1), val);
                System.out.println(val);
                return true;
            }
            // 表达式 / 函数调用
            Object result = evaluator.evaluateExpression(line);
            // 如果 FCL 求值器返回 null（如未知标识符），说明不认识 → command not found
            if (result == null) {
                return false;
            }
            if (!"".equals(result.toString())) {
                System.out.println(result);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ════════════════════════════════════════════
    // 内置命令
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private boolean doLs(List<String> args) {
        String path = cwd;
        boolean longFormat = false;
        boolean all = false;

        for (String arg : args) {
            if (arg.equals("-l")) longFormat = true;
            else if (arg.equals("-a")) all = true;
            else if (arg.equals("-la") || arg.equals("-al")) { longFormat = true; all = true; }
            else if (!arg.startsWith("-")) path = resolvePath(arg);
        }

        List<Map<String, Object>> entries;
        try {
            entries = FileUtil.getListOfFileAndDirectory(path);
        } catch (Exception e) {
            System.out.println("ls: " + path + ": " + e.getMessage());
            return true;
        }

        // 按名称排序：目录在前
        entries.sort((a, b) -> {
            boolean da = Boolean.TRUE.equals(a.get("isDirectory"));
            boolean db = Boolean.TRUE.equals(b.get("isDirectory"));
            if (da != db) return da ? -1 : 1;
            String na = (String) a.get("name");
            String nb = (String) b.get("name");
            return na.compareToIgnoreCase(nb);
        });

        if (longFormat) {
            long total = 0;
            for (Map<String, Object> e : entries) {
                total += ((Number) e.getOrDefault("size", 0)).longValue();
            }
            System.out.println("total " + total);
            for (Map<String, Object> e : entries) {
                String name = (String) e.get("name");
                boolean isDir = Boolean.TRUE.equals(e.get("isDirectory"));
                long size = ((Number) e.getOrDefault("size", 0)).longValue();
                String perm = isDir ? "drwxr-xr-x" : "-rw-r--r--";
                String color = isDir ? ANSI_BLUE : ANSI_RESET;
                System.out.println(String.format("%s  %s  %s%5d  %s%s",
                        perm, "local", "staff", size, color, name, ANSI_RESET));
            }
        } else {
            List<String> names = new ArrayList<>();
            for (Map<String, Object> e : entries) {
                String name = (String) e.get("name");
                boolean isDir = Boolean.TRUE.equals(e.get("isDirectory"));
                names.add(isDir ? (ANSI_BLUE + name + "/" + ANSI_RESET) : name);
            }
            System.out.println(String.join("  ", names));
        }
        return true;
    }

    private boolean doCd(List<String> args) {
        String target = args.isEmpty() ? env.get("HOME") : resolvePath(args.get(0));
        String resolved = PathUtil.resolvePath(target);
        if (FileUtil.exists(resolved)) {
            cwd = resolved;
            env.put("PWD", cwd);
        } else {
            System.out.println("cd: no such directory: " + args.get(0));
        }
        return true;
    }

    private void doPwd() {
        System.out.println(PathUtil.resolvePath(cwd));
    }

    private void doEcho(List<String> args) {
        System.out.println(String.join(" ", args));
    }

    private boolean doCat(List<String> args) {
        for (String arg : args) {
            String path = resolvePath(arg);
            try {
                String content = FileUtil.read(path);
                System.out.println(content);
            } catch (Exception e) {
                System.out.println("cat: " + arg + ": " + e.getMessage());
            }
        }
        return true;
    }

    private boolean doMkdir(List<String> args) {
        boolean parent = false;
        for (String arg : args) {
            if (arg.equals("-p")) { parent = true; continue; }
            if (arg.startsWith("-")) continue;
            String path = resolvePath(arg);
            String parentPath = PathUtil.getParentPath(path);
            String name = PathUtil.getFileName(path);
            try {
                FileUtil.createDirectory(parentPath, name);
            } catch (Exception e) {
                System.out.println("mkdir: " + arg + ": " + e.getMessage());
            }
        }
        return true;
    }

    private boolean doRm(List<String> args) {
        boolean recursive = false;
        boolean force = false;
        for (String arg : args) {
            if (arg.equals("-r") || arg.equals("-rf") || arg.equals("-fr")) { recursive = true; continue; }
            if (arg.equals("-f")) { force = true; continue; }
            if (arg.startsWith("-")) continue;
            String path = resolvePath(arg);
            try {
                FileUtil.removeFile(path);
            } catch (Exception e1) {
                try {
                    if (recursive) {
                        FileUtil.removeDirectory(path);
                    } else if (force) {
                        // ignore
                    } else {
                        System.out.println("rm: " + arg + ": " + e1.getMessage());
                    }
                } catch (Exception e2) {
                    if (!force) {
                        System.out.println("rm: " + arg + ": " + e2.getMessage());
                    }
                }
            }
        }
        return true;
    }

    private boolean doTouch(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            String path = resolvePath(arg);
            String parent = PathUtil.getParentPath(path);
            String name = PathUtil.getFileName(path);
            try {
                if (!FileUtil.exists(path)) {
                    FileUtil.createFile(parent, name);
                }
            } catch (Exception e) {
                System.out.println("touch: " + arg + ": " + e.getMessage());
            }
        }
        return true;
    }

    private boolean doCp(List<String> args) {
        List<String> paths = new ArrayList<>();
        boolean recursive = false;
        for (String arg : args) {
            if (arg.equals("-r") || arg.equals("-R")) { recursive = true; continue; }
            if (arg.startsWith("-")) continue;
            paths.add(arg);
        }
        if (paths.size() < 2) {
            System.out.println("cp: missing file operand");
            return true;
        }
        String dest = resolvePath(paths.remove(paths.size() - 1));
        for (String src : paths) {
            String srcPath = resolvePath(src);
            String fileName = PathUtil.getFileName(srcPath);
            String targetPath = dest + "/" + fileName;
            try {
                String content = FileUtil.read(srcPath);
                if (!FileUtil.exists(dest)) {
                    String parent = PathUtil.getParentPath(dest);
                    String name = PathUtil.getFileName(dest);
                    FileUtil.createFile(parent, name);
                    targetPath = dest;
                }
                FileUtil.write(targetPath, content);
            } catch (Exception e) {
                System.out.println("cp: " + src + ": " + e.getMessage());
            }
        }
        return true;
    }

    private boolean doMv(List<String> args) {
        if (args.size() < 2) {
            System.out.println("mv: missing file operand");
            return true;
        }
        String dest = resolvePath(args.get(args.size() - 1));
        for (int i = 0; i < args.size() - 1; i++) {
            String src = resolvePath(args.get(i));
            String name = PathUtil.getFileName(src);
            String target = dest + "/" + name;
            try {
                String content = FileUtil.read(src);
                if (!FileUtil.exists(dest)) {
                    target = dest;
                }
                FileUtil.write(target, content);
                FileUtil.removeFile(src);
            } catch (Exception e) {
                System.out.println("mv: " + args.get(i) + ": " + e.getMessage());
            }
        }
        return true;
    }

    private boolean doPs() {
        String procDir = PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH);
        java.io.File dir = new java.io.File(procDir);
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".proc"));
        if (files == null || files.length == 0) {
            System.out.println("  PID  NAME");
            return true;
        }
        System.out.println("  PID  NAME");
        for (java.io.File f : files) {
            try {
                String content = FileUtil.read(Constants.SYSTEM_PROCESS_PATH + f.getName());
                Map<String, Object> data = JsonUtil.parseToMap(content);
                Object pid = data.get("PID");
                Object name = data.getOrDefault("Name", "?");
                Object status = data.get("Status");
                String stat = (status instanceof Boolean && (Boolean) status) ? "R" : "S";
                System.out.println("  " + pid + "    " + name + "  " + stat);
            } catch (Exception ignored) {}
        }
        return true;
    }

    private boolean doKill(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            try {
                int pid = Integer.parseInt(arg);
                ProcessRunner.postMessage(pid, "Status", false);
            } catch (NumberFormatException e) {
                System.out.println("kill: invalid pid: " + arg);
            }
        }
        return true;
    }

    private void doExport(List<String> args) {
        for (String arg : args) {
            Matcher m = EXPORT_PATTERN.matcher(arg);
            if (m.matches()) {
                env.put(m.group(1), expandVars(m.group(2)));
            } else if (arg.contains("=")) {
                int eq = arg.indexOf('=');
                env.put(arg.substring(0, eq), expandVars(arg.substring(eq + 1)));
            }
        }
    }

    private boolean doSource(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("source: missing file operand");
            return true;
        }
        String path = resolvePath(args.get(0));
        try {
            String content = FileUtil.read(path);
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    execute(expandVars(trimmed));
                }
            }
        } catch (Exception e) {
            System.out.println("source: " + args.get(0) + ": " + e.getMessage());
        }
        return true;
    }

    private void doType(List<String> args) {
        for (String arg : args) {
            if (isBuiltin(arg)) {
                System.out.println(arg + " is a shell builtin");
            } else if (variables.containsKey(arg)) {
                System.out.println(arg + " is a variable");
            } else {
                System.out.println(arg + " is a FCL function");
            }
        }
    }

    private void doWhich(List<String> args) {
        for (String arg : args) {
            if (isBuiltin(arg)) {
                System.out.println(arg + ": shell builtin");
            } else if (variables.containsKey(arg)) {
                System.out.println(arg + ": variable");
            } else {
                System.out.println(arg + ": FCL function");
            }
        }
    }

    private void doClear() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private void doHistory() {
        for (int i = 0; i < history.size(); i++) {
            System.out.println(String.format("%5d  %s", i + 1, history.get(i)));
        }
    }

    private void doEnv() {
        for (Map.Entry<String, String> e : env.entrySet()) {
            System.out.println(e.getKey() + "=" + e.getValue());
        }
    }

    private void doFcl(String rawLine, List<String> args) {
        if (args.isEmpty()) {
            // FCL 子模式
            System.out.println("Entering FCL mode. Type 'exit' to return.");
            while (true) {
                System.out.print("fcl> ");
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.equals("exit") || line.equals("quit")) break;
                try {
                    // 赋值
                    Matcher am = ASSIGN_PATTERN.matcher(line);
                    if (am.matches()) {
                        Object val = evaluator.evaluateExpression(am.group(2));
                        variables.put(am.group(1), val);
                        System.out.println(val);
                        continue;
                    }
                    // 表达式/函数调用
                    Object result = evaluator.evaluateExpression(line);
                    if (result != null) {
                        String s = (result instanceof Object[]) 
                            ? Arrays.toString((Object[]) result)
                            : result.toString();
                        if (!s.isEmpty()) System.out.println(s);
                    }
                } catch (Exception e) {
                    String msg = e.getMessage();
                    System.out.println("Error: " + (msg != null ? msg : e.getClass().getSimpleName()));
                }
            }
        } else {
            // 单行 FCL 表达式（从原始输入提取，保留引号）
            int sp = rawLine.indexOf(' ');
            String expr = (sp >= 0) ? rawLine.substring(sp + 1).trim() : "";
            try {
                Matcher m2 = ASSIGN_PATTERN.matcher(expr);
                if (m2.matches()) {
                    Object val = evaluator.evaluateExpression(m2.group(2));
                    variables.put(m2.group(1), val);
                    System.out.println(val);
                    return;
                }
                Object result = evaluator.evaluateExpression(expr);
                if (result != null) {
                    String s = (result instanceof Object[]) 
                        ? Arrays.toString((Object[]) result)
                        : result.toString();
                    if (!s.isEmpty()) System.out.println(s);
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                System.out.println("Error: " + (msg != null ? msg : e.getClass().getSimpleName()));
            }
        }
    }

    private void doHelp() {
        System.out.println("Cilexec Shell - Built-in Commands");
        System.out.println("  File: ls cd pwd cat echo mkdir rm touch cp mv");
        System.out.println("  Process: ps kill");
        System.out.println("  Env: export env history");
        System.out.println("  Other: source type which clear help exit");
        System.out.println();
        System.out.println("FCL expressions and functions are also available:");
        System.out.println("  io.println, swapPool.*, file.*, process.*, ...");
    }

    // ════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════

    private boolean isBuiltin(String cmd) {
        return Set.of("cd","ls","pwd","echo","cat","mkdir","rm","touch","cp","mv",
                "ps","kill","export","source",".","type","which","clear","history",
                "env","help","exit","quit","fcl","eval").contains(cmd);
    }

    private String resolvePath(String path) {
        if (path.startsWith("/")) return PathUtil.resolvePath(path);
        if (path.startsWith("~")) return PathUtil.resolvePath(path);
        if (path.startsWith("$")) return expandVars(path);
        return PathUtil.resolvePath(cwd + "/" + path);
    }

    private String expandVars(String s) {
        StringBuffer sb = new StringBuffer();
        Matcher m = VAR_REF.matcher(s);
        while (m.find()) {
            String name = m.group(1);
            if (name.startsWith("{") && name.endsWith("}")) {
                name = name.substring(1, name.length() - 1);
            }
            String val = env.getOrDefault(name, "");
            if (val.isEmpty() && variables.containsKey(name)) {
                val = String.valueOf(variables.get(name));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escape = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escape) {
                current.append(c);
                escape = false;
                continue;
            }
            if (c == '\\') { escape = true; continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private String formatPrompt() {
        String shortPath = cwd.equals("/user/local") ? "~" : cwd;
        if (shortPath.startsWith("/user/local/")) {
            shortPath = "~/" + shortPath.substring("/user/local/".length());
        }
        return ANSI_GREEN + "cilexec" + ANSI_RESET + ":" +
               ANSI_BLUE + shortPath + ANSI_RESET +
               " % ";
    }
}
