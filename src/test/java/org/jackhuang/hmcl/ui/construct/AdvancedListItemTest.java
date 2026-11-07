/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.construct;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a row runs its action exactly once per background click.
 */
@NotNullByDefault
public final class AdvancedListItemTest {
    /**
     * Starts the toolkit for this class, or skips the class where it cannot run.
     */
    @BeforeAll
    static void startToolkit() {
        FxToolkit.requireRunning();
    }

    /**
     * Clicking the row's background runs the action once, through the layer that takes the click.
     */
    @Test
    void aBackgroundClickRunsTheRowActionOnce() throws Exception {
        onFxThread(() -> {
            AdvancedListItem item = new AdvancedListItem();
            item.setTitle("title");
            item.setSubtitle("subtitle");
            AtomicInteger actions = new AtomicInteger();
            item.setOnAction(event -> actions.incrementAndGet());

            StackPane root = new StackPane(item);
            new Scene(root, 320, 120);
            root.applyCss();
            root.layout();

            RipplerContainer wrapper = (RipplerContainer) item.getChildrenUnmodifiable().get(0);
            Node interactionLayer = wrapper.getChildrenUnmodifiable().get(0);
            double x = 40;
            double y = item.getHeight() / 2;

            // The text layer is mouse-transparent and the content box paints no background, so the
            // click belongs to the interaction layer; if that changed, the row would stop reacting.
            assertFalse(wrapper.getContainer().contains(x, y));
            assertTrue(interactionLayer.contains(x, y));

            Event.fireEvent(interactionLayer, click(x, y));
            assertEquals(1, actions.get());
        });
    }

    /**
     * An action fired on a disabled row is dropped, and the same row fires once when enabled.
     */
    @Test
    void aDisabledRowDoesNotRunItsAction() throws Exception {
        onFxThread(() -> {
            AdvancedListItem item = new AdvancedListItem();
            AtomicInteger actions = new AtomicInteger();
            item.setOnAction(event -> actions.incrementAndGet());

            item.setDisable(true);
            item.fire();
            assertEquals(0, actions.get());

            item.setDisable(false);
            item.fire();
            assertEquals(1, actions.get());
        });
    }

    /**
     * Creates a primary click at a point in the target's local coordinates.
     */
    private static MouseEvent click(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
    }

    /**
     * Runs assertions on the JavaFX thread and propagates failures to the test worker.
     */
    private static void onFxThread(Runnable action) throws Exception {
        FutureTask<@Nullable Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }
}
