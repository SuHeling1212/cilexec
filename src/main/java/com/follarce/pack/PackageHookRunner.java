package com.follarce.pack;

import com.follarce.Constants;
import com.follarce.function.EffectPolicy;
import com.follarce.function.FunctionContext;
import com.follarce.process.CodeLoader;
import com.follarce.process.ControlFlow;
import com.follarce.process.ExpressionEvaluator;
import com.follarce.util.UserUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

/** Executes lifecycle hooks in a bounded, non-process FCL sandbox. */
public final class PackageHookRunner {
    private static final int MAX_STATEMENTS = 100_000;

    public record HookResult(boolean executed, String status, boolean allow, String message) {
        public static HookResult skipped() {
            return new HookResult(false, "skipped", true, null);
        }
    }

    public HookResult run(PackageArchive archive,
                          PackageManifest.LifecycleEvent event,
                          String user,
                          String binding,
                          String effectId,
                          int pid,
                          String processGeneration) {
        PackageManifest.Hook hook = archive.manifest().lifecycle().get(event);
        if (hook == null) return HookResult.skipped();

        PackageCoordinate coordinate = archive.manifest().coordinate();
        PackagePaths.ensureDirectory(PackagePaths.userPackagesDataPath(user)
                + coordinate.namespace() + "/", user, true);
        PackagePaths.ensureDirectory(PackagePaths.userPackageInstanceDataPath(
                user, coordinate), user, true);

        Map<String, Object> contextData = hookContext(
                archive, event, user, binding, effectId);
        String script = archive.readUtf8(hook.script());
        try (var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("package-hook-", 0).factory())) {
            var future = executor.submit(() -> {
                UserUtil.setCurrentUser(user);
                try {
                    return execute(script, contextData, effectId, pid, processGeneration, user);
                } finally {
                    UserUtil.clearCurrentUser();
                }
            });
            try {
                return future.get(hook.timeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new PackageException("Lifecycle hook timed out after " + hook.timeoutMs()
                        + "ms: " + archive.manifest().coordinate().displayName()
                        + " " + event.manifestKey(), e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof PackageException packageException) throw packageException;
                throw new PackageException("Lifecycle hook failed: " + event.manifestKey(), cause);
            }
        } catch (PackageException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageException("Failed to execute lifecycle hook: " + event.manifestKey(), e);
        }
    }

