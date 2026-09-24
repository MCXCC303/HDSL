package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXCheckTreeCell;
import com.jfoenix.controls.JFXTreeView;
import javafx.application.Platform;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.TreeCell;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshPaths;
import org.jackhuang.hmcl.ui.construct.NoneMultipleSelectionModel;
import org.jackhuang.hmcl.util.SettingsMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The export page as it is drawn.
///
/// The original's step is a tree of material checkboxes whose rows cannot be selected — only ticked.
/// This launcher drew the platform's own checkbox cell instead, which is a small square with a tick
/// rather than the round box every other page draws, and it let rows be selected. Both are decided by
/// three lines in the page, and nothing else in the suite would notice them changing back.
class ModpackFilesStyleTest {

    static {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        }
        try {
            assertTrue(started.await(30, TimeUnit.SECONDS), "the JavaFX toolkit did not start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting the JavaFX toolkit", e);
        }
    }

    private static void onFxThread(Runnable work) throws Exception {
        FutureTask<Void> task = new FutureTask<>(work, null);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    /// Returns the tree the page draws.
    ///
    /// @param page the page
    /// @return its tree
    @SuppressWarnings("unchecked")
    private static JFXTreeView<String> treeOf(ModpackFilesPage page) {
        return (JFXTreeView<String>) page.getChildren().stream()
                .filter(JFXTreeView.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the page draws no JFXTreeView"));
    }

    /// Builds the page over an instance whose profile names two bundles.
    ///
    /// @param id the instance id, which is also the directory it is read from
    /// @return the instance
    private static DshInstance instanceWith(String id) throws Exception {
        Path home = DshPaths.instanceDirectory(id).resolve("home");
        Files.createDirectories(home.resolve("profiles/web"));
        Files.writeString(home.resolve("profiles/web/package.json"), """
                {"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-plugin-manager","@deepseek-ai/dsh-better-sidebar"]}}}
                """);
        return new DshInstance(id, "v0.1.7", "web", "/tmp", null, DshHomeMode.ISOLATED, null,
                List.of(), Map.of(), null, null, null, 0, 0L);
    }

    private static void delete(String id) throws Exception {
        Path directory = DshPaths.instanceDirectory(id);
        if (Files.exists(directory)) {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // The build tree is scratch space; a file left behind is not worth failing on.
                    }
                });
            }
        }
    }

    /// The row's box is the material one the original draws, and the row itself cannot be selected.
    @Test
    void theRowsAreMaterialCheckboxesThatCannotBeSelected() throws Exception {
        String id = "style-cells";
        DshInstance instance = instanceWith(id);
        try {
            onFxThread(() -> {
                // The controller is only used when the button is pressed, which this test does not do.
                ModpackFilesPage page = new ModpackFilesPage(null, instance, new SettingsMap());

                JFXTreeView<String> tree = treeOf(page);
                assertInstanceOf(NoneMultipleSelectionModel.class, tree.getSelectionModel(),
                        "a row of the original's tree is not selectable");

                TreeCell<String> cell = tree.getCellFactory().call(tree);
                assertInstanceOf(JFXCheckTreeCell.class, cell,
                        "the row draws the platform's checkbox rather than the material one");
            });
        } finally {
            delete(id);
        }
    }

    /// What the boxes say about the instance, read off the tree the page built.
    @Test
    void whatTheBoxesSayFollowsWhatIsUnderThem() throws Exception {
        String id = "style-marks";
        DshInstance instance = instanceWith(id);
        try {
            onFxThread(() -> {
                ModpackFilesPage page = new ModpackFilesPage(null, instance, new SettingsMap());
                CheckBoxTreeItem<String> root = (CheckBoxTreeItem<String>) treeOf(page).getRoot();

                CheckBoxTreeItem<String> plugins = (CheckBoxTreeItem<String>) root.getChildren().get(0);
                CheckBoxTreeItem<String> sessions = (CheckBoxTreeItem<String>) root.getChildren().get(2);
                assertTrue(plugins.isSelected(), "both bundles are ticked, so the branch is");
                assertFalse(plugins.isIndeterminate());
                assertFalse(sessions.isSelected(), "no conversation was asked for, so none travels");

                // The instance holds one branch that travels and one that does not: it is partly
                // travelling, which is the answer JavaFX never derives on its own and the one the
                // page was showing as "everything travels" before it kept the marks itself.
                assertTrue(root.isIndeterminate(), "one branch travels and one does not: partly, not fully");
                assertFalse(root.isSelected(), "a partly travelling instance is not a travelling one");
            });
        } finally {
            delete(id);
        }
    }
}
