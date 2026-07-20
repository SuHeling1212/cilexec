package com.follarce.kernel.process;

import com.follarce.kernel.Constants;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Atomically reserves process filenames for every process creation path. */
public final class ProcessFileAllocator {
    private static final ReentrantLock PID_ALLOC_LOCK = new ReentrantLock(true);

    private ProcessFileAllocator() {}

    public record Reservation(int pid, String generation, String token) {}

    public static Reservation reserve(String effectId, int reservedByPid,
                                      String reservedByGeneration) {
        PID_ALLOC_LOCK.lock();
        try {
            String processDir = PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH);
            Set<Integer> unavailable = unavailablePids(processDir);
            int pid = 2;
            while (true) {
                if (unavailable.contains(pid)) {
                    pid++;
                    continue;
                }

                String generation = ProcessIdentity.newGeneration();
                String token = UUID.randomUUID().toString();
                Map<String, Object> reservation = reservationData(
                        pid, generation, effectId, reservedByPid, reservedByGeneration, token);
                Path target = Path.of(processDir, pid + ".proc");
                try {
                    writeNewReservation(target, reservation);
                    return new Reservation(pid, generation, token);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    unavailable.add(pid);
                    pid++;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to reserve PID " + pid, e);
                }
            }
        } finally {
            PID_ALLOC_LOCK.unlock();
        }
    }

    /** Replaces exactly one reservation with a complete process snapshot. */
    public static void publish(Reservation reservation, Map<String, Object> processData) {
        String path = Constants.SYSTEM_PROCESS_PATH + reservation.pid() + ".proc";
        ReentrantLock fileLock = JsonUtil.lockFile(path);
        try {
            Map<String, Object> current = JsonUtil.parseToMapStrict(FileUtil.read(path));
            if (!Boolean.TRUE.equals(current.get("Reservation"))
                    || !reservation.generation().equals(current.get("ProcessGeneration"))
                    || !reservation.token().equals(current.get("ReservationToken"))) {
                throw new IllegalStateException("PID reservation no longer belongs to this creator: "
                        + reservation.pid());
            }

            Map<String, Object> snapshot = JsonUtil.deepCopy(processData);
            snapshot.put("PID", reservation.pid());
            snapshot.put("ProcessGeneration", reservation.generation());
            snapshot.remove("Reservation");
            snapshot.remove("ReservationToken");
            snapshot.remove("ReservedByPid");
            snapshot.remove("ReservedByGeneration");
            FileUtil.writeAtomic(path, JsonUtil.toMetaJson(snapshot));
        } finally {
            fileLock.unlock();
        }
    }

    /** Removes a failed reservation without touching a process that reused the PID. */
    public static void release(Reservation reservation) {
        String path = Constants.SYSTEM_PROCESS_PATH + reservation.pid() + ".proc";
        ReentrantLock fileLock = JsonUtil.lockFile(path);
        try {
            if (!FileUtil.exists(path)) return;
            Map<String, Object> current = JsonUtil.parseToMapStrict(FileUtil.read(path));
            if (Boolean.TRUE.equals(current.get("Reservation"))
                    && reservation.generation().equals(current.get("ProcessGeneration"))
                    && reservation.token().equals(current.get("ReservationToken"))) {
                FileUtil.removeFile(path);
            }
        } finally {
            fileLock.unlock();
        }
    }

    private static Map<String, Object> reservationData(int pid, String generation, String effectId,
                                                       int reservedByPid, String reservedByGeneration,
                                                       String token) {
        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("Name", "RESERVED-" + pid);
        reservation.put("Owner", Constants.DEFAULT_USER_LOCAL);
        reservation.put("PID", pid);
        reservation.put("ProcessState", ProcessState.PAUSED.name());
        reservation.put("ProcessGeneration", generation);
        reservation.put("CreatedByEffectId", effectId);
        reservation.put("Reservation", true);
        if (token != null) reservation.put("ReservationToken", token);
        reservation.put("ReservedByPid", reservedByPid);
        reservation.put("ReservedByGeneration", reservedByGeneration);

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", new ArrayList<String>());
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("Data", new LinkedHashMap<String, Object>());
        program.put("Code", code);
        reservation.put("Program", program);
        return reservation;
    }

    static void writeNewReservation(Path target, Map<String, Object> reservation) throws IOException {
        PID_ALLOC_LOCK.lock();
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) throw new java.nio.file.FileAlreadyExistsException(target.toString());
            Path temp = target.resolveSibling(
                    target.getFileName() + ".reservation-" + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temp, JsonUtil.toJson(reservation),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                if (Files.exists(target)) {
                    throw new java.nio.file.FileAlreadyExistsException(target.toString());
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(target.getParent());
            } finally {
                Files.deleteIfExists(temp);
            }
        } finally {
            PID_ALLOC_LOCK.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> unavailablePids(String processDir) {
        Set<Integer> result = new HashSet<>();
        File[] files = new File(processDir).listFiles(
                (directory, name) -> name.matches("\\d+\\.proc(?:\\.tmp)?"));
        if (files == null) return result;
        for (File file : files) {
            String name = file.getName();
            try {
                int filePid = Integer.parseInt(name.substring(0, name.indexOf('.')));
                result.add(filePid);
                String path = Constants.SYSTEM_PROCESS_PATH + filePid + ".proc";
                if (!FileUtil.exists(path)) continue;
                Map<String, Object> data = JsonUtil.parseToMapStrict(FileUtil.read(path));
                Object exited = data.get("ExitedChildren");
                if (exited instanceof Map) {
                    for (String exitedPid : ((Map<String, Object>) exited).keySet()) {
                        result.add(Integer.parseInt(exitedPid));
                    }
                }
            } catch (Exception ignored) {
                // A malformed or temporary snapshot still reserves its filename PID.
            }
        }
        return result;
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
