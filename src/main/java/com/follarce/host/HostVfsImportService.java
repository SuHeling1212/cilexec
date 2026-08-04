package com.follarce.host;

import com.follarce.auth.Authorization;
import com.follarce.auth.UsernamePolicy;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.domain.vfs.VfsFileLimits;
import com.follarce.fcl.FclPath;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Host-tool use case that streams one regular host file into the database-backed VFS. */
public final class HostVfsImportService {
    public static final long MAX_FILE_BYTES = VfsFileLimits.MAX_FILE_BYTES;
    static final int CHUNK_BYTES = 4 * 1024 * 1024;

    private final JdbcTransactionExecutor transactions;
    private final Clock clock;

    public HostVfsImportService(JdbcTransactionExecutor transactions, Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public ImportReport importFile(Path source, String requestedTarget, String username) {
        Path input = java.util.Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (!Files.isRegularFile(input) || Files.isSymbolicLink(input)) {
            throw new IllegalArgumentException("Host source must be a regular non-symlink file: "
                    + input);
        }
        long expectedSize = fileSize(input);
        if (expectedSize > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Host source exceeds the 1 GiB VFS file limit");
        }
        String target = FclPath.resolve("/", requestedTarget);
        if (target.equals("/")) throw new IllegalArgumentException(
                "VFS target must include a file name");
        String ownerName = UsernamePolicy.normalize(username);
        if (ownerName.equals("local")) {
            throw new IllegalArgumentException(
                    "host move to the local superuser is not allowed; import to a named user "
                            + "that holds VFS_MOUNT_HOST");
        }
        UUID ownerId = transactions.inTransaction(Isolation.READ_COMMITTED, transaction ->
                transaction.auth().findUser(ownerName)
                        .filter(account -> account.status() == UserAccount.Status.ACTIVE)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown or disabled CilExec user: " + ownerName)).userId());
        precheckTarget(ownerId, target);
        String mediaType = mediaType(input);
        ObjectHash objectHash = streamObject(ownerId, input, mediaType, expectedSize);
        Instant now = clock.instant();
        VfsNode node = transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE,
                transaction -> {
                    requireMountCapabilities(transaction, ownerId);
                    Target resolved = resolveTarget(transaction, ownerId, target);
                    if (transaction.vfs().findChild(ownerId,
                            Optional.of(resolved.parent().nodeId()), resolved.name()).isPresent()) {
                        throw new IllegalArgumentException("VFS target already exists: " + target);
                    }
                    if (transaction.vfs().logicalObjectSize(objectHash) != expectedSize) {
                        throw new IllegalStateException("Imported VFS object size changed");
                    }
                    VfsNode created = new VfsNode(UUID.randomUUID(),
                            Optional.of(resolved.parent().nodeId()), ownerId, resolved.name(),
                            VfsNode.Type.FILE, Optional.of(objectHash), Set.of(), false, now, now);
                    transaction.vfs().insertNode(created);
                    transaction.audit().append(new AuditEvent(UUID.randomUUID(),
                            AuditEvent.ActorType.USER, ownerId.toString(), "host.vfs.import",
                            "vfs.node", created.nodeId().toString(), AuditEvent.Result.SUCCEEDED,
                            Map.of("bytes", Long.toString(expectedSize), "path", target,
                                    "mediaType", mediaType), now));
                    return created;
                });
        return new ImportReport(node.nodeId(), ownerName, target, expectedSize, objectHash);
    }

    private void precheckTarget(UUID ownerId, String target) {
        transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            requireMountCapabilities(transaction, ownerId);
            Target resolved = resolveTarget(transaction, ownerId, target);
            if (transaction.vfs().findChild(ownerId,
                    Optional.of(resolved.parent().nodeId()), resolved.name()).isPresent()) {
                throw new IllegalArgumentException("VFS target already exists: " + target);
            }
            return null;
        });
    }

    private static void requireMountCapabilities(
            com.follarce.domain.port.TransactionContext transaction, UUID ownerId) {
        Authorization.require(transaction, ownerId, Capability.VFS_WRITE);
        Authorization.require(transaction, ownerId, Capability.VFS_MOUNT_HOST);
    }

    private ObjectHash streamObject(UUID ownerId, Path source, String mediaType,
                                    long expectedSize) {
        ObjectHash current = null;
        long transferred = 0;
        byte[] buffer = new byte[CHUNK_BYTES];
        try (var channel = Files.newByteChannel(source, StandardOpenOption.READ,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
             InputStream input = java.nio.channels.Channels.newInputStream(channel)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                transferred = Math.addExact(transferred, count);
                if (transferred > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("Host source changed beyond the 1 GiB limit");
                }
                byte[] chunk = Arrays.copyOf(buffer, count);
                ObjectHash previous = current;
                current = transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED,
                        transaction -> {
                            requireMountCapabilities(transaction, ownerId);
                            if (previous == null) {
                                StoredObject first = StoredObject.create(new BinaryContent(chunk),
                                        mediaType, clock.instant());
                                transaction.vfs().saveObject(first);
                                return first.objectHash();
                            }
                            return transaction.vfs().appendChunkedObject(previous, chunk,
                                    mediaType, clock.instant()).objectHash();
                        });
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read host source: " + source, failure);
        }
        if (transferred != expectedSize) {
            throw new IllegalStateException("Host source changed while it was being imported");
        }
        if (current != null) return current;
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            requireMountCapabilities(transaction, ownerId);
            StoredObject empty = StoredObject.create(new BinaryContent(new byte[0]), mediaType,
                    clock.instant());
            transaction.vfs().saveObject(empty);
            return empty.objectHash();
        });
    }

    private static Target resolveTarget(com.follarce.domain.port.TransactionContext transaction,
                                        UUID ownerId, String target) {
        int separator = target.lastIndexOf('/');
        String parentPath = separator == 0 ? "/" : target.substring(0, separator);
        String name = target.substring(separator + 1);
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid VFS target file name");
        }
        VfsNode current = transaction.vfs().findChild(ownerId, Optional.empty(), "/")
                .orElseThrow(() -> new IllegalStateException("User VFS root is missing"));
        if (!parentPath.equals("/")) {
            for (String part : parentPath.substring(1).split("/")) {
                if (current.type() != VfsNode.Type.DIRECTORY) {
                    throw new IllegalArgumentException("VFS target parent is not a directory");
                }
                current = transaction.vfs().findChild(ownerId, Optional.of(current.nodeId()), part)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown VFS target directory: " + parentPath));
            }
        }
        if (current.type() != VfsNode.Type.DIRECTORY) {
            throw new IllegalArgumentException("VFS target parent is not a directory");
        }
        return new Target(current, name);
    }

    private static long fileSize(Path source) {
        try {
            return Files.size(source);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect host source: " + source, failure);
        }
    }

    private static String mediaType(Path source) {
        try {
            String detected = Files.probeContentType(source);
            return detected == null || detected.isBlank()
                    ? "application/octet-stream" : detected;
        } catch (IOException ignored) {
            return "application/octet-stream";
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(
                name + " must not be blank");
        return value.trim();
    }

    private record Target(VfsNode parent, String name) {
    }

    public record ImportReport(UUID nodeId, String username, String vfsPath, long bytes,
                               ObjectHash objectHash) {
    }
}
