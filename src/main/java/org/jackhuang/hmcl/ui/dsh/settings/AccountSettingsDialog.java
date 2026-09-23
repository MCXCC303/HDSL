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
package org.jackhuang.hmcl.ui.dsh.settings;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXTextField;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.dsh.DshVendor;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Adds an account, and manages the ones already there.
///
/// A dialog, and a `JFXDialogLayout` rather than a bare pane. The second part is not decoration:
/// the launcher attaches a dialog's close handler to **the node it is handed**, so a pane that
/// fires the close event from a child of itself fires it past the handler and the dialog never
/// closes. Extending the layout the launcher already knows, and firing on `this`, is what makes the
/// buttons work.
///
/// Which vendor is being added is the caller's to say. The accounts page asks by offering one row
/// per vendor, so a dialog opened from there needs no vendor question; the "another vendor" row
/// opens the same dialog without an answer, and then it asks.
///
/// A key is checked before it is kept, against the vendor itself rather than by asking the harness:
/// the harness answers out of a local catalogue for a vendor it knows and never makes the request,
/// so asking it would accept any string. A key that cannot be checked — the network cannot reach the
/// vendor, the endpoint does not offer a model list — is **not** refused: a network that cannot
/// reach a supplier today may reach it tomorrow, and the refusal that matters is the vendor's.
@NotNullByDefault
public final class AccountSettingsDialog extends JFXDialogLayout {
    /// The vendor being added, or `null` when the dialog has to ask.
    private final @Nullable DshVendor preselected;

    /// The accounts already kept, redrawn whenever one is added or removed.
    private final ComponentList accounts = new ComponentList();

    /// The vendor, when it has to be asked for.
    private final JFXComboBox<DshVendor> vendorBox = new JFXComboBox<>();

    /// What the account is called.
    private final JFXTextField usernameField = new JFXTextField();

    /// The key.
    private final JFXPasswordField keyField = new JFXPasswordField();

    /// The endpoint, for a vendor whose address is per account.
    private final JFXTextField baseUrlField = new JFXTextField();

    /// The model the harness should start with.
    private final JFXTextField modelField = new JFXTextField();

    /// The row holding the endpoint, kept so it can be taken away.
    private final LinePane baseUrlRow = new LinePane();

    /// Where the verdict on a key is shown.
    private final Label verdict = new Label();

    /// The button that accepts, and the spinner shown while a key is being checked.
    private final SpinnerPane acceptPane = new SpinnerPane();

    /// Creates the dialog.
    ///
    /// @param preselected the vendor to add, or `null` to ask
    public AccountSettingsDialog(@Nullable DshVendor preselected) {
        this.preselected = preselected;

        setHeading(new Label(i18n(preselected == null
                ? "dsh.account.add.custom" : "account.create")));

        VBox body = new VBox(10, accounts, buildForm());
        setBody(body);
        setActions(buildActions());
        refreshAccounts();

        // After the body is assembled, so that hiding the endpoint row takes it out of the layout
        // rather than leaving a gap where it was.
        javafx.application.Platform.runLater(this::syncEndpointRow);
    }

    /// Builds the form for the new account.
    ///
    /// @return the form
    private VBox buildForm() {
        ComponentList list = new ComponentList();

        // The vendor is only asked for when the caller did not say. The accounts page asks by
        // offering a row per vendor, so asking again inside the dialog would be asking a question
        // that has just been answered.
        if (preselected == null) {
            vendorBox.getItems().setAll(DshVendor.offered());
            vendorBox.setConverter(FXUtils.stringConverter(DshVendor::label));
            vendorBox.getSelectionModel().selectFirst();
            vendorBox.setMaxWidth(Double.MAX_VALUE);
            vendorBox.valueProperty().addListener(observable -> syncEndpointRow());
            list.getContent().add(row(i18n("dsh.account.vendor"), vendorBox));
        }

        usernameField.setPromptText(i18n("dsh.account.label.prompt"));
        keyField.setPromptText(i18n("dsh.account.key.prompt"));
        baseUrlField.setPromptText(i18n("dsh.account.base_url.prompt"));
        modelField.setPromptText(i18n("dsh.account.model.prompt"));

        list.getContent().add(row(i18n("account.username"), usernameField));
        list.getContent().add(row(i18n("dsh.account.key"), keyField));

        baseUrlRow.setTitle(i18n("dsh.account.base_url"));
        baseUrlField.setMinWidth(360);
        baseUrlRow.setRight(baseUrlField);
        list.getContent().add(baseUrlRow);

        list.getContent().add(row(i18n("dsh.account.model"), modelField));

        verdict.getStyleClass().add("desc");
        list.getContent().add(verdict);
        return new VBox(ComponentList.createComponentListTitle(i18n("dsh.account.add")), list);
    }

