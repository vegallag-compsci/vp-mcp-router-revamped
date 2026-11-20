package vpmcp.write;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import vpmcp.vp.ModelLookup;

/**
 * Validates a write batch without mutating anything. Reads the open project
 * (diagram and element existence) but never creates, updates, or saves.
 * Every rejection lands in {@code errors[]}; {@code valid} is true iff empty.
 */
public final class PlanValidator {

    private static final Set<String> OPS =
            new HashSet<>(Arrays.asList("create_diagram", "create_element", "connect",
                    "duplicate_diagram", "add_member", "update_element", "update_member",
                    "style_element", "create_raw", "move_element", "route_connector", "show_element",
                    "delete_model", "delete_diagram", "delete_element"));

    private static final Set<String> MULTIPLICITIES = new HashSet<>(Arrays.asList(
            "Unspecified", "0", "0..1", "0..*", "1", "1..*", "*"));
    private static final String MULTIPLICITY_HELP = "Unspecified, 0, 0..1, 0..*, 1, 1..*, *";

    private PlanValidator() {
    }

    public static JsonObject validate(IProject project, JsonArray ops) {
        return validate(project, ops, TypeTiers.verifiedOnly());
    }

    /**
     * Two tiers: verified families behave as documented; pack families
     * (from the schema pack, passed in by the plugin) are accepted but
     * flagged unverified. Everything else is impossible, not forbidden.
     */
    public static JsonObject validate(IProject project, JsonArray ops, TypeTiers tiers) {
        JsonArray plan = new JsonArray();
        JsonArray errors = new JsonArray();
        Map<String, String> symbols = new HashMap<>();

        if (ops == null) {
            errors.add(error("<batch>", "Missing \"ops\" array."));
            return result(false, plan, errors);
        }

        for (JsonElement item : ops) {
            if (item == null || !item.isJsonObject()) {
                errors.add(error("<batch>", "Every op must be an object."));
                continue;
            }
            validateOp(project, item.getAsJsonObject(), symbols, plan, errors, tiers);
        }
        JsonObject result = result(errors.size() == 0, plan, errors);
        String hint = plan.size() == 0 ? unknownIdHint(project, errors) : null;
        if (hint != null) {
            result.addProperty("hint", hint);
        }
        return result;
    }

    /**
     * When every reference fails, the likeliest cause is that the operator
     * switched projects since the ids were read (live 2026-11-20: a batch built
     * on wash-watch-temp was applied while probe-scratch was open).
     */
    static String unknownIdHint(IProject project, JsonArray errors) {
        if (errors.size() == 0) {
            return null;
        }
        for (JsonElement item : errors) {
            String message = item.getAsJsonObject().get("message").getAsString();
            if (!message.startsWith("Unknown")) {
                return null;
            }
        }
        String name = project == null ? null : project.getName();
        return "No id in this batch resolves. The open project is '" + name
                + "': if the ids came from another project, reopen that one in VP (vp_capabilities "
                + "names the open project) and preview again.";
    }

    private static void validateOp(IProject project, JsonObject op, Map<String, String> symbols,
            JsonArray plan, JsonArray errors, TypeTiers tiers) {
        String id = text(op, "id");
        String kind = text(op, "op");
        if (id == null || id.isEmpty()) {
            errors.add(error("<batch>", "Op is missing its \"id\"."));
            return;
        }
        if (symbols.containsKey(id)) {
            errors.add(error(id, "Duplicate op id."));
            return;
        }
        if (kind == null) {
            errors.add(error(id, "Op is missing its \"op\" type."));
            return;
        }

        switch (kind) {
            case "create_diagram":
                validateCreateDiagram(op, id, symbols, plan, errors, tiers);
                break;
            case "create_element":
                validateCreateElement(project, op, id, symbols, plan, errors, tiers);
                break;
            case "connect":
                validateConnect(project, op, id, symbols, plan, errors, tiers);
                break;
            case "duplicate_diagram":
                validateDuplicateDiagram(project, op, id, symbols, plan, errors);
                break;
            case "add_member":
                validateAddMember(project, op, id, symbols, plan, errors, tiers);
                break;
            case "update_element":
                validateUpdateElement(project, op, id, symbols, plan, errors);
                break;
            case "update_member":
                validateUpdateMember(project, op, id, symbols, plan, errors, tiers);
                break;
            case "style_element":
                validateStyleElement(project, op, id, symbols, plan, errors);
                break;
            case "create_raw":
                validateCreateRaw(project, op, id, symbols, plan, errors);
                break;
            case "move_element":
                validateMoveElement(project, op, id, symbols, plan, errors);
                break;
            case "route_connector":
                validateRouteConnector(project, op, id, symbols, plan, errors);
                break;
            case "show_element":
                validateShowElement(project, op, id, symbols, plan, errors);
                break;
            case "delete_model":
                validateDeleteModel(project, op, id, symbols, plan, errors);
                break;
            case "delete_diagram":
                validateDeleteDiagram(project, op, id, symbols, plan, errors);
                break;
            case "delete_element":
                validateDeleteElement(project, op, id, symbols, plan, errors);
                break;
            default:
                errors.add(error(id, "Unknown op \"" + kind + "\"; allowed: " + OPS + "."));
                break;
        }
    }

