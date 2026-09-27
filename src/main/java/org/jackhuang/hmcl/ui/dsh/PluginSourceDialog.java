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
package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextField;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshGitPlugins;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.RequiredValidator;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.Validator;
import org.jackhuang.hmcl.util.FutureCallback;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Asks for the two things a plugin installed from a repository is named by.
///
/// An address alone is not enough to install from a repository: the same repository holds
/// many revisions, and a plugin somebody is trying out is as likely to be a branch as the
/// default branch. So both are asked for, and the **revision is the one that may be left
/// empty** — an empty revision is the repository's default branch, which is what somebody
/// who does not care about revisions means.
///
/// The form is the account dialog's, as it stands: a `GridPane` of two columns with the name of the
/// field in one and the box in the other, and the buttons in a row of their own under it. A row of
/// `LinePane`s was the other way of doing it and is what that dialog was complained about twice: it
/// puts the box on the right of a wide surface, so the fields do not line up with anything else, and
/// a row left over for a message keeps its height with nothing in it.
@NotNullByDefault
public final class PluginSourceDialog extends JFXDialogLayout implements DialogAware {
    /// What the two fields hold.
    ///
    /// @param repository the repository the plugin is in
    /// @param reference  the branch, tag or commit, or an empty string for the default branch
    public record Source(String repository, String reference) {
    }

    /// The value the dialog finishes with.
    private final CompletableFuture<Source> future = new CompletableFuture<>();

    /// The repository the plugin is in.
    private final JFXTextField repository = new JFXTextField();

    /// The revision to install, which may be left empty.
    private final JFXTextField reference = new JFXTextField();

    /// Says why a submission was refused, in the caller's words.
    private final Label warning = new Label();

    /// Covers the accept button while the caller decides.
    private final SpinnerPane acceptPane = new SpinnerPane();

    /// Accepts what was typed, once both fields are usable.
    private final JFXButton acceptButton = new JFXButton(i18n("button.ok"));

    /// Creates the dialog.
    ///
    /// @param onResult asked whether what was typed can be installed, and answers by resolving
    ///                 or by rejecting with a reason to show
    public PluginSourceDialog(FutureCallback<Source> onResult) {
        setHeading(new Label(i18n("dsh.instance.plugins.add.url")));

        // The account dialog's own arrangement for telling somebody a field is wrong: a validator on
        // the field plus validate-as-you-type, which paints the underline red and prints the message
        // under the box **as it is typed**. Which of the two may be left alone is then a fact about
        // the field rather than a line of prose above it — the one that is required says so when it
        // is empty, and the one that is not is never painted red for being empty.
        repository.setValidators(new RequiredValidator());
        FXUtils.setValidateWhileTextChanged(repository, true);
        acceptButton.disableProperty().bind(repository.activeValidatorProperty().isNotNull());

        // What a revision may not hold is the other half of that: a space, a '#', or a leading
        // hyphen are refused when the specification is built, and refusing them here means saying so
        // before a repository is cloned rather than after.
        reference.setValidators(new Validator(i18n("dsh.plugin.source.reference_unusable"),
                DshGitPlugins::isUsableReference));
        FXUtils.setValidateWhileTextChanged(reference, true);

        // A message only when there is one: a label that is merely invisible keeps its height, which
        // is the strip of nothing under a form that has nothing to say.
        warning.setWrapText(true);
        warning.visibleProperty().bind(warning.textProperty().isNotEmpty());
        warning.managedProperty().bind(warning.visibleProperty());

        setBody(new VBox(10, buildForm(), warning));

        acceptPane.getStyleClass().add("small-spinner-pane");
        acceptButton.getStyleClass().add("dialog-accept");
        acceptPane.setContent(acceptButton);

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(event -> fireEvent(new DialogCloseEvent()));

        HBox actions = new HBox(8, acceptPane, cancelButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        setActions(actions);

        acceptButton.setOnAction(event -> accept(onResult));
        repository.setOnAction(event -> reference.requestFocus());
        reference.setOnAction(event -> acceptButton.fire());
        onEscPressed(this, cancelButton::fire);
    }

    /// Builds the form.
    ///
    /// @return the form
    private GridPane buildForm() {
        GridPane grid = new GridPane();
        grid.setVgap(22);
        grid.setHgap(15);
        grid.setAlignment(Pos.CENTER);

        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(nameColumn, fieldColumn);

        // The boxes fill the column beside the names, rather than being given a width of their own
        // that the dialog then has to be wide enough for.
        repository.setMaxWidth(Double.MAX_VALUE);
        reference.setMaxWidth(Double.MAX_VALUE);

        grid.add(new Label(i18n("dsh.plugin.source.repository")), 0, 0);
        grid.add(repository, 1, 0);
        grid.add(new Label(i18n("dsh.plugin.source.reference")), 0, 1);
        grid.add(reference, 1, 1);
        return grid;
    }

    /// Hands what was typed to the caller, and finishes when it is accepted.
    ///
    /// @param onResult the caller's question
    private void accept(FutureCallback<Source> onResult) {
        Source source = new Source(repository.getText(), reference.getText());
        acceptPane.showSpinner();
        onResult.call(source, new FutureCallback.ResultHandler() {
            @Override
            public void resolve() {
                acceptPane.hideSpinner();
                future.complete(source);
                fireEvent(new DialogCloseEvent());
            }

            @Override
            public void reject(String reason) {
                acceptPane.hideSpinner();
                warning.setText(reason);
            }
        });
    }

    @Override
    public void onDialogShown() {
        repository.requestFocus();
    }

    /// The value the dialog finishes with.
    ///
    /// @return a future completed with what was accepted, or completed exceptionally on cancel
    public CompletableFuture<Source> getCompletableFuture() {
        return future;
    }
}
