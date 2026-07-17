package com.follarce.function;

import com.follarce.util.PathUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 函数调用上下文 —— 传递给所有插件函数。
 */
public class FunctionContext {
    @FunctionalInterface
    public interface EffectExecutor {
        Object execute(String operation, EffectPolicy policy, List<Object> arguments,
                       Function<FunctionContext, Object> invocation);
    }

    private final int pid;
    private final int ppid;
    private final String currentUser;
    private final String processGeneration;
    private final Map<String, String> pathAliases;
    private final Consumer<String> effectiveUserUpdater;
    private final Consumer<Map<String, String>> aliasUpdater;
    private final EffectExecutor effectExecutor;
    private final String effectId;
    private final boolean replay;

    public FunctionContext(int pid, int ppid, String currentUser) {
        this(pid, ppid, currentUser, null, Collections.emptyMap(), null, null, null, null, false);
    }

    public FunctionContext(int pid, int ppid, String currentUser, String processGeneration,
                           Map<String, String> pathAliases,
                           Consumer<String> effectiveUserUpdater,
                           Consumer<Map<String, String>> aliasUpdater,
                           EffectExecutor effectExecutor) {
        this(pid, ppid, currentUser, processGeneration, pathAliases, effectiveUserUpdater,
                aliasUpdater, effectExecutor, null, false);
    }

    private FunctionContext(int pid, int ppid, String currentUser, String processGeneration,
                            Map<String, String> pathAliases,
                            Consumer<String> effectiveUserUpdater,
                            Consumer<Map<String, String>> aliasUpdater,
                            EffectExecutor effectExecutor, String effectId, boolean replay) {
        this.pid = pid;
        this.ppid = ppid;
        this.currentUser = currentUser;
        this.processGeneration = processGeneration;
        this.pathAliases = Collections.unmodifiableMap(pathAliases == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(pathAliases));
        this.effectiveUserUpdater = effectiveUserUpdater;
        this.aliasUpdater = aliasUpdater;
        this.effectExecutor = effectExecutor;
        this.effectId = effectId;
        this.replay = replay;
    }

    public int getPid() { return pid; }
    public int getPpid() { return ppid; }
    public String getCurrentUser() { return currentUser; }
    public String getProcessGeneration() { return processGeneration; }
    public Map<String, String> getPathAliases() { return new LinkedHashMap<>(pathAliases); }
    public String getEffectId() { return effectId; }
    public boolean isReplay() { return replay; }

    public String resolvePath(String path) {
        return PathUtil.resolvePath(path, currentUser, pathAliases);
    }

    public void setEffectiveUser(String username) {
        if (effectiveUserUpdater == null) {
            throw new IllegalStateException("Effective user cannot be changed in this context");
        }
        effectiveUserUpdater.accept(username);
    }

    public void setPathAlias(String name, String path) {
        if (aliasUpdater == null) throw new IllegalStateException("Aliases cannot be changed in this context");
        Map<String, String> updated = new LinkedHashMap<>(pathAliases);
        updated.put(name, path);
        aliasUpdater.accept(updated);
    }

    public void removePathAlias(String name) {
        if (aliasUpdater == null) throw new IllegalStateException("Aliases cannot be changed in this context");
        Map<String, String> updated = new LinkedHashMap<>(pathAliases);
        updated.remove(name);
        aliasUpdater.accept(updated);
    }

    public Object executeEffect(String operation, EffectPolicy policy, List<Object> arguments,
                                Function<FunctionContext, Object> invocation) {
        if (effectExecutor == null || policy == EffectPolicy.PURE || policy == EffectPolicy.CONTROL) {
            return invocation.apply(this);
        }
        return effectExecutor.execute(operation, policy, arguments, invocation);
    }

    public FunctionContext forEffect(String id, boolean replaying) {
        return new FunctionContext(pid, ppid, currentUser, processGeneration, pathAliases,
                effectiveUserUpdater, aliasUpdater, effectExecutor, id, replaying);
    }
}
