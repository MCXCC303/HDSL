package org.jackhuang.hmcl.ui.dsh.install;

import com.jfoenix.controls.JFXButton;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.AppBootPin;
import com.mcxcc303.hdsl.harness.ReleaseQueries;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.dsh.VersionFilterBar;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Wizard page for selecting an application boot library version.
 */
@NotNullByDefault
public final class AppBootSelectPage extends VBox implements WizardPage {
	private final WizardController controller;
	private final Consumer<@Nullable String> onChosen;
	private final @Nullable String launcherVersion;
	private final VersionFilterBar filterBar = new VersionFilterBar(this::render, this::load);
	private final SpinnerPane spinner = new SpinnerPane();
	private final VBox list = new VBox();
	private List<String> versions = List.of();

	/**
	 * Creates the boot library selection page.
	 *
	 * @param controller      the wizard controller
	 * @param launcherVersion the active launcher version, or {@code null}
	 * @param onChosen        callback receiving the selected version
	 */
	public AppBootSelectPage(WizardController controller,
	                         @Nullable String launcherVersion,
	                         Consumer<@Nullable String> onChosen) {
		this.controller = controller;
		this.launcherVersion = launcherVersion;
		this.onChosen = onChosen;

		setSpacing(10);
		setAlignment(Pos.TOP_LEFT);

		VBox.setMargin(filterBar, new Insets(10, 10, 0, 10));

		StackPane listSurface = new StackPane(list);
		listSurface.getStyleClass().add("card");
		VBox.setMargin(listSurface, new Insets(10));
		listSurface.setAlignment(Pos.TOP_LEFT);

		ScrollPane scroll = new ScrollPane(listSurface);
		scroll.setFitToWidth(true);
		FXUtils.smoothScrolling(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);

		spinner.setContent(scroll);
		VBox.setVgrow(spinner, Priority.ALWAYS);

		getChildren().setAll(filterBar, spinner);

		Controllers.dialog(i18n("dsh.install.app_boot.warning"),
		                   i18n("dsh.install.app_boot"),
		                   MessageType.WARNING,
		                   null);

		load();
	}

	@Override
	public String getTitle() {
		return i18n("dsh.install.app_boot.choose");
	}

	private void load() {
		list.getChildren().clear();
		spinner.setLoading(true);

		CompletableFuture.supplyAsync(() -> {
			try {
				return ReleaseQueries.fetchPackageVersions(AppBootPin.APP_BOOT_PACKAGE);
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}).whenComplete((loaded, throwable) -> FXUtils.runInFX(() -> {
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				LOG.warning("Failed to list boot library versions", cause);
				list.getChildren().clear();
				LineTextPane failure = new LineTextPane();
				failure.setText(i18n("dsh.install.app_boot.unavailable") + ": " + cause.getMessage());
				list.getChildren().add(failure);
				spinner.setLoading(false);
				return;
			}
			versions = loaded;
			render();
			spinner.setLoading(false);
		}));
	}

	private void render() {
		list.getChildren().clear();

		List<String> ordered = new ArrayList<>();
		if (launcherVersion != null) {
			ordered.add(launcherVersion);
		}
		for (String version : versions) {
			if (!ordered.contains(version)) {
				ordered.add(version);
			}
		}

		int shown = 0;
		for (String version : ordered) {
			if (!filterBar.accepts(version)) {
				continue;
			}
			list.getChildren().add(buildRow(version));
			shown++;
		}
		if (shown == 0) {
			LineTextPane empty = new LineTextPane();
			empty.setText(i18n("dsh.versions.remote.empty"));
			list.getChildren().add(empty);
		}
	}

	private Node buildRow(String version) {
		boolean paired = version.equals(launcherVersion);

		ImageContainer icon = new ImageContainer(32);
		icon.setImage(DshInstanceIcon.DSH_APPLICATION.load());

		TwoLineListItem content = new TwoLineListItem();
		content.setTitle(version);
		content.getTags().clear();
		content.setSubtitle(i18n(paired ? "dsh.install.app_boot.hint" : "dsh.install.app_boot.departure.hint"));

		JFXButton arrow = new JFXButton();
		arrow.setGraphic(SVG.ARROW_FORWARD.createIcon());
		arrow.getStyleClass().add("toggle-icon4");
		arrow.setMouseTransparent(true);

		HBox row = new HBox(16, icon, content, arrow);
		row.setAlignment(Pos.CENTER);
		HBox.setHgrow(content, Priority.ALWAYS);
		StackPane.setMargin(row, new Insets(10, 16, 10, 16));

		StackPane cell = new StackPane(row);
		cell.getStyleClass().add("md-list-cell");
		StackPane.setAlignment(row, Pos.CENTER_LEFT);

		RipplerContainer rippler = new RipplerContainer(cell);
		rippler.setOnMouseClicked(event -> {
			if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
				choose(version, paired);
				event.consume();
			}
		});
		cell.setCursor(Cursor.HAND);
		return rippler;
	}

	private void choose(String version, boolean paired) {
		onChosen.accept(paired ? null : version);
		controller.onCancel();
	}
}
