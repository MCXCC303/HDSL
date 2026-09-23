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

    /// Which kind of account this dialog adds.
    private final DshAccount.AccountKind kind;

    /// Whether this dialog adds an offline account, which is a name and nothing else.
    private final boolean offline;

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
        // The kind follows the vendor, rather than being assumed from the fact that a vendor was
        // named. Hardcoding `THIRD_PARTY` here is what made the launcher's own vendor behave like
        // somebody else's: the checks that ask `kind != OFFICIAL` — whether to show the model field,
        // whether to write a default model — never fired, because "opened from a vendor row" was
        // being read as "is a third party".
        this(preselected,
                preselected != null && preselected.preferred()
                        ? DshAccount.AccountKind.OFFICIAL : DshAccount.AccountKind.THIRD_PARTY,
                false);
    }

    /// Creates a dialog that adds an offline account: a name, and nothing to check.
    ///
    /// @return the dialog
    public static AccountSettingsDialog offline() {
        return new AccountSettingsDialog(null, DshAccount.AccountKind.OFFLINE, true);
    }

    /// Creates the dialog.
    ///
    /// @param preselected the vendor to add, or `null` to ask
    /// @param kind        which kind of account this adds
    /// @param offline     whether it adds an offline account
    private AccountSettingsDialog(@Nullable DshVendor preselected,
                                  DshAccount.AccountKind kind, boolean offline) {
        this.preselected = preselected;
        this.kind = kind;
        this.offline = offline;

        setHeading(new Label(offline
                ? i18n("account.create.offline")
                : preselected == null
                        ? i18n("dsh.account.add.custom")
                        // Named, because the page's row that opened this already said which vendor and
                        // a dialog repeating only "add an account" would not say what is being added.
                        : i18n("dsh.account.add.named", preselected.displayName())));

        VBox body = new VBox(10, buildForm());
        setBody(body);
        setActions(buildActions());

        // After the body is assembled, so that hiding the endpoint row takes it out of the layout
        // rather than leaving a gap where it was.
        javafx.application.Platform.runLater(this::syncEndpointRow);
    }

    /// Builds the form.
    ///
    /// Three shapes, and each asks for exactly what its kind needs:
    ///
    /// - **offline** — a name. Nothing is handed to anybody and nothing is checked, so every other
    ///   field would be a question about something that does not exist.
    /// - **the launcher's own vendor** — a name and a key. The endpoint and the protocol are that
    ///   vendor's and the harness already knows them; the model is the harness's catalogue to
    ///   describe, which is also why a model is not asked for here.
    /// - **another vendor** — a name, a key, and the model, because the harness may not know what
    ///   models that supplier serves and it refuses to start on a default it cannot resolve. Its
    ///   endpoint is asked for only when the vendor does not publish one.
    ///
    /// @return the form
    private VBox buildForm() {
        ComponentList list = new ComponentList();

        // The vendor is asked for only when there is a vendor to ask about and the caller did not
        // say which. The page asks by offering a row per vendor, so asking again inside the dialog
        // would be a question just answered — and an offline account has no supplier at all, which is
        // the whole of what it is, so offering a list of them is offering something that cannot apply.
        if (preselected == null && !offline) {
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

        list.getContent().add(row(offline ? i18n("account.character") : i18n("account.username"),
                usernameField));

        if (!offline) {
            list.getContent().add(row(i18n("dsh.account.key"), keyField));

            baseUrlRow.setTitle(i18n("dsh.account.base_url"));
            baseUrlField.setMinWidth(360);
            baseUrlRow.setRight(baseUrlField);
            list.getContent().add(baseUrlRow);

            if (kind != DshAccount.AccountKind.OFFICIAL) {
                list.getContent().add(row(i18n("dsh.account.model"), modelField));
            }
        }

        // The verdict is a line of the form, not a second dialog on top of it: a message about what
        // was typed belongs where it was typed.
        verdict.getStyleClass().add("desc");
        verdict.setWrapText(true);
        list.getContent().add(verdict);

        // No heading over the fields: the dialog's own heading already says what is being added, and
        // a second one saying "add an account" inside a dialog titled that is the same sentence twice.
        return new VBox(list);
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

    /// Checks what was typed and, if it is not refused, keeps it.
    ///
    /// The check runs off the interface thread: it is a network call, and a dialog that stops
    /// responding while a supplier is asked a question is a dialog that looks broken.
    private void addAccount() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();

        if (offline) {
            // Nothing to check and nothing to hand over. The name is required only because a row with
            // no name would be a row nobody could tell from another.
            if (username.isEmpty()) {
                verdict.setText(i18n("dsh.account.need_username"));
                return;
            }
            SettingsManager.settings().getAccounts().add(DshAccount.offline(username));
            SettingsManager.save();
            fireEvent(new DialogCloseEvent());
            return;
        }

        DshVendor vendor = preselected != null ? preselected : vendorBox.getValue();
        String key = keyField.getText() == null ? "" : keyField.getText().trim();
        if (username.isEmpty()) {
            verdict.setText(i18n("dsh.account.need_username"));
            return;
        }
        if (vendor == null || key.isEmpty()) {
            verdict.setText(i18n("dsh.account.need_vendor_and_key"));
            return;
        }
        // The name becomes the name of the supplier the harness is handed, so it has to be one the
        // harness can address. Checked here rather than fixed up silently: a name quietly rewritten
        // is a name the person will not find where they look for it.
        if (!DshAccount.isUsableName(username)) {
            verdict.setText(i18n("dsh.account.name_unusable"));
            return;
        }
        if (!vendor.looksLikeItsKey(key)) {
            verdict.setText(i18n("dsh.account.key_looks_wrong", vendor.displayName()));
            return;
        }

        String baseUrl = baseUrlRow.isVisible() && baseUrlField.getText() != null
                ? baseUrlField.getText().trim() : "";
        String model = modelField.getText() == null ? "" : modelField.getText().trim();
        DshAccount candidate = new DshAccount(kind, vendor.id(), key,
                baseUrl.isEmpty() ? null : baseUrl,
                username, model.isEmpty() ? null : model, null);

        // Kept without asking the vendor, which is what the person pressed the button for. The row
        // it leaves behind has a check of its own: checking is a question worth asking deliberately,
        // at a moment when the answer is useful, rather than a gate in front of the door.
        SettingsManager.settings().getAccounts().add(candidate);
        SettingsManager.save();
        fireEvent(new DialogCloseEvent());
    }
}
