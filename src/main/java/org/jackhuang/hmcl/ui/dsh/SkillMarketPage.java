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
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceListing;
import com.mcxcc303.hdsl.skill.DshSkillSource;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

// Browses the community's published skills and either installs one into an instance or saves it as an archive.
@NotNullByDefault
public final class SkillMarketPage extends StackPane implements Refreshable {
	private static final int LIMIT = 50;
	private static final int SHORTEST_QUERY = 2;

	private final ObservableList<DshSkillSource.Offering> found = FXCollections.observableArrayList();
	private final ObservableList<DshSkillSource.Offering> shown = FXCollections.observableArrayList();

	private final JFXListView<DshSkillSource.Offering> resultListView = new JFXListView<>();
	private final SpinnerPane spinner = new SpinnerPane();
	private final JFXTextField nameField = new JFXTextField();
	private final JFXComboBox<DshInstance> instanceBox = new JFXComboBox<>();
	private final JFXComboBox<Sort> sortBox = new JFXComboBox<>();

	private boolean busy;

	// Creates the page.
	public SkillMarketPage() {
		VBox root = new VBox();
		root.setSpacing(10);
		root.setPadding(new Insets(10, 10, 0, 10));
		root.getChildren().add(buildSearchPane());

		VBox.setVgrow(spinner, Priority.ALWAYS);
		VBox.setMargin(spinner, new Insets(0, 0, 10, 0));
		resultListView.setPadding(Insets.EMPTY);
		resultListView.getStyleClass().add("no-horizontal-scrollbar");
		resultListView.setItems(shown);
		resultListView.setCellFactory(view -> new OfferingCell((JFXListView<DshSkillSource.Offering>) view));
		resultListView.setPlaceholder(placeholder(i18n("dsh.skills.market.prompt")));
		spinner.setContent(resultListView);
		root.getChildren().add(spinner);

		getChildren().add(root);
	}

	/**
	 * Returns the failure a future completed with.
	 *
	 * @param throwable what the future reported
	 * @return the cause, when the wrapper has one
	 */
	private static Throwable causeOf(Throwable throwable) {
		return throwable instanceof CompletionException && throwable.getCause() != null
		       ? throwable.getCause()
		       : throwable;
	}

	/**
	 * Builds the message a list with nothing in it shows.
	 *
	 * @param message the message
	 * @return the placeholder
	 */
	private static Node placeholder(String message) {
		Label label = new Label(message);
		StackPane container = new StackPane(label);
		container.getStyleClass().add("notice-pane");
		return container;
	}

	/**
	 * Builds the search form.
	 *
	 * @return the form, on the surface the original puts it on
	 */
	private Node buildSearchPane() {
		GridPane pane = new GridPane();
		pane.getStyleClass().add("card");
		pane.setHgap(16);
		pane.setVgap(10);
		pane.setPadding(new Insets(10));

		ColumnConstraints nameColumn = new ColumnConstraints();
		nameColumn.setMinWidth(Region.USE_PREF_SIZE);
		ColumnConstraints fieldColumn = new ColumnConstraints();
		fieldColumn.setHgrow(Priority.ALWAYS);
		ColumnConstraints secondFieldColumn = new ColumnConstraints();
		secondFieldColumn.setHgrow(Priority.ALWAYS);
		pane.getColumnConstraints().setAll(nameColumn, fieldColumn, nameColumn, secondFieldColumn);

		instanceBox.setMaxWidth(Double.MAX_VALUE);
		instanceBox.setConverter(FXUtils.stringConverter(DshInstance::id));
		instanceBox.getItems().setAll(InstanceListing.list());
		instanceBox.setValue(GameDirectoryManager.selectedInstanceProperty().get());

		nameField.setPromptText(i18n("dsh.skills.market.hint"));
		nameField.setMaxWidth(Double.MAX_VALUE);
		nameField.setOnAction(event -> refresh());

		sortBox.setMaxWidth(Double.MAX_VALUE);
		sortBox.setConverter(FXUtils.stringConverter(Sort::displayName));
		sortBox.getItems().setAll(Sort.values());
		sortBox.setValue(Sort.POPULARITY);
		sortBox.setMinWidth(180);
		sortBox.valueProperty().addListener((observable, was, value) -> sort());

		pane.add(new Label(i18n("dsh.download.instance")), 0, 0);
		pane.add(instanceBox, 1, 0, 3, 1);
		pane.add(new Label(i18n("mods.name")), 0, 1);

		HBox query = new HBox(16, nameField, new Label(i18n("search.sort")), sortBox);
		HBox.setHgrow(nameField, Priority.ALWAYS);
		query.setAlignment(Pos.CENTER_LEFT);
		pane.add(query, 1, 1, 3, 1);

		JFXButton search = new JFXButton(i18n("search"));
		search.getStyleClass().add("jfx-button-raised");
		search.setOnAction(event -> refresh());
		HBox buttons = new HBox(8, search);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		pane.add(buttons, 0, 2, 4, 1);

		return pane;
	}

