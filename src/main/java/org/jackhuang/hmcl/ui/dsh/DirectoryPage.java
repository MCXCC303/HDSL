package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import com.jfoenix.validation.RequiredFieldValidator;
import com.jfoenix.validation.base.ValidatorBase;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineFileChooserButton;
import org.jackhuang.hmcl.ui.construct.LineSwitch;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PageCloseEvent;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Page for adding a directory where the launcher searches for instances.
 */
@NotNullByDefault
public final class DirectoryPage extends BorderPane implements DecoratorPage {
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
			"game_directory.new")));
	private final JFXTextField nameField = new JFXTextField();
	private final LineFileChooserButton chooser = new LineFileChooserButton();
	private final LineSwitch relativePath = new LineSwitch();

	/**
	 * Creates the directory page.
	 */
	public DirectoryPage() {
		getStyleClass().add("gray-background");

		chooser.setTitle(i18n("game_directory.instance_directory"));
		chooser.setFileChooserTitle(i18n("game_directory.instance_directory.choose"));
		chooser.setType(LineFileChooserButton.Type.OPEN_DIRECTORY);
		chooser.convertToRelativePathProperty().bind(relativePath.selectedProperty());

		relativePath.setTitle(i18n("game_directory.use_relative_path"));

		RequiredFieldValidator required = new RequiredFieldValidator();
		required.setMessage(i18n("input.not_empty"));
		nameField.getValidators().add(required);

		ValidatorBase unique = new ValidatorBase() {
			@Override
			protected void eval() {
				Object source = getSrcControl();
				if (!(source instanceof JFXTextField field)) {
					hasErrors.set(false);
					return;
				}
				hasErrors.set(GameDirectoryManager.directories()
				                                  .stream()
				                                  .anyMatch(existing -> Objects.equals(existing.displayName(),
				                                                                       field.getText())));
			}
		};
		unique.setMessage(i18n("game_directory.already_exists"));
		nameField.getValidators().add(unique);

		BorderPane nameRow = new BorderPane();
		Label nameLabel = new Label(i18n("game_directory.name"));
		nameRow.setLeft(nameLabel);
		BorderPane.setAlignment(nameLabel, Pos.CENTER_LEFT);
		nameRow.setRight(nameField);
		BorderPane.setMargin(nameField, new Insets(8, 0, 8, 0));

		ComponentList list = new ComponentList();
		list.getContent().setAll(nameRow, chooser, relativePath);

		VBox content = new VBox(list);
		content.setPadding(new Insets(20));

		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.setFitToHeight(true);
		FXUtils.smoothScrolling(scroll);
		setCenter(scroll);

		JFXButton save = FXUtils.newRaisedButton(i18n("button.save"));
		save.setPrefSize(100, 40);
		save.setOnAction(event -> save());

		BorderPane footer = new BorderPane();
		footer.setPadding(new Insets(20));
		footer.setRight(save);
		BorderPane.setAlignment(save, Pos.BOTTOM_RIGHT);
		setBottom(footer);

		// Follow chosen directory name until edited manually.
		chooser.locationProperty().addListener((observable, was, now) -> {
			if (nameField.getText() == null || nameField.getText().isBlank() ||
			    Objects.equals(nameField.getText(), nameOf(was))) {
				nameField.setText(nameOf(now));
			}
		});
	}

	private static String nameOf(@Nullable String location) {
		if (location == null || location.isBlank()) {
			return "";
		}
		Path fileName = Path.of(location).getFileName();
		return fileName == null ? "" : fileName.toString();
	}

	private static Path portable(Path path) {
		try {
			return Metadata.CURRENT_DIRECTORY.relativize(path.toAbsolutePath().normalize());
		} catch (IllegalArgumentException e) {
			return path;
		}
	}

	@Override
	public ReadOnlyObjectProperty<State> stateProperty() {
		return state.getReadOnlyProperty();
	}

	private void save() {
		if (!nameField.validate()) {
			return;
		}
		String location = chooser.getLocation();
		if (location == null || location.isBlank()) {
			Controllers.dialog(i18n("game_directory.instance_directory.choose"),
			                   i18n("message.error"),
			                   MessageType.ERROR);
			return;
		}

		Path chosen = Path.of(location);
		if (!Files.isDirectory(chosen.toAbsolutePath().normalize())) {
			Controllers.dialog(chosen + " is not a directory", i18n("message.error"), MessageType.ERROR);
			return;
		}

		GameDirectory added;
		try {
			added = GameDirectoryManager.add(relativePath.isSelected() ? portable(chosen) : chosen,
			                                 nameField.getText());
			GameDirectoryManager.select(added.id());
		} catch (IllegalArgumentException e) {
			Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
			return;
		}

		// Warn if selecting a folder containing a nested instances directory.
		Path nested = chosen.toAbsolutePath().normalize().resolve("instances");
		if (GameDirectoryManager.countInstances(added) == 0 && Files.isDirectory(nested)) {
			Controllers.dialog(i18n("dsh.directory.empty.with_instances", nested.toString()),
			                   i18n("dsh.directory.add"),
			                   MessageType.WARNING,
			                   () -> fireEvent(new PageCloseEvent()));
			return;
		}

		fireEvent(new PageCloseEvent());
	}
}
