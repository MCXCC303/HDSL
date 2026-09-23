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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.dsh.DshVendor;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogPane;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Where the accounts are kept.
///
/// A DeepSeek Harness account is a key and the vendor it belongs to. The original's accounts are
/// logins it performs for the user; there is no login here, because the harness is not a service —
/// it is a program that talks to whichever model supplier it is configured with. So this is the
/// page where a key is entered, checked, and kept, and where it can be taken away again.
///
/// A key is checked before it is added, and the check is made against the vendor itself rather than
/// by asking the harness: the harness answers out of a local catalogue for a vendor it knows and
/// never makes the request, so asking it would accept any string. What decides is the vendor's own
/// model list, which is the smallest call that requires a valid key and spends nothing.
///
/// A key that cannot be checked — because the network cannot reach the vendor, or because the
/// endpoint does not offer a model list — is **not** refused: a network that cannot reach a supplier
/// today may reach it tomorrow, and the refusal that matters is the one the vendor gives.
@NotNullByDefault
public final class AccountSettingsDialog extends VBox {
    /// The vendors the person can add an account for.
    private final JFXComboBox<DshVendor> vendorBox = new JFXComboBox<>();

    /// What the account is called, for telling two keys for one vendor apart.
    private final JFXTextField labelField = new JFXTextField();

    /// The key.
    private final JFXPasswordField keyField = new JFXPasswordField();

    /// The endpoint, for a vendor whose address is per account.
    private final JFXTextField baseUrlField = new JFXTextField();

    /// Where the verdict on a key is shown.
    private final Label verdict = new Label();

    /// The endpoint row, kept so it can be taken away for a vendor that publishes its own address.
    private javafx.scene.Node accountBaseUrlRow;

    /// The accounts, redrawn whenever one is added or removed.
    private final ComponentList accounts = new ComponentList();

