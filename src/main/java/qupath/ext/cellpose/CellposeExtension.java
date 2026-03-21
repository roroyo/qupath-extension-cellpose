package qupath.ext.cellpose;

import org.controlsfx.control.action.Action;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.actions.ActionTools;
import qupath.lib.gui.actions.annotations.ActionMenu;
import qupath.lib.gui.extensions.QuPathExtension;

public class CellposeExtension implements QuPathExtension {

    @Override
    public void installExtension(QuPathGUI qupath) {
        qupath.installActions(ActionTools.getAnnotatedActions(new CellposeActions(qupath)));
    }

    @Override
    public String getName() {
        return "Cellpose extension";
    }

    @Override
    public String getDescription() {
        return "Prototype QuPath extension shell for running Cellpose on selected annotations.";
    }

    @Override
    public Version getQuPathVersion() {
        return getVersion();
    }

    public static class CellposeActions {

        @ActionMenu(value = {"Menu.Extensions", "Cellpose>"})
        public final Action actionRunPrototype;

        CellposeActions(QuPathGUI qupath) {
            actionRunPrototype = ActionTools.createAction(
                    new RunCellposePrototypeCommand(qupath),
                    "Run Cellpose on selected annotations");
        }
    }
}
