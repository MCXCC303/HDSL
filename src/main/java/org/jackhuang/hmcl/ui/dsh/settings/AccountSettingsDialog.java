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

import com.jfoenix.controls.*;
import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshVendor;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Dialog for adding and managing user accounts.
 */
@NotNullByDefault
public final class AccountSettingsDialog extends JFXDialogLayout {
	private final @Nullable DshVendor preselected;
	private final DshAccount.AccountKind kind;
	private final boolean offline;
	private final JFXComboBox<DshVendor> vendorBox = new JFXComboBox<>();
	private final JFXTextField usernameField = new JFXTextField();
	private final JFXPasswordField keyField = new JFXPasswordField();
	private final JFXTextField baseUrlField = new JFXTextField();
	private final Label verdict = new Label();
	private final SpinnerPane acceptPane = new SpinnerPane();
	private GridPane form;
	private int endpointRow = -1;

	/**
	 * Creates the dialog for adding an account.
	 *
	 * @param preselected the vendor to add, or {@code null} to prompt the user
	 */
	public AccountSettingsDialog(@Nullable DshVendor preselected) {
		this(preselected,
		     preselected != null && preselected.preferred()
		     ? DshAccount.AccountKind.OFFICIAL
		     : DshAccount.AccountKind.THIRD_PARTY,
		     false);
	}

	private AccountSettingsDialog(@Nullable DshVendor preselected, DshAccount.AccountKind kind, boolean offline) {
		this.preselected = preselected;
		this.kind = kind;
		this.offline = offline;

		setHeading(new Label(offline
		                     ? i18n("account.create.offline")
		                     : preselected == null ? i18n("dsh.account.add.custom")
		                                           : i18n("dsh.account.add.named", preselected.displayName())));

		VBox body = new VBox(10, buildForm());
		setBody(body);
		setActions(buildActions());

		Platform.runLater(this::syncEndpointRow);
	}

	/**
	 * Creates a dialog for adding an offline account.
	 *
	 * @return the dialog instance
	 */
	public static AccountSettingsDialog offline() {
		return new AccountSettingsDialog(null, DshAccount.AccountKind.OFFLINE, true);
	}

	private static boolean isNameTaken(String name) {
		for (DshAccount account : SettingsManager.settings().getAccounts()) {
			if (name.equals(account.displayName())) {
				return true;
			}
		}
		return false;
	}

