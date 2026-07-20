package com.follarce.kernel.process;

import com.follarce.kernel.api.function.EffectPolicy;
import com.follarce.kernel.api.function.UnknownEffectOutcomeException;
import com.follarce.kernel.util.JsonUtil;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Durable statement-attempt and effect receipt coordinator. */
public final class StatementAttemptManager {
    public record Invocation(String effectId, boolean replay) {}

    private Map<String, Object> processData;
    private Map<String, Object> execution;
    private Map<String, Object> activeAttempt;
    private Runnable checkpoint;
    private int effectCursor;
    private boolean restoredAttempt;

    public StatementAttemptManager(Map<String, Object> processData, Runnable checkpoint) {
        this.checkpoint = checkpoint;
        load(processData);
    }

    @SuppressWarnings("unchecked")
    public void load(Map<String, Object> processData) {
        this.processData = processData;
        ProcessIdentity.ensureDefaults(processData);
        this.execution = (Map<String, Object>) processData.get("Execution");
        Object active = execution.get("ActiveAttempt");
        this.activeAttempt = active instanceof Map ? (Map<String, Object>) active : null;
        this.effectCursor = 0;
        this.restoredAttempt = this.activeAttempt != null;
    }

    public void setCheckpoint(Runnable checkpoint) {
        this.checkpoint = checkpoint;
    }

    public void begin(int line, String statement) {
        String normalized = statement != null ? statement : "";
        if (activeAttempt != null) {
            int savedLine = number(activeAttempt.get("Line"), -1);
            String savedDigest = String.valueOf(activeAttempt.get("StatementDigest"));
            if (savedLine != line || !Objects.equals(savedDigest, digest(normalized))) {
                throw new IllegalStateException("Active attempt does not match the restored statement");
            }
            effectCursor = 0;
            restoredAttempt = true;
            return;
        }

        long ordinal = longNumber(execution.get("NextAttemptOrdinal"), 0L);
        execution.put("NextAttemptOrdinal", ordinal + 1L);
        activeAttempt = new LinkedHashMap<>();
        activeAttempt.put("Id", ProcessIdentity.generation(processData) + "-" + ordinal);
        activeAttempt.put("Ordinal", ordinal);
        activeAttempt.put("Line", line);
        activeAttempt.put("StatementDigest", digest(normalized));
        activeAttempt.put("Effects", new ArrayList<Map<String, Object>>());
        execution.put("ActiveAttempt", activeAttempt);
        effectCursor = 0;
        restoredAttempt = false;
        checkpoint.run();
    }

    public boolean hasActiveAttempt() {
        return activeAttempt != null;
    }

    public void commit() {
        execution.remove("ActiveAttempt");
        activeAttempt = null;
        effectCursor = 0;
        restoredAttempt = false;
    }

    public void abandon() {
        commit();
    }

