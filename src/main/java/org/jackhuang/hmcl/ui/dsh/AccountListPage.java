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
import com.jfoenix.controls.JFXRadioButton;
import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshVendor;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.skin.DshSkin;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.dsh.settings.AccountSettingsDialog;
import org.jackhuang.hmcl.ui.dsh.settings.AddVendorDialog;
import org.jackhuang.hmcl.ui.dsh.settings.SkinDialog;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Account management page displaying configured model vendor accounts and login methods.
 */
@NotNullByDefault
public final class AccountListPage extends DecoratorAnimatedPage implements DecoratorPage {
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
			"dsh.account.list")));
	private final VBox accountCards = new VBox();

	public AccountListPage() {
		ObservableList<DshAccount> accounts = SettingsManager.settings().getAccounts();
		accounts.addListener((ListChangeListener<DshAccount>) change -> Platform.runLater(this::refreshList));
		SettingsManager.settings()
		               .getCustomVendors()
		               .addListener((ListChangeListener<DshVendor>) change -> Platform.runLater(this::rebuildSidebar));
		refreshList();

		buildAddSidebar();
		setCenter(buildAccountPane());
	}

	private static ImageContainer dshMark() {
		ImageContainer mark = new ImageContainer(AdvancedListItem.LEFT_GRAPHIC_SIZE);
		mark.setMouseTransparent(true);
		mark.imageProperty()
		    .bind(Bindings.createObjectBinding(AccountListPage::harnessMark, Themes.darkModeProperty()));
		return mark;
	}

	private static Image harnessMark() {
		return (Themes.darkModeProperty().get() ? DshInstanceIcon.DSH_WHITE : DshInstanceIcon.DSH_BLACK).load();
	}

	static boolean isActive(DshAccount account) {
		DshAccount active = SettingsManager.settings().activeAccount();
		return active != null && active.matchesKey(account.key());
	}

	private void refreshList() {
		List<Node> cards = new ArrayList<>();
		for (DshAccount account : SettingsManager.settings().getAccounts()) {
			cards.add(buildCard(account));
		}
		if (cards.isEmpty()) {
			LineTextPane empty = new LineTextPane();
			empty.setTitle(i18n("dsh.account.none"));
			empty.setSubtitle(i18n("dsh.account.none.hint"));
			cards.add(empty);
		}
		accountCards.getChildren().setAll(cards);
	}

	private void rebuildSidebar() {
		buildAddSidebar();
	}

	private void buildAddSidebar() {
		VBox rows = new VBox();
		rows.getStyleClass().add("advanced-list-box-content");
		rows.getChildren().add(new ClassTitle(i18n("account.create").toUpperCase(Locale.ROOT)));

		DshVendor primary = DshVendor.offered().get(0);
		rows.getChildren()
		    .addAll(vendorItem(i18n("dsh.account.method.official"),
		                       null,
		                       dshMark(),
		                       () -> Controllers.dialog(new AccountSettingsDialog(primary))),
		            vendorItem(i18n("account.methods.offline"),
		                       null,
		                       SVG.PERSON,
		                       () -> Controllers.dialog(AccountSettingsDialog.offline())));

		for (DshVendor vendor : SettingsManager.settings().getCustomVendors()) {
			rows.getChildren().add(addedVendorItem(vendor));
		}

		ScrollPane scrollPane = new ScrollPane(rows);
		scrollPane.setFitToWidth(true);
		VBox.setVgrow(scrollPane, Priority.ALWAYS);
		FXUtils.setLimitWidth(scrollPane, 200);
		FXUtils.smoothScrolling(scrollPane);

		AdvancedListItem addVendor = new AdvancedListItem();
		addVendor.getStyleClass().add("navigation-drawer-item");
		addVendor.setTitle(i18n("dsh.account.add.vendor"));
		addVendor.setSubtitle(i18n("account.methods.authlib_injector"));
		addVendor.setLeftIcon(SVG.ADD_CIRCLE);
		addVendor.setOnAction(event -> Controllers.dialog(new AddVendorDialog()));
		VBox.setMargin(addVendor, new Insets(0, 0, 12, 0));

		setLeft(scrollPane, addVendor);
	}

	private Node vendorItem(String title, @Nullable String subtitle, ImageContainer graphic, Runnable action) {
		AdvancedListItem item = new AdvancedListItem();
		item.getStyleClass().add("navigation-drawer-item");
		item.setTitle(title);
		if (subtitle != null) {
			item.setSubtitle(subtitle);
		}
		item.setLeftGraphic(graphic);
		item.setOnAction(event -> action.run());
		return item;
	}

	private Node vendorItem(String title, @Nullable String subtitle, SVG icon, Runnable action) {
		AdvancedListItem item = new AdvancedListItem();
		item.getStyleClass().add("navigation-drawer-item");
		item.setTitle(title);
		if (subtitle != null) {
			item.setSubtitle(subtitle);
		}
		item.setLeftIcon(icon);
		item.setOnAction(event -> action.run());
		return item;
	}

	private Node addedVendorItem(DshVendor vendor) {
		AdvancedListItem item = new AdvancedListItem();
		item.getStyleClass().add("navigation-drawer-item");
		item.setTitle(vendor.displayName());
		item.setSubtitle(vendor.id());
		item.setLeftIcon(SVG.DRESSER);
		item.setOnAction(event -> Controllers.dialog(new AccountSettingsDialog(vendor)));
		item.setRightAction(SVG.CLOSE, () -> {
			SettingsManager.settings()
			               .getCustomVendors()
			               .removeIf(existing -> existing.id().equalsIgnoreCase(vendor.id()));
			SettingsManager.save();
		});
		return item;
	}

	private Region buildAccountPane() {
		ScrollPane scroll = new ScrollPane();
		scroll.setFitToWidth(true);

		accountCards.getStyleClass().add("card-list");
		accountCards.maxWidthProperty().bind(scroll.widthProperty());

		scroll.setContent(accountCards);
		FXUtils.smoothScrolling(scroll);

		StackPane pane = new StackPane(scroll);
		pane.setPadding(new Insets(10));
		return pane;
	}

	private Node buildCard(DshAccount account) {
		BorderPane root = new BorderPane();
		root.getStyleClass().add("md-list-cell");
		root.getStyleClass().add("dsh-account-card");
		root.setPadding(new Insets(8, 8, 8, 0));

		JFXRadioButton selector = new JFXRadioButton();
		selector.setSelected(isActive(account));
		selector.setMouseTransparent(true);
		root.setLeft(selector);
		BorderPane.setAlignment(selector, Pos.CENTER);

		Canvas avatar = new Canvas(32, 32);
		avatar.setMouseTransparent(true);
		StackPane picture = new StackPane(avatar);
		picture.setMinSize(32, 32);
		picture.setPrefSize(32, 32);
		picture.setMaxSize(32, 32);

		TwoLineListItem content = new TwoLineListItem();
		content.setTitle(account.displayName());
		content.setSubtitle(account.carriesAKey()
		                    ? account.vendorId() + " · " + account.maskedKey() + (account.modelOrDefault().isEmpty()
		                                                                          ? ""
		                                                                          : " · " + account.modelOrDefault())
		                    : i18n("account.methods.offline"));
		drawAvatar(account, avatar);

		HBox centre = new HBox(8, picture, content);
		centre.setAlignment(Pos.CENTER_LEFT);
		centre.setMouseTransparent(true);
		centre.setPrefWidth(Region.USE_PREF_SIZE);
		BorderPane.setMargin(centre, new Insets(0, 0, 0, 8));
		root.setCenter(centre);

		HBox right = new HBox();
		right.setAlignment(Pos.CENTER_RIGHT);
		right.getChildren().addAll(cardActions(account, content));
		root.setRight(right);

		root.setCursor(Cursor.HAND);
		root.setOnMouseClicked(event -> {
			if (event.getButton() == MouseButton.PRIMARY) {
				select(account);
				refreshList();
			}
		});
		return root;
	}

	private List<Node> cardActions(DshAccount account, TwoLineListItem content) {
		List<Node> buttons = new ArrayList<>();
		boolean hasKey = account.carriesAKey();

		JFXButton check = FXUtils.newToggleButton4(SVG.REFRESH);
		FXUtils.installFastTooltip(check, i18n("dsh.account.check"));
		check.setOnAction(event -> check(account, content));
		check.setDisable(!hasKey);
		buttons.add(check);

		JFXButton skin = FXUtils.newToggleButton4(SVG.CHECKROOM);
		FXUtils.installFastTooltip(skin, i18n("dsh.account.skin"));
		skin.setOnAction(event -> Controllers.dialog(new SkinDialog(account, this::refreshList)));
		buttons.add(skin);

		JFXButton changeKey = FXUtils.newToggleButton4(SVG.EDIT);
		FXUtils.installFastTooltip(changeKey, i18n("dsh.account.change_key"));
		changeKey.setOnAction(event -> changeKey(account));
		changeKey.setDisable(!hasKey);
		buttons.add(changeKey);

		JFXButton copyKey = FXUtils.newToggleButton4(SVG.CONTENT_COPY);
		FXUtils.installFastTooltip(copyKey, i18n("dsh.account.copy_key"));
		copyKey.setOnAction(event -> FXUtils.copyText(account.apiKey()));
		copyKey.setDisable(!hasKey);
		buttons.add(copyKey);

		JFXButton removeButton = FXUtils.newToggleButton4(SVG.DELETE_FOREVER);
		FXUtils.installFastTooltip(removeButton, i18n("button.remove"));
		removeButton.setOnAction(event -> remove(account));
		buttons.add(removeButton);
		return buttons;
	}

	private void drawAvatar(DshAccount account, Canvas avatar) {
		AccountAvatar.draw(avatar, DshSkin.headImage(account.key()));
	}

	private void check(DshAccount account, TwoLineListItem content) {
		content.setSubtitle(i18n("dsh.account.checking"));
		CompletableFuture.supplyAsync(account::check, Schedulers.io())
		                 .whenComplete((result, failure) -> Platform.runLater(() -> {
			                 if (!SettingsManager.settings().getAccounts().contains(account)) {
				                 return;
			                 }
			                 if (failure != null) {
				                 Throwable cause = causeOf(failure);
				                 LOG.warning("Could not check the account " + account.displayName(), cause);
				                 content.setSubtitle(rowLine(DshAccount.Outcome.UNREACHABLE));
				                 Controllers.dialog(describe(cause), i18n("message.error"), MessageType.ERROR);
				                 return;
			                 }
			                 content.setSubtitle(rowLine(result.outcome()));
			                 if (result.outcome() != DshAccount.Outcome.VALID) {
				                 Controllers.dialog(result.message(), i18n("message.error"), MessageType.ERROR);
			                 }
		                 }));
	}

	private static String rowLine(DshAccount.Outcome outcome) {
		return switch (outcome) {
			case VALID -> i18n("dsh.account.row.valid");
			case REJECTED -> i18n("dsh.account.check.rejected");
			case UNREACHABLE -> i18n("dsh.account.row.unreachable");
			case UNKNOWN -> i18n("dsh.account.row.unknown");
		};
	}

	private static String describe(Throwable throwable) {
		String said = throwable.getMessage();
		return said == null || said.isBlank() ? throwable.toString() : said;
	}

	private static Throwable causeOf(Throwable throwable) {
		return throwable instanceof CompletionException && throwable.getCause() != null
		       ? throwable.getCause()
		       : throwable;
	}

	@Override
	public ReadOnlyObjectWrapper<State> stateProperty() {
		return state;
	}

	private void select(DshAccount account) {
		SettingsManager.settings().activeAccountKeyProperty().set(account.key());
		SettingsManager.save();
		refreshList();
	}

	private void changeKey(DshAccount account) {
		InputDialogPane pane = new InputDialogPane(i18n("dsh.account.change_key"), "", (key, handler) -> {
			String trimmed = key == null ? "" : key.trim();
			List<DshAccount> accounts = SettingsManager.settings().getAccounts();
			for (int i = 0; i < accounts.size(); i++) {
				if (accounts.get(i).matchesKey(account.key())) {
					accounts.set(i,
					             new DshAccount(account.kind(),
					                            account.vendorId(),
					                            trimmed,
					                            account.baseUrl(),
					                            account.label(),
					                            account.model(),
					                            account.skin()));
					break;
				}
			}
			SettingsManager.save();
			refreshList();
			handler.resolve();
		});
		Controllers.dialog(pane);
	}

	private void remove(DshAccount account) {
		Controllers.confirm(i18n("dsh.account.remove.confirm", account.displayName()), i18n("button.remove"), () -> {
			SettingsManager.settings().getAccounts().removeIf(a -> a.matchesKey(account.key()));
			SettingsManager.save();
			refreshList();
		}, null);
	}
}
