package com.follarce.application;

import com.follarce.domain.port.AuditRepository;
import com.follarce.domain.port.AuthRepository;
import com.follarce.domain.port.EffectRepository;
import com.follarce.domain.port.IpcRepository;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.ProgramRepository;
import com.follarce.domain.port.SchedulerRepository;
import com.follarce.domain.port.TerminalRepository;
import com.follarce.domain.port.TimerRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.port.TransactionWork;
import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.audit.AuditRetentionPolicy;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcDelivery;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcSubscription;
import com.follarce.domain.ipc.IpcTopic;
import com.follarce.domain.packageinfo.PackageBinding;
import com.follarce.domain.packageinfo.PackageEnvironment;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.program.Program;
import com.follarce.domain.scheduler.SchedulerClaim;
import com.follarce.domain.scheduler.SchedulerQueueEntry;
import com.follarce.domain.terminal.TerminalSession;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.FileRevision;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsMount;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-22T03:00:00Z");

    @Test
    void storesCanonicalSourceCompiledObjectAndProgramInOneUserTransaction() {
        TestPersistence persistence = new TestPersistence();
        UUID ownerId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        ProgramService service = new ProgramService(persistence, new FclCompiler(),
                new FclProgramCodec(), Clock.fixed(NOW, ZoneOffset.UTC), () -> programId);
        String source = "first = 1\nsecond = first + 1\n";

        Program program = service.create(ownerId, source);

        assertEquals(1, persistence.userTransactions);
        assertEquals(ownerId, persistence.lastUser);
        assertEquals(Isolation.READ_COMMITTED, persistence.lastIsolation);
        assertEquals(programId, program.programId());
        assertEquals(ProgramService.LANGUAGE_VERSION, program.languageVersion());
        assertEquals(program.programHash(), program.sourceObjectHash());
        assertEquals(2, program.statementCount());
        assertEquals(NOW, program.createdAt());
        assertEquals(2, persistence.vfs.objects.size());

        StoredObject sourceObject = persistence.vfs.objects.get(program.sourceObjectHash());
        assertArrayEquals(source.getBytes(StandardCharsets.UTF_8),
                sourceObject.content().bytes());
        assertEquals(ProgramService.SOURCE_MEDIA_TYPE, sourceObject.mediaType());

        StoredObject compiledObject = persistence.vfs.objects.get(
                program.compiledObjectHash().orElseThrow());
        assertEquals(ProgramService.COMPILED_MEDIA_TYPE, compiledObject.mediaType());
        String compiledJson = new String(compiledObject.content().bytes(), StandardCharsets.UTF_8);
        assertEquals(program.programHash().value(),
                new FclProgramCodec().fromJson(compiledJson).sourceHash());
    }

    @Test
    void returnsExistingOwnerProgramForIdenticalLogicalContent() {
        TestPersistence persistence = new TestPersistence();
        UUID ownerId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID[] identifiers = {firstId, secondId};
        int[] index = {0};
        ProgramService service = new ProgramService(persistence, new FclCompiler(),
                new FclProgramCodec(), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> identifiers[index[0]++]);

        Program first = service.create(ownerId, "value = 42\n");
        Program duplicate = service.create(ownerId, "value = 42\n");

        assertSame(first, duplicate);
        assertEquals(firstId, duplicate.programId());
        assertEquals(2, persistence.userTransactions);
        assertEquals(1, persistence.programs.byId.size());
        assertEquals(2, persistence.vfs.objects.size());
    }

    static final class TestPersistence implements TransactionExecutor, UserTransactionExecutor,
            TransactionContext {
        final MemoryProgramRepository programs = new MemoryProgramRepository();
        final MemoryVfsRepository vfs = new MemoryVfsRepository();
        final MemoryProcessRepository processes = new MemoryProcessRepository();
        final MemorySchedulerRepository scheduler = new MemorySchedulerRepository(processes);
        final MemoryTerminalRepository terminal = new MemoryTerminalRepository();
        final MemoryIpcRepository ipc = new MemoryIpcRepository();
        final MemoryPackageRepository packages = new MemoryPackageRepository();
        final MemoryAuthRepository auth = new MemoryAuthRepository();
        final MemoryAuditRepository audit = new MemoryAuditRepository();
        int runtimeTransactions;
        int userTransactions;
        UUID lastUser;
        Isolation lastIsolation;

        @Override
        public <T> T inTransaction(Isolation isolation, TransactionWork<T> work) {
            runtimeTransactions++;
            lastIsolation = isolation;
            return work.execute(this);
        }

        @Override
        public <T> T inUserTransaction(UUID userId, Isolation isolation,
                                       TransactionWork<T> work) {
            userTransactions++;
            lastUser = userId;
            lastIsolation = isolation;
            return work.execute(this);
        }

        @Override public ProgramRepository programs() { return programs; }
        @Override public ProcessRepository processes() { return processes; }
        @Override public SchedulerRepository scheduler() { return scheduler; }
        @Override public VfsRepository vfs() { return vfs; }
        @Override public IpcRepository ipc() { return ipc; }
        @Override public TimerRepository timers() { return null; }
        @Override public PackageRepository packages() { return packages; }
        @Override public EffectRepository effects() { return null; }
        @Override public AuthRepository auth() { return auth; }
        @Override public AuditRepository audit() { return audit; }
        @Override public TerminalRepository terminal() { return terminal; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }

    static final class MemoryProgramRepository implements ProgramRepository {
        final Map<UUID, Program> byId = new LinkedHashMap<>();
        final Map<ObjectHash, Program> byHash = new LinkedHashMap<>();

        @Override
        public Optional<Program> findById(UUID programId) {
            return Optional.ofNullable(byId.get(programId));
        }

        @Override
        public Optional<Program> findByIdentity(ObjectHash programHash, String languageVersion,
                                                int runtimeFormatVersion) {
            return Optional.ofNullable(byHash.get(programHash)).filter(program ->
                    program.languageVersion().equals(languageVersion)
                            && program.runtimeFormatVersion() == runtimeFormatVersion);
        }

        @Override
        public Program saveIfAbsent(Program program) {
            Program existing = byHash.get(program.programHash());
            if (existing != null) return existing;
            byId.put(program.programId(), program);
            byHash.put(program.programHash(), program);
            return program;
        }
    }

    static final class MemoryVfsRepository implements VfsRepository {
        final Map<ObjectHash, StoredObject> objects = new LinkedHashMap<>();

        @Override public void saveObject(StoredObject object) {
            objects.putIfAbsent(object.objectHash(), object);
        }
        @Override public Optional<StoredObject> findObject(ObjectHash hash) {
            return Optional.ofNullable(objects.get(hash));
        }
        @Override public Optional<VfsNode> findNode(UUID nodeId) { return Optional.empty(); }
        @Override public Optional<VfsNode> findChild(UUID ownerId, Optional<UUID> parentNodeId,
                                                     String name) {
            return Optional.empty();
        }
        @Override public void insertNode(VfsNode node) { throw new UnsupportedOperationException(); }
        @Override public boolean replaceContent(UUID nodeId, Optional<ObjectHash> expected,
                                                ObjectHash replacement, Instant at) {
            throw new UnsupportedOperationException();
        }
        @Override public FileRevision appendRevision(UUID revisionId, UUID nodeId, UUID ownerId,
                ObjectHash objectHash, UUID createdBy, Instant createdAt) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<FileRevision> findRevision(UUID nodeId, long revisionNumber) {
            return Optional.empty();
        }
        @Override public List<FileRevision> findRevisions(UUID nodeId) { return List.of(); }
        @Override public void insertMount(VfsMount mount) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<VfsMount> findMount(UUID mountId) { return Optional.empty(); }
        @Override public List<VfsMount> findMounts(UUID ownerId) { return List.of(); }
        @Override public boolean disableMount(UUID mountId, UUID ownerId) { return false; }
    }

    static final class MemoryProcessRepository implements ProcessRepository {
        CilProcess current;
        UpdateResult forcedResult;
        int updates;
        long nextPid = 1;

        @Override public long allocatePid() { return nextPid++; }
        @Override public Optional<CilProcess> findByUid(UUID processUid) {
            return current != null && current.identity().processUid().equals(processUid)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<CilProcess> findByPid(long pid) {
            return current != null && current.identity().pid() == pid
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public void insert(CilProcess process) { current = process; }
        @Override public UpdateResult update(CilProcess process, long expectedVersion,
                                             long expectedEpoch) {
            updates++;
            if (forcedResult != null) return forcedResult;
            if (current == null || current.executionEpoch() != expectedEpoch) {
                return UpdateResult.EPOCH_FENCED;
            }
            if (current.stateVersion() != expectedVersion) {
                return UpdateResult.VERSION_CONFLICT;
            }
            current = process;
            return UpdateResult.UPDATED;
        }
        @Override public UpdateResult updateClaimed(CilProcess process, long expectedVersion,
                                                    SchedulerClaim claim) {
            if (!process.identity().processUid().equals(claim.processUid())
                    || !process.ownerId().equals(claim.ownerId())) {
                return UpdateResult.EPOCH_FENCED;
            }
            return update(process, expectedVersion, claim.executionEpoch());
        }
    }

    static final class MemorySchedulerRepository implements SchedulerRepository {
        final MemoryProcessRepository processes;
        SchedulerClaim lease;
        boolean leaseValid = true;
        int heartbeats;
        int releases;
        int enqueues;

        MemorySchedulerRepository(MemoryProcessRepository processes) {
            this.processes = processes;
        }

        @Override public void enqueue(SchedulerQueueEntry entry) { enqueues++; }
        @Override public Optional<SchedulerClaim> claimNext(UUID runnerId, UUID bootId,
                Instant now, Duration leaseDuration) { return Optional.empty(); }
        @Override public boolean heartbeat(SchedulerClaim claim) {
            heartbeats++;
            return leaseValid && claim.equals(lease);
        }
        @Override public void release(UUID processUid, long executionEpoch) {
            releases++;
            if (lease != null && lease.processUid().equals(processUid)
                    && lease.executionEpoch() == executionEpoch) lease = null;
        }
        @Override public int releaseExpired(Instant now) { return 0; }
    }

    static final class MemoryIpcRepository implements IpcRepository {
        final Map<UUID, IpcMessage> messages = new LinkedHashMap<>();
        final Map<UUID, IpcDelivery> deliveries = new LinkedHashMap<>();

        @Override public void saveChannel(IpcChannel channel) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<IpcChannel> findChannel(UUID channelId) {
            return Optional.empty();
        }
        @Override public void saveTopic(IpcTopic topic) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<IpcTopic> findTopic(String topicName) {
            return Optional.empty();
        }
        @Override public void saveSubscription(IpcSubscription subscription) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<UUID> selectChannelReceiver(UUID channelId) {
            return Optional.empty();
        }
        @Override public List<UUID> findTopicReceivers(UUID topicId) { return List.of(); }
        @Override public void saveMessage(IpcMessage message) {
            messages.put(message.messageId(), message);
        }
        @Override public void saveDeliveries(List<IpcDelivery> newDeliveries) {
            newDeliveries.forEach(delivery -> deliveries.put(delivery.deliveryId(), delivery));
        }
        @Override public Optional<IpcMessage> findMessage(UUID messageId) {
            return Optional.ofNullable(messages.get(messageId));
        }
        @Override public Optional<IpcDelivery> findDelivery(UUID deliveryId) {
            return Optional.ofNullable(deliveries.get(deliveryId));
        }
        @Override public List<IpcDelivery> findPending(UUID receiverProcessUid, int limit) {
            return deliveries.values().stream()
                    .filter(delivery -> delivery.receiverProcessUid().equals(receiverProcessUid))
                    .filter(delivery -> delivery.status() == IpcDelivery.Status.PENDING)
                    .limit(limit).toList();
        }
        @Override public boolean updateDelivery(IpcDelivery delivery,
                                                IpcDelivery.Status expectedStatus) {
            IpcDelivery current = deliveries.get(delivery.deliveryId());
            if (current == null || current.status() != expectedStatus) return false;
            deliveries.put(delivery.deliveryId(), delivery);
            return true;
        }
    }

    static final class MemoryTerminalRepository implements TerminalRepository {
        boolean interrupt;
        final Map<UUID, TerminalSession> sessions = new LinkedHashMap<>();
        final Map<UUID, TerminalSession.Attachment> attachments = new LinkedHashMap<>();
        final List<TerminalSession.Input> inputs = new java.util.ArrayList<>();

        @Override public void saveSession(TerminalSession session) {
            sessions.put(session.sessionId(), session);
        }
        @Override public Optional<TerminalSession> findSession(UUID sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }
        @Override public void appendInput(TerminalSession.Input input) { inputs.add(input); }
        @Override public void saveAttachment(TerminalSession.Attachment attachment) {
            attachments.entrySet().removeIf(entry -> entry.getValue().sessionId()
                    .equals(attachment.sessionId()) && entry.getValue().detachedAt().isEmpty());
            attachments.put(attachment.processUid(), attachment);
        }
        @Override public Optional<TerminalSession.Attachment> findAttachment(
                UUID sessionId, UUID processUid) {
            return Optional.ofNullable(attachments.get(processUid))
                    .filter(value -> value.sessionId().equals(sessionId));
        }
        @Override public Optional<TerminalSession.Attachment> findActiveAttachment(UUID sessionId) {
            return attachments.values().stream()
                    .filter(value -> value.sessionId().equals(sessionId))
                    .filter(value -> value.detachedAt().isEmpty()).findFirst();
        }
        @Override public Optional<TerminalSession.Input> acceptPendingInput(
                UUID processUid, Instant at) {
            return Optional.empty();
        }
        @Override public void requestInterrupt(TerminalSession.Interrupt requested) {
            interrupt = requested.handledAt().isEmpty();
        }
        @Override public boolean consumeInterrupt(UUID processUid) {
            boolean consumed = interrupt;
            interrupt = false;
            return consumed;
        }
    }

    static final class MemoryPackageRepository implements PackageRepository {
        final Map<String, ProcessPackageBinding> processBindings = new LinkedHashMap<>();
        final Map<PackageRelease.Hash, PackageRelease> releases = new LinkedHashMap<>();
        final Map<UUID, PackageEnvironment> environments = new LinkedHashMap<>();
        final Map<String, PackageBinding> bindings = new LinkedHashMap<>();

        @Override public ReleaseWriteResult registerRelease(PackageIndex packageIndex) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<PackageRelease> findRelease(PackageRelease.Hash packageHash) {
            return Optional.ofNullable(releases.get(packageHash));
        }
        @Override public Optional<PackageRelease> findRelease(
                PackageRelease.Coordinate coordinate) {
            return Optional.empty();
        }
        @Override public void saveEnvironment(PackageEnvironment environment) {
            environments.put(environment.environmentId(), environment);
        }
        @Override public Optional<PackageEnvironment> findEnvironment(UUID environmentId) {
            return Optional.ofNullable(environments.get(environmentId));
        }
        @Override public void saveBinding(PackageBinding binding) {
            bindings.put(binding.environmentId() + ":" + binding.binding(), binding);
        }
        @Override public Optional<PackageBinding> findBinding(UUID environmentId, String binding) {
            return Optional.ofNullable(bindings.get(environmentId + ":" + binding));
        }
        @Override public void saveProcessBinding(ProcessPackageBinding binding) {
            processBindings.put(binding.processUid() + ":" + binding.importName(), binding);
        }
        @Override public Optional<ProcessPackageBinding> findProcessBinding(
                UUID processUid, String importName) {
            return Optional.ofNullable(processBindings.get(processUid + ":" + importName));
        }
        @Override public List<ProcessPackageBinding> findProcessBindings(UUID processUid) {
            return processBindings.values().stream()
                    .filter(binding -> binding.processUid().equals(processUid))
                    .sorted(java.util.Comparator.comparing(ProcessPackageBinding::importName))
                    .toList();
        }
    }

    static final class MemoryAuthRepository implements AuthRepository {
        @Override public Optional<UserAccount> findUser(UUID userId) { return Optional.empty(); }
        @Override public Optional<UserAccount> findUser(String username) { return Optional.empty(); }
        @Override public void saveUser(UserAccount user) { }
        @Override public String provisionPrincipal(UUID userId, char[] password) {
            return "cilexec_user_" + userId.toString().replace("-", "");
        }
        @Override public void disablePrincipal(UUID userId) { }
        @Override public Set<Capability> capabilities(UUID userId) {
            return Set.copyOf(java.util.EnumSet.allOf(Capability.class));
        }
        @Override public void replaceCapabilities(UUID userId, Set<Capability> capabilities) { }
    }

    static final class MemoryAuditRepository implements AuditRepository {
        final List<AuditEvent> events = new java.util.ArrayList<>();
        @Override public void append(AuditEvent event) { events.add(event); }
        @Override public List<AuditEvent> findByResource(
                String resourceType, String resourceId, int limit) {
            return events.stream().filter(event -> event.resourceType().equals(resourceType)
                    && event.resourceId().equals(resourceId)).limit(limit).toList();
        }
        @Override public void saveRetentionPolicy(AuditRetentionPolicy policy) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<AuditRetentionPolicy> findRetentionPolicy(String eventType) {
            return Optional.empty();
        }
        @Override public int purgeExpired(int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
