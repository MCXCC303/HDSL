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
import com.mcxcc303.hdsl.plugin.DshPluginSources;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.util.FutureCallback;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Dialog prompting for a plugin repository source and revision.
 */
@NotNullByDefault
public final class PluginSourceDialog extends JFXDialogLayout implements DialogAware {

	private final CompletableFuture<Source> future = new CompletableFuture<>();
	private final JFXTextField repository = new JFXTextField();
	private final JFXTextField reference = new JFXTextField();
	private final Label warning = new Label();
	private final SpinnerPane acceptPane = new SpinnerPane();
	private final JFXButton acceptButton = new JFXButton(i18n("button.ok"));

	/**
	 * Creates the dialog.
	 *
	 * @param onResult callback to validate and handle the entered source
	 */
	public PluginSourceDialog(FutureCallback<Source> onResult) {
		setHeading(new Label(i18n("dsh.instance.plugins.add.url")));

		repository.setValidators(new RequiredValidator());
		reference.setValidators(new Validator(i18n("dsh.plugin.source.reference_unusable"),
		                                      DshPluginSources::isUsableReference));

		FXUtils.setValidateWhileTextChanged(repository, true);
		FXUtils.setValidateWhileTextChanged(reference, true);

		acceptButton.disableProperty()
		            .bind(repository.activeValidatorProperty()
		                            .isNotNull()
		                            .or(reference.activeValidatorProperty().isNotNull()));

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

		repository.setMaxWidth(Double.MAX_VALUE);
		reference.setMaxWidth(Double.MAX_VALUE);

		grid.add(new Label(i18n("dsh.plugin.source.repository")), 0, 0);
		grid.add(repository, 1, 0);
		grid.add(new Label(i18n("dsh.plugin.source.reference")), 0, 1);
		grid.add(reference, 1, 1);
		return grid;
	}

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

	/**
	 * Returns the future completed when a valid source is accepted.
	 *
	 * @return the future result
	 */
	public CompletableFuture<Source> getCompletableFuture() {
		return future;
	}

	/**
	 * Stored plugin repository source and reference.
	 *
	 * @param repository the repository the plugin is in
	 * @param reference  the branch, tag or commit, or an empty string for the default branch
	 */
	public record Source(String repository, String reference) {
	}
}

