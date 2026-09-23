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

import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXRadioButton;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.dsh.DshVendor;
import org.jackhuang.hmcl.dsh.skin.DshSkin;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ClassTitle;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.dsh.settings.AccountSettingsDialog;
import org.jackhuang.hmcl.ui.dsh.settings.SkinDialog;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The accounts this launcher holds.
///
/// Laid out the way the original lays out its own account list, because the shape is doing real
/// work there:
///
/// - On the **left**, how an account is added — what kind of thing it is, listed as the ways of
///   getting one. The original lists Microsoft, offline, and each authentication server it has been
///   told about; here it is the model vendors, since there is no login to perform: the harness is
///   not a service, it is a program that talks to whichever supplier it is configured with, and the
///   thing that stands in the place of a login is a key.
/// - On the **right**, the accounts themselves, one card per account, each with the small set of
///   things one does to an account: check that it still works, and remove it.
/// - The **radio button** on each row chooses the account the launcher uses when an instance does
///   not name one of its own. That is the original's arrangement too — its radio picks the account
///   the game starts with — and it is why an account is a thing to choose rather than a setting to
///   type: there is one active account, and looking at another does not change which.
@NotNullByDefault
public final class AccountListPage extends DecoratorAnimatedPage implements DecoratorPage {
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.account.list")));

    /// The card listing the accounts.
    private final JFXListView<DshAccount> accountList = new JFXListView<>();

    /// Creates the page.
    public AccountListPage() {
        accountList.getStyleClass().add("card-list");
        accountList.setCellFactory(list -> new AccountListCell(accountList));
        javafx.collections.ObservableList<DshAccount> accounts = SettingsManager.settings().getAccounts();
        // The list follows the settings, so a dialog that adds an account redraws this page without
        // the page having to know a dialog exists.
        accounts.addListener((javafx.collections.ListChangeListener<DshAccount>) change ->
                javafx.application.Platform.runLater(this::refreshList));
        refreshList();

        buildAddSidebar();
        setCenter(buildAccountPane());
    }

    /// Builds the left column: the ways of getting an account, one row each.
    ///
    /// The original's shape — a list of methods with the page's own "add" item at the foot — because
    /// the question "how do I add an account" has one answer per kind, and a single "add" button
    /// that then asks which kind is a question asked in the wrong order.
    ///
    /// Builds the sidebar.
    ///
    /// It sets the page's left column itself rather than returning a node, because the column is
    /// **two** nodes — the list and the foot — and a builder that returned one of them would have the
    /// caller set that one over both. That is exactly what happened: the foot was built, added, and
    /// then replaced by the builder's return value one line later, so it was present in the code and
    /// nowhere on screen.
    private void buildAddSidebar() {
        // The original's shape, which says something by its order:
        //
        //     ┌ 添加账户 ─────────────
        //     │  微软账户            ← the one the launcher is built around
        //     │  离线模式            ← the one that needs nothing
        //     │  LittleSkin     ×   ← the others, each removable
        //     └ ...
        //     + 添加认证服务器          ← pinned at the foot
        //
        // The first row is the vendor this launcher exists for, the second needs nothing at all,
        // and the third *opens* the list of the rest rather than being it: thirteen vendors down the
        // side of the window is a list nobody reads, and the one a person wants is usually the first
        // or the second row.
        VBox rows = new VBox();
        rows.getStyleClass().add("advanced-list-box-content");

        DshVendor primary = DshVendor.offered().get(0);
        rows.getChildren().addAll(
                vendorItem(i18n("dsh.account.method.official"), null,
                        SVG.DRESSER, () -> Controllers.dialog(new AccountSettingsDialog(primary))),
                vendorItem(i18n("account.methods.offline"), null,
                        SVG.PERSON, () -> Controllers.dialog(AccountSettingsDialog.offline())));

        ScrollPane scrollPane = new ScrollPane(rows);
        scrollPane.setFitToWidth(true);
        // The scrolling part takes what is left and the fixed part keeps its height, which is what
        // puts the foot at the foot rather than directly under the last row.
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        FXUtils.setLimitWidth(scrollPane, 200);
        FXUtils.smoothScrolling(scrollPane);

        // The foot, as in the original: what is about accounts as a whole rather than about one.
        // The original's is "add an authentication server"; this launcher's is the same idea — the
        // vendors it ships with are a fixed list, and the rest are reached by asking for one here.
        AdvancedListBox actions = new AdvancedListBox()
                .addNavigationDrawerItem(i18n("dsh.account.add.vendor"), SVG.ADD_CIRCLE,
                        () -> Controllers.dialog(new AccountSettingsDialog(null)));
        // Room under the foot. The row is forty high but its text sits near the bottom of that, so a
        // column that ends exactly at the window's edge cuts the descenders — which is what "被裁断
        // 一部分" is: the row is laid out, and its last few pixels are below the visible area.
        VBox.setMargin(actions, new Insets(0, 0, 8, 0));
        FXUtils.setLimitHeight(actions, 40);

        setLeft(scrollPane, actions);
    }

