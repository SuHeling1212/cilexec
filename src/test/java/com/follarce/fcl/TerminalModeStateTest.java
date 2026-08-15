package com.follarce.fcl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerminalModeStateTest {
    @Test
    void replaysModesRequestedByRoutedTerminalOutput() {
        FclScope scope = new FclScope();

        TerminalModeState.capture(scope, "\u001b[?1049h\u001b[?1002h\u001b[?1006h"
                + "\u001b[?2004h\u001b[?1004h\u001b[?25l");

        assertEquals("\u001b[?1049h\u001b[?1002h\u001b[?1006h\u001b[?2004h"
                        + "\u001b[?1004h\u001b[?25l",
                TerminalModeState.replay(scope));
    }

    @Test
    void latestPrivateModeSequenceWinsWithinAndAcrossFrames() {
        FclScope scope = new FclScope();
        TerminalModeState.capture(scope, "\u001b[?1049h\u001b[?25l");
        TerminalModeState.capture(scope, "\u001b[?1049l\u001b[?25h\u001b[?2004h\u001b[?2004l");

        assertEquals("", TerminalModeState.replay(scope));
    }

    @Test
    void survivesContinuationCodecRoundTrip() {
        FclContinuation continuation = new FclContinuation();
        TerminalModeState.capture(continuation.globalScope(), "\u001b[?1049h\u001b[?1002h"
                + "\u001b[?1006h\u001b[?2004h\u001b[?25l");

        FclContinuation restored = new FclContinuationCodec().fromJson(
                new FclContinuationCodec().toJson(continuation));

        assertEquals("\u001b[?1049h\u001b[?1002h\u001b[?1006h\u001b[?2004h\u001b[?25l",
                TerminalModeState.replay(restored.globalScope()));
    }
}
