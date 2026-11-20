package vpmcp.layout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;

/**
 * What a well-designed diagram looks like (ADR-0038): grid, font, sizes per
 * element type, spacing, actor sides, score weights. The built-in default
 * ships in the jar; {@code style_profile=<path>} in mcp.properties names a
 * JSON file whose keys override it (deep merge), e.g. a private course
 * profile. Values are read with typed accessors that fall back to the
 * default, so a partial override is always complete.
 */
public final class StyleProfile {

    private static final String DEFAULT_RESOURCE = "/vpmcp/layout/default-profile.json";

    private final JsonObject root;
    private final String source;

    private StyleProfile(JsonObject root, String source) {
        this.root = root;
        this.source = source;
    }

    public static StyleProfile defaults() {
        return new StyleProfile(readDefault(), "built-in default");
    }

    /** Default merged with an override document (keys in override win). */
    public static StyleProfile withOverride(JsonObject override, String source) {
        JsonObject merged = readDefault();
        merge(merged, override);
        return new StyleProfile(merged, source);
    }

    /** Built-in default, overridden by mcp.properties {@code style_profile} when set and readable. */
    public static StyleProfile load(File pluginDir) {
        Properties properties = new Properties();
        File file = pluginDir == null ? null : new File(pluginDir, "mcp.properties");
        if (file != null && file.isFile()) {
            try (InputStream stream = new FileInputStream(file)) {
                properties.load(stream);
            } catch (IOException unreadable) {
                return defaults();
            }
        }
        String path = properties.getProperty("style_profile", "").trim();
        if (path.isEmpty()) {
            return defaults();
        }
        File profile = new File(path);
        try {
            JsonObject override = JsonParser.parseString(
                    new String(Files.readAllBytes(profile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            return withOverride(override, profile.getAbsolutePath());
        } catch (IOException | RuntimeException unreadable) {
            vpmcp.vp.VpLog.error("style_profile " + path + " unreadable; using the built-in default.", unreadable);
            return defaults();
        }
    }

    public String name() {
        return string(root, "name", "default");
    }

    public String source() {
        return source;
    }

    public int grid() {
        return Math.max(1, integer(root, "grid", 10));
    }

    public String fontName() {
        return string(object(root, "font"), "name", "Dialog");
    }

    public int fontSize() {
        return integer(object(root, "font"), "size", 11);
    }

    public int lineHeight() {
        return integer(root, "line_height", 15);
    }

    /** A size field for a model type, falling back to the "default" entry, then to {@code fallback}. */
    public int size(String modelType, String field, int fallback) {
        JsonObject sizes = object(root, "sizes");
        JsonObject specific = modelType == null ? null : object(sizes, modelType);
        if (specific != null && specific.has(field)) {
            return integer(specific, field, fallback);
        }
        return integer(object(sizes, "default"), field, fallback);
    }

    /** True when the profile fixes this size for the type (e.g. actors are always 30×60). */
    public boolean fixes(String modelType, String field) {
        JsonObject specific = modelType == null ? null : object(object(root, "sizes"), modelType);
        return specific != null && specific.has(field);
    }

    public int spacing(String field, int fallback) {
        return integer(object(root, "spacing"), field, fallback);
    }

    public String actorSides() {
        return string(object(root, "actors"), "sides", "left_right");
    }

    public int scoreWeight(String severity) {
        return integer(object(root, "score"), severity, "error".equals(severity) ? 15
                : "warning".equals(severity) ? 5 : 1);
    }

    /** The merged profile, for reporting. */
    public JsonObject toJson() {
        return root.deepCopy();
    }

    // ---- helpers ----------------------------------------------------------------

    private static JsonObject readDefault() {
        try (InputStream stream = StyleProfile.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + DEFAULT_RESOURCE + " in the plugin jar.");
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cannot read " + DEFAULT_RESOURCE, unreadable);
        }
    }

    private static void merge(JsonObject target, JsonObject override) {
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            JsonElement current = target.get(entry.getKey());
            if (current != null && current.isJsonObject() && entry.getValue().isJsonObject()) {
                merge(current.getAsJsonObject(), entry.getValue().getAsJsonObject());
            } else {
                target.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private static JsonObject object(JsonObject holder, String key) {
        JsonElement value = holder == null ? null : holder.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static int integer(JsonObject holder, String key, int fallback) {
        JsonElement value = holder == null ? null : holder.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String string(JsonObject holder, String key, String fallback) {
        JsonElement value = holder == null ? null : holder.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
