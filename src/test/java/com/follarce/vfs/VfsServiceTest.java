package com.follarce.vfs;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
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
import com.follarce.domain.port.TransactionWork;
import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.FileRevision;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsMount;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VfsServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");

    @Test
    void versionedFileWritesAndReadsEveryImmutableRevision() {
        MemoryPersistence persistence = persistence(Capability.VFS_READ, Capability.VFS_WRITE);
        UUID ownerId = UUID.randomUUID();
        VfsNode root = root(ownerId);
        persistence.vfs.insertNode(root);
        VfsService service = service(persistence, Set.of());

        VfsNode file = service.createFile(ownerId, root.nodeId(), "state.fcl",
                bytes("first"), "text/plain", Set.of("read"), true);
        VfsNode changed = service.replaceContent(ownerId, file.nodeId(),
                file.currentObjectHash().orElseThrow(), bytes("second"), "text/plain");

        List<FileRevision> revisions = service.fileRevisions(ownerId, file.nodeId());
        assertEquals(List.of(1L, 2L), revisions.stream()
                .map(FileRevision::revisionNumber).toList());
        assertEquals(file.currentObjectHash().orElseThrow(), revisions.get(0).objectHash());
        assertEquals(changed.currentObjectHash().orElseThrow(), revisions.get(1).objectHash());
        assertArrayEquals(bytes("first"),
                service.readRevision(ownerId, file.nodeId(), 1).content().bytes());
        assertArrayEquals(bytes("second"),
                service.readRevision(ownerId, file.nodeId(), 2).content().bytes());
    }

    @Test
    void unversionedFileWritesNoHistory() {
        MemoryPersistence persistence = persistence(Capability.VFS_READ, Capability.VFS_WRITE);
        UUID ownerId = UUID.randomUUID();
        VfsNode root = root(ownerId);
        persistence.vfs.insertNode(root);
        VfsService service = service(persistence, Set.of());

        VfsNode file = service.createFile(ownerId, root.nodeId(), "current-only.fcl",
                bytes("first"), "text/plain", Set.of(), false);
        service.replaceContent(ownerId, file.nodeId(), file.currentObjectHash().orElseThrow(),
                bytes("second"), "text/plain");

        assertTrue(persistence.vfs.findRevisions(file.nodeId()).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> service.fileRevisions(ownerId, file.nodeId()));
    }

    @Test
    void declaresQueriesAndDisablesOnlyConfiguredReadOnlyMounts() {
        MemoryPersistence persistence = persistence(Capability.VFS_WRITE,
                Capability.VFS_MOUNT_HOST);
        UUID ownerId = UUID.randomUUID();
        VfsNode root = root(ownerId);
        persistence.vfs.insertNode(root);
        VfsService service = service(persistence, Set.of("project-data"));

        VfsMount mount = service.declareMount(ownerId, root.nodeId(), "project",
                "project-data", "/srv/cilexec/project", Set.of("read"));

        assertTrue(mount.readOnly());
        assertEquals(VfsMount.HOST_CAPABILITY, mount.requiredCapability());
        assertEquals(VfsMount.Status.ACTIVE, mount.status());
        assertEquals(VfsNode.Type.MOUNT,
                persistence.vfs.findNode(mount.nodeId()).orElseThrow().type());
        assertEquals(Optional.of(mount), service.findMount(ownerId, mount.mountId()));
        assertEquals(List.of(mount), service.mounts(ownerId));

        VfsMount disabled = service.disableMount(ownerId, mount.mountId());
        assertEquals(VfsMount.Status.DISABLED, disabled.status());
        assertFalse(persistence.vfs.findMount(mount.mountId()).orElseThrow().status()
                == VfsMount.Status.ACTIVE);
    }

    @Test
    void mountRequiresCapabilityWhitelistAndSafeContainerPath() {
        UUID ownerId = UUID.randomUUID();
        MemoryPersistence persistence = persistence(Capability.VFS_WRITE);
        VfsNode root = root(ownerId);
        persistence.vfs.insertNode(root);
        VfsService service = service(persistence, Set.of("project-data"));

        assertThrows(SecurityException.class, () -> service.declareMount(ownerId,
                root.nodeId(), "project", "project-data", "/srv/project", Set.of()));

        persistence.capabilities.add(Capability.VFS_MOUNT_HOST);
        assertThrows(SecurityException.class, () -> service.declareMount(ownerId,
                root.nodeId(), "project", "host-path", "/srv/project", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> service.declareMount(ownerId,
                root.nodeId(), "project", "project-data", "../host", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> service.declareMount(ownerId,
                root.nodeId(), "project", "project-data", "/srv/../host", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> service.declareMount(ownerId,
                root.nodeId(), "project", "project-data", "/", Set.of()));
        assertTrue(persistence.vfs.mounts.isEmpty());
    }

    private static VfsService service(MemoryPersistence persistence, Set<String> sourceKeys) {
        return new VfsService(persistence, Clock.fixed(NOW, ZoneOffset.UTC), sourceKeys);
    }

    private static MemoryPersistence persistence(Capability... capabilities) {
        return new MemoryPersistence(EnumSet.copyOf(List.of(capabilities)));
    }

    private static VfsNode root(UUID ownerId) {
        return new VfsNode(UUID.randomUUID(), Optional.empty(), ownerId, "/",
                VfsNode.Type.DIRECTORY, Optional.empty(), Set.of(), false, NOW, NOW);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class MemoryPersistence implements UserTransactionExecutor,
            TransactionContext {
        private final MemoryVfsRepository vfs = new MemoryVfsRepository();
        private final Set<Capability> capabilities;
        private final List<AuditEvent> auditEvents = new ArrayList<>();

        private MemoryPersistence(Set<Capability> capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public <T> T inUserTransaction(UUID userId, Isolation isolation,
                                       TransactionWork<T> work) {
            return work.execute(this);
        }

        @Override public VfsRepository vfs() { return vfs; }
        @Override public AuthRepository auth() { return new AuthRepository() {
            @Override public Optional<UserAccount> findUser(UUID userId) { return Optional.empty(); }
            @Override public Optional<UserAccount> findUser(String username) { return Optional.empty(); }
            @Override public void saveUser(UserAccount user) { }
            @Override public String provisionPrincipal(UUID userId, char[] password) {
                throw new UnsupportedOperationException();
            }
            @Override public void disablePrincipal(UUID userId) { }
            @Override public Set<Capability> capabilities(UUID userId) {
                return Set.copyOf(capabilities);
            }
            @Override public void replaceCapabilities(UUID userId, Set<Capability> replacement) {
                capabilities.clear();
                capabilities.addAll(replacement);
            }
        }; }
        @Override public AuditRepository audit() { return new AuditRepository() {
            @Override public void append(AuditEvent event) { auditEvents.add(event); }
            @Override public List<AuditEvent> findByResource(String type, String id, int limit) {
                return auditEvents.stream().filter(event -> event.resourceType().equals(type)
                        && event.resourceId().equals(id)).limit(limit).toList();
            }
        }; }
        @Override public ProgramRepository programs() { return null; }
        @Override public ProcessRepository processes() { return null; }
        @Override public SchedulerRepository scheduler() { return null; }
        @Override public IpcRepository ipc() { return null; }
        @Override public TimerRepository timers() { return null; }
        @Override public PackageRepository packages() { return null; }
        @Override public EffectRepository effects() { return null; }
        @Override public TerminalRepository terminal() { return null; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }

    private static final class MemoryVfsRepository implements VfsRepository {
        private final Map<ObjectHash, StoredObject> objects = new LinkedHashMap<>();
        private final Map<UUID, VfsNode> nodes = new LinkedHashMap<>();
        private final Map<UUID, List<FileRevision>> revisions = new LinkedHashMap<>();
        private final Map<UUID, VfsMount> mounts = new LinkedHashMap<>();

        @Override public void saveObject(StoredObject object) {
            objects.putIfAbsent(object.objectHash(), object);
        }
        @Override public Optional<StoredObject> findObject(ObjectHash objectHash) {
            return Optional.ofNullable(objects.get(objectHash));
        }
        @Override public Optional<VfsNode> findNode(UUID nodeId) {
            return Optional.ofNullable(nodes.get(nodeId));
        }
        @Override public Optional<VfsNode> findChild(UUID ownerId, Optional<UUID> parentNodeId,
                                                     String name) {
            return nodes.values().stream().filter(node -> node.ownerId().equals(ownerId)
                    && node.parentNodeId().equals(parentNodeId) && node.name().equals(name))
                    .findFirst();
        }
        @Override public void insertNode(VfsNode node) { nodes.put(node.nodeId(), node); }
        @Override public boolean replaceContent(UUID nodeId, Optional<ObjectHash> expected,
                                                ObjectHash replacement, Instant at) {
            VfsNode current = nodes.get(nodeId);
            if (current == null || !current.currentObjectHash().equals(expected)) return false;
            nodes.put(nodeId, current.replaceContent(replacement, at));
            return true;
        }
        @Override public FileRevision appendRevision(UUID revisionId, UUID nodeId, UUID ownerId,
                ObjectHash objectHash, UUID createdBy, Instant createdAt) {
            List<FileRevision> history = revisions.computeIfAbsent(nodeId,
                    ignored -> new ArrayList<>());
            FileRevision revision = new FileRevision(revisionId, nodeId, ownerId,
                    history.size() + 1L, objectHash, createdBy, createdAt);
            history.add(revision);
            return revision;
        }
        @Override public Optional<FileRevision> findRevision(UUID nodeId, long revisionNumber) {
            return findRevisions(nodeId).stream()
                    .filter(revision -> revision.revisionNumber() == revisionNumber).findFirst();
        }
        @Override public List<FileRevision> findRevisions(UUID nodeId) {
            return List.copyOf(revisions.getOrDefault(nodeId, List.of()));
        }
        @Override public void insertMount(VfsMount mount) { mounts.put(mount.mountId(), mount); }
        @Override public Optional<VfsMount> findMount(UUID mountId) {
            return Optional.ofNullable(mounts.get(mountId));
        }
        @Override public List<VfsMount> findMounts(UUID ownerId) {
            return mounts.values().stream().filter(mount -> mount.ownerId().equals(ownerId))
                    .toList();
        }
        @Override public boolean disableMount(UUID mountId, UUID ownerId) {
            VfsMount current = mounts.get(mountId);
            if (current == null || !current.ownerId().equals(ownerId)
                    || current.status() != VfsMount.Status.ACTIVE) return false;
            mounts.put(mountId, current.disable());
            return true;
        }
    }
}