    private static void validateCreateDiagram(JsonObject op, String id, Map<String, String> symbols,
            JsonArray plan, JsonArray errors, TypeTiers tiers) {
        String diagramType = text(op, "diagram_type");
        String name = text(op, "name");
        if (diagramType == null || !tiers.isKnownDiagram(diagramType)) {
            errors.add(error(id, "VP has no diagram type \"" + diagramType
                    + "\"; use a type from the vp://schemas diagram-types resource "
                    + "(anything beyond the verified families is unverified)."));
            return;
        }
        if (name == null || name.trim().isEmpty()) {
            errors.add(error(id, "Diagram \"name\" is required."));
            return;
        }
        symbols.put(id, "diagram");
        if (tiers.isVerifiedDiagram(diagramType)) {
            plan.add(entry(id, "create_diagram", diagramType + " '" + name.trim() + "'",
                    "delete diagram '" + name.trim() + "'"));
        } else {
            plan.add(unverifiedEntry(id, "create_diagram",
                    diagramType + " '" + name.trim() + "'",
                    "delete diagram '" + name.trim() + "'"));
        }
    }

    private static void validateCreateElement(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors, TypeTiers tiers) {
        String diagram = resolveDiagram(project, op.get("diagram"), symbols);
        if (diagram == null) {
            errors.add(error(id, "Unknown diagram reference; use an existing diagram id or a plan ref."));
            return;
        }
        String modelType = text(op, "model_type");
        if (modelType != null && tiers.isVerifiedMember(modelType)) {
            errors.add(error(id,
                    "Members are not placeable; use add_member with a parent element."));
            return;
        }
        if (modelType == null || !(tiers.isVerifiedElement(modelType)
                || tiers.isCreatable(modelType))) {
            errors.add(error(id, "VP cannot create \"" + modelType
                    + "\"; see the vp://schemas factory-creates resource "
                    + "(anything beyond the verified families is unverified)."));
            return;
        }
        String name = text(op, "name");
        if (name == null || name.trim().isEmpty()) {
            errors.add(error(id, "Element \"name\" is required."));
            return;
        }
        if (!optionalNumbers(op, "x", "y", "width", "height")) {
            errors.add(error(id, "Geometry fields x/y/width/height must be numbers."));
            return;
        }
        JsonElement containerRef = op.get("container");
        String container = null;
        if (containerRef != null && !containerRef.isJsonNull()) {
            container = resolveElement(project, containerRef, symbols);
            if (container == null) {
                errors.add(error(id, "Unknown \"container\" " + describeRef(containerRef)
                        + "; use the view id of a shape on the same diagram (for example a "
                        + "System boundary or Package) or a plan ref."));
                return;
            }
        }
        symbols.put(id, "element");
        symbols.put(TYPE_KEY + id, modelType);
        String summary = modelType + " '" + name.trim() + "' on diagram '" + diagram + "'"
                + (container == null ? "" : " inside '" + container + "'");
        String undo = "remove '" + name.trim() + "' from its diagram and delete its model";
        if (tiers.isVerifiedElement(modelType)) {
            plan.add(entry(id, "create_element", summary, undo));
        } else {
            plan.add(unverifiedEntry(id, "create_element", summary, undo));
        }
    }

    private static void validateConnect(IProject project, JsonObject op, String id, Map<String, String> symbols,
            JsonArray plan, JsonArray errors, TypeTiers tiers) {
        String diagram = resolveDiagram(project, op.get("diagram"), symbols);
        if (diagram == null) {
            errors.add(error(id, "Unknown diagram reference; use an existing diagram id or a plan ref."));
            return;
        }
        String relType = text(op, "rel_type");
        if (relType == null || !(tiers.isVerifiedRelationship(relType)
                || tiers.isCreatable(relType))) {
            String hint = ConnectionRules.builtIn().notInVp(relType);
            errors.add(error(id, "VP cannot create a relationship \"" + relType + "\"; "
                    + (hint != null ? hint + " " : "")
                    + "vp_relationship_rules lists the relationship names and what each can join."));
            return;
        }
        String from = resolveElement(project, op.get("from"), symbols);
        String to = resolveElement(project, op.get("to"), symbols);
        if (from == null || to == null) {
            errors.add(error(id, "Unknown endpoint; use an existing element id or a plan ref."));
            return;
        }
        String endsProblem = ConnectionRules.builtIn().problem(relType,
                endpointType(project, op.get("from"), symbols), endpointType(project, op.get("to"), symbols));
        if (endsProblem != null) {
            errors.add(error(id, endsProblem));
            return;
        }
        String problem = pointsProblem(op.get("points"));
        if (problem == null) {
            problem = memberProblem(op.get("from_member"), "from_member");
        }
        if (problem == null) {
            problem = memberProblem(op.get("to_member"), "to_member");
        }
        if (problem == null) {
            problem = multiplicityProblem(op.get("from_multiplicity"), "from_multiplicity");
        }
        if (problem == null) {
            problem = multiplicityProblem(op.get("to_multiplicity"), "to_multiplicity");
        }
        if (problem == null) {
            problem = aggregationProblem(op.get("from_aggregation"), "from_aggregation");
        }
        if (problem == null) {
            problem = aggregationProblem(op.get("to_aggregation"), "to_aggregation");
        }
        for (String field : new String[] {"from_navigable", "to_navigable", "identifying"}) {
            if (problem == null) {
                problem = booleanProblem(op.get(field), field);
            }
        }
        if (problem != null) {
            errors.add(error(id, problem));
            return;
        }
        symbols.put(id, "relationship");
        if (tiers.isVerifiedRelationship(relType)) {
            plan.add(entry(id, "connect", relType + " from '" + from + "' to '" + to + "'",
                    "remove the " + relType + " connector and delete its model"));
        } else {
            plan.add(unverifiedEntry(id, "connect",
                    relType + " from '" + from + "' to '" + to + "'",
                    "remove the " + relType + " connector and delete its model"));
        }
    }

