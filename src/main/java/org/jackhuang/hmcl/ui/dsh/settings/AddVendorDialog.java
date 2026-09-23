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
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.dsh.DshVendor;
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

/// Adds a supplier by its address.
///
/// The original's own two steps, from `AddAuthlibInjectorServerPane`: an address is asked for on one
/// pane, and what was found at it is shown on the next, with a way back. The shape is worth keeping
/// for the same reason it has it — the second pane is the one place a person can see what they are
/// about to add **before** it is added, and a dialog that went straight from an address to a new row
/// would give them nothing to check it against.
///
/// What it asks for is an address rather than a name, because a name is the one thing that cannot be
/// discovered: nothing tells a stranger what to call a service. The id can be, and is — the harness
/// ships a catalogue of thirty-seven providers with their addresses, so an address that is in it is
/// answered with the id the harness already routes by. An address that is not is asked of the service
/// itself, and only then is a person asked what to call it.
@NotNullByDefault
public final class AddVendorDialog extends JFXDialogLayout {
    /// The address as typed.
    private final JFXTextField addressField = new JFXTextField();

    /// What the supplier is called, when the catalogue could not say.
    private final JFXTextField nameField = new JFXTextField();

    /// What went wrong with the address, if anything.
    private final Label warning = new Label();

    /// What was found at the address, once something has been.
    private final Label foundId = new Label();
    private final Label foundUrl = new Label();

    /// The row holding the name, shown only when a name has to be asked for.
    private final Label nameLabel = new Label(i18n("dsh.account.vendor.name"));

    /// The two steps, and the box that holds whichever is showing.
    private final VBox addressPane = new VBox(8);
    private final GridPane confirmPane = new GridPane();
    private final StackPane content = new StackPane();

    /// The buttons, kept because which of them is in force changes with the step.
    private final SpinnerPane nextPane = new SpinnerPane();
    private final JFXButton next = new JFXButton(i18n("wizard.next"));
    private final JFXButton previous = new JFXButton(i18n("wizard.prev"));
    private final JFXButton finish = new JFXButton(i18n("wizard.finish"));
    private final HBox actions = new HBox(8);

    /// The supplier the second step is about, once the first has found one.
    private @Nullable DshVendor found;

    /// Creates the dialog.
    public AddVendorDialog() {
        setHeading(new Label(i18n("dsh.account.add.vendor")));
        buildAddressPane();
        buildConfirmPane();
        buildActions();
        setBody(content);
        setActions(actions);
        showAddressStep();
    }

