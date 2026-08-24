package com.follarce.application;

import com.follarce.fcl.FclCompiler;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolatileProcessServiceTest {
    @Test
    void executesPureComputationWithoutAnyDurableRuntimeAdapter() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<VolatileProcessCompletion> outcome = new AtomicReference<>();
        try (VolatileProcessService service = new VolatileProcessService(completion -> {
            outcome.set(completion);
            done.countDown();
        })) {
            service.launch(request("""
                    total = 0
                    index = 0
                    while index < 10000 {
                        total = total + index
                        index++
                    }
                    """));

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(outcome.get().successful());
            assertNull(outcome.get().value());
        }
    }

    @Test
    void rejectsAnyFunctionThatIsNotPartOfThePureRegistry() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<VolatileProcessCompletion> outcome = new AtomicReference<>();
        try (VolatileProcessService service = new VolatileProcessService(completion -> {
            outcome.set(completion);
            done.countDown();
        })) {
            service.launch(request("file.write(\"/not-allowed\", \"data\")"));

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNotNull(outcome.get());
            assertTrue(!outcome.get().successful());
        }
    }

    private static VolatileProcessRequest request(String source) {
        return new VolatileProcessRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new FclCompiler().compile(source), java.util.List.of(), "/calculation.fcl");
    }
}
