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
import javafx.util.StringConverter;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Toolbar containing a name query field, release type filter, and refresh action for version lists.
 */
@NotNullByDefault
public final class VersionFilterBar extends GridPane {
	private final JFXTextField nameField = new JFXTextField();
	private final JFXComboBox<TypeFilter> typeFilter = new JFXComboBox<>();

	/**
	 * Creates a version filter bar.
	 *
	 * @param onChange  callback invoked when either filter changes
	 * @param onRefresh callback invoked when the refresh button is clicked
	 */
	public VersionFilterBar(Runnable onChange, Runnable onRefresh) {
		nameField.setPromptText(i18n("download.name.prompt"));
		nameField.textProperty().addListener((observable, was, value) -> onChange.run());

		typeFilter.getItems().setAll(TypeFilter.values());
		typeFilter.getSelectionModel().select(TypeFilter.ALL);
		typeFilter.setConverter(new StringConverter<>() {
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
		addRow(0, new Label(i18n("download.name")), nameField, new Label(i18n("download.type")), typeFilter, refresh);
		getStyleClass().add("card");
		setPadding(new Insets(8));
	}

	/**
	 * Checks whether a version matches the active query and release type filters.
	 *
	 * @param version the candidate version string
	 * @return {@code true} if accepted, {@code false} otherwise
	 */
	public boolean accepts(String version) {
		String needle = nameField.getText() == null ? "" : nameField.getText().trim().toLowerCase(Locale.ROOT);
		if (!needle.isEmpty() && !version.toLowerCase(Locale.ROOT).contains(needle)) {
			return false;
		}
		TypeFilter type = typeFilter.getValue();
		return type == null || type.accepts(version);
	}

	/**
	 * Filter options for release stability channels.
	 */
	public enum TypeFilter {
		ALL, STABLE, RC, BETA, ALPHA, OTHER;

		/**
		 * Checks whether a version string matches this stability filter.
		 *
		 * @param version the version string
		 * @return {@code true} if accepted, {@code false} otherwise
		 */
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

		String id() {
			return name().toLowerCase(Locale.ROOT);
		}
	}
}
