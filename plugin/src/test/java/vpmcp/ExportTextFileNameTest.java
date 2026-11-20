package vpmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vpmcp.tools.ExportTextTool;

/** Export file names: safe in shells and on every file system, never empty. */
class ExportTextFileNameTest {

    @Test
    void namesBecomeShellSafeWithSingleDashes() {
        for (String name : new String[] {"UC - WashWatch (All)", "a/b\\c:d*e?f\"g<h>i|j", "  Läuft  ", "..hidden"}) {
            String base = ExportTextTool.fileBase(name);
            assertTrue(base.matches("[A-Za-z0-9._-]+"), base);
            assertFalse(base.contains("--"), base);
            assertFalse(base.startsWith("-") || base.startsWith(".") || base.endsWith("-"), base);
        }
        assertEquals("UC-WashWatch-All", ExportTextTool.fileBase("UC - WashWatch (All)"));
    }

    @Test
    void namesWithNothingUsableStillGetAFileName() {
        assertEquals("diagram", ExportTextTool.fileBase("(  )"));
        assertEquals("diagram", ExportTextTool.fileBase(null));
    }
}
