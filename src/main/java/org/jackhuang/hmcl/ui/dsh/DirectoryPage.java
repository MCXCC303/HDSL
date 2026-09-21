package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import com.jfoenix.validation.RequiredFieldValidator;
import com.jfoenix.validation.base.ValidatorBase;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.construct.PageCloseEvent;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineFileChooserButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Adds a folder for the launcher to look for instances in.
///
/// A page of its own rather than a folder chooser: the folder is only one of the
/// three things a directory has. It has a name to show, which need not be the
/// folder's own, and it may be recorded relative to where the launcher runs so
/// that moving both together keeps it working. The original arranges it this way,
/// and a chooser has nowhere to ask for the other two.
@NotNullByDefault
public final class DirectoryPage extends BorderPane implements DecoratorPage {
    /// The page's state, which supplies the title.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("game_directory.new")));

    /// What the folder is to be called.
    private final JFXTextField nameField = new JFXTextField();

    /// The folder itself.
    private final LineFileChooserButton chooser = new LineFileChooserButton();

    /// Whether to record the folder relative to the launcher's own location.
    private final LineToggleButton relativePath = new LineToggleButton();

    /// Creates the page.
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
                hasErrors.set(GameDirectoryManager.directories().stream()
                        .anyMatch(existing -> Objects.equals(existing.displayName(), field.getText())));
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
        // The original pads a settings page's root by twenty.
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

        // The name follows the folder until the name is edited, so that choosing
        // a folder is all a user has to do.
        chooser.locationProperty().addListener((observable, was, now) -> {
            if (nameField.getText() == null || nameField.getText().isBlank()
                    || Objects.equals(nameField.getText(), nameOf(was))) {
                nameField.setText(nameOf(now));
            }
        });
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Returns a folder's own name, for the name box.
    ///
    /// @param location the folder, or `null`
    /// @return its last segment, or an empty string
    private static String nameOf(@Nullable String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        Path fileName = Path.of(location).getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    /// Expresses a folder relative to where the launcher runs, when it can be.
    ///
    /// The original records it this way when the toggle is on, so that moving the
    /// launcher and the folder together keeps the entry working. A folder that
    /// cannot be expressed relative to the launcher is kept as it is.
    ///
    /// @param path the folder
    /// @return the path to record
    private static Path portable(Path path) {
        try {
            return Metadata.CURRENT_DIRECTORY.relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    /// Records the folder and returns to the list.
    private void save() {
        if (!nameField.validate()) {
            return;
        }
        String location = chooser.getLocation();
        if (location == null || location.isBlank()) {
            Controllers.dialog(i18n("game_directory.instance_directory.choose"),
                    i18n("message.error"), MessageType.ERROR);
            return;
        }

        Path chosen = Path.of(location);
        if (!Files.isDirectory(chosen.toAbsolutePath().normalize())) {
            Controllers.dialog(chosen + " is not a directory", i18n("message.error"), MessageType.ERROR);
            return;
        }

        GameDirectory added;
        try {
            added = GameDirectoryManager.add(
                    relativePath.isSelected() ? portable(chosen) : chosen, nameField.getText());
            GameDirectoryManager.select(added.id());
        } catch (IllegalArgumentException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
            return;
        }

        // A folder that holds no instances looks the same as one that was added
        // wrongly, and the common mistake is a launcher's own directory, which
        // holds the instances one level further down. Saying so here is cheaper
        // than leaving someone to work out why the list is empty.
        if (GameDirectoryManager.countInstances(added) == 0) {
            Path nested = chosen.toAbsolutePath().normalize().resolve("instances");
            Controllers.dialog(
                    Files.isDirectory(nested)
                            ? i18n("dsh.directory.empty.with_instances", nested.toString())
                            : i18n("dsh.directory.empty"),
                    i18n("dsh.directory.add"), MessageType.WARNING, () -> {
                        // The navigator listens for this and takes the page away,
                        // which is how the original returns to the list.
                        fireEvent(new PageCloseEvent());
                    });
            return;
        }

        fireEvent(new PageCloseEvent());
    }
}
