package vpmcp.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profile merge/loading and geometry primitives. */
class StyleProfileAndGeometryTest {

    @TempDir
    File temp;

    @Test
    void partialOverrideKeepsEveryOtherDefault() {
        StyleProfile base = StyleProfile.defaults();
        StyleProfile course = StyleProfile.withOverride(JsonParser.parseString(
                "{\"name\":\"course\",\"grid\":20,\"sizes\":{\"UseCase\":{\"height\":50}}}").getAsJsonObject(), "test");

        assertEquals("course", course.name());
        assertEquals(20, course.grid());
        assertEquals(50, course.size("UseCase", "height", 0));
        assertEquals(base.size("UseCase", "text_padding", -1), course.size("UseCase", "text_padding", -2));
        assertEquals(base.size("Actor", "width", -1), course.size("Actor", "width", -2));
        assertEquals(base.fontName(), course.fontName());
    }

    @Test
    void unknownTypeFallsBackToTheDefaultEntry() {
        StyleProfile profile = StyleProfile.defaults();

        assertEquals(profile.size("default", "min_width", -1), profile.size("Gizmo", "min_width", -2));
        assertFalse(profile.fixes("Gizmo", "height"));
        assertTrue(profile.fixes("Actor", "height"));
    }

    @Test
    void loadReadsTheConfiguredFileAndSurvivesABrokenOne() throws IOException {
        File good = new File(temp, "course.json");
        Files.write(good.toPath(), "{\"name\":\"course\"}".getBytes(StandardCharsets.UTF_8));
        writeProperties("style_profile=" + good.getAbsolutePath().replace('\\', '/'));
        assertEquals("course", StyleProfile.load(temp).name());

        Files.write(good.toPath(), "{ not json".getBytes(StandardCharsets.UTF_8));
        assertEquals(StyleProfile.defaults().name(), StyleProfile.load(temp).name());

        writeProperties("");
        assertEquals(StyleProfile.defaults().name(), StyleProfile.load(temp).name());
    }

    private void writeProperties(String content) throws IOException {
        Files.write(new File(temp, "mcp.properties").toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void gridHelpers() {
        assertEquals(40, Geometry.snap(43, 10));
        assertEquals(50, Geometry.snap(46, 10));
        assertEquals(230, Geometry.ceilToGrid(221, 10));
        assertEquals(220, Geometry.ceilToGrid(220, 10));
        assertTrue(Geometry.onGrid(-20, 10));
        assertFalse(Geometry.onGrid(43, 10));
    }

    @Test
    void segmentsCrossOnlyInTheirInteriors() {
        int[] a = {0, 0};
        int[] b = {10, 10};
        assertTrue(Geometry.segmentsCross(a, b, new int[] {0, 10}, new int[] {10, 0}));
        assertFalse(Geometry.segmentsCross(a, b, new int[] {10, 10}, new int[] {20, 0}), "shared end point");
        assertFalse(Geometry.segmentsCross(a, b, new int[] {20, 20}, new int[] {30, 30}), "collinear, apart");
        assertFalse(Geometry.segmentsCross(a, b, new int[] {0, 5}, new int[] {3, 20}), "near but apart");
    }

    @Test
    void segmentThroughBoxIgnoresGrazingWithinTheInset() {
        int[] box = {100, 100, 50, 50};
        assertTrue(Geometry.segmentThroughBox(new int[] {0, 125}, new int[] {300, 125}, box, 3));
        assertFalse(Geometry.segmentThroughBox(new int[] {0, 101}, new int[] {300, 101}, box, 3), "grazes top edge");
        assertFalse(Geometry.segmentThroughBox(new int[] {0, 0}, new int[] {90, 300}, box, 3));
    }

    @Test
    void wrapBreaksAtWordsAndKeepsOverlongWordsWhole() {
        TextMeasure measure = TextMeasure.fixed(10);
        assertEquals(List.of("one two", "three"), TextMeasure.wrap(measure, "one two three", 70));
        assertEquals(List.of("unbreakable"), TextMeasure.wrap(measure, "unbreakable", 30));
        assertEquals(List.of(), TextMeasure.wrap(measure, "  ", 30));
    }
}
