package com.follarce.process;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reconciles durable work before process threads are started. */
public final class RecoveryManager {
    private RecoveryManager() {}

    public static void recoverAll() {
        ProcessInbox.recoverDeliveries();
        File directory = new File(PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH));
        File[] files = directory.listFiles((ignored, name) -> name.matches("\\d+\\.proc(?:\\.tmp)?"));
        if (files == null) return;
        Set<Integer> pids = new LinkedHashSet<>();
        for (File file : files) {
            try {
                pids.add(Integer.parseInt(file.getName().substring(0, file.getName().indexOf('.'))));
            } catch (NumberFormatException ignored) {
            }
        }

        for (int pid : pids) {
            String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
            try {
                // exists() first promotes a valid tmp-only snapshot.
                if (!FileUtil.exists(path)) continue;
                Map<String, Object> process = JsonUtil.parseToMapStrict(FileUtil.read(path));
                if (Boolean.TRUE.equals(process.get("Reservation"))) {
                    if (!reservationIsOwned(process)) FileUtil.removeFile(path);
                    continue;
                }
                if (ProcessIdentity.ensureDefaults(process) || reconcileRelationshipGenerations(pid, process)) {
                    JsonUtil.updateFile(path, data -> {
                        ProcessIdentity.ensureDefaults(data);
                        reconcileRelationshipGenerations(pid, data);
                    });
                    process = JsonUtil.parseToMapStrict(FileUtil.read(path));
                }
                ProcessState state = ProcessState.restore(process.get("ProcessState"), process.get("Status"));
                if (state.isTerminal() && (process.get("LifecycleCleanup") instanceof Map
                        || process.get("TerminationCleanup") instanceof Map)) {
                    ProcessRunner.reconcileLifecycle(pid);
                    continue;
                }
                if (!state.isTerminal()) {
                    ProcessRunner.recoverInbox(pid, ProcessIdentity.generation(process));
                }
            } catch (Exception e) {
                Logger.warn("Recovery skipped PID " + pid + ": " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean reconcileRelationshipGenerations(int pid, Map<String, Object> process) {
        boolean changed = false;
        Object parentObject = process.get("Parent");
        if (parentObject instanceof Map) {
            Map<String, Object> parent = (Map<String, Object>) parentObject;
            Object parentPid = parent.get("PID");
            if (parentPid instanceof Number && !(parent.get("Generation") instanceof String)) {
                int parentId = ((Number) parentPid).intValue();
                String generation = hasChild(parentId, pid) ? readGeneration(parentId) : null;
                if (generation != null) {
                    parent.put("Generation", generation);
                } else {
                    parent.remove("PID");
                    parent.remove("Name");
                    parent.remove("Generation");
                }
                changed = true;
            }
        }

        Object childrenObject = process.get("Child");
        if (!(childrenObject instanceof Map)) return changed;
        Map<String, Object> children = (Map<String, Object>) childrenObject;
        Map<String, Object> reconciled = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : children.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                changed = true;
                continue;
            }
            Map<String, Object> childInfo = new LinkedHashMap<>((Map<String, Object>) entry.getValue());
            Object childPid = childInfo.get("PID");
            if (!(childPid instanceof Number)) {
                changed = true;
                continue;
            }
            int child = ((Number) childPid).intValue();
            String generation = readGeneration(child);
            if (generation == null || !hasParent(child, pid)) {
                changed = true;
                continue;
            }
            if (!generation.equals(childInfo.get("Generation"))) changed = true;
            childInfo.put("Generation", generation);
            reconciled.put(entry.getKey(), childInfo);
        }
        if (changed) {
            children.clear();
            children.putAll(reconciled);
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasParent(int childPid, int expectedParentPid) {
        String path = Constants.SYSTEM_PROCESS_PATH + childPid + ".proc";
        if (!FileUtil.exists(path)) return false;
        try {
            Map<String, Object> child = JsonUtil.parseToMapStrict(FileUtil.read(path));
            ProcessIdentity.ensureDefaults(child);
            Object parent = child.get("Parent");
            return parent instanceof Map && ((Map<String, Object>) parent).get("PID") instanceof Number
                    && ((Number) ((Map<String, Object>) parent).get("PID")).intValue() == expectedParentPid;
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasChild(int parentPid, int expectedChildPid) {
        String path = Constants.SYSTEM_PROCESS_PATH + parentPid + ".proc";
        if (!FileUtil.exists(path)) return false;
        try {
            Map<String, Object> parent = JsonUtil.parseToMapStrict(FileUtil.read(path));
            Object children = parent.get("Child");
            if (!(children instanceof Map)) return false;
            Object child = ((Map<String, Object>) children).get(String.valueOf(expectedChildPid));
            return child instanceof Map && ((Map<String, Object>) child).get("PID") instanceof Number
                    && ((Number) ((Map<String, Object>) child).get("PID")).intValue() == expectedChildPid;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String readGeneration(int pid) {
        String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        if (!FileUtil.exists(path)) return null;
        try {
            Map<String, Object> snapshot = JsonUtil.parseToMapStrict(FileUtil.read(path));
            if (ProcessIdentity.ensureDefaults(snapshot)) {
                JsonUtil.updateFile(path, ProcessIdentity::ensureDefaults);
                snapshot = JsonUtil.parseToMapStrict(FileUtil.read(path));
            }
            return ProcessIdentity.generation(snapshot);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean reservationIsOwned(Map<String, Object> reservation) {
        Object ownerPidObject = reservation.get("ReservedByPid");
        Object ownerGeneration = reservation.get("ReservedByGeneration");
        Object effectId = reservation.get("CreatedByEffectId");
        if (!(ownerPidObject instanceof Number) || !(ownerGeneration instanceof String)
                || !(effectId instanceof String)) return false;
        int ownerPid = ((Number) ownerPidObject).intValue();
        String ownerPath = Constants.SYSTEM_PROCESS_PATH + ownerPid + ".proc";
        if (!FileUtil.exists(ownerPath)) return false;
        try {
            Map<String, Object> owner = JsonUtil.parseToMapStrict(FileUtil.read(ownerPath));
            if (!ownerGeneration.equals(owner.get("ProcessGeneration"))) return false;
            Object executionObject = owner.get("Execution");
            if (!(executionObject instanceof Map)) return false;
            Object activeObject = ((Map<String, Object>) executionObject).get("ActiveAttempt");
            if (!(activeObject instanceof Map)) return false;
            Object effectsObject = ((Map<String, Object>) activeObject).get("Effects");
            if (!(effectsObject instanceof Iterable)) return false;
            for (Object item : (Iterable<?>) effectsObject) {
                if (item instanceof Map && effectId.equals(((Map<?, ?>) item).get("Id"))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