    private static void validateDuplicateDiagram(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String diagram = resolveDiagram(project, op.get("diagram"), symbols);
        if (diagram == null) {
            errors.add(error(id, "Unknown diagram reference; use an existing diagram id or a plan ref."));
            return;
        }
        String name = text(op, "name");
        if (name == null || name.trim().isEmpty()) {
            errors.add(error(id, "Duplicate \"name\" is required."));
            return;
        }
        symbols.put(id, "diagram");
        plan.add(entry(id, "duplicate_diagram",
                "copy of diagram '" + diagram + "' as '" + name.trim() + "'",
                "delete diagram '" + name.trim() + "' (shared models are kept)"));
    }

    private static void validateAddMember(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors, TypeTiers tiers) {
        String parent = resolveElement(project, op.get("parent"), symbols);
        if (parent == null) {
            errors.add(error(id, "Unknown parent '" + describeRef(op.get("parent"))
                    + "'; use a view id (view_id from an applied entry) or a plan ref."));
            return;
        }
        String memberType = text(op, "member_type");
        if (memberType == null || !(tiers.isVerifiedMember(memberType)
                || tiers.isCreatable(memberType))) {
            errors.add(error(id, "VP cannot create a member \"" + memberType
                    + "\"; see the vp://schemas factory-creates resource "
                    + "(anything beyond the verified families is unverified)."));
            return;
        }
        String name = text(op, "name");
        if (name == null || name.trim().isEmpty()) {
            errors.add(error(id, "Member \"name\" is required."));
            return;
        }
        JsonElement type = op.get("type");
        if (type != null && !type.isJsonNull()
                && (!type.isJsonPrimitive() || type.getAsString().trim().isEmpty())) {
            errors.add(error(id, "Member \"type\" must be a non-blank string."));
            return;
        }
        for (String field : new String[] {"visibility", "multiplicity",
                "initial_value", "return_type"}) {
            JsonElement value = op.get(field);
            if (value != null && !value.isJsonNull()
                    && (!value.isJsonPrimitive() || value.getAsString().trim().isEmpty())) {
                errors.add(error(id, "Member \"" + field + "\" must be a non-blank string."));
                return;
            }
        }
        JsonElement parameters = op.get("parameters");
        if (parameters != null && !parameters.isJsonNull()) {
            String problem = parametersProblem(parameters);
            if (problem != null) {
                errors.add(error(id, problem));
                return;
            }
        }
        JsonElement length = op.get("length");
        if (length != null && !length.isJsonNull()
                && (!length.isJsonPrimitive() || !length.getAsJsonPrimitive().isNumber())) {
            errors.add(error(id, "Member \"length\" must be a number."));
            return;
        }
        JsonElement nullable = op.get("nullable");
        if (nullable != null && !nullable.isJsonNull()
                && (!nullable.isJsonPrimitive() || !nullable.getAsJsonPrimitive().isBoolean())) {
            errors.add(error(id, "Member \"nullable\" must be a boolean."));
            return;
        }
        symbols.put(id, "member");
        if (tiers.isVerifiedMember(memberType)) {
            plan.add(entry(id, "add_member",
                    memberType + " '" + name.trim() + "' on '" + parent + "'",
                    "remove '" + name.trim() + "' from its parent"));
        } else {
            plan.add(unverifiedEntry(id, "add_member",
                    memberType + " '" + name.trim() + "' on '" + parent + "'",
                    "remove '" + name.trim() + "' from its parent"));
        }
    }

    private static void validateUpdateElement(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String model = resolveModel(project, op.get("model"), symbols);
        if (model == null) {
            errors.add(error(id, "Unknown model; use an existing model id or a plan ref."));
            return;
        }
        JsonElement name = op.get("name");
        if (name != null && !name.isJsonNull()
                && (!name.isJsonPrimitive() || name.getAsString().trim().isEmpty())) {
            errors.add(error(id, "Updated \"name\" must be a non-blank string."));
            return;
        }
        JsonElement documentation = op.get("documentation");
        if (documentation != null && !documentation.isJsonNull()
                && !documentation.isJsonPrimitive()) {
            errors.add(error(id, "Updated \"documentation\" must be a string; empty clears it."));
            return;
        }
        JsonElement stereotypes = op.get("stereotypes");
        if (stereotypes != null && !stereotypes.isJsonNull()) {
            if (!stereotypes.isJsonArray()) {
                errors.add(error(id,
                        "Updated \"stereotypes\" must be an array of strings; it replaces all."));
                return;
            }
            for (JsonElement item : stereotypes.getAsJsonArray()) {
                if (item == null || !item.isJsonPrimitive()
                        || item.getAsString().trim().isEmpty()) {
                    errors.add(error(id,
                            "Updated \"stereotypes\" must be an array of non-blank strings."));
                    return;
                }
            }
        }
        JsonElement taggedValues = op.get("tagged_values");
        String taggedProblem = taggedValuesProblem(taggedValues);
        if (taggedProblem != null) {
            errors.add(error(id, taggedProblem));
            return;
        }
        boolean hasTagged = taggedValues != null && !taggedValues.isJsonNull();
        IModelElement existing = null;
        if (op.get("model") != null && op.get("model").isJsonPrimitive() && project != null) {
            try {
                existing = ModelLookup.byId(project, op.get("model").getAsString());
            } catch (RuntimeException unknown) {
                existing = null;
            }
        }
        String existingType = existing == null ? null : existing.getModelType();
        if (hasTagged) {
            String tagProblem = tagNameProblem(taggedValues.getAsJsonObject().keySet(), existingType,
                    tagNames(existing));
            if (tagProblem != null) {
                errors.add(error(id, tagProblem));
                return;
            }
        }
        JsonElement requirement = op.get("requirement");
        boolean hasRequirement = requirement != null && !requirement.isJsonNull();
        if (hasRequirement) {
            String problem = requirementFieldsProblem(requirement, existingType);
            if (problem != null) {
                errors.add(error(id, problem));
                return;
            }
        }
        if ((name == null || name.isJsonNull())
                && (documentation == null || documentation.isJsonNull())
                && (stereotypes == null || stereotypes.isJsonNull())
                && !hasTagged && !hasRequirement) {
            errors.add(error(id, "Update needs at least one of name/documentation/stereotypes/"
                    + "tagged_values/requirement."));
            return;
        }
        symbols.put(id, "element");
        String summary = "update model '" + model + "'";
        if (hasTagged) {
            summary += "; tagged values " + taggedValues.getAsJsonObject().keySet();
        }
        if (hasRequirement) {
            summary += "; requirement fields " + requirement.getAsJsonObject().keySet();
        }
        plan.add(entry(id, "update_element", summary,
                "restore its previous name, documentation, stereotypes, tagged values and requirement fields"));
    }