	@Override
	public void refresh() {
		if (busy) return;

		String query = nameField.getText() == null ? "" : nameField.getText().trim();
		if (query.length() < SHORTEST_QUERY) {
			found.clear();
			shown.clear();
			resultListView.setPlaceholder(placeholder(i18n("dsh.skills.market.prompt")));
			return;
		}

		busy = true;
		spinner.setLoading(true);

		// A catalogue that fails is reported with the answer
		CompletableFuture
				.supplyAsync(() -> DshSkillSource.find(query, LIMIT), Schedulers.io())
				.whenComplete((result, throwable) -> runInFX(() -> {
					busy = false;
					spinner.setLoading(false);
					if (throwable != null) {
						LOG.warning("Failed to search the skill catalogues", causeOf(throwable));
						found.clear();
						shown.clear();
						resultListView.setPlaceholder(placeholder(causeOf(throwable).getMessage()));
						return;
					}
					found.setAll(result.skills());
					sort();
					if (!result.failures().isEmpty()) {
						LOG.warning("These catalogues did not answer: " + result.failures());
					}
					resultListView.setPlaceholder(placeholder(
							result.failures().isEmpty()
							? i18n("dsh.skills.market.empty")
							: i18n("dsh.skills.market.partial", String.join(", ", result.failures()))));
				}));
	}

	// Puts the results in the chosen order.
	private void sort() {
		sortBox.getValue();
		Sort order = sortBox.getValue();
		List<DshSkillSource.Offering> ordered = new ArrayList<>(found);
		ordered.sort(order.order());
		shown.setAll(ordered);
	}

	/**
	 * Installs one skill into the instance the form is pointed at.
	 *
	 * @param offering the skill
	 */
	private void install(DshSkillSource.Offering offering) {
		DshInstance instance = instanceBox.getValue();

		ProgressDialog.run(i18n("dsh.skills.market.install"), report -> {
			report.accept(offering.source());
			DshSkillSource.Bundle bundle = DshSkillSource.resolve(offering);
			DshSkillSource.install(instance.homeDirectory(), bundle, report);
		}, null);
	}

	/**
	 * Saves one skill as an archive
	 *
	 * @param offering the skill
	 */
	private void save(DshSkillSource.Offering offering) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("dsh.skills.market.save"));
		chooser.setInitialFileName(offering.name() + ".zip");
		chooser.getExtensionFilters()
		       .add(new FileChooser.ExtensionFilter(i18n("dsh.skills.market.save.filter"), "*.zip"));

		Path target = Controllers.showSaveDialog(chooser);
		if (target == null) return;

		ProgressDialog.run(i18n("dsh.skills.market.save"), report -> {
			report.accept(offering.source());
			DshSkillSource.Bundle bundle = DshSkillSource.resolve(offering);
			DshSkillSource.download(target, bundle, report);
		}, null);
	}

	/**
	 * Asks what to do with one skill
	 *
	 * @param offering the skill
	 */
	private void open(DshSkillSource.Offering offering) {
		String body = offering.description().isEmpty()
		              ? i18n("dsh.skills.market.no_description")
		              : offering.description();
		MessageDialogPane.Builder builder = new MessageDialogPane.Builder(body + "\n\n" + offering.source(),
		                                                                  offering.name(),
		                                                                  MessageType.QUESTION);

		builder.addAction(i18n("mods.install"), () -> install(offering));
		builder.addAction(i18n("dsh.skills.market.save"), () -> save(offering));
		builder.addAction(i18n("dsh.skills.market.to_github"),
		                  () -> Controllers.onHyperlinkAction("https://github.com/" + offering.source()));
		builder.addCancel(null);
		Controllers.dialog(builder.build());
	}

	// How the results can be ordered.
	private enum Sort {
		POPULARITY("dsh.skills.market.sort.popularity",
		           Comparator.comparingLong(DshSkillSource.Offering::popularity).reversed()),
		NAME("dsh.skills.market.sort.name",
		     Comparator.comparing(DshSkillSource.Offering::name, String.CASE_INSENSITIVE_ORDER)),
		SOURCE("dsh.skills.market.sort.source",
		       Comparator.comparing(DshSkillSource.Offering::source, String.CASE_INSENSITIVE_ORDER)
		                 .thenComparing(DshSkillSource.Offering::name, String.CASE_INSENSITIVE_ORDER));

		// The key its name is read from.
		private final String label;
		private final Comparator<DshSkillSource.Offering> order;

		Sort(String label, Comparator<DshSkillSource.Offering> order) {
			this.label = label;
			this.order = order;
		}

		/**
		 * Returns what the box shows for this order.
		 *
		 * @return the name
		 */
		String displayName() {
			return i18n(label);
		}

		/**
		 * Returns the order itself.
		 *
		 * @return the comparator
		 */
		Comparator<DshSkillSource.Offering> order() {
			return order;
		}
	}

	// A result row.
	private final class OfferingCell extends MDListCell<DshSkillSource.Offering> {
		private final TwoLineListItem content = new TwoLineListItem();

		/**
		 * Creates the cell.
		 *
		 * @param listView the list it belongs to
		 */
		OfferingCell(JFXListView<DshSkillSource.Offering> listView) {
			super(listView);
			onClicked(() -> {
				DshSkillSource.Offering item = getItem();
				open(item);
			});
		}

		@Override
		protected void updateControl(@Nullable DshSkillSource.Offering offering, boolean empty) {
			if (empty || offering == null) return;

			content.setTitle(offering.name());
			content.setSubtitle(offering.description().isEmpty() ? offering.source() : offering.description());

			content.getTags().clear();
			content.addTag(offering.catalog());
			content.addTag(offering.source());
			content.addTag(i18n("dsh.skills.market.popularity", String.valueOf(offering.popularity())));

			HBox row = new HBox(8);
			row.setPadding(new Insets(8));
			row.setAlignment(Pos.CENTER_LEFT);
			row.setCursor(Cursor.HAND);
			HBox.setHgrow(content, Priority.ALWAYS);
			row.getChildren().add(content);
			getContainer().getChildren().setAll(row);
			if (!getContainer().getStyleClass().contains("card-no-padding")) {
				getContainer().getStyleClass().add("card-no-padding");
			}
		}
	}
}