	private Node buildForm() {
		GridPane grid = new GridPane();
		grid.setVgap(22);
		grid.setHgap(15);
		grid.setAlignment(Pos.CENTER);

		ColumnConstraints nameColumn = new ColumnConstraints();
		nameColumn.setMinWidth(Region.USE_PREF_SIZE);
		grid.getColumnConstraints().add(nameColumn);
		ColumnConstraints fieldColumn = new ColumnConstraints();
		fieldColumn.setHgrow(Priority.ALWAYS);
		grid.getColumnConstraints().add(fieldColumn);

		int row = 0;

		if (preselected == null && !offline) {
			vendorBox.getItems().setAll(DshVendor.offered());
			vendorBox.setConverter(FXUtils.stringConverter(DshVendor::label));
			vendorBox.getSelectionModel().selectFirst();
			vendorBox.setMaxWidth(Double.MAX_VALUE);
			vendorBox.valueProperty().addListener(observable -> syncEndpointRow());
			grid.add(new Label(i18n("dsh.account.vendor")), 0, row);
			grid.add(vendorBox, 1, row);
			row++;
		}

		usernameField.setPromptText(i18n(offline
		                                 ? "account.methods.offline.name.special_characters"
		                                 : "dsh.account.label.prompt"));
		keyField.setPromptText(i18n("dsh.account.key.prompt"));
		baseUrlField.setPromptText(i18n("dsh.account.base_url.prompt"));

		grid.add(new Label(offline ? i18n("account.character") : i18n("account.username")), 0, row);
		grid.add(usernameField, 1, row);
		row++;

		usernameField.setValidators(new RequiredValidator());
		if (!offline) {
			usernameField.getValidators().add(new Validator(i18n("dsh.account.name_unusable"),
			                                                name -> name == null || name.isBlank() ||
			                                                        DshAccount.isUsableName(name.trim())));
		}
		usernameField.getValidators()
		             .add(new Validator(i18n("dsh.account.name_taken"),
		                                name -> name == null || name.isBlank() || !isNameTaken(name.trim())));

		usernameField.getValidators()
		             .add(new Validator(i18n("dsh.account.name_reserved_deepseek"),
		                                name -> !DshVendor.answersTo(name,
		                                                             vendor -> "deepseek".equalsIgnoreCase(vendor.id()))));
		usernameField.getValidators()
		             .add(new Validator(i18n("dsh.account.name_reserved_openai"),
		                                name -> !DshVendor.answersTo(name,
		                                                             vendor -> vendor.id()
		                                                                             .toLowerCase(Locale.ROOT)
		                                                                             .contains("openai"))));
		usernameField.getValidators()
		             .add(new Validator(i18n("dsh.account.name_reserved_other"),
		                                name -> !DshVendor.answersTo(name, vendor -> {
			                                String id = vendor.id().toLowerCase(Locale.ROOT);
			                                return !id.contains("openai") && !id.equals("deepseek");
		                                })));
		FXUtils.setValidateWhileTextChanged(usernameField, true);

		if (!offline) {
			keyField.setValidators(new RequiredValidator());
			FXUtils.setValidateWhileTextChanged(keyField, true);

			grid.add(new Label(i18n("dsh.account.key")), 0, row);
			grid.add(keyField, 1, row);
			row++;

			baseUrlField.setValidators(new URLValidator(true));
			FXUtils.setValidateWhileTextChanged(baseUrlField, true);

			grid.add(new Label(i18n("dsh.account.base_url")), 0, row);
			grid.add(baseUrlField, 1, row);
			endpointRow = row;
			row++;
		}

		verdict.getStyleClass().add("desc");
		verdict.setWrapText(true);
		verdict.managedProperty().bind(verdict.textProperty().isNotEmpty());
		verdict.visibleProperty().bind(verdict.textProperty().isNotEmpty());
		grid.add(verdict, 0, row, 2, 1);

		form = grid;
		return grid;
	}

	private HBox buildActions() {
		JFXButton accept = new JFXButton(i18n("dsh.account.add.button"));
		accept.getStyleClass().add("dialog-accept");
		accept.setOnAction(event -> addAccount());
		acceptPane.getStyleClass().add("small-spinner-pane");
		acceptPane.setContent(accept);

		JFXButton cancel = new JFXButton(i18n("button.cancel"));
		cancel.getStyleClass().add("dialog-cancel");
		cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));

		HBox actions = new HBox(8, acceptPane, cancel);
		actions.setAlignment(Pos.CENTER_RIGHT);
		return actions;
	}

	private void syncEndpointRow() {
		if (form == null || endpointRow < 0) {
			return;
		}
		DshVendor vendor = preselected != null ? preselected : vendorBox.getValue();
		boolean needed = vendor == null || !vendor.hasBaseUrl();
		for (Node node : form.getChildren()) {
			if (GridPane.getRowIndex(node) != null && GridPane.getRowIndex(node) == endpointRow) {
				node.setVisible(needed);
				node.setManaged(needed);
			}
		}
	}

	private void addAccount() {
		if (!usernameField.validate()) {
			return;
		}
		String username = usernameField.getText() == null ? "" : usernameField.getText().trim();

		if (offline) {
			keep(DshAccount.offline(username));
			return;
		}

		if (!keyField.validate()) {
			return;
		}

		DshVendor vendor = preselected != null ? preselected : vendorBox.getValue();
		if (vendor == null) {
			verdict.setText(i18n("dsh.account.need_vendor_and_key"));
			return;
		}
		String key = keyField.getText() == null ? "" : keyField.getText().trim();
		if (!vendor.looksLikeItsKey(key)) {
			verdict.setText(i18n("dsh.account.key_looks_wrong", vendor.displayName()));
			return;
		}

		String baseUrl =
				baseUrlField.isVisible() && baseUrlField.getText() != null ? baseUrlField.getText().trim() : "";
		keep(new DshAccount(kind, vendor.id(), key, baseUrl.isEmpty() ? null : baseUrl, username, null, null));
	}

	private void keep(DshAccount account) {
		SettingsManager.settings().getAccounts().add(account);
		SettingsManager.settings().activeAccountKeyProperty().set(account.key());
		SettingsManager.save();
		fireEvent(new DialogCloseEvent());
	}
}
