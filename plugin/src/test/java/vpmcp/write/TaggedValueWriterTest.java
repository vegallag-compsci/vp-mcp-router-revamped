package vpmcp.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.ITaggedValue;
import com.vp.plugin.model.ITaggedValueContainer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import vpmcp.VpFakes;

/**
 * Merge semantics for tagged values over stateful fakes: named tags change,
 * others stay, null removes, typed tags coerce, VP vetoes become warnings,
 * and restore puts every touched tag back.
 */
class TaggedValueWriterTest {

    /** A tiny in-memory VP: tags by name, each with a type and a text value. */
    private static final class Store {
        final Map<String, ITaggedValue> tags = new LinkedHashMap<>();
        UnaryOperator<String> veto = UnaryOperator.identity();
        ITaggedValueContainer container;
        IModelElement model;

        Store(boolean withContainer) {
            if (withContainer) {
                container = newContainer();
            }
            model = VpFakes.of(IModelElement.class, new HashMap<>(), (method, args) -> {
                switch (method.getName()) {
                    case "getTaggedValues":
                        return container;
                    case "setTaggedValues":
                        container = (ITaggedValueContainer) args[0];
                        return null;
                    default:
                        return null;
                }
            });
        }

        ITaggedValueContainer newContainer() {
            return VpFakes.of(ITaggedValueContainer.class, new HashMap<>(), (method, args) -> {
                switch (method.getName()) {
                    case "getTaggedValueByName":
                        return tags.get(args[0]);
                    case "createTaggedValue":
                        return tag(ITaggedValue.TYPE_TEXT, null);
                    case "removeTaggedValue":
                        tags.values().remove(args[0]);
                        return null;
                    default:
                        return null;
                }
            });
        }

        ITaggedValue tag(int type, String initial) {
            String[] name = {null};
            String[] value = {initial};
            ITaggedValue[] self = new ITaggedValue[1];
            self[0] = VpFakes.of(ITaggedValue.class, new HashMap<>(), (method, args) -> {
                switch (method.getName()) {
                    case "setName":
                        name[0] = (String) args[0];
                        tags.put(name[0], self[0]);
                        return null;
                    case "getName":
                        return name[0];
                    case "getType":
                        return type;
                    case "setValue":
                        value[0] = veto.apply(String.valueOf(args[0]));
                        return null;
                    case "getValueAsString":
                        return value[0];
                    default:
                        return null;
                }
            });
            return self[0];
        }

        void seed(String name, int type, String value) {
            tag(type, value).setName(name);
        }

        String value(String name) {
            ITaggedValue tag = tags.get(name);
            return tag == null ? null : tag.getValueAsString();
        }
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static TaggedValueWriter writer(Store store) {
        return new TaggedValueWriter(store::newContainer);
    }

    @Test
    void namedTagsChangeAndOthersStay() {
        Store store = new Store(true);
        store.seed("Preconditions", ITaggedValue.TYPE_TEXT, "");
        store.seed("Author", ITaggedValue.TYPE_TEXT, "Sam");

        JsonObject result = writer(store).apply(store.model, json("{\"Preconditions\":\"Logged in\"}"));

        assertEquals("Logged in", store.value("Preconditions"));
        assertEquals("Sam", store.value("Author"));
        assertEquals(0, result.getAsJsonArray("warnings").size());
    }

    @Test
    void nullRemovesATag() {
        Store store = new Store(true);
        store.seed("Old", ITaggedValue.TYPE_TEXT, "x");

        writer(store).apply(store.model, json("{\"Old\":null}"));

        assertFalse(store.tags.containsKey("Old"));
    }

    @Test
    void integerTagCoercesTextAndRejectsGarbageWithAWarning() {
        Store store = new Store(true);
        store.seed("Priority", ITaggedValue.TYPE_INTEGER, "1");

        JsonObject ok = writer(store).apply(store.model, json("{\"Priority\":\"3\"}"));
        assertEquals("3", store.value("Priority"));
        assertEquals(0, ok.getAsJsonArray("warnings").size());

        JsonObject bad = writer(store).apply(store.model, json("{\"Priority\":\"high\"}"));
        assertEquals("3", store.value("Priority"));
        assertTrue(bad.getAsJsonArray("warnings").size() > 0);
    }

    @Test
    void vetoedValueComesBackAsAWarningWithTheKeptValue() {
        Store store = new Store(true);
        store.seed("Complexity", ITaggedValue.TYPE_TEXT, "");
        store.veto = requested -> "Medium"; // an enumeration VP snaps to its own literal

        JsonObject result = writer(store).apply(store.model, json("{\"Complexity\":\"Huge\"}"));

        assertEquals("Medium", result.getAsJsonObject("applied").get("Complexity").getAsString());
        assertEquals(1, result.getAsJsonArray("warnings").size());
    }

    @Test
    void restorePutsBackOldValuesAndRemovesCreatedTags() {
        Store store = new Store(true);
        store.seed("Status", ITaggedValue.TYPE_TEXT, "Draft");
        JsonObject wanted = json("{\"Status\":\"Final\",\"Reviewer\":\"Sam\"}");
        Map<String, String> before = TaggedValueWriter.snapshot(store.model, wanted);

        writer(store).apply(store.model, wanted);
        assertEquals("Final", store.value("Status"));
        assertEquals("Sam", store.value("Reviewer"));

        TaggedValueWriter.restore(store.model, before);

        assertEquals("Draft", store.value("Status"));
        assertNull(store.value("Reviewer"));
    }

    @Test
    void modelWithoutAContainerGetsOne() {
        Store store = new Store(false);

        writer(store).apply(store.model, json("{\"Level\":\"User goal\"}"));

        assertTrue(store.container != null);
        assertEquals("User goal", store.value("Level"));
    }
}
