package com.follarce.extension;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.extension.api.ExtensionEffectPolicy;
import com.follarce.extension.api.ExtensionFunctionContext;
import com.follarce.extension.api.ExtensionState;
import com.follarce.extension.api.ExtensionTransaction;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclScope;
import com.follarce.fcl.FclSuspension;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Internal binding of one declared Java function to a durable FCL invocation. */
final class ExtensionFunctionInvocation implements ExtensionFunctionContext {
    private static final Pattern STATE_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private final String extensionId;
    private final String qualifiedFunctionName;
    private final List<Object> arguments;
    private final FclFunctionRegistry.Invocation invocation;
    private final TransactionContext transactionContext;
    private final CilProcess process;
    private final Instant now;
    private final ExtensionState state;
    private final ExtensionTransaction transaction;
    private final FclContinuationCodec codec = new FclContinuationCodec();

    ExtensionFunctionInvocation(String extensionId, String qualifiedFunctionName,
                                List<Object> arguments,
                                FclFunctionRegistry.Invocation invocation,
                                TransactionContext transaction, CilProcess process,
                                Instant now) {
        this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
        this.qualifiedFunctionName = Objects.requireNonNull(
                qualifiedFunctionName, "qualifiedFunctionName");
        this.arguments = List.copyOf(arguments);
        this.invocation = Objects.requireNonNull(invocation, "invocation");
        this.transactionContext = Objects.requireNonNull(transaction, "transaction");
        this.process = Objects.requireNonNull(process, "process");
        this.now = Objects.requireNonNull(now, "now");
        state = new DurableState(extensionId, invocation.continuation().globalScope());
        this.transaction = new TransactionView(transaction);
    }

    @Override public String extensionId() { return extensionId; }

    @Override public String qualifiedFunctionName() { return qualifiedFunctionName; }

    @Override public List<Object> arguments() { return arguments; }

    @Override public long expressionId() { return invocation.expressionId(); }

    @Override public UUID processUid() { return process.identity().processUid(); }

    @Override public long pid() { return process.identity().pid(); }

    @Override public UUID ownerId() { return process.ownerId(); }

    @Override public long executionEpoch() { return process.executionEpoch(); }

    @Override public Instant now() { return now; }

    @Override public ExtensionState state() { return state; }

    @Override public ExtensionTransaction transaction() { return transaction; }

    @Override
    public Object awaitEffect(String effectType, Object request, ExtensionEffectPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.EFFECT_RESULT)) {
            Object delivered = continuation.scope().remove(ProcessInbox.EFFECT_RESULT);
            if (!(delivered instanceof Map<?, ?> result)
                    || !Boolean.TRUE.equals(result.get("ok"))) {
                throw new FclRuntimeException("External effect failed: " + delivered);
            }
            return result.get("value");
        }
        Authorization.require(transactionContext, ownerId(), Capability.EFFECT_REQUEST);
        UUID effectId = UUID.randomUUID();
        Continuation.PersistedValue payload = new Continuation.PersistedValue(
                codec.valueType(request), codec.valueToJson(request));
        transactionContext.effects().save(EffectRequest.prepare(effectId, processUid(),
                effectType, payload, toPolicy(policy), now));
        continuation.waitFor("effect:" + effectId, Map.of("effectType", effectType,
                "extensionId", extensionId));
        transactionContext.audit().append(new AuditEvent(UUID.randomUUID(),
                AuditEvent.ActorType.USER, ownerId().toString(), "extension.effect.request",
                "effect.effect", effectId.toString(), AuditEvent.Result.SUCCEEDED,
                Map.of("extensionId", extensionId, "effectType", effectType), now));
        throw FclSuspension.suspend();
    }

    private static EffectRequest.Policy toPolicy(ExtensionEffectPolicy policy) {
        EffectRequest.UnknownAction action = switch (policy.recovery()) {
            case MANUAL -> EffectRequest.UnknownAction.MANUAL;
            case RETRY_IDEMPOTENT -> EffectRequest.UnknownAction.RETRY_IDEMPOTENT;
            case QUERY_REMOTE -> EffectRequest.UnknownAction.QUERY_REMOTE;
        };
        return new EffectRequest.Policy(policy.idempotent(), policy.idempotencyKey(),
                policy.remotelyQueryable(), policy.retryable(), action);
    }

    private static final class DurableState implements ExtensionState {
        private final String prefix;
        private final FclScope scope;

        private DurableState(String extensionId, FclScope scope) {
            prefix = "cilexec.extension." + extensionId + ".";
            this.scope = scope;
        }

        @Override public boolean contains(String key) { return scope.contains(name(key)); }

        @Override
        public Optional<Object> find(String key) {
            String name = name(key);
            return scope.contains(name) ? Optional.ofNullable(scope.get(name)) : Optional.empty();
        }

        @Override public void put(String key, Object value) { scope.put(name(key), value); }

        @Override
        public Optional<Object> remove(String key) {
            String name = name(key);
            return scope.contains(name) ? Optional.ofNullable(scope.remove(name)) : Optional.empty();
        }

        @Override
        public Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            scope.values().forEach((key, value) -> {
                if (key.startsWith(prefix)) result.put(key.substring(prefix.length()), value);
            });
            return Collections.unmodifiableMap(result);
        }

        private String name(String key) {
            if (key == null || key.length() > 128 || !STATE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid extension state key: " + key);
            }
            return prefix + key;
        }
    }

    private record TransactionView(TransactionContext delegate) implements ExtensionTransaction {
        private TransactionView {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override public com.follarce.domain.port.ProgramRepository programs() {
            return delegate.programs();
        }
        @Override public com.follarce.domain.port.ProcessRepository processes() {
            return delegate.processes();
        }
        @Override public com.follarce.domain.port.SchedulerRepository scheduler() {
            return delegate.scheduler();
        }
        @Override public com.follarce.domain.port.IpcRepository ipc() { return delegate.ipc(); }
        @Override public com.follarce.domain.port.TimerRepository timers() {
            return delegate.timers();
        }
        @Override public com.follarce.domain.port.VfsRepository vfs() { return delegate.vfs(); }
        @Override public com.follarce.domain.port.PackageRepository packages() {
            return delegate.packages();
        }
        @Override public com.follarce.domain.port.EffectRepository effects() {
            return delegate.effects();
        }
        @Override public com.follarce.domain.port.AuthRepository auth() { return delegate.auth(); }
        @Override public com.follarce.domain.port.AuditRepository audit() {
            return delegate.audit();
        }
        @Override public com.follarce.domain.port.TerminalRepository terminal() {
            return delegate.terminal();
        }
    }
}
