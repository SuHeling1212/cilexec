package com.follarce.application;

import com.follarce.domain.process.CilProcess;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalRenderFunctionTest {
    @Test
    void queuesTerminalFrameWithoutCreatingOrWaitingForExternalEffect() {
        ProgramServiceTest.TestPersistence persistence = new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        String source = "term.render(\"frame\")\n";
        var program = new ProgramService(persistence).create(ownerId, source);
        CilProcess process = new ProcessService(persistence).create(ownerId, program,
                Optional.empty());
        FclContinuation continuation = new FclContinuation();
        UUID routeId = UUID.randomUUID();
        continuation.globalScope().put(TerminalReplService.TERMINAL_OUTPUT_ROUTE_SCOPE_KEY,
                routeId.toString());
        List<ProcessOutput> outputs = new ArrayList<>();
        FclRuntime runtime = new FclRuntime(FclRuntimeFunctions.create(persistence, process,
                program, continuation, Instant.now(),
                com.follarce.extension.SourceExtensionIndex.catalog(), ignored -> { },
                outputs::add));

        FclStepResult result = runtime.executeOne(new FclCompiler().compile(source), continuation);

        assertEquals(FclStepResult.Status.ADVANCED, result.status());
        assertEquals(FclStepResult.Status.COMPLETED,
                runtime.executeOne(new FclCompiler().compile(source), continuation).status());
        assertEquals(List.of(ProcessOutput.interactionFrame(routeId, "frame")), outputs);
        assertTrue(persistence.effects.requests.isEmpty());
        assertTrue(continuation.waitState().kind()
                == FclContinuation.WaitKind.NONE);
    }
}
