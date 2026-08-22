package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.auth.PasswordPolicy;
import com.follarce.auth.UsernamePolicy;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageInstallation;
import com.follarce.domain.packageinfo.PackageDataUsage;
import com.follarce.domain.packageinfo.PackageUninstallResult;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcTopic;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.EnvironmentRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.program.Program;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.ipc.IpcService;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.domain.vfs.VfsFileLimits;
import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclScope;
import com.follarce.fcl.FclValues;
import com.follarce.fcl.TerminalModeState;
import com.follarce.fcl.FclSuspension;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.extension.SourceExtensionIndex;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.follarce.package_manager.PackageCoordinateConflictException;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.package_manager.PackageDataService;
import com.follarce.package_manager.PackageDependencyPolicy;
import com.follarce.market.client.MarketRuntimeFunctions;
import com.follarce.terminal.TerminalAccessService;
import com.follarce.terminal.TerminalDimensions;
import com.follarce.timer.TimerService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class FclProcessRuntimeFunctions extends FclRuntimeFunctions {
    FclProcessRuntimeFunctions(FclRuntimeFunctions source) { super(source); }

    protected void registerProcesses() {
        registry.register("process", "getPID", args -> {
                    arity(args, 0, "process.getPID");
                    return process.identity().pid();
                })
                .register("process", "getPPID", args -> {
                    arity(args, 0, "process.getPPID");
                    return process.parentProcessUid()
                            .flatMap(transaction.processes()::findByUid)
                            .map(parent -> parent.identity().pid()).orElse(0L);
                })
                .register("process", "listChildren", args -> {
                    arity(args, 0, "process.listChildren");
                    return transaction.processes().findChildren(process.identity().processUid())
                            .stream().map(child -> child.identity().pid()).toList();
                })
                .register("process", "list", args -> {
                    arity(args, 0, "process.list");
                    return transaction.processes().findAll().stream()
                            .map(FclRuntimeFunctions::processMap).toList();
                })
                .registerContextual("process", "kill", (args, invocation) -> {
                    arity(args, 1, "process.kill");
                    long pid = integer(args.getFirst(), "process.kill pid");
                    if (pid == process.identity().pid()) {
                        invocation.continuation().exit(null);
                        return true;
                    }
                    return terminate(pid);
                })
                .register("process", "pause", args -> changeProcess(
                        integerAt(args, 0, 1, "process.pause"), true))
                .register("process", "continue", args -> changeProcess(
                        integerAt(args, 0, 1, "process.continue"), false))
                .registerContextual("process", "fork", (args, invocation) -> {
                    arity(args, 0, "process.fork");
                    Authorization.require(transaction, process.ownerId(),
                            Capability.PROCESS_CREATE);
                    UUID childUid = UUID.randomUUID();
                    long childPid = transaction.processes().allocatePid();
                    FclContinuation childRuntime = invocation.continuation().snapshot();
                    stripTerminalLifecycle(childRuntime);
                    childRuntime.cacheCallResult(invocation.expressionId(), 0L);
                    childRuntime.clearWait();
                    Continuation childContinuation = new FclPersistenceBridge(
                            new FclContinuationCodec()).persist(childUid, program,
                            process.continuation(), childRuntime);
                    CilProcess child = new CilProcess(new ProcessIdentity(childUid, childPid),
                            process.ownerId(), CilProcess.Status.READY, 0, 0, childContinuation,
                            Optional.of(process.identity().processUid()), now, now);
                    transaction.processes().insert(child);
                    transaction.scheduler().enqueue(
                            new com.follarce.domain.scheduler.SchedulerQueueEntry(childUid,
                                    now, now,
                                    com.follarce.domain.scheduler.SchedulerQueueEntry.Status.READY));
                    audit("process.fork", childUid, Map.of("pid", Long.toString(childPid)));
                    return childPid;
                })
                .registerContextual("process", "exec", (args, invocation) -> {
                    arity(args, 1, "process.exec");
                    Authorization.require(transaction, process.ownerId(),
                            Capability.PROCESS_CONTROL_OWN);
                    String requestedPath = string(args.getFirst(), "process.exec path");
                    // C-style resolution: relative paths resolve against the process
                    // working directory (cilexec.path.cwd); the resolved absolute path is
                    // what gets persisted with the suspension and audited.
                    String absolutePath = requestedPath.replace('\\', '/').startsWith("/")
                            ? normalize(requestedPath)
                            : FclPath.resolve(invocation.continuation(), requestedPath);
                    RoutedPath routed = route(absolutePath, process.ownerId());
                    if (!routed.ownerId().equals(process.ownerId())) {
                        throw new FclRuntimeException(
                                "process.exec accepts a file in the current user's VFS");
                    }
                    VfsNode sourceNode = requireNode(routed.path(), routed.ownerId());
                    requireType(sourceNode, VfsNode.Type.FILE, "process.exec");
                    if (absolutePath.toLowerCase(Locale.ROOT).endsWith(".db")) {
                        throw new FclRuntimeException(
                                "process.exec accepts an FCL source file, not a package database");
                    }
                    String source = readText(routed.path(), routed.ownerId());
                    String expanded = new FclSourceIncludes().expand(transaction,
                            process.ownerId(), source, parentDirectory(routed.path()));
                    String terminalLibrary = TerminalReplService.isTerminalProcess(
                            invocation.continuation())
                            ? TerminalReplService.librarySource(invocation.continuation()) : "";
                    Program target = createProgram(terminalLibrary + expanded);
                    invocation.continuation().waitFor("exec:" + target.programId(),
                            Map.of("path", absolutePath));
                    throw FclSuspension.suspend();
                })
                .registerContextual("process", "wait", (args, invocation) -> {
                    arity(args, 0, "process.wait");
                    Optional<CilProcess> active = transaction.processes()
                            .findChildren(process.identity().processUid()).stream()
                            .filter(child -> !child.isTerminal()).findFirst();
                    if (active.isEmpty()) return List.of();
                    return waitForProcess(active.orElseThrow(), invocation);
                })
                .registerContextual("process", "waitPID", (args, invocation) -> {
                    arity(args, 1, "process.waitPID");
                    CilProcess target = targetProcess(integer(args.getFirst(),
                            "process.waitPID pid"), "process.waitPID");
                    return waitForProcess(target, invocation);
                })
                .register("process", "removeFinished", args -> removeFinishedProcesses(args));
    }

    /**
     * Manually removes terminal processes and their persisted state. Without arguments it
     * removes every TERMINATED/FAILED process; with a PID it removes only that process when
     * it has already ended. Running, suspended, and waiting processes are never removed.
     */
    protected Object removeFinishedProcesses(List<Object> args) {
        if (args.size() > 1) throw new FclRuntimeException(
                "process.removeFinished expects zero arguments or one process PID");
        Authorization.requireAdministrator(transaction, process.ownerId());
        if (args.isEmpty()) {
            long deleted = transaction.processes().deleteTerminated();
            audit("process.removeFinished", process.identity().processUid(), Map.of(
                    "deleted", Long.toString(deleted)));
            return deleted;
        }
        long pid = integer(args.getFirst(), "process.removeFinished pid");
        Optional<CilProcess> target = transaction.processes().findByPid(pid);
        if (target.isPresent() && !targetProcess(pid, "process.removeFinished").isTerminal()) {
            throw new FclRuntimeException(
                    "process.removeFinished can only remove a process that has already ended; "
                            + "process " + pid + " is still active");
        }
        boolean deleted = transaction.processes().deleteTerminatedByPid(pid);
        audit("process.removeFinished", process.identity().processUid(), Map.of(
                "pid", Long.toString(pid), "deleted", Boolean.toString(deleted)));
        return deleted;
    }

    protected void registerSwapPool() {
        registry.register("swapPool", "create", args -> {
                    String pool = path(args, 0, 1, "swapPool.create");
                    return transaction.ipc().createSwapPool(process.ownerId(),
                            process.identity().processUid(), pool, now);
                })
                .register("swapPool", "remove", args -> {
                    if (args.size() == 1) {
                        String pool = path(args, 0, 1, "swapPool.remove");
                        return transaction.ipc().removeSwapPool(process.ownerId(),
                                process.identity().processUid(), pool);
                    }
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "swapPool.remove expects a pool, optional variable, and optional fencing token");
                    return transaction.ipc().removeSwapValue(process.ownerId(),
                            string(args.get(0), "swapPool.remove pool"),
                            string(args.get(1), "swapPool.remove variable"),
                            process.identity().processUid(), process.executionEpoch(),
                            args.size() == 3 ? Optional.of(integer(args.get(2),
                                    "swapPool.remove fencing token")) : Optional.empty());
                })
                .register("swapPool", "exists", args -> transaction.ipc().swapPoolExists(
                        process.ownerId(), path(args, 0, 1, "swapPool.exists")))
                .register("swapPool", "list", args -> {
                    if (args.isEmpty()) {
                        return transaction.ipc().findSwapPools(process.ownerId());
                    }
                    arity(args, 1, "swapPool.list");
                    return transaction.ipc().findSwapVariables(
                            process.ownerId(), path(args, 0, 1, "swapPool.list"));
                })
                .register("swapPool", "add", args -> addSwapValue(args))
                .register("swapPool", "get", args -> {
                    arity(args, 2, "swapPool.get");
                    return transaction.ipc().consumeSwapValue(process.ownerId(),
                                    string(args.get(0), "swapPool.get pool"),
                                    string(args.get(1), "swapPool.get variable"), now)
                            .map(value -> codec.valueFromJson(value.canonicalPayload()))
                            .orElse(null);
                })
                .register("swapPool", "update", args -> {
                    if (args.size() < 3 || args.size() > 4) throw new FclRuntimeException(
                            "swapPool.update expects three or four arguments");
                    return transaction.ipc().updateSwapValue(process.ownerId(),
                            string(args.get(0), "swapPool.update pool"),
                            string(args.get(1), "swapPool.update variable"), typed(args.get(2)),
                            process.identity().processUid(), process.executionEpoch(),
                            args.size() == 4 ? Optional.of(integer(args.get(3),
                                    "swapPool.update fencing token")) : Optional.empty(), now);
                })
                .register("swapPool", "clear", args -> transaction.ipc().clearSwapPool(
                        process.ownerId(), path(args, 0, 1, "swapPool.clear"),
                        process.identity().processUid()))
                .register("swapPool", "lock", args -> {
                    arity(args, 3, "swapPool.lock");
                    long leaseMillis = positiveMillis(args.get(2), "swapPool.lock lease");
                    return transaction.ipc().acquireSwapLock(process.ownerId(),
                                    string(args.get(0), "swapPool.lock pool"),
                                    string(args.get(1), "swapPool.lock variable"),
                                    process.identity().processUid(), process.executionEpoch(),
                                    now.plusMillis(leaseMillis), now)
                            .map(FclRuntimeFunctions::lockMap).orElse(null);
                })
                .register("swapPool", "renewLock", args -> {
                    arity(args, 4, "swapPool.renewLock");
                    long token = integer(args.get(2), "swapPool.renewLock token");
                    long leaseMillis = positiveMillis(args.get(3), "swapPool.renewLock lease");
                    return transaction.ipc().renewSwapLock(process.ownerId(),
                                    string(args.get(0), "swapPool.renewLock pool"),
                                    string(args.get(1), "swapPool.renewLock variable"),
                                    process.identity().processUid(), process.executionEpoch(), token,
                                    now.plusMillis(leaseMillis), now)
                            .map(FclRuntimeFunctions::lockMap).orElse(null);
                })
                .register("swapPool", "unlock", args -> {
                    arity(args, 3, "swapPool.unlock");
                    return transaction.ipc().releaseSwapLock(process.ownerId(),
                            string(args.get(0), "swapPool.unlock pool"),
                            string(args.get(1), "swapPool.unlock variable"),
                            process.identity().processUid(), process.executionEpoch(),
                            integer(args.get(2), "swapPool.unlock token"));
                })
                .register("swapPool", "signal", args -> {
                    arity(args, 2, "swapPool.signal");
                    return transaction.ipc().signalSwapValue(process.ownerId(),
                            string(args.get(0), "swapPool.signal pool"),
                            string(args.get(1), "swapPool.signal variable"), now);
                })
                .registerContextual("swapPool", "waitFor", (args, invocation) ->
                        waitForSwap(args, invocation));
    }

    protected void registerIpc() {
        registry.register("ipc", "createChannel", args -> {
                    arity(args, 1, "ipc.createChannel");
                    IpcChannel channel = IpcService.createChannelIn(transaction,
                            process.ownerId(), string(args.get(0), "ipc.createChannel name"), now);
                    return Map.of("channelId", channel.channelId().toString(),
                            "name", channel.name());
                })
                .register("ipc", "createTopic", args -> {
                    arity(args, 1, "ipc.createTopic");
                    IpcTopic topic = IpcService.createTopicIn(transaction, process.ownerId(),
                            string(args.get(0), "ipc.createTopic name"), now);
                    return Map.of("topicId", topic.topicId().toString(),
                            "name", topic.name());
                })
                .register("ipc", "removeChannel", args -> {
                    arity(args, 1, "ipc.removeChannel");
                    return IpcService.removeChannelIn(transaction, process.ownerId(),
                            uuid(args.get(0), "ipc.removeChannel channelId"));
                })
                .register("ipc", "removeTopic", args -> {
                    arity(args, 1, "ipc.removeTopic");
                    return IpcService.removeTopicIn(transaction, process.ownerId(),
                            uuid(args.get(0), "ipc.removeTopic topicId"));
                })
                .register("ipc", "subscribeChannel", args -> {
                    arity(args, 1, "ipc.subscribeChannel");
                    IpcService.subscribeChannelIn(transaction, process.ownerId(),
                            process.identity().processUid(),
                            uuid(args.get(0), "ipc.subscribeChannel channelId"), now);
                    return true;
                })
                .register("ipc", "subscribeTopic", args -> {
                    arity(args, 1, "ipc.subscribeTopic");
                    IpcService.subscribeTopicIn(transaction, process.ownerId(),
                            process.identity().processUid(),
                            string(args.get(0), "ipc.subscribeTopic topic"), now);
                    return true;
                })
                .register("ipc", "sendDirect", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "ipc.sendDirect expects receiver pid, payload and optional expiresAt");
                    long pid = integer(args.get(0), "ipc.sendDirect pid");
                    CilProcess receiver = transaction.processes().findByPid(pid)
                            .orElseThrow(() -> new FclRuntimeException("Unknown process pid: " + pid));
                    IpcMessage message = IpcService.sendDirectIn(transaction, process.ownerId(),
                            Optional.of(process.identity().processUid()),
                            receiver.identity().processUid(),
                            ipcPayload(args.get(1), "ipc.sendDirect payload"),
                            ipcExpiry(args, 2, "ipc.sendDirect expiresAt"), now);
                    return Map.of("messageId", message.messageId().toString());
                })
                .register("ipc", "sendChannel", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "ipc.sendChannel expects channelId, payload and optional expiresAt");
                    IpcMessage message = IpcService.sendChannelIn(transaction, process.ownerId(),
                            Optional.of(process.identity().processUid()),
                            uuid(args.get(0), "ipc.sendChannel channelId"),
                            ipcPayload(args.get(1), "ipc.sendChannel payload"),
                            ipcExpiry(args, 2, "ipc.sendChannel expiresAt"), now);
                    return Map.of("messageId", message.messageId().toString());
                })
                .register("ipc", "publishTopic", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "ipc.publishTopic expects topic, payload and optional expiresAt");
                    IpcMessage message = IpcService.publishTopicIn(transaction, process.ownerId(),
                            Optional.of(process.identity().processUid()),
                            string(args.get(0), "ipc.publishTopic topic"),
                            ipcPayload(args.get(1), "ipc.publishTopic payload"),
                            ipcExpiry(args, 2, "ipc.publishTopic expiresAt"), now);
                    return Map.of("messageId", message.messageId().toString());
                })
                .register("ipc", "broadcast", args -> {
                    if (args.size() < 2 || args.size() > 3) throw new FclRuntimeException(
                            "ipc.broadcast expects topic, payload and optional expiresAt");
                    IpcMessage message = IpcService.broadcastIn(transaction, process.ownerId(),
                            Optional.of(process.identity().processUid()),
                            string(args.get(0), "ipc.broadcast topic"),
                            ipcPayload(args.get(1), "ipc.broadcast payload"),
                            ipcExpiry(args, 2, "ipc.broadcast expiresAt"), now);
                    return Map.of("messageId", message.messageId().toString());
                })
                .register("ipc", "purge", args -> {
                    if (args.isEmpty() || args.size() > 2) throw new FclRuntimeException(
                            "ipc.purge expects olderThan and optional limit");
                    Instant olderThan = instant(args.getFirst(), "ipc.purge olderThan");
                    long requestedLimit = args.size() == 2
                            ? integer(args.get(1), "ipc.purge limit") : 1000;
                    if (requestedLimit < 1 || requestedLimit > IpcService.MAX_PURGE_BATCH) {
                        throw new FclRuntimeException("ipc.purge limit must be between 1 and "
                                + IpcService.MAX_PURGE_BATCH);
                    }
                    return IpcService.purgeMessagesIn(transaction, process.ownerId(),
                            olderThan, now, (int) requestedLimit);
                })
                .registerContextual("ipc", "receive", (args, invocation) -> {
                    if (!args.isEmpty()) throw new FclRuntimeException("ipc.receive takes no arguments");
                    return ipcReceive(invocation);
                })
                .register("ipc", "poll", args -> {
                    arity(args, 0, "ipc.poll");
                    return IpcService.reserveNextIn(transaction, process.ownerId(),
                                    process.identity().processUid(), UUID.randomUUID(), now)
                            .map(envelope -> IpcService.envelopeMap(envelope.delivery(),
                                    envelope.message()))
                            .orElse(null);
                })
                .register("ipc", "consume", args -> {
                    arity(args, 1, "ipc.consume");
                    return IpcService.consumeIn(transaction, process.ownerId(),
                            uuid(args.get(0), "ipc.consume deliveryId"));
                });
    }

    /** Serializes any FCL value into a durable IPC payload. */
    protected IpcService.Payload ipcPayload(Object value, String field) {
        Continuation.PersistedValue persisted = typed(value);
        return IpcService.Payload.json(persisted.type(), persisted.canonicalPayload());
    }

    /**
     * Blocking receive: consumes an already-delivered envelope from the process inbox, or
     * suspends the process as WAITING_IPC until a delivery wakes it. A delivery committed
     * before this statement runs is reserved from the durable PENDING queue inside the
     * slice transaction, so the check-before-sleep window cannot lose a message.
     */
    protected Object ipcReceive(FclFunctionRegistry.Invocation invocation) {
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.IPC_RESULT)) {
            Object delivered = continuation.scope().remove(ProcessInbox.IPC_RESULT);
            if (delivered instanceof Continuation.PersistedValue persisted) {
                return codec.valueFromJson(persisted.canonicalPayload());
            }
            return delivered;
        }
        Optional<IpcService.Envelope> pending = IpcService.receiveIn(transaction,
                process.ownerId(), process.identity().processUid(), UUID.randomUUID(), now);
        if (pending.isPresent()) {
            IpcService.Envelope envelope = pending.orElseThrow();
            return IpcService.envelopeMap(envelope.delivery(), envelope.message());
        }
        continuation.waitFor("ipc:" + process.identity().processUid(),
                Map.of("ipc", "receive"));
        throw FclSuspension.suspend();
    }

    protected Object addSwapValue(List<Object> args) {
        if (args.size() < 2) throw new FclRuntimeException(
                "swapPool.add expects at least data and pool arguments");
        String data = string(args.get(0), "swapPool.add data");
        int separator = data.indexOf(':');
        if (separator < 1) throw new FclRuntimeException(
                "swapPool.add data must use variable:value format");
        String variable = data.substring(0, separator);
        Object value = data.substring(separator + 1);
        String pool = string(args.get(1), "swapPool.add pool");
        String mode = "ALWAYS";
        Optional<Integer> remaining = Optional.empty();
        for (int index = 2; index < args.size(); index++) {
            String parameter = string(args.get(index), "swapPool.add option");
            if (parameter.equalsIgnoreCase("type:sync")) mode = "SYNC";
            if (parameter.toLowerCase(java.util.Locale.ROOT).startsWith("type:times(")) {
                int close = parameter.lastIndexOf(')');
                if (close < 12) throw new FclRuntimeException("Invalid times retention option");
                int count;
                try {
                    count = Integer.parseInt(parameter.substring(11, close));
                } catch (NumberFormatException failure) {
                    throw new FclRuntimeException("Invalid times retention count", failure);
                }
                if (count < 1) throw new FclRuntimeException(
                        "times retention count must be positive");
                mode = "TIMES";
                remaining = Optional.of(count);
            }
        }
        return transaction.ipc().addSwapValue(process.ownerId(), pool, variable, typed(value),
                mode, remaining, now);
    }

    protected Object waitForSwap(List<Object> args, FclFunctionRegistry.Invocation invocation) {
        arity(args, 2, "swapPool.waitFor");
        String pool = string(args.get(0), "swapPool.waitFor pool");
        String variable = string(args.get(1), "swapPool.waitFor variable");
        if (invocation.continuation().scope().contains(ProcessInbox.TIMER_RESULT)) {
            invocation.continuation().scope().remove(ProcessInbox.TIMER_RESULT);
        }
        if (transaction.ipc().consumeSwapSignal(process.ownerId(), pool, variable)) return true;
        UUID timerId = UUID.randomUUID();
        transaction.timers().save(new ProcessTimer(timerId, process.identity().processUid(),
                now.plusMillis(50), ProcessTimer.Status.SCHEDULED, now, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(typed(null))));
        invocation.continuation().waitFor("timer:" + timerId,
                Map.of("swapPool", pool, "variable", variable));
        throw FclSuspension.suspend();
    }

    protected static long positiveMillis(Object value, String field) {
        long millis = integer(value, field);
        if (millis < 1) throw new FclRuntimeException(field + " must be positive");
        return millis;
    }

    protected static Map<String, Object> lockMap(
            com.follarce.domain.port.IpcRepository.SwapLock lock) {
        return Map.of("fencingToken", lock.fencingToken(),
                "leaseUntil", lock.leaseUntil().toString());
    }

    protected static Map<String, Object> fileLockMap(
            com.follarce.domain.port.VfsRepository.FileLock lock) {
        return Map.of("fencingToken", lock.fencingToken(),
                "leaseUntil", lock.leaseUntil().toString());
    }

    protected void registerSystem() {
        registry.register("system", "list", args -> {
                    arity(args, 0, "system.list");
                    return new ArrayList<>(registry.qualifiedNames());
                })
                .aliasQualified("process.kill", "system", "kill")
                .register("system", "resolveEffect", args -> unavailable("system.resolveEffect",
                        "manual effect resolution belongs to the administrator control plane"))
                .registerContextual("system", "exec", (args, invocation) -> {
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    if (args.size() != 1) throw new FclRuntimeException(
                            "system.exec expects a command string or argument array");
                    Object command = args.getFirst();
                    if (!(command instanceof String) && !(command instanceof List<?>)) {
                        throw new FclRuntimeException(
                                "system.exec command must be a string or argument array");
                    }
                    return external(invocation, "system.exec", Map.of("command", command),
                            MANUAL_EFFECT, true);
                })
                .registerContextual("system", "invoke", (args, invocation) -> {
                    if (args.isEmpty() || args.size() > 2) throw new FclRuntimeException(
                            "system.invoke expects a qualified FCL function and optional array");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    String function = string(args.getFirst(), "system.invoke function");
                    if (function.equals("system.invoke")) {
                        throw new FclRuntimeException("system.invoke cannot invoke itself");
                    }
                    List<Object> arguments;
                    if (args.size() == 1) arguments = List.of();
                    else if (args.get(1) instanceof List<?> supplied) {
                        arguments = new ArrayList<>(supplied);
                    } else {
                        throw new FclRuntimeException("system.invoke arguments must be an array");
                    }
                    return registry.invoke(function, arguments, invocation);
                })
                .register("system", "extensions", args -> {
                    arity(args, 0, "system.extensions");
                    return extensions.descriptors().stream().map(descriptor -> Map.of(
                            "id", descriptor.id(),
                            "version", descriptor.version(),
                            "description", descriptor.description())).toList();
                })
                .register("system", "reset", args -> unavailable("system.reset",
                        "runtime reset requires the administrator control plane"));
    }

    protected Object terminalInput(FclFunctionRegistry.Invocation invocation, boolean oneCharacter) {
        return terminalInput(invocation, oneCharacter, false);
    }

    protected Object terminalInput(FclFunctionRegistry.Invocation invocation, boolean oneCharacter,
                                 boolean rawKey) {
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.TERMINAL_INPUT)) {
            String input = display(continuation.scope().remove(ProcessInbox.TERMINAL_INPUT));
            return oneCharacter ? (input.isEmpty() ? ""
                    : input.substring(0, input.offsetByCodePoints(0, 1))) : input;
        }
        continuation.waitFor(rawKey ? "input:key" : "input",
                Map.of("readChar", oneCharacter, "rawKey", rawKey));
        throw FclSuspension.suspend();
    }

    /**
     * io.readKey returns one structured terminal event. A pending key event is consumed
     * immediately; otherwise the process waits in key mode, with an optional durable timer
     * delivering a timeout event when no key arrives.
     */
    protected Object readKey(FclFunctionRegistry.Invocation invocation, long timeout,
                           boolean coalesceText) {
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.TERMINAL_INPUT)) {
            return parseTerminalEvent(display(continuation.scope()
                    .remove(ProcessInbox.TERMINAL_INPUT)));
        }
        if (timeout >= 0 && continuation.scope().contains(ProcessInbox.TIMER_RESULT)) {
            Object timerResult = continuation.scope().remove(ProcessInbox.TIMER_RESULT);
            if (TimerService.TERMINAL_INPUT_TIMEOUT.equals(display(timerResult))) {
                return Map.of("kind", "timeout");
            }
            return parseTerminalEvent(display(timerResult));
        }
        UUID timerId = timeout >= 0 ? UUID.randomUUID() : null;
        if (timerId != null) {
            transaction.timers().save(new ProcessTimer(timerId,
                    process.identity().processUid(), now.plus(Duration.ofMillis(timeout)),
                    ProcessTimer.Status.SCHEDULED, now, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(typed(TimerService.TERMINAL_INPUT_TIMEOUT))));
        }
        continuation.waitFor(timerId != null ? "input:key:" + timerId : "input:key",
                Map.of("rawKey", true, "coalesceText", coalesceText));
        throw FclSuspension.suspend();
    }

    /** Parses a terminal event payload into a structured FCL map. */
    protected static Object parseTerminalEvent(String input) {
        if (input == null || input.isBlank()) return null;
        if (input.startsWith("{")) {
            try {
                return JSON.fromJson(input, Map.class);
            } catch (RuntimeException malformed) {
                return Map.of("kind", "raw", "sequence", input);
            }
        }
        return Map.of("kind", "key", "key", input,
                "shift", false, "ctrl", false, "alt", false, "text", "");
    }

}
