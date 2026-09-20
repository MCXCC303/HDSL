package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The toolbar the version lists share: a name box, a release-type filter and a
/// refresh, on a card.
///
/// The download page, the versions page and the boot library chooser all list
/// published versions and all narrow them the same way. One row, built once,
/// keeps them the same — which they were not while each page built its own.
@NotNullByDefault
public final class VersionFilterBar extends GridPane {
    /// The name box.
    private final JFXTextField nameField = new JFXTextField();

    /// The release-type filter.
    private final JFXComboBox<TypeFilter> typeFilter = new JFXComboBox<>();

    /// What the type filter can be set to.
    ///
    /// Its own type with an "all" member rather than a nullable release type:
    /// "all" has to be a real selection, because a prompt is a different node
    /// with different padding and lands the text a few pixels from the
    /// original's.
    public enum TypeFilter {
        ALL, STABLE, RC, BETA, ALPHA, OTHER;

        /// Reports whether a version string is of this kind.
        ///
        /// The kind is read off the version string, which is all the boot
        /// library publishes — the registry lists its releases with dist-tags
        /// but the package itself is a list of version strings.
        ///
        /// @param version the version string
        /// @return whether it is shown
        public boolean accepts(String version) {
            if (this == ALL) {
                return true;
            }
            String lower = version.toLowerCase(Locale.ROOT);
            String kind;
            if (lower.contains("-alpha")) {
                kind = "ALPHA";
            } else if (lower.contains("-rc")) {
                kind = "RC";
            } else if (lower.contains("-beta")) {
                kind = "BETA";
            } else {
                kind = "STABLE";
            }
            return name().equals(kind);
        }

        /// Returns the translation key for this filter.
        ///
        /// @return the key suffix
        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /// Creates the bar.
    ///
    /// @param onChange  run whenever either filter changes
    /// @param onRefresh run when the refresh button is pressed
    public VersionFilterBar(Runnable onChange, Runnable onRefresh) {
        nameField.setPromptText(i18n("download.name.prompt"));
        nameField.textProperty().addListener((observable, was, value) -> onChange.run());

        typeFilter.getItems().setAll(TypeFilter.values());
        // A selection, not a prompt.
        typeFilter.getSelectionModel().select(TypeFilter.ALL);
        typeFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(@Nullable TypeFilter type) {
                return type == null ? i18n("download.type.all") : i18n("download.type." + type.id());
            }

            @Override
            public TypeFilter fromString(String string) {
                return TypeFilter.ALL;
            }
        });
        typeFilter.valueProperty().addListener((observable, was, value) -> onChange.run());

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(event -> onRefresh.run());

        // The original's grid: the labels take their own width, the name field
        // takes what is left, and the type filter is capped.
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints filterColumn = new ColumnConstraints();
        filterColumn.setMaxWidth(150);
        ColumnConstraints actionColumn = new ColumnConstraints();

        getColumnConstraints().setAll(labelColumn, fieldColumn, labelColumn, filterColumn, actionColumn);
        setHgap(16);
        setVgap(10);
        addRow(0, new Label(i18n("download.name")), nameField,
                new Label(i18n("download.type")), typeFilter, refresh);
        getStyleClass().add("card");
        setPadding(new Insets(8));
    }

    /// Reports whether a version passes both filters.
    ///
    /// @param version the version string
    /// @return whether it is shown
    public boolean accepts(String version) {
        String needle = nameField.getText() == null
                ? "" : nameField.getText().trim().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty() && !version.toLowerCase(Locale.ROOT).contains(needle)) {
            return false;
        }
        TypeFilter type = typeFilter.getValue();
        return type == null || type.accepts(version);
    }
}