    /// Builds the buttons.
    ///
    /// @return the actions
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
        actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        return actions;
    }

    /// Builds a row of a name and its box.
    ///
    /// @param title the name
    /// @param field the box
    /// @return the row
    private LinePane row(String title, javafx.scene.Node field) {
        LinePane pane = new LinePane();
        pane.setTitle(title);
        if (field instanceof Region region) {
            region.setMinWidth(360);
        }
        pane.setRight(field);
        return pane;
    }

    /// Takes the endpoint row away for a vendor that publishes its own address.
    private void syncEndpointRow() {
        DshVendor vendor = preselected != null ? preselected : vendorBox.getValue();
        boolean needed = vendor == null || !vendor.hasBaseUrl();
        baseUrlRow.setVisible(needed);
        baseUrlRow.setManaged(needed);
    }

    /// Redraws the list of accounts.
    private void refreshAccounts() {
        List<javafx.scene.Node> rows = new ArrayList<>();
        for (DshAccount account : SettingsManager.settings().getAccounts()) {
            rows.add(accountRow(account));
        }
        if (rows.isEmpty()) {
            LineTextPane empty = new LineTextPane();
            empty.setTitle(i18n("dsh.account.none"));
            empty.setSubtitle(i18n("dsh.account.none.hint"));
            rows.add(empty);
        }
        accounts.getContent().setAll(rows);
    }

    /// Builds the row for one account.
    ///
    /// The key is shown masked: the row is a record of what is configured, not a place to read a
    /// secret back from, and a full key on screen is a key in every screenshot.
    ///
    /// @param account the account
    /// @return the row
    private javafx.scene.Node accountRow(DshAccount account) {
        LineTextPane row = new LineTextPane();
        row.setTitle(account.displayName());
        row.setSubtitle(account.vendorId() + " · " + account.maskedKey()
                + (account.modelOrDefault().isEmpty() ? "" : " · " + account.modelOrDefault()));

        JFXButton check = FXUtils.newToggleButton4(SVG.CHECK_CIRCLE);
        FXUtils.installFastTooltip(check, i18n("dsh.account.check"));
        check.setOnAction(event -> {
            row.setSubtitle(i18n("dsh.account.checking"));
            CompletableFuture.supplyAsync(account::check, Schedulers.io())
                    .whenComplete((result, failure) -> javafx.application.Platform.runLater(() ->
                            row.setSubtitle(failure != null
                                    ? failure.getMessage() : result.message())));
        });

        JFXButton changeKey = FXUtils.newToggleButton4(SVG.EDIT);
        FXUtils.installFastTooltip(changeKey, i18n("dsh.account.change_key"));
        changeKey.setOnAction(event -> changeKey(account));

        JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE_FOREVER);
        FXUtils.installFastTooltip(remove, i18n("button.remove"));
        remove.setOnAction(event -> {
            SettingsManager.settings().getAccounts().removeIf(a -> a.matchesKey(account.key()));
            SettingsManager.save();
            refreshAccounts();
        });

        HBox actions = new HBox(4, check, changeKey, remove);
        actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        row.setRowTrailing(actions);
        return row;
    }

    /// Replaces an account's key, keeping everything else about it.
    ///
    /// What changes is the key and nothing else: the vendor, the name and the model are the
    /// account's identity, and a key is the one part of it that expires. An account whose vendor is
    /// no longer offered can still have its key changed, because nothing here consults the vendor
    /// list.
    ///
    /// @param account the account
    private void changeKey(DshAccount account) {
        org.jackhuang.hmcl.ui.construct.InputDialogPane pane =
                new org.jackhuang.hmcl.ui.construct.InputDialogPane(
                        i18n("dsh.account.change_key"), "", (key, handler) -> {
                            // The pane refuses an empty answer itself, so what arrives here is a key.
                            String trimmed = key == null ? "" : key.trim();
                            java.util.List<DshAccount> accounts =
                                    SettingsManager.settings().getAccounts();
                            for (int i = 0; i < accounts.size(); i++) {
                                if (accounts.get(i).matchesKey(account.key())) {
                                    accounts.set(i, new DshAccount(account.vendorId(), trimmed,
                                            account.baseUrl(), account.label(), account.model()));
                                    break;
                                }
                            }
                            SettingsManager.save();
                            refreshAccounts();
                            handler.resolve();
                        });
        org.jackhuang.hmcl.ui.Controllers.dialog(pane);
    }

    /// Checks what was typed and, if it is not refused, keeps it.
    ///
    /// The check runs off the interface thread: it is a network call, and a dialog that stops
    /// responding while a supplier is asked a question is a dialog that looks broken.
    private void addAccount() {
        DshVendor vendor = preselected != null ? preselected : vendorBox.getValue();
        String key = keyField.getText() == null ? "" : keyField.getText().trim();
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        if (vendor == null || key.isEmpty()) {
            verdict.setText(i18n("dsh.account.need_vendor_and_key"));
            return;
        }
        if (!vendor.looksLikeItsKey(key)) {
            verdict.setText(i18n("dsh.account.key_looks_wrong", vendor.displayName()));
            return;
        }

        String baseUrl = baseUrlRow.isVisible() && baseUrlField.getText() != null
                ? baseUrlField.getText().trim() : "";
        String model = modelField.getText() == null ? "" : modelField.getText().trim();
        DshAccount candidate = new DshAccount(vendor.id(), key,
                baseUrl.isEmpty() ? null : baseUrl,
                username.isEmpty() ? vendor.displayName() : username,
                model.isEmpty() ? null : model);

        // Kept without asking the vendor, which is what the person pressed the button for. The row
        // it leaves behind has a check of its own: checking is a question worth asking deliberately,
        // at a moment when the answer is useful, rather than a gate in front of the door.
        SettingsManager.settings().getAccounts().add(candidate);
        SettingsManager.save();
        fireEvent(new DialogCloseEvent());
    }
}
