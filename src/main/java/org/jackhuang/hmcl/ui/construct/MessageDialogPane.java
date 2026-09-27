/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.jfoenix.controls.JFXButton;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public final class MessageDialogPane extends HBox {

    public enum MessageType {
        ERROR(SVG.ERROR),
        INFO(SVG.INFO),
        WARNING(SVG.WARNING),
        QUESTION(SVG.HELP),
        SUCCESS(SVG.CHECK_CIRCLE);

        private final SVG icon;

        MessageType(SVG icon) {
            this.icon = icon;
        }

        public SVG getIcon() {
            return icon;
        }

        public String getDisplayName() {
            return i18n("message." + name().toLowerCase(Locale.ROOT));
        }
    }

    private final HBox actions;

    /// What the action row held before the dialog began waiting, so it can be put back.
    private final java.util.List<Node> originalActions = new java.util.ArrayList<>();

    /// The spinner shown in place of the buttons while the dialog waits.
    private final SpinnerPane workingSpinner = new SpinnerPane();

    private @Nullable ButtonBase cancelButton;

    /// Whether the dialog is waiting for work its buttons asked for.
    ///
    /// A button that starts work used to close the dialog as soon as it was pressed, which is exactly
    /// when the work is most likely to be pressed again: the window is still there, the row is still
    /// there, and nothing has visibly happened yet. While this is set no button closes the dialog,
    /// so the dialog outlives the work that was asked for and the row it belongs to cannot be
    /// answered twice.
    private boolean working;

    public MessageDialogPane(@NotNull String text, @Nullable String title, @NotNull MessageType type) {
        this.setSpacing(16);
        this.getStyleClass().add("jfx-dialog-layout");

        Label graphic = new Label();
        graphic.setTranslateX(10);
        graphic.setTranslateY(10);
        graphic.setMinSize(40, 40);
        graphic.setMaxSize(40, 40);
        graphic.setGraphic(type.getIcon().createIcon(40));

        VBox vbox = new VBox();
        HBox.setHgrow(vbox, Priority.ALWAYS);
        {
            StackPane titlePane = new StackPane();
            titlePane.getStyleClass().addAll("jfx-layout-heading", "title");
            Label titleLabel = new Label(title != null ? title : type.getDisplayName());
            titlePane.getChildren().setAll(titleLabel);

            StackPane content = new StackPane();
            content.getStyleClass().add("jfx-layout-body");
            EnhancedTextFlow textFlow = new EnhancedTextFlow(text);
            textFlow.setStyle("-fx-font-size: 14px;");
            if (textFlow.computePrefHeight(400.0) <= 350.0)
                content.getChildren().setAll(textFlow);
            else {
                ScrollPane scrollPane = new ScrollPane(textFlow);
                FXUtils.smoothScrolling(scrollPane);
                scrollPane.setPrefHeight(350);
                VBox.setVgrow(scrollPane, Priority.ALWAYS);
                scrollPane.setFitToWidth(true);
                content.getChildren().setAll(scrollPane);
            }

            actions = new HBox();
            actions.getStyleClass().add("jfx-layout-actions");

            vbox.getChildren().setAll(titlePane, content, actions);
        }

        this.getChildren().setAll(graphic, vbox);

        workingSpinner.getStyleClass().add("small-spinner-pane");

        onEscPressed(this, () -> {
            if (cancelButton != null && !working) {
                cancelButton.fire();
            }
        });
    }

    /// Adds a button whose press closes the dialog.
    ///
    /// @param btn the button
    public void addButton(Node btn) {
        addButton(btn, true);
    }

    /// Adds a button, saying whether its press closes the dialog.
    ///
    /// The closing handler goes on the button as soon as it is added, so it runs **before** anything
    /// a caller attaches to the same button afterwards: whoever adds a button gets the first word on
    /// what pressing it means. A caller whose press starts work that takes seconds therefore cannot
    /// hold the dialog open by setting [#setWorking] from its own handler — the close has already
    /// been fired by then, which is exactly the defect this parameter exists to prevent. Such a
    /// caller declares the press its own business instead, and closes the dialog itself when the
    /// work has finished.
    ///
    /// @param btn    the button
    /// @param closes whether pressing it closes the dialog
    public void addButton(Node btn, boolean closes) {
        if (closes) {
            btn.addEventHandler(ActionEvent.ACTION, e -> {
                if (!working) {
                    fireEvent(new DialogCloseEvent());
                }
            });
        }
        actions.getChildren().add(btn);
    }

    /// What pressing a question's 是 does.
    ///
    /// The dialog is handed over rather than held by the caller, because a handler on a button is
    /// built before the dialog that button is in exists.
    @FunctionalInterface
    public interface Answer {
        /// Answers the question.
        ///
        /// @param dialog the dialog the button was pressed in
        void pressed(MessageDialogPane dialog);
    }

    /// Returns the row the dialog's buttons are in.
    ///
    /// Handed out so that a caller asking a question whose answer takes time can watch for the
    /// answer itself: the buttons a builder makes close the dialog, and a question that must outlive
    /// its own answer needs the press rather than the close.
    ///
    /// @return the action row, whose children are the buttons in the order they were added
    public HBox getActions() {
        return actions;
    }

    /// Returns whether the dialog is waiting for work.
    ///
    /// @return whether the dialog is holding itself open
    public boolean isWorking() {
        return working;
    }

    /// Puts the dialog into, or out of, the state of waiting for work.
    ///
    /// The button row is replaced while the work runs by what it is waiting for and a spinner, and
    /// nothing else about the dialog changes. That is what makes the press safe to repeat and the
    /// dialog safe to leave: what is not on the screen is not something a person can press twice,
    /// while the window itself stays where it is until there is an answer — which is the point,
    /// because the answer is what the row behind it is waiting for.
    ///
    /// Both buttons come back together when the work ends without having done what it was asked for:
    /// a question that can never be answered again is worse than one that was answered twice.
    ///
    /// @param working whether the dialog is waiting for work
    /// @param reason  what it is waiting for, shown in place of the buttons, or `null` to keep it
    public void setWorking(boolean working, @Nullable String reason) {
        this.working = working;
        if (working) {
            replaceActions();
            Label waiting = new Label(reason != null ? reason : i18n("message.working"));
            waiting.setPadding(new Insets(0, 5, 0, 0));
            actions.getChildren().setAll(waiting, workingSpinner);
        } else {
            actions.getChildren().setAll(originalActions);
        }
    }

    /// Remembers what the action row holds, once.
    private void replaceActions() {
        if (originalActions.isEmpty()) {
            originalActions.addAll(actions.getChildren());
        }
    }

    public void setCancelButton(@Nullable ButtonBase btn) {
        cancelButton = btn;
    }

    public ButtonBase getCancelButton() {
        return cancelButton;
    }

    private static final class EnhancedTextFlow extends TextFlow {
        EnhancedTextFlow(String text) {
            this.getChildren().setAll(FXUtils.parseSegment(text, Controllers::onHyperlinkAction));
        }

        @Override
        public double computePrefHeight(double width) {
            return super.computePrefHeight(width);
        }
    }

    public static class Builder {
        private final MessageDialogPane dialog;

        public Builder(String text, String title, MessageType type) {
            this.dialog = new MessageDialogPane(text, title, type);
        }

        public Builder addHyperLink(String text, String externalLink) {
            JFXHyperlink link = new JFXHyperlink(text);
            link.setExternalLink(externalLink);
            dialog.actions.getChildren().add(link);
            return this;
        }

        public Builder addAction(Node actionNode) {
            dialog.addButton(actionNode);
            actionNode.getStyleClass().add("dialog-accept");
            return this;
        }

        public Builder addAction(String text, @Nullable Runnable action) {
            JFXButton btnAction = new JFXButton(text);
            btnAction.getStyleClass().add("dialog-accept");
            if (action != null) {
                btnAction.setOnAction(e -> action.run());
            }
            dialog.addButton(btnAction);
            return this;
        }

        public Builder ok(@Nullable Runnable ok) {
            JFXButton btnOk = new JFXButton(i18n("button.ok"));
            btnOk.getStyleClass().add("dialog-accept");
            if (ok != null) {
                btnOk.setOnAction(e -> ok.run());
            }
            dialog.addButton(btnOk);
            dialog.setCancelButton(btnOk);
            return this;
        }

        public Builder addCancel(@Nullable Runnable cancel) {
            return addCancel(i18n("button.cancel"), cancel);
        }

        public Builder addCancel(String cancelText, @Nullable Runnable cancel) {
            JFXButton btnCancel = new JFXButton(cancelText);
            btnCancel.setButtonType(JFXButton.ButtonType.FLAT);
            btnCancel.getStyleClass().add("dialog-cancel");
            if (cancel != null) {
                btnCancel.setOnAction(e -> cancel.run());
            }
            dialog.addButton(btnCancel);
            dialog.setCancelButton(btnCancel);
            return this;
        }

        public Builder yesOrNo(@Nullable Runnable yes, @Nullable Runnable no) {
            JFXButton btnYes = new JFXButton(i18n("button.yes"));
            btnYes.getStyleClass().add("dialog-accept");
            if (yes != null) {
                btnYes.setOnAction(e -> yes.run());
            }
            dialog.addButton(btnYes);

            addCancel(i18n("button.no"), no);
            return this;
        }

        /// A 是 button whose answer is the caller's business.
        ///
        /// Pressing it does not close the dialog, so a question whose answer takes time stays on the
        /// screen until the answer has arrived — see [MessageDialogPane#addButton(Node, boolean)] for
        /// why a caller cannot arrange that from its own handler. The caller ends the dialog itself:
        /// by firing a [DialogCloseEvent] when the work it started has finished, or by putting the
        /// buttons back with [MessageDialogPane#setWorking] when it failed.
        ///
        /// 否 closes as usual: refusing is not work, and a question nobody can decline is worse than
        /// one answered twice.
        ///
        /// @param yes what pressing 是 does, given the dialog it was pressed in
        /// @param no  what pressing 否 does, or `null`
        public Builder askYesOrNo(Answer yes, @Nullable Runnable no) {
            JFXButton btnYes = new JFXButton(i18n("button.yes"));
            btnYes.getStyleClass().add("dialog-accept");
            btnYes.setOnAction(e -> yes.pressed(dialog));
            dialog.addButton(btnYes, false);

            addCancel(i18n("button.no"), no);
            return this;
        }

        public Builder actionOrCancel(ButtonBase actionButton, Runnable cancel) {
            dialog.addButton(actionButton);

            addCancel(cancel);
            return this;
        }

        public MessageDialogPane build() {
            return dialog;
        }
    }
}
