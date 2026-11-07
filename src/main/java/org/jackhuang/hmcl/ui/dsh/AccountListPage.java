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
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
			"dsh.account.list")));

	/// The cards, one per account.
	///
	/// A plain box rather than a `ListView`. The original's account list is a `VBox` of cards under a
	/// scroll pane, and that is not a shortcut: a `ListView` is *one* surface with rows in it, which
	/// is what a table is, and an account list is a handful of separate things each drawn as its own
	/// card. Keeping both was worse still — two lists to hold in step by hand.
	private final VBox accountCards = new VBox();

	/// Creates the page.
	public AccountListPage() {
		ObservableList<DshAccount> accounts = SettingsManager.settings().getAccounts();
		// The list follows the settings, so a dialog that adds an account redraws this page without
		// the page having to know a dialog exists.
		accounts.addListener((ListChangeListener<DshAccount>) change -> Platform.runLater(this::refreshList));
		// The suppliers too: one added by address is a new row in the add column, and this page is
		// the only thing that draws them.
		SettingsManager.settings()
		               .getCustomVendors()
		               .addListener((ListChangeListener<DshVendor>) change -> Platform.runLater(this::rebuildSidebar));
		refreshList();

		buildAddSidebar();
		setCenter(buildAccountPane());
	}

	/// Returns the harness mark, sized for a row's leading picture.
	///
	/// Bound to the theme rather than chosen once, because the theme can be changed while the page is
	/// open — and picked by it, because the mark comes in two and only one of them can be seen on a
	/// given surface.
	///
	/// @return the picture
	private static ImageContainer dshMark() {
		ImageContainer mark = new ImageContainer(AdvancedListItem.LEFT_GRAPHIC_SIZE);
		mark.setMouseTransparent(true);
		mark.imageProperty()
		    .bind(Bindings.createObjectBinding(AccountListPage::harnessMark, Themes.darkModeProperty()));
		return mark;
	}

	/// Returns the harness mark for the current theme.
	///
	/// @return the mark, white on a dark surface and black on a light one
	private static Image harnessMark() {
		return (Themes.darkModeProperty().get() ? DshInstanceIcon.DSH_WHITE : DshInstanceIcon.DSH_BLACK).load();
	}

	/// Whether an account is the active one.
	///
	/// @param account the account
	/// @return whether the launcher uses it by default
	static boolean isActive(DshAccount account) {
		DshAccount active = SettingsManager.settings().activeAccount();
		return active != null && active.matchesKey(account.key());
	}

	/// Redraws the cards from the settings.
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
	/// Rebuilds the add column.
	///
	/// It sets the page's left column again rather than patching a row in, because that column *is*
	/// the list of suppliers: a supplier added while the page is open changes it, and one row
	/// appended to a box that the page no longer reads from would not appear.
	private void rebuildSidebar() {
		buildAddSidebar();
	}

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

		// The column is headed, and the heading is not decoration: it is what tells a person that
		// these are ways of *adding* an account rather than the accounts themselves — which, on a page
		// whose centre is a list of accounts, is the one thing about this column that is not obvious.
		// The original heads its own with `ClassTitle(account.create.toUpperCase())`.
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

		// The suppliers somebody added by address, after the two the launcher leads with. They are
		// rows of this column rather than a list of their own, because that is what they are: another
		// way of adding an account. The original draws its added auth servers the same way — the same
		// icon as the leading row, and a close button to take the row away again.
		for (DshVendor vendor : SettingsManager.settings().getCustomVendors()) {
			rows.getChildren().add(addedVendorItem(vendor));
		}

		// The original's shape, node for node:
		//
		//     ScrollPane(ways)            VBox.setVgrow(ALWAYS)
		//     AdvancedListItem(add one)   VBox.setMargin(0, 0, 12, 0)
		//     setLeft(scrollPane, addItem)
		//
		// Three details of it are each load-bearing and I had all three wrong:
		//
		// - the add-row is a **plain item**, not another `AdvancedListBox`. That class is a
		//   `ScrollPane`, and a scroll pane placed after one that is already growing gets no height of
		//   its own — which is why the row read as "偏下" and why the pair of them looked like one
		//   scrollable area: it was one scrollable area.
		// - it is **not** pushed to the bottom. It sits directly under the ways, the same distance
		//   from them as any other row, and the 12px margin under it is all the room it has.
		// - its height is **not** fixed. `setLimitHeight` pinned it to forty pixels, and the row's
		//   text sits near the bottom of its own box, so the column ended inside the glyphs. Given no
		//   height, it takes the height its content asks for.
		ScrollPane scrollPane = new ScrollPane(rows);
		scrollPane.setFitToWidth(true);
		VBox.setVgrow(scrollPane, Priority.ALWAYS);
		FXUtils.setLimitWidth(scrollPane, 200);
		FXUtils.smoothScrolling(scrollPane);

		AdvancedListItem addVendor = new AdvancedListItem();
		addVendor.getStyleClass().add("navigation-drawer-item");
		addVendor.setTitle(i18n("dsh.account.add.vendor"));
		// The original's own subtitle on this row, and the original's own string for it: this adds an
		// external provider rather than one of the launcher's built-in ways, which is what "外置登录"
		// says and what pressing the row to find out would otherwise be needed for.
		addVendor.setSubtitle(i18n("account.methods.authlib_injector"));
		addVendor.setLeftIcon(SVG.ADD_CIRCLE);
		addVendor.setOnAction(event -> Controllers.dialog(new AddVendorDialog()));
		VBox.setMargin(addVendor, new Insets(0, 0, 12, 0));

		setLeft(scrollPane, addVendor);
	}

	/// Builds one row of the add column.
	///
	/// @param title    the row's name
	/// @param subtitle the row's own line, or `null` for one-line rows
	/// @param icon     the row's icon
	/// @param action   what pressing it does
	/// @param title    the row's first line
	/// @param action   what the row does
	/// @return the row
	/// Builds the row for the launcher's own supplier.
	///
	/// Its picture is the mark of the thing an account on it is for, rather than the original's
	/// dresser: the row is not "an account" among several, it is this supplier's, and the mark is
	/// what says so at a glance. Drawn from the same set of pictures the instance entries use, at the
	/// same size, so the column reads as one list.
	/// @return the row
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

	/// Builds one row for a supplier somebody added by address.
	///
	/// The original's own shape for its added auth servers: the same icon as the row the launcher
	/// leads with, the supplier's own name, and a close button that takes it away. The close button
	/// is the half that matters — an address typed once and disliked is otherwise in the column for
	/// good, with nothing on screen that says where it came from.
	///
	/// @param vendor the supplier
	/// @return the row
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

	/// Builds the accounts themselves.
	///
	/// @return the page's centre
	private Region buildAccountPane() {
		// The original's structure, ported as it stands: a scroll pane holding a `card-list` box of
		// cards, and **nothing behind them**.
		//
		// That last part is the whole of the difference the eye notices. `.card` paints
		// `-monet-surface-container-low-transparent-80`; a plate behind the cards painting the same
		// colour stacks with theirs and the pair comes out opaque, which is why the list read as a
		// solid block while the original's reads as separate translucent cards. The stylesheets are
		// not the difference — `.card` and `.card-list` are byte-identical between the two — the
		// container is.
		ScrollPane scroll = new ScrollPane();
		scroll.setFitToWidth(true);

		accountCards.getStyleClass().add("card-list");
		accountCards.maxWidthProperty().bind(scroll.widthProperty());

		// The content first, then the smooth scrolling. `smoothScrolling` subscribes to the pane's
		// content to know what to scroll, and a pane with none throws — which is how this page came
		// to crash on the way up: the window appeared and was gone, and nothing in a test run says a
		// page cannot be built, because no test builds one.
		scroll.setContent(accountCards);
		FXUtils.smoothScrolling(scroll);

		// A margin, not a background: the cards keep their distance from the window's edge without
		// anything being painted under them.
		StackPane pane = new StackPane(scroll);
		pane.setPadding(new Insets(10));
		return pane;
	}

	/// Builds one account's card.
	///
	/// The original's row, in its order: choose it, see its face and name, then the small set of
	/// things one does to an account. What an account has no key for, it offers no button for — an
	/// offline account is a name and a face, and three buttons that can only fail are not buttons.
	///
	/// @param account the account
	/// @return the card
	private Node buildCard(DshAccount account) {
		BorderPane root = new BorderPane();
		// The class stays `md-list-cell` because the list cell's sizing was built around it; only the
		// *surface* is the card's, applied by the stylesheet. Putting `card` here instead changes the
		// row's box model, and the row then measures zero and the list comes out empty.
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

	/// Builds the buttons an account's card offers.
	///
	/// **All of them, always.** The original adds every button to the row and disables the ones that
	/// cannot act on this account (`btnRefresh.setDisable(true)`,
	/// `btnUpload.disableProperty().bind(...)`), so an offline account shows the same five buttons as
	/// any other with three of them greyed. Leaving them out instead — which is what this did — makes
	/// the row change shape from account to account and reads as features that are missing rather
	/// than as actions that do not apply.
	///
	/// The order is the original's: refresh, skin, then the key's own pair, then remove. Its first
	/// spot is "move to portable", which has no meaning here, and its second is "upload the skin",
	/// which here is choosing one — there is no account to upload to.
	///
	/// @param account the account
	/// @param content the card's two lines, which the check writes its verdict onto
	/// @return the buttons
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

	/// Draws an account's face.
	///
	/// Every account has one. The original's list draws `TexturesLoader.getDefaultSkin(uuid)` for
	/// every row, so an account that has chosen nothing is drawn wearing the picture its own
	/// identity selects rather than replaced by a letter — which is what this did, and what was
	/// asked to be changed.
	///
	/// @param account the account
	/// @param avatar  where to draw the head
	private void drawAvatar(DshAccount account, Canvas avatar) {
		AccountAvatar.draw(avatar, DshSkin.headImage(account.key()));
	}

	/// Asks the vendor whether an account's key still works.
	///
	/// The row carries the verdict in one line and a dialog carries what the vendor said about it: the
	/// second is a paragraph with the response body in it, and a card one line high cannot show it. A key
	/// that works only says so — a dialog for that is a dialog somebody dismisses to learn nothing.
	///
	/// @param account the account
	/// @param content the card's own two lines, whose second one carries the verdict
	private void check(DshAccount account, TwoLineListItem content) {
		content.setSubtitle(i18n("dsh.account.checking"));
		CompletableFuture.supplyAsync(account::check, Schedulers.io())
		                 .whenComplete((result, failure) -> Platform.runLater(() -> {
			                 // The card may be gone — the account removed while the answer was in flight — and
			                 // a row that no longer exists must not be written to.
			                 if (!SettingsManager.settings().getAccounts().contains(account)) {
				                 return;
			                 }
			                 if (failure != null) {
				                 // A future that failed has no verdict of its own, and it is not a refusal
				                 // either: the question was never answered, which is what the row must say.
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

	/// The one line an account's row carries once the answer is in.
	///
	/// @param outcome what the check found
	/// @return the line
	private static String rowLine(DshAccount.Outcome outcome) {
		return switch (outcome) {
			case VALID -> i18n("dsh.account.row.valid");
			case REJECTED -> i18n("dsh.account.check.rejected");
			case UNREACHABLE -> i18n("dsh.account.row.unreachable");
			case UNKNOWN -> i18n("dsh.account.row.unknown");
		};
	}

	/// What a throwable says, for the dialog that shows it.
	///
	/// @param throwable what went wrong
	/// @return the text, never empty
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

	/// Makes an account the one the launcher uses.
	///
	/// It is **named**, not moved. The list used to reorder itself so that the chosen account came
	/// first, which meant looking at one and choosing it were the same gesture as far as the list was
	/// concerned — the row somebody had just clicked jumped to the top, and every other row moved
	/// under the pointer. What the launcher needs is an answer to "which account", and a key is that
	/// answer without the list having to encode it.
	///
	/// @param account the account
	private void select(DshAccount account) {
		SettingsManager.settings().activeAccountKeyProperty().set(account.key());
		SettingsManager.save();
		refreshList();
	}

	/// Replaces an account's key, keeping everything else about it.
	///
	/// @param account the account
	private void changeKey(DshAccount account) {
		InputDialogPane pane = new InputDialogPane(i18n("dsh.account.change_key"), "", (key, handler) -> {
			// The pane refuses an empty answer itself, so what arrives is a key.
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

	/// Removes an account, after asking.
	///
	/// @param account the account
	private void remove(DshAccount account) {
		Controllers.confirm(i18n("dsh.account.remove.confirm", account.displayName()), i18n("button.remove"), () -> {
			SettingsManager.settings().getAccounts().removeIf(a -> a.matchesKey(account.key()));
			SettingsManager.save();
			refreshList();
		}, null);
	}

	/// One row of the account list.
	///
	/// Built here rather than in a class of its own because it is only ever this page's row, and
	/// because the two things it can do are both the page's business.

}
