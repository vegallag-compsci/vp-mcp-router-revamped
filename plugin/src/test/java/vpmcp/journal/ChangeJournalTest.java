package vpmcp.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vpmcp.write.BatchApplier.Compensation;

/** Journal contracts: ordering, conflict gating, session scoping, persistence. */
class ChangeJournalTest {

    private static final String PROJECT = "C:/models/wash.vpp";

    @TempDir
    File temp;

    private final Object session = new Object();
    private final List<String> ran = new ArrayList<>();

    private ChangeJournal journal() {
        return new ChangeJournal(new File(temp, "journal"),
                Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC));
    }

    /** A connect-like op between two existing views, producing one created connector. */
    private static JsonArray connectOps(String opId, String from, String to) {
        JsonObject op = new JsonObject();
        op.addProperty("op", "connect");
        op.addProperty("id", opId);
        op.addProperty("from", from);
        op.addProperty("to", to);
        JsonArray ops = new JsonArray();
        ops.add(op);
        return ops;
    }

    private static JsonObject result(String kind, String... vpIdsByOp) {
        JsonObject result = new JsonObject();
        JsonArray applied = new JsonArray();
        for (int i = 0; i < vpIdsByOp.length; i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "op" + i);
            entry.addProperty("kind", kind);
            entry.addProperty("vp_id", vpIdsByOp[i]);
            applied.add(entry);
        }
        result.addProperty("mutated", true);
        result.add("applied", applied);
        result.add("compensated", new JsonArray());
        result.add("errors", new JsonArray());
        return result;
    }

    private List<Compensation> steps(String... ids) {
        List<Compensation> steps = new ArrayList<>();
        for (String id : ids) {
            steps.add(new Compensation(id, () -> ran.add(id)));
        }
        return steps;
    }

    private String record(ChangeJournal journal, JsonArray ops, JsonObject result, List<Compensation> undo) {
        return journal.recordBatch(PROJECT, session, ops, result, undo).get("batch_id").getAsString();
    }

    @Test
    void revertRunsUndoStepsNewestFirstAndOnlyOnce() {
        ChangeJournal journal = journal();
        String batch = record(journal, connectOps("c", "A", "B"), result("creation", "X", "Y"),
                steps("first", "second", "third"));

        JsonObject reverted = journal.revert(PROJECT, session, batch);

        assertEquals(List.of("third", "second", "first"), ran);
        assertEquals("reverted", reverted.get("status").getAsString());
        assertEquals(3, reverted.getAsJsonArray("steps").size());
        assertFalse(journal.planRevert(PROJECT, session, batch).get("allowed").getAsBoolean());
        assertThrows(IllegalStateException.class, () -> journal.revert(PROJECT, session, batch));
        assertEquals(3, ran.size(), "a refused revert must not run any step");
    }

    @Test
    void laterBatchTouchingTheSameElementBlocksUntilItIsRevertedFirst() {
        ChangeJournal journal = journal();
        String earlier = record(journal, connectOps("c1", "A", "B"), result("creation", "X"), steps("e"));
        String later = record(journal, connectOps("c2", "X", "C"), result("creation", "Z"), steps("l"));

        JsonObject plan = journal.planRevert(PROJECT, session, earlier);
        assertFalse(plan.get("allowed").getAsBoolean());
        assertTrue(contains(plan.getAsJsonArray("conflicts"), later));
        assertThrows(IllegalStateException.class, () -> journal.revert(PROJECT, session, earlier));
        assertTrue(ran.isEmpty());

        journal.revert(PROJECT, session, later);
        journal.revert(PROJECT, session, earlier);
        assertEquals(List.of("l", "e"), ran);
    }

    @Test
    void unrelatedLaterBatchDoesNotBlock() {
        ChangeJournal journal = journal();
        String earlier = record(journal, connectOps("c1", "A", "B"), result("creation", "X"), steps("e"));
        record(journal, connectOps("c2", "P", "Q"), result("creation", "R"), steps("l"));

        assertTrue(journal.planRevert(PROJECT, session, earlier).get("allowed").getAsBoolean());
        journal.revert(PROJECT, session, earlier);
        assertEquals(List.of("e"), ran);
    }

    @Test
    void reopenedProjectRefusesBecauseUndoStepsAreStale() {
        ChangeJournal journal = journal();
        String batch = record(journal, connectOps("c", "A", "B"), result("creation", "X"), steps("s"));

        Object reopened = new Object();
        assertFalse(journal.planRevert(PROJECT, reopened, batch).get("allowed").getAsBoolean());
        assertThrows(IllegalStateException.class, () -> journal.revert(PROJECT, reopened, batch));
        assertTrue(ran.isEmpty());
    }

    @Test
    void historyOutlivesTheSessionButUndoDoesNot() {
        String batch = record(journal(), connectOps("c", "A", "B"), result("creation", "X"), steps("s"));

        ChangeJournal restarted = journal();
        JsonArray listed = restarted.list(PROJECT, 0);
        assertEquals(1, listed.size());
        JsonObject entry = listed.get(0).getAsJsonObject();
        assertEquals(batch, entry.get("batch_id").getAsString());
        assertFalse(entry.get("revertable").getAsBoolean());
        JsonObject plan = restarted.planRevert(PROJECT, session, batch);
        assertFalse(plan.get("allowed").getAsBoolean());
        assertTrue(plan.get("reason").getAsString().contains("earlier VP session"));
        assertNull(restarted.latestBatchId(PROJECT));
    }

    @Test
    void deletionsAreReportedIrreversibleAndDeletionOnlyBatchesCannotRevert() {
        ChangeJournal journal = journal();
        String mixed = record(journal, connectOps("c", "A", "B"), mixedResult(), steps("create"));
        String deletionOnly = record(journal, connectOps("d", "M", "N"), result("deletion", "Q"),
                new ArrayList<>());

        JsonObject mixedPlan = journal.planRevert(PROJECT, session, mixed);
        assertTrue(mixedPlan.get("allowed").getAsBoolean());
        assertEquals(1, mixedPlan.getAsJsonArray("irreversible").size());
        assertFalse(journal.planRevert(PROJECT, session, deletionOnly).get("allowed").getAsBoolean());
    }

    private static JsonObject mixedResult() {
        JsonObject result = result("creation", "X", "GONE");
        result.getAsJsonArray("applied").get(1).getAsJsonObject().addProperty("kind", "deletion");
        return result;
    }

    @Test
    void failedBatchIsJournaledButNeverRevertable() {
        ChangeJournal journal = journal();
        JsonObject failed = result("creation", "X");
        JsonObject error = new JsonObject();
        error.addProperty("id", "op1");
        error.addProperty("message", "boom");
        failed.getAsJsonArray("errors").add(error);

        JsonObject recorded = journal.recordBatch(PROJECT, session, connectOps("c", "A", "B"), failed, steps("s"));
        String batch = recorded.get("batch_id").getAsString();

        assertEquals("failed_compensated", recorded.get("status").getAsString());
        assertFalse(journal.planRevert(PROJECT, session, batch).get("allowed").getAsBoolean());
        assertNull(journal.latestBatchId(PROJECT));
    }

    @Test
    void failingStepIsReportedAndTheRestStillRun() {
        ChangeJournal journal = journal();
        List<Compensation> undo = steps("a");
        undo.add(new Compensation("broken", () -> {
            throw new IllegalStateException("view gone");
        }));
        undo.addAll(steps("c"));
        String batch = record(journal, connectOps("x", "A", "B"), result("creation", "X"), undo);

        JsonObject reverted = journal.revert(PROJECT, session, batch);

        assertEquals("partially_reverted", reverted.get("status").getAsString());
        assertEquals(List.of("c", "a"), ran);
        long failedSteps = 0;
        for (JsonElement step : reverted.getAsJsonArray("steps")) {
            if (!step.getAsJsonObject().get("undone").getAsBoolean()) {
                failedSteps++;
            }
        }
        assertEquals(1, failedSteps);
    }

    @Test
    void listIsNewestFirstHonoursLimitAndMarksReverts() {
        ChangeJournal journal = journal();
        String first = record(journal, connectOps("c1", "A", "B"), result("creation", "X"), steps("1"));
        String second = record(journal, connectOps("c2", "P", "Q"), result("creation", "Y"), steps("2"));
        assertNotEquals(first, second);
        assertEquals(second, journal.latestBatchId(PROJECT));

        String revert = journal.revert(PROJECT, session, second).get("batch_id").getAsString();
        JsonArray all = journal.list(PROJECT, 0);

        assertEquals(3, all.size());
        assertEquals(revert, all.get(0).getAsJsonObject().get("batch_id").getAsString());
        JsonObject revertedEntry = find(all, second);
        assertEquals(revert, revertedEntry.get("reverted_by").getAsString());
        assertFalse(revertedEntry.get("revertable").getAsBoolean());
        assertTrue(find(all, first).get("revertable").getAsBoolean());
        assertEquals(first, journal.latestBatchId(PROJECT));
        assertEquals(2, journal.list(PROJECT, 2).size());
    }

    @Test
    void tornLineIsSkippedAndOtherProjectsStaySeparate() throws IOException {
        ChangeJournal journal = journal();
        record(journal, connectOps("c", "A", "B"), result("creation", "X"), steps("s"));
        journal.recordBatch("D:/other/wash.vpp", session, connectOps("o", "A", "B"),
                result("creation", "Z"), steps("o"));
        File file = new File(new File(temp, "journal"), "wash.jsonl");
        Files.write(file.toPath(), "{\"batch_id\":\"torn".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        assertEquals(1, journal().list(PROJECT, 0).size());
        assertEquals(1, journal().list("D:/other/wash.vpp", 0).size());
    }

    @Test
    void touchedIdsTakeReferencesAndProducedIdsButNotPlanRefs() {
        JsonArray ops = connectOps("c", "A", "B");
        JsonObject ref = new JsonObject();
        ref.addProperty("ref", "planned");
        ops.get(0).getAsJsonObject().add("diagram", ref);

        assertEquals(java.util.Set.of("A", "B", "X"),
                ChangeJournal.touchedIds(ops, result("creation", "X").getAsJsonArray("applied")));
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement element : array) {
            if (value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject find(JsonArray entries, String batchId) {
        for (JsonElement element : entries) {
            if (batchId.equals(element.getAsJsonObject().get("batch_id").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("no entry " + batchId);
    }
}
