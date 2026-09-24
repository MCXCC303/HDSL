package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXCheckBox;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// The three answers a material checkbox draws.
///
/// JavaFX has the state — `indeterminate` — and no look for it, and neither did this launcher's skin:
/// the box was filled and the tick hidden, which is to say that a partly ticked branch of a tree was
/// drawn as an *empty* one. That was half of why the export page's branch marks read wrong: the page
/// computed the third answer and the skin threw it away. Nothing but a screenshot noticed, so the
/// marks themselves are pinned here.
class CheckBoxMarksTest {

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

    /// What a checkbox draws.
    ///
    /// @param tick whether the check mark is shown
    /// @param bar  whether the partly-ticked bar is shown
    private record Marks(boolean tick, boolean bar) {
    }

    /// Returns what the checkbox draws, with any selection animation settled.
    ///
    /// The animation is finished rather than waited for: what is being pinned is the state each
    /// answer ends in, not how long it takes to arrive. In the interface the bar appears the moment
    /// a branch becomes partial, which is the same state.
    ///
    /// @param box the checkbox
    /// @return the marks
    private static Marks marksOf(JFXCheckBox box) {
        StackPane root = new StackPane(box);
        new Scene(root, 200, 60);
        root.applyCss();
        root.layout();
        assertInstanceOf(com.jfoenix.skins.JFXCheckBoxSkin.class, box.getSkin());
        ((com.jfoenix.skins.JFXCheckBoxSkin) box.getSkin()).finishSelectionAnimation();

        Node tick = box.lookup(".jfx-check-mark");
        Node bar = box.lookup(".jfx-indeterminate-mark");
        assertNotNull(tick, "the skin draws no check mark");
        assertNotNull(bar, "the skin draws no partly-ticked bar");
        return new Marks(tick.isVisible(), bar.isVisible());
    }

    @Test
    void aBoxThatIsNeitherTickedNorPartlyTickedShowsNeitherMark() throws Exception {
        onFxThread(() -> {
            JFXCheckBox box = new JFXCheckBox();
            Marks marks = marksOf(box);
            assertFalse(marks.tick());
            assertFalse(marks.bar());
        });
    }

    @Test
    void aTickedBoxShowsATickAndNoBar() throws Exception {
        onFxThread(() -> {
            JFXCheckBox box = new JFXCheckBox();
            box.setSelected(true);
            Marks marks = marksOf(box);
            assertTrue(marks.tick());
            assertFalse(marks.bar());
        });
    }

    @Test
    void aPartlyTickedBoxShowsABarRatherThanATick() throws Exception {
        onFxThread(() -> {
            JFXCheckBox box = new JFXCheckBox();
            // A tree cell turns the click-cycling off — the third answer is decided by the tree, not
            // by the order somebody clicks in — so this is the state the skin is asked to draw.
            box.setAllowIndeterminate(false);
            box.setIndeterminate(true);

            Marks marks = marksOf(box);
            assertTrue(marks.bar(),
                    "a branch whose children disagree is neither ticked nor empty, and has to look it");
            assertFalse(marks.tick(), "the tick gives way to the bar");
        });
    }
}
