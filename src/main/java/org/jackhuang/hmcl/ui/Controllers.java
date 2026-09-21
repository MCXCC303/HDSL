/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
package org.jackhuang.hmcl.ui;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.Motion;
import org.jackhuang.hmcl.ui.construct.InputDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import com.jfoenix.validation.base.ValidatorBase;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jackhuang.hmcl.util.FutureCallback;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Path;
import java.util.Objects;

/// Owns the application stage, the window decorator and the dialog helpers the
/// ported component library calls into.
///
/// This is the HMCL-DSH replacement for HMCL's `Controllers`: same entry points,
/// but without the Minecraft page registry, account dialogs or update checker.
@NotNullByDefault
public final class Controllers {
    private Controllers() {
    }

    /// The main window decorator, or `null` before [#initialize] or after [#shutdown].
    private static @Nullable Decorator decorator;

    /// The primary application stage, or `null` before [#initialize] or after [#shutdown].
    private static @Nullable Stage stage;

    /// The instance list page, created on first use.
    private static @Nullable org.jackhuang.hmcl.ui.dsh.InstancesPage instancesPage;

    /// Whether idle heap trimming is enabled.
    ///
    /// Trimming is opt-out for machines where a stop-the-world collection after
    /// each task is more noticeable than the retained heap.
    public static final boolean AUTO_TRIM_HEAP =
            !"false".equalsIgnoreCase(System.getenv("HDSL_AUTO_TRIM_HEAP"));

    /// Installs the main page into a decorator attached to the primary stage.
    ///
    /// @param primaryStage the application stage, which must not have been shown yet
    /// @param mainPage     the page shown first
    /// @return the scene created for the stage
    public static javafx.scene.Scene initialize(Stage primaryStage, Node mainPage) {
        stage = primaryStage;
        decorator = new Decorator(mainPage);
        return decorator.attachStage(primaryStage);
    }

    /// Returns the primary application stage.
    ///
    /// @return the primary stage
    /// @throws NullPointerException when the launcher has not been initialized
    public static Stage getStage() {
        return Objects.requireNonNull(stage, "Main window is not initialized");
    }

    /// Returns the application-wide window decorator.
    ///
    /// @return the main window decorator
    /// @throws NullPointerException when the launcher has not been initialized
    public static Decorator getDecorator() {
        return Objects.requireNonNull(decorator, "Main window is not initialized");
    }

    /// Releases stage-specific listeners and retained window ownership.
    public static void onApplicationStop() {
        Decorator current = decorator;
        if (current != null) {
            current.detachStage();
        }
    }

    /// Returns whether the main window has been torn down.
    ///
    /// @return whether the launcher window is gone
    public static boolean isStopped() {
        return decorator == null;
    }

    /// Releases controller-owned pages and JavaFX helper resources.
    public static void shutdown() {
        onApplicationStop();
        decorator = null;
        stage = null;
        FXUtils.shutdown();
    }

    /// Shows a dialog containing an arbitrary region.
    ///
    /// Does nothing when the main window is not available, so background
    /// failures during shutdown cannot resurrect the UI.
    ///
    /// @param content the dialog body
    public static void dialog(Region content) {
        Decorator current = decorator;
        if (current != null)
            current.showDialog(content);
    }

    /// Shows an informational dialog containing plain text.
    ///
    /// @param text the message body
    public static void dialog(String text) {
        dialog(text, null);
    }

    /// Shows an informational dialog containing plain text.
    ///
    /// @param text  the message body
    /// @param title the dialog title, or `null`
    public static void dialog(String text, @Nullable String title) {
        dialog(text, title, MessageType.INFO);
    }

    /// Shows a dialog containing plain text.
    ///
    /// @param text  the message body
    /// @param title the dialog title, or `null`
    /// @param type  the message severity
    public static void dialog(String text, @Nullable String title, MessageType type) {
        dialog(text, title, type, null);
    }

    /// Shows a dialog containing plain text and runs an action when it is accepted.
    ///
    /// @param text  the message body
    /// @param title the dialog title, or `null`
    /// @param type  the message severity
    /// @param ok    the action to run on accept, or `null`
    public static void dialog(String text, @Nullable String title, MessageType type, @Nullable Runnable ok) {
        dialog(new MessageDialogPane.Builder(text, title, type).ok(ok).build());
    }

    /// Shows a yes/no confirmation dialog.
    ///
    /// @param text  the message body
    /// @param title the dialog title, or `null`
    /// @param yes   the action to run when confirmed
    /// @param no    the action to run when declined, or `null`
    public static void confirm(String text, @Nullable String title, Runnable yes, @Nullable Runnable no) {
        confirm(text, title, MessageType.QUESTION, yes, no);
    }