    /// Creates the dialog's content.
    public AccountSettingsDialog() {
        setSpacing(10);
        setPadding(new Insets(10));

        accounts.getContent().addAll(accountRows());
        getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("dsh.account.existing")), accounts,
                ComponentList.createComponentListTitle(i18n("dsh.account.add")), addForm());
    }

    /// Builds one row per account.
    ///
    /// @return the rows
    private java.util.List<javafx.scene.Node> accountRows() {
        java.util.List<javafx.scene.Node> rows = new java.util.ArrayList<>();
        for (DshAccount account : SettingsManager.settings().getAccounts()) {
            rows.add(accountRow(account));
        }
        if (rows.isEmpty()) {
            LineTextPane empty = new LineTextPane();
            empty.setTitle(i18n("dsh.account.none"));
            empty.setSubtitle(i18n("dsh.account.none.hint"));
            rows.add(empty);
        }
        return rows;
    }

    /// Builds the row for one account: what it is, its key masked, and a button to remove it.
    ///
    /// The key is shown masked because the row is a record of what is configured, not a place to
    /// read a secret back from — and a full key on screen is a key in every screenshot.
    ///
    /// @param account the account
    /// @return the row
    private javafx.scene.Node accountRow(DshAccount account) {
        LineTextPane row = new LineTextPane();
        row.setTitle(account.displayName());
        row.setSubtitle(account.vendorId() + " · " + account.maskedKey());

        JFXButton check = FXUtils.newToggleButton4(SVG.CHECK_CIRCLE);
        FXUtils.installFastTooltip(check, i18n("dsh.account.check"));
        check.setOnAction(event -> {
            row.setSubtitle(i18n("dsh.account.checking"));
            java.util.concurrent.CompletableFuture
                    .supplyAsync(account::check, org.jackhuang.hmcl.task.Schedulers.io())
                    .whenComplete((result, failure) -> javafx.application.Platform.runLater(() -> {
                        if (failure != null) {
                            row.setSubtitle(failure.getMessage());
                            return;
                        }
                        row.setSubtitle(result.message());
                    }));
        });

        JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE_FOREVER);
        FXUtils.installFastTooltip(remove, i18n("button.remove"));
        remove.setOnAction(event -> {
            SettingsManager.settings().getAccounts().remove(account);
            SettingsManager.save();
            refresh();
        });

        HBox actions = new HBox(4, check, remove);
        actions.setAlignment(Pos.CENTER_RIGHT);
        row.setRowTrailing(actions);
        return row;
    }

    /// Builds the form for adding one.
    ///
    /// @return the form
    private javafx.scene.Node addForm() {
        vendorBox.getItems().setAll(DshVendor.offered());
        vendorBox.setConverter(FXUtils.stringConverter(DshVendor::label));
        vendorBox.getSelectionModel().selectFirst();
        vendorBox.setMaxWidth(Double.MAX_VALUE);

        labelField.setPromptText(i18n("dsh.account.label.prompt"));
        keyField.setPromptText(i18n("dsh.account.key.prompt"));
        baseUrlField.setPromptText(i18n("dsh.account.base_url.prompt"));
        verdict.getStyleClass().add("desc");

        // An endpoint is only needed by a vendor whose address is per account, so the box is only
        // offered for one — a row asking for something that is not wanted is a row that makes
        // people wonder what they were supposed to type.
        Runnable syncBaseUrl = () -> {
            DshVendor vendor = vendorBox.getValue();
            boolean needed = vendor != null && !vendor.hasBaseUrl();
            if (accountBaseUrlRow != null) {
                accountBaseUrlRow.setVisible(needed);
                accountBaseUrlRow.setManaged(needed);
            }
        };
        vendorBox.valueProperty().addListener(observable -> syncBaseUrl.run());
        javafx.application.Platform.runLater(syncBaseUrl);

        JFXButton add = new JFXButton(i18n("dsh.account.add.button"));
        add.getStyleClass().add("jfx-button-raised");
        add.setOnAction(event -> addAccount());
        HBox buttons = new HBox(add);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        javafx.scene.Node baseUrlRow = labelled(i18n("dsh.account.base_url"), baseUrlField);
        accountBaseUrlRow = baseUrlRow;

        ComponentList list = new ComponentList();
        list.getContent().add(labelled(i18n("dsh.account.vendor"), vendorBox));
        list.getContent().add(labelled(i18n("dsh.account.label"), labelField));
        list.getContent().add(labelled(i18n("dsh.account.key"), keyField));
        list.getContent().add(baseUrlRow);
        list.getContent().add(verdict);
        list.getContent().add(buttons);
        return list;
    }

    /// Builds a row of a name and its box.
    ///
    /// @param title the name
    /// @param field the box
    /// @return the row
    private javafx.scene.Node labelled(String title, javafx.scene.Node field) {
        LinePane pane = new LinePane();
        pane.setTitle(title);
        if (field instanceof javafx.scene.layout.Region region) {
            region.setMinWidth(420);
        }
        pane.setRight(field);
        return pane;
    }

    /// Checks what was typed and, if it is not refused, keeps it.
    ///
    /// The check runs off the interface thread: it is a network call, and a page that stops
    /// responding while a supplier is asked a question is a page that looks broken.
    private void addAccount() {
        DshVendor vendor = vendorBox.getValue();
        String key = keyField.getText() == null ? "" : keyField.getText().trim();
        if (vendor == null || key.isEmpty()) {
            verdict.setText(i18n("dsh.account.need_vendor_and_key"));
            return;
        }
        if (!vendor.looksLikeItsKey(key)) {
            verdict.setText(i18n("dsh.account.key_looks_wrong", vendor.displayName()));
            return;
        }

        String baseUrl = baseUrlField.isVisible() && baseUrlField.getText() != null
                ? baseUrlField.getText().trim() : "";
        String label = labelField.getText() == null ? "" : labelField.getText().trim();
        DshAccount candidate = new DshAccount(vendor.id(), key,
                baseUrl.isEmpty() ? null : baseUrl, label.isEmpty() ? null : label);

        verdict.setText(i18n("dsh.account.checking"));
        java.util.concurrent.CompletableFuture
                .supplyAsync(candidate::check, org.jackhuang.hmcl.task.Schedulers.io())
                .whenComplete((result, failure) -> javafx.application.Platform.runLater(() -> {
                    if (failure != null) {
                        LOG.warning("The account check failed", failure);
                        verdict.setText(failure.getMessage());
                        return;
                    }
                    verdict.setText(result.message());
                    // Only a refusal stops it. An unreachable vendor, or an endpoint that does not
                    // answer this question, leaves the key's worth unknown rather than disproved.
                    if (result.outcome() == DshAccount.Outcome.REJECTED) {
                        return;
                    }
                    SettingsManager.settings().getAccounts().add(candidate);
                    SettingsManager.save();
                    keyField.clear();
                    labelField.clear();
                    refresh();
                }));
    }

    /// Redraws the account list.
    private void refresh() {
        accounts.getContent().setAll(accountRows());
    }
}