    public Object invoke(String operation, EffectPolicy policy, List<Object> arguments,
                         Function<Invocation, Object> action) {
        if (policy == EffectPolicy.PURE || policy == EffectPolicy.CONTROL || activeAttempt == null) {
            return action.apply(new Invocation(null, false));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> effects = (List<Map<String, Object>>) activeAttempt.computeIfAbsent(
                "Effects", ignored -> new ArrayList<Map<String, Object>>());
        int ordinal = effectCursor++;
        String argumentsDigest = digest(JsonUtil.toJsonCompact(arguments));
        Map<String, Object> effect;
        boolean existing = ordinal < effects.size();
        if (existing) {
            effect = effects.get(ordinal);
            validate(effect, ordinal, operation, argumentsDigest, policy);
            if ("COMPLETED".equals(effect.get("State"))) {
                return decodeOutcome(effect.get("Outcome"));
            }
            if ("IN_DOUBT".equals(effect.get("State"))) {
                throw new EffectRecoveryRequiredException(String.valueOf(effect.get("Id")), operation);
            }
            Object decision = effect.remove("ManualDecision");
            if (!"RETRY".equals(decision) && !canRetry(policy)) {
                effect.put("State", "IN_DOUBT");
                checkpoint.run();
                throw new EffectRecoveryRequiredException(String.valueOf(effect.get("Id")), operation);
            }
        } else {
            effect = new LinkedHashMap<>();
            String effectId = activeAttempt.get("Id") + "-" + ordinal;
            effect.put("Id", effectId);
            effect.put("Ordinal", ordinal);
            effect.put("Operation", operation);
            effect.put("ArgumentsDigest", argumentsDigest);
            effect.put("Policy", policy.name());
            effect.put("State", "PREPARED");
            effects.add(effect);
            checkpoint.run();
        }

        String effectId = String.valueOf(effect.get("Id"));
        Object result;
        try {
            result = action.apply(new Invocation(effectId, existing && restoredAttempt));
        } catch (UnknownEffectOutcomeException e) {
            effect.put("State", "IN_DOUBT");
            effect.put("Message", e.getMessage());
            checkpoint.run();
            throw new EffectRecoveryRequiredException(effectId, operation);
        }
        effect.put("Outcome", encodeOutcome(result));
        effect.put("State", "COMPLETED");
        checkpoint.run();
        return result;
    }

    @SuppressWarnings("unchecked")
    public static boolean resolve(Map<String, Object> process, String decision, Object suppliedResult) {
        return resolve(process, null, decision, suppliedResult);
    }

    @SuppressWarnings("unchecked")
    public static boolean resolve(Map<String, Object> process, String expectedEffectId,
                                  String decision, Object suppliedResult) {
        Object executionObject = process.get("Execution");
        if (!(executionObject instanceof Map)) return false;
        Object activeObject = ((Map<String, Object>) executionObject).get("ActiveAttempt");
        if (!(activeObject instanceof Map)) return false;
        Object effectsObject = ((Map<String, Object>) activeObject).get("Effects");
        if (!(effectsObject instanceof List)) return false;
        List<Object> effects = (List<Object>) effectsObject;
        for (int i = effects.size() - 1; i >= 0; i--) {
            if (!(effects.get(i) instanceof Map)) continue;
            Map<String, Object> effect = (Map<String, Object>) effects.get(i);
            if (!"IN_DOUBT".equals(effect.get("State"))) continue;
            if (expectedEffectId != null && !expectedEffectId.equals(effect.get("Id"))) continue;
            switch (decision.toLowerCase()) {
                case "retry" -> {
                    effect.put("State", "PREPARED");
                    effect.put("ManualDecision", "RETRY");
                }
                case "skip" -> {
                    effect.put("Outcome", encodeOutcome(null));
                    effect.put("State", "COMPLETED");
                }
                case "result" -> {
                    effect.put("Outcome", encodeOutcome(suppliedResult));
                    effect.put("State", "COMPLETED");
                }
                case "fail" -> {
                    process.put("ProcessState", ProcessState.FAILED.name());
                    process.put("ExitReason", ExitReason.ERROR.name());
                    process.put("StateMessage", "Effect recovery rejected: " + effect.get("Id"));
                    Map<String, Object> cleanup = new LinkedHashMap<>();
                    cleanup.put("Phase", "PENDING");
                    cleanup.put("DeleteAfterCleanup", false);
                    cleanup.put("ProcessGeneration", process.get("ProcessGeneration"));
                    process.put("LifecycleCleanup", cleanup);
                }
                default -> throw new IllegalArgumentException("Unknown effect decision: " + decision);
            }
            if (!"fail".equalsIgnoreCase(decision)) {
                process.put("ProcessState", ProcessState.READY.name());
                process.put("BlockReason", null);
                process.remove("_effectRecovery");
            }
            return true;
        }
        return false;
    }

    private static boolean canRetry(EffectPolicy policy) {
        return policy == EffectPolicy.RECORDED_RESULT
                || policy == EffectPolicy.LOCAL_TRANSACTIONAL
                || policy == EffectPolicy.IDEMPOTENT_EXTERNAL
                || policy == EffectPolicy.AT_LEAST_ONCE;
    }

    private static void validate(Map<String, Object> effect, int ordinal, String operation,
                                 String argumentsDigest, EffectPolicy policy) {
        if (number(effect.get("Ordinal"), -1) != ordinal
                || !Objects.equals(effect.get("Operation"), operation)
                || !Objects.equals(effect.get("ArgumentsDigest"), argumentsDigest)
                || !Objects.equals(effect.get("Policy"), policy.name())) {
            throw new IllegalStateException("Effect replay does not match the durable receipt at ordinal " + ordinal);
        }
    }

    private static Map<String, Object> encodeOutcome(Object value) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        if (value == null) {
            outcome.put("Kind", "NULL");
            return outcome;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            List<Object> values = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) values.add(Array.get(value, i));
            outcome.put("Kind", "ARRAY");
            outcome.put("Component", type.getComponentType().getName());
            outcome.put("Value", values);
            return outcome;
        }
        outcome.put("Kind", "VALUE");
        outcome.put("Value", JsonUtil.deepCopy(value));
        return outcome;
    }

    @SuppressWarnings("unchecked")
    private static Object decodeOutcome(Object raw) {
        if (!(raw instanceof Map)) throw new IllegalStateException("Effect outcome is missing");
        Map<String, Object> outcome = (Map<String, Object>) raw;
        String kind = String.valueOf(outcome.get("Kind"));
        if ("NULL".equals(kind)) return null;
        if ("VALUE".equals(kind)) return JsonUtil.deepCopy(outcome.get("Value"));
        if (!"ARRAY".equals(kind) || !(outcome.get("Value") instanceof List)) {
            throw new IllegalStateException("Unsupported effect outcome: " + kind);
        }
        List<Object> values = (List<Object>) outcome.get("Value");
        String component = String.valueOf(outcome.get("Component"));
        if ("int".equals(component)) {
            int[] result = new int[values.size()];
            for (int i = 0; i < values.size(); i++) result[i] = ((Number) values.get(i)).intValue();
            return result;
        }
        if ("java.lang.String".equals(component)) {
            String[] result = new String[values.size()];
            for (int i = 0; i < values.size(); i++) result[i] = Objects.toString(values.get(i), null);
            return result;
        }
        return values.toArray();
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static long longNumber(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
}
