package com.follarce.process;

import com.follarce.Constants;
import com.follarce.log.Logger;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reconciles durable work before process threads are started. */
public final class RecoveryManager {
    private RecoveryManager() {}

    public static void recoverAll() {
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
                if (ProcessIdentity.ensureDefaults(process)) {
                    JsonUtil.updateFile(path, ProcessIdentity::ensureDefaults);
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
