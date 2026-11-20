package vpmcp.write;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.ITaggedValue;
import com.vp.plugin.model.ITaggedValueContainer;
import com.vp.plugin.model.factory.IModelElementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Merge-by-name tagged value writes for update_element (ADR-0028). Only the
 * named tags change: a value sets the tag (creating it when absent), null
 * removes it. Existing tags keep their VP type; the JSON value is coerced to
 * it. Every write is read back, and a value VP did not keep (enumeration
 * literals it rejects, types it cannot take from text) lands in warnings
 * instead of failing the batch.
 */
final class TaggedValueWriter {

    private final Supplier<ITaggedValueContainer> newContainer;

    TaggedValueWriter(IModelElementFactory factory) {
        this(factory::createTaggedValueContainer);
    }

    /** Test seam: the container source used when a model has none yet. */
    TaggedValueWriter(Supplier<ITaggedValueContainer> newContainer) {
        this.newContainer = newContainer;
    }

    /** Old state of the touched tags: value as text, or null when the tag did not exist. */
    static Map<String, String> snapshot(IModelElement model, JsonObject wanted) {
        Map<String, String> before = new LinkedHashMap<>();
        ITaggedValueContainer container = model.getTaggedValues();
        for (String name : wanted.keySet()) {
            ITaggedValue tag = container == null ? null : container.getTaggedValueByName(name);
            before.put(name, tag == null ? null : tag.getValueAsString());
        }
        return before;
    }

    /**
     * Applies the wanted tags. Returns {applied: {name: kept value or null},
     * warnings: [..]} for the applied entry.
     */
    JsonObject apply(IModelElement model, JsonObject wanted) {
        JsonObject applied = new JsonObject();
        com.google.gson.JsonArray warnings = new com.google.gson.JsonArray();
        ITaggedValueContainer container = model.getTaggedValues();
        for (Map.Entry<String, JsonElement> item : wanted.entrySet()) {
            String name = item.getKey().trim();
            JsonElement value = item.getValue();
            ITaggedValue tag = container == null ? null : container.getTaggedValueByName(name);
            if (value == null || value.isJsonNull()) {
                if (tag != null) {
                    container.removeTaggedValue(tag);
                }
                applied.add(name, null);
                continue;
            }
            if (container == null) {
                container = newContainer.get();
                model.setTaggedValues(container);
            }
            if (tag == null) {
                tag = container.createTaggedValue();
                tag.setName(name);
                setTyped(tag, value.getAsJsonPrimitive());
            } else {
                String problem = setForExistingType(tag, value.getAsJsonPrimitive());
                if (problem != null) {
                    warnings.add("Tag \"" + name + "\": " + problem);
                }
            }
            String kept = tag.getValueAsString();
            applied.addProperty(name, kept);
            String requested = value.getAsString();
            if (!requested.equals(kept == null ? "" : kept)) {
                warnings.add("Tag \"" + name + "\": VP kept \"" + kept + "\" instead of \""
                        + requested + "\" (enumeration or typed tag).");
            }
        }
        JsonObject result = new JsonObject();
        result.add("applied", applied);
        result.add("warnings", warnings);
        return result;
    }

    /** Puts the touched tags back as they were: old text restored, created tags removed. */
    static void restore(IModelElement model, Map<String, String> before) {
        ITaggedValueContainer container = model.getTaggedValues();
        for (Map.Entry<String, String> item : before.entrySet()) {
            ITaggedValue tag = container == null ? null : container.getTaggedValueByName(item.getKey());
            if (item.getValue() == null) {
                if (tag != null) {
                    container.removeTaggedValue(tag);
                }
                continue;
            }
            if (tag == null) {
                if (container == null) {
                    continue;
                }
                tag = container.createTaggedValue();
                tag.setName(item.getKey());
            }
            setForExistingType(tag, new JsonPrimitive(item.getValue()));
        }
    }

    /** New tags take the JSON type: boolean, integer, decimal, otherwise text. */
    private static void setTyped(ITaggedValue tag, JsonPrimitive value) {
        if (value.isBoolean()) {
            tag.setValue(value.getAsBoolean());
        } else if (value.isNumber()) {
            double number = value.getAsDouble();
            if (number == Math.rint(number) && Math.abs(number) <= Integer.MAX_VALUE) {
                tag.setValue((int) number);
            } else {
                tag.setValue((float) number);
            }
        } else {
            tag.setValue(value.getAsString());
        }
    }

    /** Existing tags keep their VP type. Null when set, else why it was skipped. */
    private static String setForExistingType(ITaggedValue tag, JsonPrimitive value) {
        String text = value.getAsString();
        try {
            switch (tag.getType()) {
                case ITaggedValue.TYPE_INTEGER:
                    tag.setValue(Integer.parseInt(text.trim()));
                    return null;
                case ITaggedValue.TYPE_FLOATING_POINT_NUMBER:
                    tag.setValue(Float.parseFloat(text.trim()));
                    return null;
                case ITaggedValue.TYPE_BOOLEAN:
                    if (!"true".equalsIgnoreCase(text.trim()) && !"false".equalsIgnoreCase(text.trim())) {
                        return "expects true or false; left unchanged.";
                    }
                    tag.setValue(Boolean.parseBoolean(text.trim()));
                    return null;
                case ITaggedValue.TYPE_MODEL_ELEMENT:
                case ITaggedValue.TYPE_DATE:
                case ITaggedValue.TYPE_TIME:
                    return "is a model-element/date/time tag, which cannot be set from text; "
                            + "left unchanged.";
                default:
                    tag.setValue(text);
                    return null;
            }
        } catch (NumberFormatException notANumber) {
            return "expects a number; left unchanged.";
        }
    }
}