    /// Shows a yes/no confirmation dialog with an explicit severity.
    ///
    /// @param text  the message body
    /// @param title the dialog title, or `null`
    /// @param type  the message severity
    /// @param yes   the action to run when confirmed
    /// @param no    the action to run when declined, or `null`
    public static void confirm(String text, @Nullable String title, MessageType type,
                               Runnable yes, @Nullable Runnable no) {
        dialog(new MessageDialogPane.Builder(text, title, type).yesOrNo(yes, no).build());
    }

    /// Navigates the content area to a page with the standard transition.
    ///
    /// @param node the page to show
    public static void navigate(Node node) {
        Decorator current = decorator;
        if (current != null)
            current.navigate(node, ContainerAnimations.NAVIGATION, Motion.SHORT4, Motion.EASE);
    }

    /// Navigates the content area to a page with the forward transition.
    ///
    /// @param node the page to show
    public static void navigateForward(Node node) {
        Decorator current = decorator;
        if (current != null)
            current.navigate(node, ContainerAnimations.FORWARD, Motion.SHORT4, Motion.EASE);
    }

    /// Returns the instance list page, creating it on first use.
    ///
    /// The page is kept here rather than by whichever page opens it, because more
    /// than one does: the home page's sidebar, an instance page that has just
    /// renamed or removed its instance, and the home page's own empty state.
    /// Handing out one page is what keeps them from drifting apart, and it is
    /// where the original keeps its game list too.
    ///
    /// @return the instance list page
    public static org.jackhuang.hmcl.ui.dsh.InstancesPage getInstancesPage() {
        org.jackhuang.hmcl.ui.dsh.InstancesPage page = instancesPage;
        if (page == null) {
            page = new org.jackhuang.hmcl.ui.dsh.InstancesPage();
            instancesPage = page;
        }
        return page;
    }

    /// Shows a transient snackbar message over the main window.
    ///
    /// @param content the message to show
    public static void showToast(String content) {
        Decorator current = decorator;
        if (current != null)
            current.showToast(content);
    }

    /// Shows a directory chooser owned by the main window.
    ///
    /// @param chooser the chooser to show
    /// @return the selected directory, or `null` when cancelled
    public static @Nullable Path showDialog(DirectoryChooser chooser) {
        return FileUtils.toPath(chooser.showDialog(stage));
    }

    /// Shows a file-open chooser owned by the main window.
    ///
    /// @param chooser the chooser to show
    /// @return the selected file, or `null` when cancelled
    public static @Nullable Path showOpenDialog(FileChooser chooser) {
        return FileUtils.toPath(chooser.showOpenDialog(stage));
    }

    /// Shows a file-save chooser owned by the main window.
    ///
    /// @param chooser the chooser to show
    /// @return the selected file, or `null` when cancelled
    public static @Nullable Path showSaveDialog(FileChooser chooser) {
        return FileUtils.toPath(chooser.showSaveDialog(stage));
    }

    /// Handles a hyperlink click coming from rendered rich text.
    ///
    /// HMCL-DSH has no in-application URI scheme yet, so every link is handed
    /// to the desktop or copied to the clipboard.
    ///
    /// @param href the link target
    public static void onHyperlinkAction(String href) {
        openUriOrCopy(href);
    }

    /// Opens a URI in the desktop browser, falling back to copying it.
    ///
    /// @param uri the URI to open, or `null`
    public static void openUriOrCopy(@Nullable URI uri) {
        if (uri != null) {
            openUriOrCopy(uri.toString());
        }
    }

    /// Opens a URI in the desktop browser, falling back to copying it.
    ///
    /// @param uri the URI to open, or `null`
    public static void openUriOrCopy(@Nullable String uri) {
        if (uri == null) {
            return;
        }
        FXUtils.openLink(uri);
    }

    /// Prompts for a single line of text.
    ///
    /// @param title        the prompt shown above the field
    /// @param onResult     the callback that accepts or rejects the entered value
    /// @param initialValue the value shown when the dialog opens
    /// @param validators   optional validators applied as the user types
    /// @return a future completed with the accepted value, or completed exceptionally on cancel
    public static CompletableFuture<String> prompt(String title, FutureCallback<String> onResult,
                                                  String initialValue, ValidatorBase... validators) {
        InputDialogPane pane = new InputDialogPane(title, initialValue, onResult, validators);
        dialog(pane);
        return pane.getCompletableFuture();
    }

    /// Prompts for a single line of text with an empty initial value.
    ///
    /// @param title    the prompt shown above the field
    /// @param onResult the callback that accepts or rejects the entered value
    /// @return a future completed with the accepted value, or completed exceptionally on cancel
    public static CompletableFuture<String> prompt(String title, FutureCallback<String> onResult) {
        return prompt(title, onResult, "");
    }

    /// Requests a garbage collection when heap trimming is enabled.
    public static void trimHeap() {
        if (AUTO_TRIM_HEAP) {
            System.gc();
        }
    }
}
