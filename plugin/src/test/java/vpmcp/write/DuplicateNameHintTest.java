package vpmcp.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import org.junit.jupiter.api.Test;
import vpmcp.VpFakes;

/** A refused name is explained when a same-type sibling already holds it. */
class DuplicateNameHintTest {

    @Test
    void aSameTypeSiblingWithTheNameIsNamedInTheHint() {
        IModelElement existing = VpFakes.model("old", "Board shows status", "Requirement");
        IModelElement fresh = VpFakes.model("new", "Requirement", "Requirement");
        IModelElement otherType = VpFakes.model("uc", "View Board", "UseCase");
        IProject project = VpFakes.project("p", new IModelElement[] {existing, fresh, otherType},
                new com.vp.plugin.diagram.IDiagramUIModel[0]);

        String hint = BatchApplier.duplicateNameHint(project, fresh, "Board shows status");

        assertTrue(hint.contains("old"), hint);
        assertEquals("", BatchApplier.duplicateNameHint(project, fresh, "Something else"));
        assertEquals("", BatchApplier.duplicateNameHint(project, fresh, "View Board"),
                "a different type with the name is not a duplicate");
    }
}