    private HookResult execute(String source,
                               Map<String, Object> initialData,
                               String effectId,
                               int pid,
                               String processGeneration,
                               String user) {
        Map<String, Object> data = new LinkedHashMap<>(initialData);
        Map<String, Object> reserved = new LinkedHashMap<>(initialData);
        AtomicInteger effectOrdinal = new AtomicInteger();
        FunctionContext[] base = new FunctionContext[1];
        FunctionContext.EffectExecutor effectExecutor = (operation, policy, arguments, invocation) -> {
            validateHookEffect(operation, policy);
            String childEffect = effectId + "-hook-" + effectOrdinal.getAndIncrement();
            return invocation.apply(base[0].forEffect(childEffect, false));
        };
        Object packageData = initialData.get("__package_data");
        base[0] = new FunctionContext(pid, 0, user, processGeneration,
                Map.of(), null, null, effectExecutor,
                packageData instanceof String path ? path : null);

        ExpressionEvaluator evaluator = new ExpressionEvaluator(pid, () -> 0, null, () -> base[0]);
        evaluator.setData(data);
        CodeLoader loader = new CodeLoader();
        List<String> code = loader.loadFromString(source);
        ControlFlow flow = new ControlFlow(evaluator);
        flow.setCode(code, loader.getBoundaryTable());
        flow.setBlockStack(new ArrayList<>());

        int line = 0;
        int statements = 0;
        while (line < code.size()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new PackageException("Lifecycle hook was interrupted");
            }
            if (++statements > MAX_STATEMENTS) {
                throw new PackageException("Lifecycle hook exceeded " + MAX_STATEMENTS + " statements");
            }

            String statement = code.get(line).trim();
            if (statement.isEmpty() || statement.equals("{")) {
                line++;
                continue;
            }
            if (statement.startsWith("}")) {
                line = flow.handleClosingBraces(ControlFlow.countBraces(statement)[1], line);
                continue;
            }
            if (statement.startsWith("func ") || statement.startsWith("import ")
                    || statement.startsWith("include ")) {
                throw new PackageException("Lifecycle hooks cannot declare functions or import code: " + statement);
            }
            if (statement.startsWith("if ") || statement.startsWith("if(")) {
                line = flow.handleIf(condition(statement, "if"), line);
                continue;
            }
            if (statement.equals("else") || statement.startsWith("else ")) {
                line++;
                while (line < code.size() && code.get(line).trim().equals("{")) line++;
                continue;
            }
            if (statement.startsWith("while ") || statement.startsWith("while(")) {
                line = flow.handleWhile(condition(statement, "while"), line);
                continue;
            }
            if (statement.startsWith("switch ") || statement.startsWith("switch(")) {
                line = flow.handleSwitch(condition(statement, "switch"), line);
                continue;
            }
            if (statement.startsWith("case ") || statement.equals("default")) {
                if (!flow.getBlockStack().isEmpty()) {
                    Map<String, Object> top = flow.getBlockStack().get(flow.getBlockStack().size() - 1);
                    if ("SWITCH".equals(top.get("type"))) {
                        line = ((Number) top.get("endLine")).intValue() + 1;
                        flow.getBlockStack().remove(flow.getBlockStack().size() - 1);
                        continue;
                    }
                }
                line++;
                continue;
            }
            if (statement.equals("break")) {
                line = flow.handleBreak(line);
                continue;
            }
            if (statement.equals("continue")) {
                line = flow.handleContinue(line);
                continue;
            }
            if (statement.startsWith("return")) {
                String expression = statement.substring("return".length()).trim();
                if (!expression.isEmpty()) data.put("hookResult", checked(evaluator.evaluateExpression(expression)));
                break;
            }

            Matcher indexAssignment = ExpressionEvaluator.INDEX_ASSIGN_PATTERN.matcher(statement);
            if (indexAssignment.matches()) {
                applyIndexAssignment(data, evaluator, indexAssignment);
                line++;
                continue;
            }
            Matcher assignment = ExpressionEvaluator.ASSIGN_PATTERN.matcher(statement);
            if (assignment.matches()) {
                data.put(assignment.group(1), checked(evaluator.evaluateExpression(assignment.group(2).trim())));
                line++;
                continue;
            }
            Object result = checked(evaluator.evaluateExpression(statement));
            if (result instanceof String marker && isEngineMarker(marker)) {
                throw new PackageException("Lifecycle hooks cannot perform process control: " + marker);
            }
            line++;
        }