    /// Builds the pane that asks for an address.
    private void buildAddressPane() {
        addressField.setPromptText(i18n("dsh.account.base_url.prompt"));
        addressField.setOnAction(event -> look());
        // The original's own three, on its own address field: a required check, a URL check, and
        // `setValidateWhileTextChanged` so the box says so while it is being typed.
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

    /// Builds the pane that shows what was found.
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

    /// Builds the buttons and the rule that decides which are in force.
    private void buildActions() {
        next.getStyleClass().add("dialog-accept");
        next.setOnAction(event -> look());
        // Disabled by the field's own validators rather than by a check of its own: an address that is
        // empty or is not a URL has nothing to look up, and the box already says so.
        next.disableProperty().bind(addressField.activeValidatorProperty().isNotNull());
        nextPane.getStyleClass().add("small-spinner-pane");
        nextPane.setContent(next);

        previous.getStyleClass().add("dialog-cancel");
        previous.setOnAction(event -> back());

        finish.getStyleClass().add("dialog-accept");
        finish.setOnAction(event -> keep());

        actions.setAlignment(Pos.CENTER_RIGHT);
    }

    /// Shows the first step.
    private void showAddressStep() {
        found = null;
        warning.setText("");
        content.getChildren().setAll(addressPane);
        actions.getChildren().setAll(cancelButton(), nextPane);
        javafx.application.Platform.runLater(addressField::requestFocus);
    }

    /// Shows the second step, for a supplier that has been found.
    ///
    /// The name row appears only when the catalogue could not answer, which is the original's own
    /// division: what is known is shown, and what is not is asked for. Asking for a name that is
    /// already known would invite a person to write one that disagrees with the id beside it.
    ///
    /// @param vendor      the supplier
    /// @param askForName  whether the person has to name it
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
            javafx.application.Platform.runLater(nameField::requestFocus);
        }
    }

    /// Builds a cancel button.
    ///
    /// @return the button, which is a fresh one because it is put in two different action rows
    private JFXButton cancelButton() {
        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        return cancel;
    }

    /// Asks the address what it is.
    ///
    /// Two questions, in the order that costs least: the catalogue is a lookup and answers for every
    /// supplier the harness already knows, and only an address it does not hold is asked of the
    /// service itself. The other order would make a person wait on the network to be told something
    /// the launcher had on disk.
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
                // Found, and useless: the harness speaks three protocols and this is not one of them.
                // Saying so is the honest answer, and better than adding a row whose accounts cannot
                // launch.
                warning.setText(i18n("dsh.account.vendor.unroutable", known.id()));
                return;
            }
            showConfirmStep(withAddress(known, url), false);
            return;
        }

        // Off the interface thread: this is a network call with a twenty-second timeout, and a dialog
        // that freezes while a stranger's host does not answer is a dialog that looks broken.
        nextPane.showSpinner();
        content.setDisable(true);
        CompletableFuture.supplyAsync(() -> DshAccount.probe(url), Schedulers.io())
                .whenComplete((status, failure) -> javafx.application.Platform.runLater(() -> {
                    nextPane.hideSpinner();
                    content.setDisable(false);
                    // A window that has been closed while the answer was in flight must not be
                    // written to.
                    if (getScene() == null) {
                        return;
                    }
                    if (failure != null || status == null
                            || !DshAccount.statusLooksLikeASupplier(status)) {
                        warning.setText(i18n("dsh.account.vendor.not_a_provider"));
                        return;
                    }
                    showConfirmStep(DshVendor.discovered(suggestId(url), "", url), true);
                }));
    }

    /// Goes back to the first step.
    private void back() {
        showAddressStep();
    }

    /// Keeps the supplier and closes.
    private void keep() {
        DshVendor vendor = found;
        if (vendor == null) {
            return;
        }
        if (nameField.isVisible() && !nameField.validate()) {
            return;
        }
        DshVendor chosen = nameField.isVisible()
                ? new DshVendor(vendor.id(), nameField.getText().trim(), vendor.apiKeyEnv(),
                        vendor.api(), vendor.baseUrl(), false)
                : vendor;
        // Replaced rather than duplicated when the id is already there: adding the same supplier
        // twice, under two addresses, is how a list stops being readable.
        SettingsManager.settings().getCustomVendors().removeIf(
                existing -> existing.id().equalsIgnoreCase(chosen.id()));
        SettingsManager.settings().getCustomVendors().add(chosen);
        SettingsManager.save();
        fireEvent(new DialogCloseEvent());
    }

    /// Returns the supplier with the address the person actually typed.
    ///
    /// The catalogue holds each provider's canonical address; what somebody pastes may carry a
    /// trailing slash, or be the same service written with a path of its own. The typed one is kept,
    /// because it is the one that answered and the one they will recognise.
    ///
    /// @param vendor the supplier from the catalogue
    /// @param url    the address that was typed
    /// @return the supplier to add
    private static DshVendor withAddress(DshVendor vendor, String url) {
        return new DshVendor(vendor.id(), vendor.displayName(), vendor.apiKeyEnv(), vendor.api(),
                url, vendor.preferred());
    }

    /// Reports whether the harness can route this supplier.
    ///
    /// @param vendor the supplier
    /// @return whether its protocol is one of the three the harness accepts
    private static boolean isRoutable(DshVendor vendor) {
        return DshVendor.APIS.contains(vendor.api());
    }

    /// Trims an address to something the rest of the code can use.
    ///
    /// @param text what was typed
    /// @return the address, or `null` when it is not one
    private static @Nullable String normalized(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String url = text.trim().replaceAll("/+$", "");
        return DshVendor.hostOf(url) == null ? null : url;
    }

    /// Guesses an id for a supplier the catalogue does not hold.
    ///
    /// The id is the name the harness will address the route by, so it has to be one the harness can
    /// take. The host's first label is the part that names the service — `api.acme-corp.com` is Acme
    /// — and a person who dislikes the guess sees it in the field beside the name and can change it.
    ///
    /// @param url the address
    /// @return the id
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
}
