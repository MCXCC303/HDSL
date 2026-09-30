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
package org.jackhuang.hmcl.ui;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.Motion;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.InputDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import com.jfoenix.validation.base.ValidatorBase;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jackhuang.hmcl.util.FutureCallback;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Owns the application stage, the window decorator and the dialog helpers the
/// ported component library calls into.
///
/// This is the HDSL replacement for HMCL's `Controllers`: same entry points,
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

    /// The download page, created on first use.
    private static @Nullable org.jackhuang.hmcl.ui.dsh.DownloadPage downloadPage;

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

    /// Asks a yes/no question whose answer takes time, and stays until it has been answered.
    ///
    /// The dialog a question is asked in used to close the moment the button was pressed, which is
    /// the moment least likely to be the end of it: removing an instance stops a running child and
    /// then takes its tree away with retries, which is seconds rather than milliseconds, and nothing
    /// on the screen says so. What a person sees is a dialog that vanished and a list that still
    /// shows the row, so they press it again — and a question that closes before its answer arrives
    /// is a question that gets answered twice, on a row whose second answer is about something that
    /// may already have happened.
    ///
    /// This one keeps the dialog open, replaces its buttons with a spinner and does not close it
    /// until the work has finished. The press is taken on the interface thread and the work is not,
    /// because the whole point of it is that it waits: the spinner has to be on the screen before
    /// anything blocks, and one frame of it after is one frame too late.
    ///
    /// A failure leaves the dialog where it is and says why beside it, so the person can try again or
    /// decline — which is the only honest thing to do with a question whose answer did not happen.
    ///
    /// @param text   the question
    /// @param title  the dialog title, or `null`
    /// @param wait   what the dialog is waiting for, shown in place of the buttons
    /// @param work   the work to run when confirmed, off the interface thread
    /// @param failed the title of the dialog that reports a failure, or `null` to say nothing
    public static void confirmAsync(String text, @Nullable String title, String wait,
                                    Supplier<? extends CompletionStage<?>> work,
                                    @Nullable String failed) {
        MessageDialogPane pane = new MessageDialogPane.Builder(text, title, MessageType.QUESTION)
                .askYesOrNo(question -> answerQuestion(question, wait, work, failed), null)
                .build();
        dialog(pane);
    }

    /// Starts the work a question was answered with, and holds the question open until it ends.
    ///
    /// The waiting state is set here rather than by the button, because the button's own closing
    /// handler is registered first and would have closed the dialog before this ran — which is what
    /// the removal confirmation used to do: it vanished on the press and the removal carried on
    /// invisibly, so pressing again looked like the obvious thing to do.
    ///
    /// The work itself is the caller's, started by the supplier it handed over; what happens here is
    /// only what the answer means. Success closes the question. Failure puts its buttons back and
    /// says why beside it, because a question whose answer did not happen is one that has to be
    /// asked again.
    ///
    /// Both of those touch the dialog, so both happen on the interface thread. The work finishes
    /// wherever it was running — for a removal, on a worker thread some forty seconds later — and a
    /// dialog is closed on the interface thread or not at all: [DialogUtils#close] checks the thread
    /// and throws into this stage when it is not the interface one, and nobody reads the result of a
    /// stage like this one. What that looks like from the outside is a question that keeps its
    /// spinner after its work has finished, with nothing logged beside it — which is what the removal
    /// confirmation did.
    ///
    /// Not private, because that thread is the whole of what this method promises and it is measured
    /// by [org.jackhuang.hmcl.ui.ConfirmationWorkThreadTest].
    ///
    /// @param pane   the question
    /// @param wait   what it is waiting for, shown in place of the buttons
    /// @param work   the work to run when confirmed
    /// @param failed the title of the dialog that reports a failure, or `null` to say nothing
    static void answerQuestion(MessageDialogPane pane, String wait,
                               Supplier<? extends CompletionStage<?>> work,
                               @Nullable String failed) {
        pane.setWorking(true, wait);
        CompletableFuture<?> running;
        try {
            running = work.get().toCompletableFuture();
        } catch (RuntimeException thrown) {
            reportFailure(pane, failed, thrown);
            return;
        }
        running.handle((ignored, throwable) -> throwable)
                .thenAcceptAsync(thrown -> {
                    if (thrown == null) {
                        pane.fireEvent(new DialogCloseEvent());
                    } else {
                        reportFailure(pane, failed, thrown);
                    }
                }, Schedulers.javafx())
                // The toolkit turns a dialog touched from the wrong thread into an exception in this
                // stage, and a stage nobody reads swallows it: the question then sits there for as
                // long as the launcher is open, saying it is still waiting for work that has already
                // finished. Read here, so that the next such mistake is one line in the log.
                .exceptionally(thrown -> {
                    LOG.error("Could not finish the dialog of a question whose work had ended", thrown);
                    return null;
                });
    }

    /// Says why a question could not be answered, and lets it be asked again.
    ///
    /// The reason is dug out of the wrapping rather than taken from its outside, because the work
    /// runs in a task: what a person needs is the sentence the layer that failed wrote, not the name
    /// of the layer that was carrying it.
    ///
    /// @param pane   the question's dialog
    /// @param title  the failure dialog's title, or `null` to say nothing
    /// @param thrown what went wrong, or `null`
    private static void reportFailure(MessageDialogPane pane, @Nullable String title, @Nullable Throwable thrown) {
        if (title == null) {
            return;
        }
        pane.setWorking(false, null);
        String reason = thrown == null ? "" : thrown.toString();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                reason = cause.getMessage();
                break;
            }
        }
        LOG.warning("A question was answered and the work failed: " + reason, thrown);
        dialog(reason, title, MessageType.ERROR);
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

    /// Returns the download page, creating it on first use.
    ///
    /// Kept here for the same reason the instance list is: more than one place
    /// opens it — the home page's own entry, and creating an instance, which the
    /// original routes through the version list rather than through a wizard
    /// step of its own.
    ///
    /// @return the download page
    public static org.jackhuang.hmcl.ui.dsh.DownloadPage getDownloadPage() {
        org.jackhuang.hmcl.ui.dsh.DownloadPage page = downloadPage;
        if (page == null) {
            page = new org.jackhuang.hmcl.ui.dsh.DownloadPage();
            downloadPage = page;
        }
        return page;
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
    /// HDSL has no in-application URI scheme yet, so every link is handed
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