    /**
     * Requirement boxes: which tag lines to show (IRequirementUIModel.setShowAttributes,
     * values probed live 2026-11-20). "non_empty" hides the empty verifyMethod/risk/status lines.
     */
    public static final Map<String, Integer> SHOW_TAGS = new java.util.LinkedHashMap<>();

    static {
        SHOW_TAGS.put("diagram", 0);
        SHOW_TAGS.put("all", 1);
        SHOW_TAGS.put("non_empty", 2);
        SHOW_TAGS.put("none", 3);
    }

    /** Symbol-table key prefix recording the model type of elements created earlier in the batch. */
    private static final String TYPE_KEY = "type:";

    /** Model type behind an endpoint: a view id on the project, or a create_element ref; null if unknown. */
    private static String endpointType(IProject project, JsonElement reference, Map<String, String> symbols) {
        if (reference == null) {
            return null;
        }
        if (reference.isJsonObject() && reference.getAsJsonObject().has("ref")) {
            return symbols.get(TYPE_KEY + reference.getAsJsonObject().get("ref").getAsString());
        }
        if (reference.isJsonPrimitive() && project != null) {
            try {
                com.vp.plugin.diagram.IDiagramElement view = project.getDiagramElementById(reference.getAsString());
                IModelElement model = view == null ? null : view.getModelElement();
                return model == null ? null : model.getModelType();
            } catch (RuntimeException unknown) {
                return null;
            }
        }
        return null;
    }

    /** Requirement properties VP keeps as fields, not tags: JSON key → what VP calls it. */
    public static final Map<String, String> REQUIREMENT_FIELDS = new java.util.LinkedHashMap<>();

    static {
        REQUIREMENT_FIELDS.put("id", "ID");
        REQUIREMENT_FIELDS.put("text", "Text");
        REQUIREMENT_FIELDS.put("kind", "Kind");
        REQUIREMENT_FIELDS.put("source", "Source");
        REQUIREMENT_FIELDS.put("risk", "Risk");
        REQUIREMENT_FIELDS.put("verify_method", "Verify Method");
    }

