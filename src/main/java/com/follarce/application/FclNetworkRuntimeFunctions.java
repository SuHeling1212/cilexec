package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclSuspension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class FclNetworkRuntimeFunctions extends FclVfsRuntimeSupport {
    FclNetworkRuntimeFunctions(FclVfsRuntimeSupport source) { super(source); }

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













}
