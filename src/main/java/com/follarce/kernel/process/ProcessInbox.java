package com.follarce.kernel.process;

import com.follarce.kernel.Constants;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** File-backed inbox with per-incarnation ordering and idempotent publication. */
public final class ProcessInbox {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_GATES = new ConcurrentHashMap<>();

    private ProcessInbox() {}

    public static ProcessMessage publish(int targetPid, String targetGeneration,
                                         String messageId, int senderPid,
                                         String senderGeneration, String field, Object value) {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId is required");
        Path root = Path.of(PathUtil.toRealPath(Constants.SYSTEM_PROCESS_INBOX_PATH));
        Path deliveries = root.resolve("deliveries");
        String messageHash = hash(messageId);
        Path deliveryPath = deliveries.resolve(messageHash + ".delivery");
        Path deliveryGatePath = deliveries.resolve(messageHash + ".lock");
        try {
            Files.createDirectories(deliveries);
            ReentrantLock localGate = LOCAL_GATES.computeIfAbsent(
                    deliveryGatePath.toAbsolutePath(), ignored -> new ReentrantLock());
            localGate.lock();
            try (FileChannel channel = FileChannel.open(deliveryGatePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                ProcessMessage message;
                if (Files.exists(deliveryPath)) {
                    ProcessMessage existing = read(deliveryPath);
                    if (!existing.field().equals(field)
                            || !JsonUtil.toJsonCompact(existing.value()).equals(JsonUtil.toJsonCompact(value))) {
                        throw new IllegalStateException("Message ID collision: " + messageId);
                    }
                    message = existing;
                } else {
                    Path directory = inboxDirectory(targetPid, targetGeneration);
                    Files.createDirectories(directory);
                    long sequence = allocateSequence(directory);
                    message = new ProcessMessage(ProcessMessage.SCHEMA_VERSION,
                            messageId, sequence, targetPid, targetGeneration, senderPid,
                            senderGeneration, field, JsonUtil.deepCopy(value), System.currentTimeMillis());
                    Path deliveryTemp = deliveries.resolve(messageHash + ".delivery.tmp");
                    writeAndForce(deliveryTemp, JsonUtil.toJson(message.toMap()));
                    Files.move(deliveryTemp, deliveryPath, StandardCopyOption.ATOMIC_MOVE);
                    forceDirectory(deliveries);
                }
                ensurePublished(message);
                return message;
            } finally {
                localGate.unlock();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to publish process message " + messageId, e);
        }
    }

    public static List<ProcessMessage> list(int targetPid, String targetGeneration) {
        Path directory = inboxDirectory(targetPid, targetGeneration);
        if (!Files.isDirectory(directory)) return List.of();
        List<ProcessMessage> result = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".msg"))
                    .forEach(path -> result.add(read(path)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to list process inbox for PID " + targetPid, e);
        }
        result.sort(Comparator.comparingLong(ProcessMessage::sequence)
                .thenComparing(ProcessMessage::messageId));
        return result;
    }

    /** Re-publish messages whose durable delivery record survived a crash before inbox publication. */
    public static void recoverDeliveries() {
        Path deliveries = Path.of(PathUtil.toRealPath(Constants.SYSTEM_PROCESS_INBOX_PATH)).resolve("deliveries");
        if (!Files.isDirectory(deliveries)) return;
        try (var stream = Files.list(deliveries)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".delivery"))
                    .forEach(path -> {
                        try {
                            ensurePublished(read(path));
                        } catch (IOException | RuntimeException e) {
                            throw new RuntimeException("Failed to recover process delivery " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan process deliveries", e);
        }
    }

    public static void acknowledge(ProcessMessage message) {
        Path directory = inboxDirectory(message.targetPid(), message.targetGeneration());
        Path path = messagePath(directory, message.messageId());
        try {
            Files.deleteIfExists(path);
            forceDirectory(directory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to acknowledge process message " + message.messageId(), e);
        }
    }

    public static boolean isApplied(Map<String, Object> process, String messageId) {
        Object stateObject = process.get("InboxState");
        if (!(stateObject instanceof Map)) return false;
        Object receipts = ((Map<?, ?>) stateObject).get("AppliedMessageIds");
        return receipts instanceof Map && ((Map<?, ?>) receipts).containsKey(messageId);
    }

    @SuppressWarnings("unchecked")
    public static void recordApplied(Map<String, Object> process, ProcessMessage message) {
        Map<String, Object> state = (Map<String, Object>) process.computeIfAbsent(
                "InboxState", ignored -> new java.util.LinkedHashMap<String, Object>());
        Map<String, Object> receipts = (Map<String, Object>) state.computeIfAbsent(
                "AppliedMessageIds", ignored -> new java.util.LinkedHashMap<String, Object>());
        receipts.put(message.messageId(), message.sequence());
    }

    public static void removeIncarnation(int pid, String generation) {
        Path directory = inboxDirectory(pid, generation);
        if (!Files.exists(directory)) return;
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove process inbox for PID " + pid, e);
        }
    }

    private static ProcessMessage read(Path path) {
        try {
            Map<String, Object> map = JsonUtil.parseToMapStrict(Files.readString(path));
            ProcessMessage message = ProcessMessage.fromMap(map);
            if (message.schemaVersion() != ProcessMessage.SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported process message schema");
            }
            return message;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read process message: " + path, e);
        }
    }

    private static long allocateSequence(Path directory) throws IOException {
        Path gate = directory.resolve("inbox.lock");
        ReentrantLock localGate = LOCAL_GATES.computeIfAbsent(gate.toAbsolutePath(), ignored -> new ReentrantLock());
        localGate.lock();
        try (FileChannel channel = FileChannel.open(gate,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return nextSequenceLocked(directory);
        } finally {
            localGate.unlock();
        }
    }

    private static long nextSequenceLocked(Path directory) throws IOException {
        Path sequencePath = directory.resolve("sequence");
        long current = 0L;
        if (Files.exists(sequencePath)) {
            String value = Files.readString(sequencePath).trim();
            if (!value.isEmpty()) current = Long.parseLong(value);
        }
        if (current == Long.MAX_VALUE) throw new IllegalStateException("Process inbox sequence exhausted");
        long next = current + 1L;
        Path temp = directory.resolve("sequence.tmp");
        writeAndForce(temp, Long.toString(next));
        Files.move(temp, sequencePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return next;
    }

    private static void ensurePublished(ProcessMessage message) throws IOException {
        Path directory = inboxDirectory(message.targetPid(), message.targetGeneration());
        Files.createDirectories(directory);
        Path path = messagePath(directory, message.messageId());
        if (Files.exists(path)) return;
        Path temp = directory.resolve(path.getFileName() + ".tmp");
        writeAndForce(temp, JsonUtil.toJson(message.toMap()));
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            Files.deleteIfExists(temp);
        }
        forceDirectory(directory);
    }

    private static Path inboxDirectory(int pid, String generation) {
        if (generation == null || generation.isBlank()) throw new IllegalArgumentException("generation is required");
        return Path.of(PathUtil.toRealPath(Constants.SYSTEM_PROCESS_INBOX_PATH
                + pid + "/" + hash(generation) + "/"));
    }

    private static Path messagePath(Path directory, String messageId) {
        return directory.resolve(hash(messageId) + ".msg");
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void writeAndForce(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
