package com.follarce.domain;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.effect.EffectPayload;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.terminal.TerminalSession;
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectAuthAuditTerminalDomainTest {
    private static final Instant T0 = Instant.parse("2026-07-22T03:00:00Z");

    @Test
    void effectJournalFollowsPreparedClaimedExecutingCompletedFlow() {
        EffectRequest.Policy policy = new EffectRequest.Policy(true, Optional.of("request-7"),
                false, true, EffectRequest.UnknownAction.RETRY_IDEMPOTENT);
        EffectRequest prepared = EffectRequest.prepare(UUID.randomUUID(), UUID.randomUUID(),
                "http.post", value("request"), policy, T0);

        EffectRequest claimed = prepared.claim(UUID.randomUUID(), T0.plusSeconds(1));
        EffectRequest executing = claimed.start(T0.plusSeconds(2));
        EffectRequest completed = executing.complete(value("response"), T0.plusSeconds(3));

        assertEquals(EffectRequest.Status.COMPLETED, completed.status());
        assertEquals("response", completed.result().orElseThrow().canonicalPayload());
        assertThrows(IllegalStateException.class,
                () -> completed.complete(value("again"), T0.plusSeconds(4)));
    }

    @Test
    void uncertainNonIdempotentEffectRequiresManualResolution() {
        EffectRequest.Policy manual = new EffectRequest.Policy(false, Optional.empty(),
                false, false, EffectRequest.UnknownAction.MANUAL);
        EffectRequest unknown = EffectRequest.prepare(UUID.randomUUID(), UUID.randomUUID(),
                        "hardware.write", value("payload"), manual, T0)
                .claim(UUID.randomUUID(), T0.plusSeconds(1))
                .start(T0.plusSeconds(2))
                .unknown("external success cannot be determined", T0.plusSeconds(3));

        assertTrue(unknown.requiresManualResolution());
        assertThrows(IllegalArgumentException.class, () -> new EffectRequest.Policy(
                false, Optional.empty(), false, true,
                EffectRequest.UnknownAction.RETRY_IDEMPOTENT));
        assertThrows(IllegalArgumentException.class, () -> new EffectRequest.Policy(
                true, Optional.of("key"), false, false,
                EffectRequest.UnknownAction.QUERY_REMOTE));
    }

    @Test
    void effectPayloadRequiresOneRepresentationAndManualOutcomeIsExplicit() {
        ObjectHash hash = new ObjectHash("a".repeat(64));
        EffectPayload objectPayload = EffectPayload.object(hash);
        EffectRequest unknown = EffectRequest.prepareObject(UUID.randomUUID(), UUID.randomUUID(),
                        "host.file_write", hash,
                        new EffectRequest.Policy(false, Optional.empty(), false, false,
                                EffectRequest.UnknownAction.MANUAL), T0)
                .claim(UUID.randomUUID(), T0.plusSeconds(1))
                .start(T0.plusSeconds(2))
                .unknown("operator must inspect the host", T0.plusSeconds(3));

        EffectRequest completed = unknown.resolveUnknownSuccess(objectPayload,
                T0.plusSeconds(4));

        assertEquals(hash, completed.resultObjectHash().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new EffectPayload(
                Optional.of(value("inline")), Optional.of(hash)));
        assertThrows(IllegalArgumentException.class, () -> EffectRequest.prepare(
                UUID.randomUUID(), UUID.randomUUID(), "not_namespaced", value("request"),
                new EffectRequest.Policy(false, Optional.empty(), false, false,
                        EffectRequest.UnknownAction.MANUAL), T0));
        assertThrows(IllegalStateException.class, () -> completed.resolveUnknownFailure(
                "too late", T0.plusSeconds(5)));
    }

    @Test
    void databaseRoleIdentitySurvivesUsernameChanges() {
        UUID userId = UUID.randomUUID();
        UserAccount account = UserAccount.active(userId, "alice", T0);
        UserAccount renamed = account.rename("alice-renamed");
        UserAccount rotated = renamed.rotateCredential();
        UserAccount disabled = rotated.disable(T0.plusSeconds(1));

        assertEquals(account.postgresRoleName(), renamed.postgresRoleName());
        assertEquals("cilexec_user_" + userId.toString().replace("-", ""),
                account.postgresRoleName());
        assertEquals(2, rotated.credentialVersion());
        assertEquals(UserAccount.Status.DISABLED, disabled.status());
        assertTrue(disabled.disabledAt().isPresent());
        assertThrows(IllegalArgumentException.class, () -> new UserAccount(
                userId, "alice", "cilexec_user_wrong", UserAccount.Status.ACTIVE, T0,
                Optional.empty(), 1));
    }

    @Test
    void auditEventOwnsStructuredDetailsAndRejectsMissingIdentity() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("package", "std/network/1.0.0");
        AuditEvent event = new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                UUID.randomUUID().toString(), "package.bind", "package", "network",
                AuditEvent.Result.SUCCEEDED, mutable, T0);
        mutable.clear();

        assertEquals(1, event.details().size());
        assertThrows(UnsupportedOperationException.class,
                () -> event.details().put("extra", "value"));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(
                UUID.randomUUID(), AuditEvent.ActorType.USER, " ", "action", "resource",
                "id", AuditEvent.Result.FAILED, Map.of(), T0));
    }

    @Test
    void terminalStoresOnlyCompleteCommittedInputsInSequence() {
        TerminalSession session = new TerminalSession(UUID.randomUUID(), UUID.randomUUID(),
                TerminalSession.Status.OPEN, 1, T0, T0, Optional.empty());
        String completeInput = "func value() {\n  return 42\n}";
        TerminalSession.Input input = session.commitInput(completeInput, T0.plusSeconds(1));
        TerminalSession advanced = session.advanceAfter(input);

        assertEquals(completeInput, input.committedText());
        assertEquals(1, input.sequence());
        assertEquals(2, advanced.nextInputSequence());
        TerminalSession closed = advanced.close(T0.plusSeconds(2));
        assertThrows(IllegalStateException.class,
                () -> closed.commitInput("ignored", T0.plusSeconds(3)));
    }

    @Test
    void persistentInterruptIsHandledAtMostOnce() {
        TerminalSession.Interrupt requested = new TerminalSession.Interrupt(
                UUID.randomUUID(), T0, Optional.empty());

        TerminalSession.Interrupt handled = requested.handled(T0.plusSeconds(1));

        assertFalse(requested.handledAt().isPresent());
        assertTrue(handled.handledAt().isPresent());
        assertThrows(IllegalStateException.class,
                () -> handled.handled(T0.plusSeconds(2)));
    }

    private static Continuation.PersistedValue value(String payload) {
        return new Continuation.PersistedValue("json", payload);
    }
}