    private static String squash(String name) {
        return name == null ? "" : name.replaceAll("[\\s_-]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Tag names that would silently become a second tag (live: "text" and "id"
     * sat next to the built-in "Text" and "ID"): a name that differs from an
     * existing tag only in case or spacing, or, on a Requirement, a name that is
     * really one of its fields. Null when the names are fine.
     */
    static String tagNameProblem(java.util.Set<String> wanted, String modelType, java.util.List<String> existing) {
        for (String raw : wanted) {
            String name = raw.trim();
            if ("Requirement".equals(modelType) && ("id".equals(squash(name)) || "text".equals(squash(name)))) {
                // Live 2026-11-20: the box's ID is the model's user ID and its Text is the
                // documentation; the ID/Text tags do not change what the box shows.
                String key = squash(name);
                return "On a Requirement the box's " + ("id".equals(key) ? "ID" : "Text") + " is not the \""
                        + name + "\" tag: set it with \"requirement\": {\"" + key + "\": ...}"
                        + ("text".equals(key) ? " (the same value as the documentation)." : ".");
            }
            for (String have : existing) {
                if (!have.equals(name) && squash(have).equals(squash(name))) {
                    return "Tag \"" + name + "\" differs from the existing tag \"" + have
                            + "\" only in case or spacing, and would become a second tag. Use \"" + have
                            + "\" exactly.";
                }
            }
        }
        return null;
    }

    static java.util.List<String> tagNames(IModelElement model) {
        java.util.List<String> names = new java.util.ArrayList<>();
        com.vp.plugin.model.ITaggedValueContainer container = model == null ? null : model.getTaggedValues();
        com.vp.plugin.model.ITaggedValue[] tags = container == null ? null : container.toTaggedValueArray();
        if (tags != null) {
            for (com.vp.plugin.model.ITaggedValue tag : tags) {
                if (tag != null && tag.getName() != null) {
                    names.add(tag.getName());
                }
            }
        }
        return names;
    }

    /**
     * update_element "requirement": {id?, text?, kind?, source?, risk?,
     * verify_method?}, all strings, only on Requirement models (a plan ref is
     * checked when applied). Null when fine.
     */
    static String requirementFieldsProblem(JsonElement requirement, String modelType) {
        if (!requirement.isJsonObject() || requirement.getAsJsonObject().size() == 0) {
            return "\"requirement\" must be a non-empty object with any of " + REQUIREMENT_FIELDS.keySet() + ".";
        }
        if (modelType != null && !"Requirement".equals(modelType)) {
            return "\"requirement\" fields only apply to a Requirement; this model is a " + modelType + ".";
        }
        for (Map.Entry<String, JsonElement> field : requirement.getAsJsonObject().entrySet()) {
            if (!REQUIREMENT_FIELDS.containsKey(field.getKey())) {
                return "Unknown requirement field \"" + field.getKey() + "\"; use any of "
                        + REQUIREMENT_FIELDS.keySet() + ".";
            }
            if (field.getValue() == null || !field.getValue().isJsonPrimitive()) {
                return "Requirement field \"" + field.getKey() + "\" must be a string.";
            }
        }
        return null;
    }

    /**
     * Tagged values merge by name: only the named tags change. A string,
     * number or boolean sets the value (creating the tag when absent); null
     * removes the tag. Null when the field is absent or well-formed.
     */
    static String taggedValuesProblem(JsonElement taggedValues) {
        if (taggedValues == null || taggedValues.isJsonNull()) {
            return null;
        }
        if (!taggedValues.isJsonObject() || taggedValues.getAsJsonObject().size() == 0) {
            return "\"tagged_values\" must be a non-empty object of tag name to value "
                    + "(string, number or boolean; null removes the tag).";
        }
        for (Map.Entry<String, JsonElement> tag : taggedValues.getAsJsonObject().entrySet()) {
            if (tag.getKey().trim().isEmpty()) {
                return "\"tagged_values\" names must be non-blank.";
            }
            JsonElement value = tag.getValue();
            if (value != null && !value.isJsonNull() && !value.isJsonPrimitive()) {
                return "Tagged value \"" + tag.getKey()
                        + "\" must be a string, number, boolean, or null to remove it.";
            }
        }
        return null;
    }

    private static void validateUpdateMember(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors, TypeTiers tiers) {
        String member = resolveMember(project, op.get("member"), symbols, tiers);
        if (member == null) {
            errors.add(error(id,
                    "Unknown member; use a member model id, or a plan ref to add_member."));
            return;
        }
        JsonElement name = op.get("name");
        if (name != null && !name.isJsonNull()
                && (!name.isJsonPrimitive() || name.getAsString().trim().isEmpty())) {
            errors.add(error(id, "Updated \"name\" must be a non-blank string."));
            return;
        }
        JsonElement parameters = op.get("parameters");
        if (parameters != null && !parameters.isJsonNull()) {
            String problem = parametersProblem(parameters);
            if (problem != null) {
                errors.add(error(id, problem));
                return;
            }
        }
        JsonElement length = op.get("length");
        if (length != null && !length.isJsonNull()
                && (!length.isJsonPrimitive() || !length.getAsJsonPrimitive().isNumber())) {
            errors.add(error(id, "Updated \"length\" must be a number."));
            return;
        }
        for (String field : new String[] {"nullable", "primary_key", "unique"}) {
            JsonElement value = op.get(field);
            if (value != null && !value.isJsonNull() && (!value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isBoolean())) {
                errors.add(error(id, "Updated \"" + field + "\" must be a boolean."));
                return;
            }
        }
        for (String field : new String[] {"type", "visibility", "multiplicity",
                "initial_value", "return_type"}) {
            JsonElement value = op.get(field);
            if (value != null && !value.isJsonNull() && !value.isJsonPrimitive()) {
                errors.add(error(id, "Updated \"" + field + "\" must be a string."));
                return;
            }
        }
        boolean any = false;
        for (String field : new String[] {"name", "type", "visibility", "multiplicity",
                "initial_value", "return_type", "parameters", "length", "nullable",
                "primary_key", "unique"}) {
            JsonElement value = op.get(field);
            if (value != null && !value.isJsonNull()) {
                any = true;
                break;
            }
        }
        if (!any) {
            errors.add(error(id,
                    "Update needs at least one of name/type/visibility/multiplicity/"
                            + "initial_value/return_type/parameters/length/nullable/"
                            + "primary_key/unique."));
            return;
        }
        symbols.put(id, "member");
        plan.add(entry(id, "update_member", "update member '" + member + "'",
                "restore its previous fields"));
    }

    private static String parametersProblem(JsonElement parameters) {
        if (!parameters.isJsonArray()) {
            return "\"parameters\" must be an array of {name, type?, direction?}; it replaces all.";
        }
        for (JsonElement item : parameters.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) {
                return "\"parameters\" entries must be objects.";
            }
            JsonObject parameter = item.getAsJsonObject();
            JsonElement name = parameter.get("name");
            if (name == null || !name.isJsonPrimitive()
                    || name.getAsString().trim().isEmpty()) {
                return "\"parameters\" entries need a non-blank \"name\".";
            }
            for (String field : new String[] {"type", "direction"}) {
                JsonElement value = parameter.get(field);
                if (value != null && !value.isJsonNull() && !value.isJsonPrimitive()) {
                    return "\"parameters\" " + field + " must be a string.";
                }
            }
        }
        return null;
    }