    /// Builds one row of the add column.
    ///
    /// @param title    the row's name
    /// @param subtitle the row's own line, or `null` for one-line rows
    /// @param icon     the row's icon
    /// @param action   what pressing it does
    /// @return the row
    private javafx.scene.Node vendorItem(String title, @org.jetbrains.annotations.Nullable String subtitle,
                                         SVG icon, Runnable action) {
        org.jackhuang.hmcl.ui.construct.AdvancedListItem item =
                new org.jackhuang.hmcl.ui.construct.AdvancedListItem();
        item.getStyleClass().add("navigation-drawer-item");
        item.setTitle(title);
        if (subtitle != null) {
            item.setSubtitle(subtitle);
        }
        item.setLeftIcon(icon);
        item.setOnAction(event -> action.run());
        return item;
    }

    /// Builds the accounts themselves.
    ///
    /// Wrapped the way every other page wraps its content: the original's `ComponentList` is what
    /// gives a list its opaque surface, and a bare list would let the window's wallpaper show
    /// through the rows.
    ///
    /// @return the page's centre
    private Region buildAccountPane() {
        StackPane pane = new StackPane();
        pane.setPadding(new Insets(10));
        pane.getStyleClass().add("notice-pane");

        ComponentList root = new ComponentList();
        root.getStyleClass().add("no-padding");
        root.getContent().add(accountList);
        // `ComponentList` wraps its children, so a VGrow set on the child lands on a node the box
        // does not lay out. The box reads this property off the child and applies it to the wrapper.
        ComponentList.setVgrow(accountList, Priority.ALWAYS);
        pane.getChildren().setAll(root);
        return pane;
    }

    @Override
    public ReadOnlyObjectWrapper<State> stateProperty() {
        return state;
    }

    /// Redraws the list from the settings.
    private void refreshList() {
        accountList.getItems().setAll(SettingsManager.settings().getAccounts());
    }

