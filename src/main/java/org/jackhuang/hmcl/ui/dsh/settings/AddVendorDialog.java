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
package org.jackhuang.hmcl.ui.dsh.settings;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextField;
import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshVendor;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.RequiredValidator;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.URLValidator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Dialog for adding a custom model provider by its URL.
 */
@NotNullByDefault
public final class AddVendorDialog extends JFXDialogLayout {
	private final JFXTextField addressField = new JFXTextField();
	private final JFXTextField nameField = new JFXTextField();
	private final Label warning = new Label();
	private final Label foundId = new Label();
	private final Label foundUrl = new Label();
	private final Label nameLabel = new Label(i18n("dsh.account.vendor.name"));
	private final VBox addressPane = new VBox(8);
	private final GridPane confirmPane = new GridPane();
	private final StackPane content = new StackPane();
	private final SpinnerPane nextPane = new SpinnerPane();
	private final JFXButton next = new JFXButton(i18n("wizard.next"));
	private final JFXButton previous = new JFXButton(i18n("wizard.prev"));
	private final JFXButton finish = new JFXButton(i18n("wizard.finish"));
	private final HBox actions = new HBox(8);
	private @Nullable DshVendor found;

	/**
	 * Creates the add vendor dialog.
	 */
	public AddVendorDialog() {
		setHeading(new Label(i18n("dsh.account.add.vendor")));
		buildAddressPane();
		buildConfirmPane();
		buildActions();
		setBody(content);
		setActions(actions);
		showAddressStep();
	}

	private static DshVendor withAddress(DshVendor vendor, String url) {
		return new DshVendor(vendor.id(),
		                     vendor.displayName(),
		                     vendor.apiKeyEnv(),
		                     vendor.api(),
		                     url,
		                     vendor.preferred());
	}

	private static boolean isRoutable(DshVendor vendor) {
		return DshVendor.APIS.contains(vendor.api());
	}

	private static @Nullable String normalized(@Nullable String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String url = text.trim().replaceAll("/+$", "");
		return DshVendor.hostOf(url) == null ? null : url;
	}

	private static String suggestId(String url) {
		String host = DshVendor.hostOf(url);
		if (host == null) {
			return "custom";
		}
		String[] labels = host.split("\\.");
		String candidate = labels.length > 1 && labels[0].equals("api") ? labels[1] : labels[0];
		candidate = candidate.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
		if (candidate.isEmpty() || !Character.isLetterOrDigit(candidate.charAt(0))) {
			candidate = "custom-" + candidate;
		}
		return candidate;
	}

	private void buildAddressPane() {
		addressField.setPromptText(i18n("dsh.account.base_url.prompt"));
		addressField.setOnAction(event -> look());
		addressField.getValidators().addAll(new RequiredValidator(), new URLValidator());
		FXUtils.setValidateWhileTextChanged(addressField, true);

		warning.setWrapText(true);
		warning.getStyleClass().add("dsh-vendor-warning");
		warning.setMinWidth(0);
		warning.setPrefWidth(0);
		warning.maxWidthProperty().bind(addressPane.widthProperty());
		warning.visibleProperty().bind(warning.textProperty().isNotEmpty());
		warning.managedProperty().bind(warning.visibleProperty());

		addressPane.getChildren().addAll(addressField, warning);
	}

	private void buildConfirmPane() {
		confirmPane.getStyleClass().add("dsh-vendor-confirm");
		confirmPane.setVgap(15);
		confirmPane.setHgap(15);
		confirmPane.setAlignment(Pos.CENTER_LEFT);

		ColumnConstraints names = new ColumnConstraints();
		names.setMinWidth(Region.USE_PREF_SIZE);
		confirmPane.getColumnConstraints().addAll(names, new ColumnConstraints());

		foundId.setWrapText(true);
		foundUrl.setWrapText(true);
		nameField.getValidators().add(new RequiredValidator());
		FXUtils.setValidateWhileTextChanged(nameField, true);

		confirmPane.add(new Label(i18n("dsh.account.vendor.id")), 0, 0);
		confirmPane.add(foundId, 1, 0);
		confirmPane.add(new Label(i18n("dsh.account.vendor.url")), 0, 1);
		confirmPane.add(foundUrl, 1, 1);
		confirmPane.add(nameLabel, 0, 2);
		confirmPane.add(nameField, 1, 2);
	}