        for (Map.Entry<String, Object> item : reserved.entrySet()) {
            if (!Objects.equals(item.getValue(), data.get(item.getKey()))) {
                throw new PackageException("Lifecycle hook modified read-only context variable: " + item.getKey());
            }
        }
        return parseResult(data.get("hookResult"));
    }

    @SuppressWarnings("unchecked")
    private static HookResult parseResult(Object value) {
        if (value == null) return new HookResult(true, "ok", true, null);
        if (!(value instanceof Map<?, ?> raw)) {
            throw new PackageException("hookResult must be a map");
        }
        Map<String, Object> result = (Map<String, Object>) raw;
        String status = Objects.toString(result.getOrDefault("status", "ok"));
        Object allowValue = result.get("allow");
        if (allowValue != null && !(allowValue instanceof Boolean)) {
            throw new PackageException("hookResult.allow must be a boolean");
        }
        boolean allow = !(allowValue instanceof Boolean allowed) || allowed;
        String message = result.get("message") == null ? null : result.get("message").toString();
        if (!"ok".equalsIgnoreCase(status) && !"success".equalsIgnoreCase(status)) {
            throw new PackageException("Lifecycle hook returned status " + status
                    + (message == null ? "" : ": " + message));
        }
        if (!allow) {
            throw new PackageException("Lifecycle hook denied the operation"
                    + (message == null ? "" : ": " + message));
        }
        return new HookResult(true, status, true, message);
    }

    private static Map<String, Object> hookContext(PackageArchive archive,
                                                    PackageManifest.LifecycleEvent event,
                                                    String user,
                                                    String binding,
                                                    String effectId) {
        PackageCoordinate coordinate = archive.manifest().coordinate();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("__package_event", event.manifestKey());
        context.put("__package_namespace", coordinate.namespace());
        context.put("__package_name", coordinate.name());
        context.put("__package_version", coordinate.version());
        context.put("__package_hash", archive.integrity());
        context.put("__package_binding", binding);
        context.put("__package_scope", "user");
        context.put("__package_user", user);
        context.put("__package_data", PackagePaths.userPackageInstanceDataPath(user, coordinate));
        context.put("__effect_id", effectId);
        return context;
    }

    private static void validateHookEffect(String operation, EffectPolicy policy) {
        if (operation.startsWith("package.") || operation.startsWith("process.")
                || operation.startsWith("system.") || operation.startsWith("network.")
                || operation.startsWith("socket.") || operation.startsWith("user.")) {
            throw new PackageException("Lifecycle hook operation is not allowed: " + operation);
        }
        if (policy == EffectPolicy.MANUAL_RECOVERY || policy == EffectPolicy.AT_MOST_ONCE
                || policy == EffectPolicy.IDEMPOTENT_EXTERNAL || policy == EffectPolicy.CONTROL) {
            throw new PackageException("Lifecycle hook effect policy is not retry-safe: " + operation);
        }
    }

    private static Object checked(Object result) {
        if (result instanceof Object[] values && values.length > 0
                && Constants.ERROR_MARKER.equals(values[0])) {
            String message = values.length > 1 ? Objects.toString(values[1]) : "Unknown FCL error";
            throw new PackageException("Lifecycle hook error: " + message);
        }
        if (result instanceof String marker && isEngineMarker(marker)) {
            throw new PackageException("Lifecycle hook engine command is not allowed: " + marker);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void applyIndexAssignment(Map<String, Object> data,
                                             ExpressionEvaluator evaluator,
                                             Matcher assignment) {
        Object target = data.get(assignment.group(1));
        Object index = checked(evaluator.evaluateExpression(assignment.group(2).trim()));
        Object value = checked(evaluator.evaluateExpression(assignment.group(3).trim()));
        if (target instanceof List<?> list && index instanceof Number number) {
            ((List<Object>) list).set(number.intValue(), value);
        } else if (target instanceof Map<?, ?> map) {
            ((Map<Object, Object>) map).put(index, value);
        } else {
            throw new PackageException("Invalid lifecycle hook index assignment target: " + assignment.group(1));
        }
    }

    private static String condition(String statement, String keyword) {
        String value = statement.substring(keyword.length()).trim();
        if (value.startsWith("(")) value = value.substring(1);
        int brace = value.indexOf('{');
        if (brace >= 0) value = value.substring(0, brace).trim();
        if (value.endsWith(")")) value = value.substring(0, value.length() - 1).trim();
        return value;
    }

    private static boolean isEngineMarker(String value) {
        return value.equals("FORK") || value.equals("WAIT") || value.equals("EXIT")
                || value.startsWith("KILL:") || value.startsWith("WAITPID:")
                || value.startsWith("PAUSE:") || value.startsWith("CONTINUE:")
                || value.startsWith("EXEC:") || value.startsWith("USER:");
    }
}
