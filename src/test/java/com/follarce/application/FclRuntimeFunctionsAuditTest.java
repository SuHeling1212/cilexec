package com.follarce.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FclRuntimeFunctionsAuditTest {
    @Test
    void classifiesExplicitResourceControlAuditEventsByTheirActualResource() {
        assertEquals("program.program", FclRuntimeFunctions.auditResourceType("program.remove"));
        assertEquals("terminal.session", FclRuntimeFunctions.auditResourceType("terminal.remove"));
        assertEquals("process.timer", FclRuntimeFunctions.auditResourceType("timer.purge"));
        assertEquals("audit.event", FclRuntimeFunctions.auditResourceType("audit.purge"));
    }

    @Test
    void retainsExistingAuditResourceClassifications() {
        assertEquals("effect.effect", FclRuntimeFunctions.auditResourceType("effect.request"));
        assertEquals("process.process", FclRuntimeFunctions.auditResourceType("process.kill"));
        assertEquals("network.request", FclRuntimeFunctions.auditResourceType("network.get"));
        assertEquals("package.binding", FclRuntimeFunctions.auditResourceType("package.install"));
        assertEquals("vfs.node", FclRuntimeFunctions.auditResourceType("file.write"));
    }
}
