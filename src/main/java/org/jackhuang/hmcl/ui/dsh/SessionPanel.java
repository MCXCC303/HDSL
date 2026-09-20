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
package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshAcpClient;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// A chat panel that talks to an instance over the Agent Client Protocol.
///
/// This is what makes HMCL-DSH more than a launcher: instead of opening a
/// browser tab, the panel drives `dsh --profile acp` directly and renders the
/// streamed assistant text.
///
/// The connection is opened lazily, on the first prompt, so merely looking at an
/// instance does not start a child process or touch its home.
@NotNullByDefault
public final class SessionPanel extends BorderPane {
    /// The instance this panel talks to.
    private final DshInstance instance;

    /// The transcript, one child per message.
    private final VBox transcript = new VBox(8);

    /// The scroll container around the transcript.
    private final ScrollPane transcriptScroll;

    /// The prompt entry field.
    private final TextArea input = new TextArea();

    /// The send button.
    private final JFXButton send = new JFXButton(i18n("dsh.session.send"));

    /// The status line.
    private final Label status = new Label();

    /// The live connection, or `null` before the first prompt.
    private volatile @Nullable DshAcpClient client;

    /// The session id, or `null` before the connection is established.
    private volatile @Nullable String sessionId;

    /// The label currently receiving streamed assistant text.
    private volatile @Nullable Label streaming;

    /// Creates the panel for an instance.
    ///
    /// @param instance the instance to talk to
    public SessionPanel(DshInstance instance) {
        this.instance = instance;

        setPadding(new Insets(10));

        transcript.setPadding(new Insets(4));
        transcriptScroll = new ScrollPane(transcript);
        transcriptScroll.setFitToWidth(true);
        transcriptScroll.getStyleClass().add("edge-to-edge");

        setTop(status);
        setCenter(transcriptScroll);
        setBottom(buildComposer());

        status.setText(i18n("dsh.session.idle"));
    }

    /// Builds the prompt composer.
    ///
    /// @return the composer row
    private HBox buildComposer() {
        input.setPromptText(i18n("dsh.session.placeholder"));
        input.setPrefRowCount(2);
        input.setWrapText(true);
        HBox.setHgrow(input, Priority.ALWAYS);

        send.setOnAction(event -> onSend());

        HBox box = new HBox(8, input, send);
        box.setAlignment(Pos.BOTTOM_RIGHT);
        box.setPadding(new Insets(8, 0, 0, 0));
        return box;
    }

    /// Sends the current input.
    private void onSend() {
        String text = input.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        input.clear();

        appendMessage(i18n("dsh.session.you"), text);
        streaming = null;
        send.setDisable(true);
        status.setText(i18n("dsh.session.thinking"));

        CompletableFuture.runAsync(() -> {
            try {
                DshAcpClient connection = ensureConnected();
                String session = sessionId;
                if (session == null) {
                    throw new DshException("The ACP session was not created");
                }
                connection.prompt(session, text);
            } catch (DshException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((ignored, throwable) -> runInFX(() -> {
            send.setDisable(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                LOG.warning("ACP prompt failed", cause);
                appendMessage(i18n("dsh.session.error"), String.valueOf(cause.getMessage()));
                status.setText(i18n("dsh.session.failed"));
            } else {
                status.setText(i18n("dsh.session.ready"));
            }
            streaming = null;
        }));
    }

    /// Returns the live connection, opening one when needed.
    ///
    /// @return the connection
    /// @throws DshException when the connection cannot be established
    private synchronized DshAcpClient ensureConnected() throws DshException {
        DshAcpClient existing = client;
        if (existing != null && existing.isRunning()) {
            return existing;
        }

        DshAcpClient connection = DshAcpClient.connect(instance, new DshAcpClient.Listener() {
            @Override
            public void onText(String text) {
                Platform.runLater(() -> appendStreaming(text));
            }

            @Override
            public void onToolCall(String title, String statusText) {
                Platform.runLater(() -> appendMessage(i18n("dsh.session.tool"),
                        title + (statusText.isEmpty() ? "" : " (" + statusText + ")")));
            }

            @Override
            public void onFailure(String message) {
                Platform.runLater(() -> {
                    status.setText(i18n("dsh.session.disconnected"));
                    appendMessage(i18n("dsh.session.error"), message);
                });
            }
        });

        sessionId = connection.newSession();
        client = connection;
        return connection;
    }

    /// Appends streamed assistant text to the current bubble.
    ///
    /// @param text the chunk
    private void appendStreaming(String text) {
        Label current = streaming;
        if (current == null) {
            current = appendMessage(i18n("dsh.session.agent"), text);
            streaming = current;
        } else {
            current.setText(current.getText() + text);
        }
        transcriptScroll.setVvalue(1.0);
    }

    /// Appends one message to the transcript.
    ///
    /// @param author the speaker label
    /// @param text   the message body
    /// @return the label holding the body, so it can be extended
    private Label appendMessage(String author, String text) {
        LineTextPane header = new LineTextPane();
        header.setTitle(author);
        header.getStyleClass().add("section-header");

        Label body = new Label(text);
        body.setWrapText(true);
        body.setPadding(new Insets(0, 10, 6, 10));

        VBox bubble = new VBox(header, body);
        transcript.getChildren().add(bubble);

        if (transcript.getChildren().size() > 200) {
            transcript.getChildren().remove(0);
        }
        transcriptScroll.setVvalue(1.0);
        return body;
    }

    /// Closes the connection, if one is open.
    public void dispose() {
        DshAcpClient connection = client;
        client = null;
        sessionId = null;
        if (connection != null) {
            connection.detachListener();
            connection.close();
        }
    }
}
