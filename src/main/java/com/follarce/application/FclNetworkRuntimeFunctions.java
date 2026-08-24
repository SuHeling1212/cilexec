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

final class FclNetworkRuntimeFunctions extends FclRuntimeFunctions {
    FclNetworkRuntimeFunctions(FclRuntimeFunctions source) { super(source); }

    protected void registerNetworkAndSockets() {
        registry.registerContextual("network", "httpGet", (args, invocation) -> {
                    arity(args, 1, "network.httpGet");
                    String url = string(args.getFirst(), "network.httpGet url");
                    return external(invocation, "network.http-get", Map.of("url", url),
                            idempotentPolicy(invocation, "GET:" + url), true);
                }, "webget")
                .registerContextual("network", "httpPost", (args, invocation) -> {
                    arity(args, 2, "network.httpPost");
                    return external(invocation, "network.http-post", Map.of(
                            "url", string(args.get(0), "network.httpPost url"),
                            "body", display(args.get(1))), MANUAL_EFFECT, true);
                }, "webpost")
                .registerContextual("network", "download", this::download);
        for (String name : List.of("connect", "send", "receive", "close", "bind", "accept")) {
            registry.registerContextual("socket", name, (args, invocation) ->
                    external(invocation, "socket." + name,
                            Map.of("arguments", List.copyOf(args)), MANUAL_EFFECT, true));
        }
    }

    protected Object external(FclFunctionRegistry.Invocation invocation, String effectType,
                            Map<String, Object> payload, EffectRequest.Policy policy,
                            boolean returnValue) {
        FclContinuation continuation = invocation.continuation();
        if (continuation.scope().contains(ProcessInbox.EFFECT_RESULT)) {
            Object delivered = continuation.scope().remove(ProcessInbox.EFFECT_RESULT);
            if (!(delivered instanceof Map<?, ?> result)
                || !Boolean.TRUE.equals(result.get("ok"))) {
                throw new FclRuntimeException("External effect failed: " + display(delivered));
            }
            return returnValue ? result.get("value") : null;
        }
        Authorization.require(transaction, process.ownerId(), Capability.EFFECT_REQUEST);
        UUID effectId = UUID.randomUUID();
        transaction.effects().save(EffectRequest.prepare(effectId,
                process.identity().processUid(), effectType, typed(payload), policy, now));
        continuation.waitFor("effect:" + effectId, Map.of("effectType", effectType));
        audit("effect.request", effectId, Map.of("effectType", effectType));
        throw FclSuspension.suspend();
    }

    protected Object download(List<Object> args, FclFunctionRegistry.Invocation invocation) {
        arity(args, 2, "network.download");
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String url = string(args.get(0), "network.download url");
        String path = string(args.get(1), "network.download destination");
        FclScope scope = invocation.continuation().scope();
        String expression = "cilexec.download." + invocation.expressionId();
        String identity = downloadIdentity(url, path);
        if (scope.contains(expression + ".target")) {
            String previous = string(scope.get(expression + ".target"),
                    "network.download identity");
            if (!previous.equals(identity)) {
                clearDownloadState(scope, expression + "." + previous + ".");
            }
        }
        scope.put(expression + ".target", identity);
        String state = expression + "." + identity + ".";
        long offset = scope.contains(state + "offset")
                ? integer(scope.get(state + "offset"), "network.download offset") : 0L;
        Optional<ObjectHash> currentHash = scope.contains(state + "hash")
                ? Optional.of(new ObjectHash(string(scope.get(state + "hash"),
                "network.download object hash"))) : Optional.empty();
        String mediaType = scope.contains(state + "mediaType")
                ? string(scope.get(state + "mediaType"), "network.download media type") : null;
        if (offset < 0 || offset > MAX_FILE_BYTES) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("Download state exceeds the 1 GiB file limit");
        }