	private void buildActions() {
		next.getStyleClass().add("dialog-accept");
		next.setOnAction(event -> look());
		next.disableProperty().bind(addressField.activeValidatorProperty().isNotNull());
		nextPane.getStyleClass().add("small-spinner-pane");
		nextPane.setContent(next);

		previous.getStyleClass().add("dialog-cancel");
		previous.setOnAction(event -> back());

		finish.getStyleClass().add("dialog-accept");
		finish.setOnAction(event -> keep());

		actions.setAlignment(Pos.CENTER_RIGHT);
	}

	private void showAddressStep() {
		found = null;
		warning.setText("");
		content.getChildren().setAll(addressPane);
		actions.getChildren().setAll(cancelButton(), nextPane);
		Platform.runLater(addressField::requestFocus);
	}

	private void showConfirmStep(DshVendor vendor, boolean askForName) {
		found = vendor;
		foundId.setText(vendor.id());
		foundUrl.setText(vendor.baseUrl() == null ? "" : vendor.baseUrl());
		nameField.setText(vendor.displayName());
		nameLabel.setVisible(askForName);
		nameLabel.setManaged(askForName);
		nameField.setVisible(askForName);
		nameField.setManaged(askForName);
		content.getChildren().setAll(confirmPane);
		actions.getChildren().setAll(previous, cancelButton(), finish);
		if (askForName) {
			Platform.runLater(nameField::requestFocus);
		}
	}

	private JFXButton cancelButton() {
		JFXButton cancel = new JFXButton(i18n("button.cancel"));
		cancel.getStyleClass().add("dialog-cancel");
		cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
		return cancel;
	}

	private void look() {
		if (next.isDisable()) {
			return;
		}
		warning.setText("");
		String url = normalized(addressField.getText());
		if (url == null) {
			warning.setText(i18n("dsh.account.vendor.not_a_provider"));
			return;
		}

		DshVendor known = DshVendor.byBaseUrl(url);
		if (known != null) {
			if (!isRoutable(known)) {
				warning.setText(i18n("dsh.account.vendor.unroutable", known.id()));
				return;
			}
			showConfirmStep(withAddress(known, url), false);
			return;
		}

		nextPane.showSpinner();
		content.setDisable(true);
		CompletableFuture.supplyAsync(() -> DshAccount.ask(url), Schedulers.io())
		                 .whenComplete((answer, failure) -> Platform.runLater(() -> {
			                 nextPane.hideSpinner();
			                 content.setDisable(false);
			                 if (getScene() == null) {
				                 return;
			                 }
			                 if (failure != null || answer == null || !answer.listsModels()) {
				                 warning.setText(i18n("dsh.account.vendor.not_a_provider"));
				                 return;
			                 }
			                 showConfirmStep(DshVendor.discovered(suggestId(url), "", url), true);
		                 }));
	}

	private void back() {
		showAddressStep();
	}

	private void keep() {
		DshVendor vendor = found;
		if (vendor == null) {
			return;
		}
		if (nameField.isVisible() && !nameField.validate()) {
			return;
		}
		DshVendor chosen = nameField.isVisible() ? new DshVendor(vendor.id(),
		                                                         nameField.getText().trim(),
		                                                         vendor.apiKeyEnv(),
		                                                         vendor.api(),
		                                                         vendor.baseUrl(),
		                                                         false) : vendor;
		SettingsManager.settings().getCustomVendors().removeIf(existing -> existing.id().equalsIgnoreCase(chosen.id()));
		SettingsManager.settings().getCustomVendors().add(chosen);
		SettingsManager.save();
		fireEvent(new DialogCloseEvent());
	}
}