    /// Makes an account the one the launcher uses.
    ///
    /// @param account the account
    private void select(DshAccount account) {
        java.util.List<DshAccount> accounts = SettingsManager.settings().getAccounts();
        int index = -1;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).matchesKey(account.key())) {
                index = i;
                break;
            }
        }
        if (index > 0) {
            // Moved to the front rather than flagged: the launcher's answer to "which account" is
            // the first one it holds, and a separate pointer would be one more thing that can point
            // at nothing.
            accounts.remove(index);
            accounts.add(0, account);
            SettingsManager.save();
        }
        accountList.refresh();
    }

    /// Replaces an account's key, keeping everything else about it.
    ///
    /// @param account the account
    private void changeKey(DshAccount account) {
        org.jackhuang.hmcl.ui.construct.InputDialogPane pane =
                new org.jackhuang.hmcl.ui.construct.InputDialogPane(
                        i18n("dsh.account.change_key"), "", (key, handler) -> {
                            // The pane refuses an empty answer itself, so what arrives is a key.
                            String trimmed = key == null ? "" : key.trim();
                            java.util.List<DshAccount> accounts = SettingsManager.settings().getAccounts();
                            for (int i = 0; i < accounts.size(); i++) {
                                if (accounts.get(i).matchesKey(account.key())) {
                                    accounts.set(i, new DshAccount(account.kind(), account.vendorId(),
                                            trimmed, account.baseUrl(), account.label(), account.model()));
                                    break;
                                }
                            }
                            SettingsManager.save();
                            accountList.refresh();
                            handler.resolve();
                        });
        Controllers.dialog(pane);
    }

    /// Removes an account, after asking.
    ///
    /// @param account the account
    private void remove(DshAccount account) {
        Controllers.confirm(i18n("dsh.account.remove.confirm", account.displayName()),
                i18n("button.remove"), () -> {
                    SettingsManager.settings().getAccounts().removeIf(a -> a.matchesKey(account.key()));
                    SettingsManager.save();
                    refreshList();
                }, null);
    }

    /// Whether an account is the active one.
    ///
    /// @param account the account
    /// @return whether the launcher uses it by default
    static boolean isActive(DshAccount account) {
        java.util.List<DshAccount> accounts = SettingsManager.settings().getAccounts();
        return !accounts.isEmpty() && accounts.get(0).matchesKey(account.key());
    }

    /// One row of the account list.
    ///
    /// Built here rather than in a class of its own because it is only ever this page's row, and
    /// because the two things it can do are both the page's business.
    private final class AccountListCell extends org.jackhuang.hmcl.ui.construct.MDListCell<DshAccount> {
        /// The radio button that makes this the active account.
        private final JFXRadioButton selector = new JFXRadioButton() {
            @Override
            public void fire() {
                DshAccount account = getItem();
                if (!isDisable() && account != null) {
                    select(account);
                }
            }
        };

        /// The name and what the account is.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The monogram standing in for a picture.
        private final Label monogram = new Label();

        /// The skin drawn on its own, when one has been chosen.
        private final javafx.scene.canvas.Canvas avatar = new javafx.scene.canvas.Canvas();

        /// The buttons that need an account to have a key.
        private final com.jfoenix.controls.JFXButton check =
                FXUtils.newToggleButton4(SVG.REFRESH);
        private final com.jfoenix.controls.JFXButton changeKey =
                FXUtils.newToggleButton4(SVG.EDIT);
        private final com.jfoenix.controls.JFXButton copyKey =
                FXUtils.newToggleButton4(SVG.CONTENT_COPY);

        /// Creates a cell.
        ///
        /// @param listView the list it belongs to
        AccountListCell(JFXListView<DshAccount> listView) {
            super(listView);

            BorderPane root = new BorderPane();
            // Each row is its own card, as the original draws its accounts. The class stays
            // `md-list-cell` because the cell's own sizing is built around it — replacing it with
            // `card` changes the row's box model and the cell then measures zero, which is how the
            // rows vanished — and the card's *surface* is put on it by the stylesheet instead.
            root.getStyleClass().add("md-list-cell");
            root.getStyleClass().add("dsh-account-card");
            root.setPadding(new Insets(8, 8, 8, 0));

            selector.setMouseTransparent(false);
            root.setLeft(selector);
            BorderPane.setAlignment(selector, Pos.CENTER);

            // The original draws the account's skin head here. That is now portable, so it is what
            // this draws: a skin belongs to the person using the launcher, and seeing it beside the
            // account is the reason to have chosen one. The monogram stays as the answer when no
            // skin has been chosen — a vendor has nothing to draw, and an invented picture would be
            // a picture of nothing.
            monogram.getStyleClass().add("dsh-account-monogram");
            monogram.setMinSize(32, 32);
            monogram.setPrefSize(32, 32);
            monogram.setAlignment(Pos.CENTER);

            avatar.setWidth(32);
            avatar.setHeight(32);
            avatar.setMouseTransparent(true);
            StackPane picture = new StackPane(avatar, monogram);
            // Its own size, not the row's: a picture box that stretches to the row's height centres
            // the face below the name it belongs to.
            picture.setMinSize(32, 32);
            picture.setPrefSize(32, 32);
            picture.setMaxSize(32, 32);

            HBox centre = new HBox(8, picture, content);
            centre.setAlignment(Pos.CENTER_LEFT);
            centre.setMouseTransparent(true);
            centre.setPrefWidth(Region.USE_PREF_SIZE);
            BorderPane.setMargin(centre, new Insets(0, 0, 0, 8));
            BorderPane.setAlignment(content, Pos.CENTER);
            root.setCenter(centre);

            // The original's row, in its order: refresh, skin, copy, delete. Its first spot is
            // "move to portable", which has no meaning here, and its second is "upload the skin",
            // which here is choosing it — there is no account to upload to.
            //
            // What an account has no key for, it offers no button for. An offline account is a name
            // and a face: checking a key that does not exist, changing one, or copying one would be
            // three buttons that can only fail.
            FXUtils.installFastTooltip(check, i18n("dsh.account.check"));
            check.setOnAction(event -> check());

            com.jfoenix.controls.JFXButton skin = FXUtils.newToggleButton4(SVG.CHECKROOM);
            FXUtils.installFastTooltip(skin, i18n("dsh.account.skin"));
            skin.setOnAction(event -> Controllers.dialog(new SkinDialog()));

            FXUtils.installFastTooltip(changeKey, i18n("dsh.account.change_key"));
            changeKey.setOnAction(event -> {
                DshAccount account = getItem();
                if (account != null) {
                    changeKey(account);
                }
            });

            FXUtils.installFastTooltip(copyKey, i18n("dsh.account.copy_key"));
            copyKey.setOnAction(event -> {
                DshAccount account = getItem();
                if (account != null) {
                    FXUtils.copyText(account.apiKey());
                }
            });

            com.jfoenix.controls.JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE_FOREVER);
            FXUtils.installFastTooltip(remove, i18n("button.remove"));
            remove.setOnAction(event -> {
                DshAccount account = getItem();
                if (account != null) {
                    remove(account);
                }
            });

            HBox right = new HBox(check, skin, changeKey, copyKey, remove);
            right.setAlignment(Pos.CENTER_RIGHT);
            root.setRight(right);

            // The content goes into the container the base class lays out. Building a graphic of
            // its own and calling `setGraphic` does not work: the base class re-sets the graphic on
            // every update, so the row would come out blank while its radio button and buttons — the
            // parts it adds itself — still showed.
            getContainer().getChildren().setAll(root);

            root.setCursor(Cursor.HAND);
            root.setOnMouseClicked(event -> {
                DshAccount account = getItem();
                if (account != null && event.getButton() == MouseButton.PRIMARY) {
                    select(account);
                }
            });
        }

        /// Draws the skin head, or leaves the monogram showing when there is no skin.
        ///
        /// The head is the top-left eighth of the skin — the original reads the same rectangle — and
        /// the hat layer over it is copied as well, because that is where a skin keeps hair, a
        /// hood, or anything else drawn above the face.
        private void drawAvatar() {
            javafx.scene.image.Image skin = DshSkin.image();
            if (skin == null) {
                avatar.setVisible(false);
                monogram.setVisible(true);
                return;
            }
            monogram.setVisible(false);
            avatar.setVisible(true);

            javafx.scene.canvas.GraphicsContext gc = avatar.getGraphicsContext2D();
            gc.clearRect(0, 0, 32, 32);
            gc.setImageSmoothing(false);
            // 8x8 head at (8,8), scaled to fill 32 pixels; then the hat layer at (40,8).
            double unit = 32.0 / 8.0;
            gc.drawImage(skin, 8, 8, 8, 8, 0, 0, unit * 8, unit * 8);
            gc.drawImage(skin, 40, 8, 8, 8, 0, 0, unit * 8, unit * 8);
        }

        /// Asks the vendor whether the key still works.
        ///
        /// Off the interface thread, because it is a network call, and only a refusal means
        /// anything: a vendor that cannot be reached has not said the key is bad.
        /// Asks the vendor whether this account's key still works.
        ///
        /// The verdict goes on the row it belongs to, which is where the original puts a state it
        /// has just learned about an account: beside the account, not in a dialog that covers it.
        private void check() {
            DshAccount account = getItem();
            if (account == null) {
                return;
            }
            content.setSubtitle(i18n("dsh.account.checking"));
            java.util.concurrent.CompletableFuture
                    .supplyAsync(account::check, org.jackhuang.hmcl.task.Schedulers.io())
                    .whenComplete((result, failure) -> javafx.application.Platform.runLater(() -> {
                        java.util.List<DshAccount> accounts = SettingsManager.settings().getAccounts();
                        if (!accounts.contains(account)) {
                            return;
                        }
                        content.setSubtitle(failure != null ? failure.getMessage() : result.message());
                    }));
        }

        @Override
        protected void updateControl(@Nullable DshAccount account, boolean empty) {
            if (empty || account == null) {
                return;
            }
            content.setTitle(account.displayName());
            // An account with no key says what it is instead of showing an empty one: the row is a
            // description, and "····" beside "offline" would be a description of nothing.
            content.setSubtitle(account.carriesAKey()
                    ? account.vendorId() + " · " + account.maskedKey()
                            + (account.modelOrDefault().isEmpty() ? "" : " · " + account.modelOrDefault())
                    : i18n("account.methods.offline"));
            monogram.setText(account.displayName().isEmpty()
                    ? "?" : account.displayName().substring(0, 1).toUpperCase(java.util.Locale.ROOT));
            drawAvatar();

            boolean carriesAKey = account.carriesAKey();
            check.setVisible(carriesAKey);
            check.setManaged(carriesAKey);
            changeKey.setVisible(carriesAKey);
            changeKey.setManaged(carriesAKey);
            copyKey.setVisible(carriesAKey);
            copyKey.setManaged(carriesAKey);
            selector.setSelected(isActive(account));
        }
    }
}