    /** Members resolve by plan ref or by id, but only creatable member kinds. */
    private static String resolveMember(IProject project, JsonElement reference,
            Map<String, String> symbols, TypeTiers tiers) {
        String id = resolveRef(reference, symbols, "member");
        if (id != null) {
            return id;
        }
        if (reference != null && reference.isJsonPrimitive()) {
            String candidate = reference.getAsString();
            try {
                IModelElement model = ModelLookup.byId(project, candidate);
                if (model != null && (tiers.isVerifiedMember(model.getModelType())
                        || tiers.isCreatable(model.getModelType()))) {
                    return candidate;
                }
            } catch (RuntimeException unknown) {
                return null;
            }
        }
        return null;
    }

    private static void validateStyleElement(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String element = resolveElement(project, op.get("element"), symbols);
        if (element == null) {
            errors.add(error(id, "Unknown element reference; use an existing element id or a plan ref."));
            return;
        }
        for (String field : new String[] {"background", "foreground", "line_color", "font_color",
                "fill_color"}) {
            JsonElement value = op.get(field);
            if (value != null && !value.isJsonNull()
                    && (!value.isJsonPrimitive() || !isHexColor(value.getAsString()))) {
                errors.add(error(id, "Style field \"" + field + "\" must be a hex colour like \"#FF0000\"."));
                return;
            }
        }
        for (String field : new String[] {"line_weight", "font_size"}) {
            JsonElement value = op.get(field);
            if (value != null && !value.isJsonNull()
                    && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                            || value.getAsDouble() <= 0)) {
                errors.add(error(id, "Style field \"" + field + "\" must be a positive number."));
                return;
            }
        }
        for (String field : new String[] {"font_bold", "font_italic"}) {
            JsonElement value = op.get(field);
            if (value != null && !value.isJsonNull()
                    && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())) {
                errors.add(error(id, "Style field \"" + field + "\" must be a boolean."));
                return;
            }
        }
        JsonElement fontName = op.get("font_name");
        if (fontName != null && !fontName.isJsonNull()
                && (!fontName.isJsonPrimitive() || fontName.getAsString().trim().isEmpty())) {
            errors.add(error(id, "Style field \"font_name\" must be a non-blank string."));
            return;
        }
        JsonElement showTags = op.get("show_tags");
        if (showTags != null && !showTags.isJsonNull()
                && (!showTags.isJsonPrimitive() || !SHOW_TAGS.containsKey(showTags.getAsString()))) {
            errors.add(error(id, "\"show_tags\" must be one of " + SHOW_TAGS.keySet() + "."));
            return;
        }
        boolean any = false;
        for (String field : new String[] {"background", "foreground", "line_color", "line_weight",
                "fill_color", "font_color", "font_size", "font_bold", "font_italic", "font_name", "show_tags"}) {
            JsonElement value = op.get(field);
            if (value != null && !value.isJsonNull()) {
                any = true;
                break;
            }
        }
        if (!any) {
            errors.add(error(id,
                    "Style needs at least one of background/fill_color/foreground/line_color/"
                            + "line_weight/font_color/font_size/font_bold/font_italic/font_name/show_tags."));
            return;
        }
        symbols.put(id, "element");
        plan.add(entry(id, "style_element", "style element '" + element + "'",
                "restore its previous colours, line and font"));
    }

    /**
     * The escape hatch: any no-arg create method of IModelElementFactory
     * (1453 of the 1454 in the v18.1 pack). Nothing here knows whether the
     * method or its result behaves; that honesty is the point. The
     * "create" prefix keeps the hatch from invoking non-creation methods.
     */
    private static void validateCreateRaw(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String method = text(op, "factory_method");
        if (method == null || method.trim().isEmpty()) {
            errors.add(error(id, "Raw creation needs a \"factory_method\"."));
            return;
        }
        if (!method.startsWith("create")) {
            errors.add(error(id,
                    "\"factory_method\" must be a create method of IModelElementFactory, "
                            + "for example \"createRequirement\"."));
            return;
        }
        String diagram = null;
        if (op.get("diagram") != null && !op.get("diagram").isJsonNull()) {
            diagram = resolveDiagram(project, op.get("diagram"), symbols);
            if (diagram == null) {
                errors.add(error(id,
                        "Unknown diagram reference; use an existing diagram id or a plan ref."));
                return;
            }
        }
        JsonElement name = op.get("name");
        if (name != null && !name.isJsonNull()
                && (!name.isJsonPrimitive() || name.getAsString().trim().isEmpty())) {
            errors.add(error(id, "Raw creation \"name\" must be a non-blank string."));
            return;
        }
        if (!optionalNumbers(op, "x", "y", "width", "height")) {
            errors.add(error(id, "Geometry fields x/y/width/height must be numbers."));
            return;
        }
        symbols.put(id, "element");
        plan.add(entry(id, "create_raw",
                "raw model via factory method '" + method.trim() + "'"
                        + (diagram == null ? " (no diagram; an orphan model)"
                                : " on diagram '" + diagram + "'")
                        + " — unverified family, VP may veto or misplace",
                "delete what was created"));
    }

    private static boolean isHexColor(String value) {
        return value != null && value.matches("#?[0-9a-fA-F]{6}");
    }

    private static String pointsProblem(JsonElement points) {
        if (points == null || points.isJsonNull()) {
            return null;
        }
        if (!points.isJsonArray() || points.getAsJsonArray().size() < 2) {
            return "\"points\" must be an array of at least two {x, y} waypoints.";
        }
        for (JsonElement item : points.getAsJsonArray()) {
            if (item == null || !item.isJsonObject() || !isNumber(item.getAsJsonObject(), "x")
                    || !isNumber(item.getAsJsonObject(), "y")) {
                return "\"points\" entries must be {x, y} numbers.";
            }
        }
        return null;
    }

    private static boolean isNumber(JsonObject holder, String name) {
        JsonElement value = holder.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    /**
     * route_connector {id, element, points}: replace a connector's path. The
     * element is an existing connector view id or a plan ref to a connect op;
     * points are absolute and span the whole path, end points included
     * (ADR-0039).
     */
    private static void validateRouteConnector(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        JsonElement reference = op.get("element");
        String element = resolveRef(reference, symbols, "relationship");
        if (element == null && reference != null && reference.isJsonPrimitive()) {
            try {
                Object view = project == null ? null : project.getDiagramElementById(reference.getAsString());
                if (view instanceof com.vp.plugin.diagram.IConnectorUIModel) {
                    element = reference.getAsString();
                } else if (view != null) {
                    errors.add(error(id, "route_connector needs a connector; '" + reference.getAsString()
                            + "' is a shape (move it with move_element)."));
                    return;
                }
            } catch (RuntimeException unknown) {
                element = null;
            }
        }
        if (element == null) {
            errors.add(error(id, "Unknown connector reference; use a connector view id or a connect op ref."));
            return;
        }
        JsonElement points = op.get("points");
        if (points == null || points.isJsonNull()) {
            errors.add(error(id, "route_connector needs \"points\": the full path, end points included."));
            return;
        }
        String problem = pointsProblem(points);
        JsonElement label = op.get("label");
        boolean hasLabel = label != null && !label.isJsonNull();
        if (problem == null && hasLabel && (!label.isJsonObject() || !isNumber(label.getAsJsonObject(), "x")
                || !isNumber(label.getAsJsonObject(), "y"))) {
            problem = "\"label\" must be {x, y, width?, height?} numbers: the label's absolute box.";
        }
        if (problem != null) {
            errors.add(error(id, problem));
            return;
        }
        symbols.put(id, "route");
        plan.add(entry(id, "route_connector", "route '" + element + "' through "
                + points.getAsJsonArray().size() + " points" + (hasLabel ? " and move its label" : ""),
                hasLabel ? "restore its previous points and label" : "restore its previous points"));
    }

    private static void validateMoveElement(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String element = resolveElement(project, op.get("element"), symbols);
        if (element == null) {
            errors.add(error(id, "Unknown element reference; use an existing element id or a plan ref."));
            return;
        }
        boolean any = false;
        for (String field : new String[] {"x", "y", "width", "height"}) {
            JsonElement value = op.get(field);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                errors.add(error(id, "Geometry fields x/y/width/height must be numbers."));
                return;
            }
            any = true;
        }
        if (!any) {
            errors.add(error(id, "Move needs at least one of x/y/width/height."));
            return;
        }
        symbols.put(id, "element");
        plan.add(entry(id, "move_element", "move '" + element + "'",
                "restore its previous bounds"));
    }

    private static void validateShowElement(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String diagram = resolveDiagram(project, op.get("diagram"), symbols);
        if (diagram == null) {
            errors.add(error(id, "Unknown diagram reference; use an existing diagram id or a plan ref."));
            return;
        }
        String model = resolveModel(project, op.get("model"), symbols);
        if (model == null) {
            errors.add(error(id, "Unknown model; use an existing model id or a plan ref."));
            return;
        }
        if (!optionalNumbers(op, "x", "y", "width", "height")) {
            errors.add(error(id, "Geometry fields x/y/width/height must be numbers."));
            return;
        }
        JsonElement containerRef = op.get("container");
        String container = null;
        if (containerRef != null && !containerRef.isJsonNull()) {
            container = resolveElement(project, containerRef, symbols);
            if (container == null) {
                errors.add(error(id, "Unknown \"container\" " + describeRef(containerRef)
                        + "; use the view id or plan ref of a shape on the same diagram."));
                return;
            }
        }
        symbols.put(id, "element");
        plan.add(entry(id, "show_element",
                "show model '" + model + "' on diagram '" + diagram + "' (model is shared)"
                        + (container == null ? "" : " inside '" + container + "' (view only; the model "
                                + "keeps its owner)"),
                "remove the view; the shared model is kept"));
    }

    private static void validateDeleteModel(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String model = resolveModel(project, op.get("model"), symbols);
        if (model == null) {
            errors.add(error(id, "Unknown model; use an existing model id or a plan ref."));
            return;
        }
        symbols.put(id, "deletion");
        plan.add(entry(id, "delete_model", "delete model '" + model + "' and all its views",
                "none; deletion is final (project is not auto-saved)"));
    }

    private static String resolveModel(IProject project, JsonElement reference,
            Map<String, String> symbols) {
        for (String kind : new String[] {"element", "member", "relationship"}) {
            String id = resolveRef(reference, symbols, kind);
            if (id != null) {
                return id;
            }
        }
        if (reference != null && reference.isJsonPrimitive()) {
            String candidate = reference.getAsString();
            try {
                if (project != null && ModelLookup.byId(project, candidate) != null) {
                    return candidate;
                }
            } catch (RuntimeException unknown) {
                return null;
            }
        }
        return null;
    }

    private static void validateDeleteDiagram(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String diagram = resolveDiagram(project, op.get("diagram"), symbols);
        if (diagram == null) {
            errors.add(error(id, "Unknown diagram reference; use an existing diagram id or a plan ref."));
            return;
        }
        symbols.put(id, "deletion");
        plan.add(entry(id, "delete_diagram", "delete diagram '" + diagram + "'",
                "none; deletion is final (project is not auto-saved)"));
    }

    private static void validateDeleteElement(IProject project, JsonObject op, String id,
            Map<String, String> symbols, JsonArray plan, JsonArray errors) {
        String element = resolveElement(project, op.get("element"), symbols);
        if (element == null && isRefTo(op.get("element"), symbols, "relationship")) {
            element = text(op.getAsJsonObject("element"), "ref");
        }
        if (element == null) {
            errors.add(error(id, "Unknown element reference; use an existing element id or a plan ref."));
            return;
        }
        symbols.put(id, "deletion");
        plan.add(entry(id, "delete_element", "delete element '" + element + "'",
                "none; deletion is final (project is not auto-saved)"));
    }

    private static boolean isRefTo(JsonElement reference, Map<String, String> symbols, String wantKind) {
        return reference != null && reference.isJsonObject()
                && wantKind.equals(symbols.get(text(reference.getAsJsonObject(), "ref")));
    }

    private static String memberProblem(JsonElement member, String name) {
        if (member == null || member.isJsonNull()) {
            return null;
        }
        if (!member.isJsonPrimitive() || member.getAsString().trim().isEmpty()) {
            return "\"" + name + "\" must be a member model id.";
        }
        return null;
    }

    private static String resolveDiagram(IProject project, JsonElement reference, Map<String, String> symbols) {
        String id = resolveRef(reference, symbols, "diagram");
        if (id != null) {
            return id;
        }
        if (reference != null && reference.isJsonPrimitive()) {
            String candidate = reference.getAsString();
            IDiagramUIModel[] diagrams = project == null ? null : project.toDiagramArray();
            if (diagrams != null) {
                for (IDiagramUIModel diagram : diagrams) {
                    if (diagram != null && candidate.equals(diagram.getId())) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static String resolveElement(IProject project, JsonElement reference, Map<String, String> symbols) {
        String id = resolveRef(reference, symbols, "element");
        if (id != null) {
            return id;
        }
        if (reference != null && reference.isJsonPrimitive()) {
            String candidate = reference.getAsString();
            try {
                if (project != null && project.getDiagramElementById(candidate) != null) {
                    return candidate;
                }
            } catch (RuntimeException unknown) {
                return null;
            }
        }
        return null;
    }

    private static String resolveRef(JsonElement reference, Map<String, String> symbols, String wantKind) {
        if (reference != null && reference.isJsonObject() && reference.getAsJsonObject().has("ref")) {
            String id = text(reference.getAsJsonObject(), "ref");
            if (wantKind.equals(symbols.get(id))) {
                return id;
            }
        }
        return null;
    }

    /** Renders an id-or-ref slot for error messages: the raw id, "ref X", or "?". */
    private static String describeRef(JsonElement reference) {
        if (reference != null && reference.isJsonPrimitive()) {
            return reference.getAsString();
        }
        if (reference != null && reference.isJsonObject()
                && reference.getAsJsonObject().get("ref") != null) {
            return "ref " + reference.getAsJsonObject().get("ref").getAsString();
        }
        return "?";
    }

    private static boolean optionalNumbers(JsonObject op, String... names) {
        for (String name : names) {
            JsonElement value = op.get(name);
            if (value != null && !value.isJsonNull() && !value.isJsonPrimitive()) {
                return false;
            }
            if (value != null && value.isJsonPrimitive() && !value.getAsJsonPrimitive().isNumber()) {
                return false;
            }
        }
        return true;
    }

    private static String multiplicityProblem(JsonElement value, String field) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !MULTIPLICITIES.contains(value.getAsString())) {
            return "\"" + field + "\" must be one of " + MULTIPLICITY_HELP + ".";
        }
        return null;
    }

    private static String aggregationProblem(JsonElement value, String field) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            String aggregation = value.getAsString();
            if (aggregation.equals("none") || aggregation.equals("shared")
                    || aggregation.equals("composite")) {
                return null;
            }
        }
        return "\"" + field + "\" must be one of none/shared/composite.";
    }

    private static String booleanProblem(JsonElement value, String field) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return "\"" + field + "\" must be true or false.";
        }
        return null;
    }

    private static String text(JsonObject holder, String name) {
        JsonElement value = holder.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsString();
    }

    private static JsonObject entry(String id, String op, String summary, String undo) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("op", op);
        entry.addProperty("summary", summary);
        entry.addProperty("undo", undo);
        return entry;
    }

    /** A plan entry for a pack-tier type: same shape, plus the unverified flag. */
    private static JsonObject unverifiedEntry(String id, String op, String summary, String undo) {
        JsonObject entry = entry(id, op, summary + " — " + TypeTiers.unverifiedNote(), undo);
        entry.addProperty("unverified", true);
        return entry;
    }

    private static JsonObject entry(String id, String op, String summary) {
        return entry(id, op, summary, "none");
    }

    private static JsonObject error(String id, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("id", id);
        error.addProperty("message", message);
        return error;
    }

    private static JsonObject result(boolean valid, JsonArray plan, JsonArray errors) {
        JsonObject result = new JsonObject();
        result.addProperty("valid", valid);
        result.add("plan", plan);
        result.add("errors", errors);
        return result;
    }
}
