package com.follarce.terminal;

import com.follarce.application.ProcessOutput;
import com.follarce.application.ProcessOutputPublisher;

/** Terminal adapter for disposable interactive process-output hints. */
public final class TerminalProcessOutputPublisher implements ProcessOutputPublisher {
    @Override
    public void publish(ProcessOutput output) {
        if (output.kind() == ProcessOutput.Kind.INTERACTION_FRAME) {
            TerminalOutputRouter.publishFrame(output.routeId(), output.text());
        }
    }
}