        int maximum = (int) Math.min(DOWNLOAD_CHUNK_BYTES,
                MAX_FILE_BYTES - offset + 1L);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("url", url);
        request.put("offset", offset);
        request.put("maximumBytes", (long) maximum);
        if (scope.contains(state + "validator")) {
            request.put("validator", string(scope.get(state + "validator"),
                    "network.download validator"));
        }
        Object delivered = external(invocation, "network.download", Map.copyOf(request),
                idempotentPolicy(invocation, "DOWNLOAD:" + url + ":" + offset), true);
        if (!(delivered instanceof Map<?, ?> response)) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download returned an invalid response");
        }
        long status = integer(response.get("status"), "network.download status");
        long total = response.containsKey("totalBytes")
                ? integer(response.get("totalBytes"), "network.download total bytes") : -1L;
        boolean complete = Boolean.TRUE.equals(response.get("complete"));
        if (total > MAX_FILE_BYTES) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("Downloaded file exceeds the 1 GiB limit");
        }
        if (status == 416 && complete && total == offset) {
            if (mediaType == null) mediaType = "application/octet-stream";
            clearDownloadState(scope, state);
            ObjectHash finalHash;
            if (currentHash.isPresent()) {
                finalHash = currentHash.orElseThrow();
            } else {
                // A zero-byte object: the first range probe was answered 416 bytes */0,
                // so there is nothing to download yet the file legitimately exists.
                if (offset != 0) {
                    throw new FclRuntimeException(
                            "network.download cannot resume an object with no stored hash");
                }
                StoredObject empty = StoredObject.create(
                        new BinaryContent(new byte[0]), mediaType, now);
                transaction.vfs().saveObject(empty);
                finalHash = empty.objectHash();
            }
            String nodeId = attachDownloadedObject(path, finalHash, mediaType,
                    offset, "network.download");
            return completedDownload(nodeId, path, url, 206L, offset, mediaType);
        }
        if (status < 200 || status >= 300) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download failed with HTTP status " + status);
        }
        long returnedOffset = integer(response.get("offset"), "network.download returned offset");
        if (returnedOffset != offset) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download returned the wrong byte range");
        }
        String encoded = string(response.get("bodyBase64"), "network.download body");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download returned invalid binary data");
        }
        long reportedBytes = integer(response.get("bytes"), "network.download returned bytes");
        if (reportedBytes != bytes.length || offset + bytes.length > MAX_FILE_BYTES) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("Downloaded file exceeds the 1 GiB limit");
        }
        if (bytes.length == 0 && !complete) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download returned an empty incomplete range");
        }
        if (mediaType == null) {
            mediaType = response.get("mediaType") instanceof String value && !value.isBlank()
                    ? value : "application/octet-stream";
        }

        ObjectHash nextHash;
        if (currentHash.isEmpty()) {
            StoredObject first = StoredObject.create(new BinaryContent(bytes), mediaType, now);
            transaction.vfs().saveObject(first);
            nextHash = first.objectHash();
        } else if (bytes.length == 0) {
            nextHash = currentHash.orElseThrow();
        } else {
            nextHash = transaction.vfs().appendChunkedObject(currentHash.orElseThrow(), bytes,
                    mediaType, now).objectHash();
        }
        long downloaded = offset + bytes.length;
        if (complete && total >= 0 && total != downloaded) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download completed at the wrong file size");
        }
        complete = complete || total == downloaded;
        if (complete) {
            clearDownloadState(scope, state);
            String nodeId = attachDownloadedObject(path, nextHash, mediaType, downloaded,
                    "network.download");
            return completedDownload(nodeId, path, url, status, downloaded, mediaType);
        }

        // The next chunk needs an If-Range validator; without one a changed remote file
        // would silently interleave new and old content across chunks.
        if (!scope.contains(state + "validator")
                && !(response.get("validator") instanceof String validator
                && !validator.isBlank())) {
            clearDownloadState(scope, state);
            throw new FclRuntimeException("network.download cannot resume without a validator "
                    + "from the server (ETag or Last-Modified required for multi-chunk "
                    + "downloads)");
        }

        scope.put(state + "offset", downloaded);
        scope.put(state + "hash", nextHash.value());
        scope.put(state + "mediaType", mediaType);
        if (response.get("validator") instanceof String validator && !validator.isBlank()) {
            scope.put(state + "validator", validator);
        }
        return download(args, invocation);
    }

    protected Map<String, Object> completedDownload(String nodeId, String path, String url,
                                                   long status, long bytes, String mediaType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("path", normalize(path));
        result.put("url", url);
        result.put("status", status);
        result.put("bytes", bytes);
        result.put("mediaType", mediaType);
        return Map.copyOf(result);
    }

    protected static void clearDownloadState(FclScope scope, String prefix) {
        for (String suffix : List.of("offset", "hash", "mediaType", "validator")) {
            String key = prefix + suffix;
            if (scope.contains(key)) scope.remove(key);
        }
    }

    /**
     * Stable identity of a download attempt so terminal resubmissions cannot reuse stale
     * offset state. The destination path is part of the identity: resuming with the same
     * URL but a different target must not append new chunks to the old object.
     */
    protected static String downloadIdentity(String url, String destinationPath) {
        return sha256((url + "\0" + destinationPath).getBytes(StandardCharsets.UTF_8))
                .substring(0, 16);
    }

    protected EffectRequest.Policy idempotentPolicy(FclFunctionRegistry.Invocation invocation,
                                                   String operation) {
        // A terminal process is deliberately reused across commands. Expression identifiers
        // restart for every compiled submission, so epoch + expression alone aliases effects
        // from separate commands. stateVersion is stable across a transaction retry but advances
        // before the next terminal submission. Hash the material to keep attacker-controlled URLs
        // out of the unique-index key.
        String material = process.identity().processUid() + ":" + process.executionEpoch() + ":"
                + process.stateVersion() + ":" + invocation.expressionId() + ":" + operation;
        String key = sha256(material.getBytes(StandardCharsets.UTF_8));
        return new EffectRequest.Policy(true, Optional.of(key), false, true,
                EffectRequest.UnknownAction.RETRY_IDEMPOTENT);
    }

    protected Continuation.PersistedValue typed(Object value) {
        return new Continuation.PersistedValue(codec.valueType(value), codec.valueToJson(value));
    }

}
