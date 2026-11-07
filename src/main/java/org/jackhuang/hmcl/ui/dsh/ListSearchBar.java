package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Toolbar component for list pages that toggles between action buttons and an inline search field.
 */
@NotNullByDefault
public final class ListSearchBar extends StackPane {
	private final JFXTextField searchField = new JFXTextField();
	private final HBox normalBar = new HBox();
	private final HBox searchBar = new HBox();
	private final Runnable onFilterChanged;
	private final java.util.function.@Nullable Consumer<Boolean> onSearchStateChanged;
	private @Nullable String filter;

	/**
	 * Creates a search bar with a filter change callback.
	 *
	 * @param onFilterChanged callback invoked when search filter text changes
	 */
	public ListSearchBar(Runnable onFilterChanged) {
		this(onFilterChanged, null);
	}

	/**
	 * Creates a search bar with filter change and search open/close state callbacks.
	 *
	 * @param onFilterChanged      callback invoked when search filter text changes
	 * @param onSearchStateChanged callback invoked when search bar opens or closes, or {@code null}
	 */
	public ListSearchBar(Runnable onFilterChanged,
	                     java.util.function.@Nullable Consumer<Boolean> onSearchStateChanged) {
		this.onFilterChanged = onFilterChanged;
		this.onSearchStateChanged = onSearchStateChanged;

		setAlignment(Pos.CENTER_LEFT);

		normalBar.setAlignment(Pos.CENTER_LEFT);

		searchField.setPromptText(i18n("search"));
		HBox.setHgrow(searchField, Priority.ALWAYS);
		searchField.textProperty().addListener((observable, was, value) -> {
			filter = value;
			onFilterChanged.run();
		});

		JFXButton close = ToolbarListPageSkin.createToolbarButton2(null, SVG.CLOSE, this::hideSearch);
		FXUtils.installFastTooltip(close, i18n("button.cancel"));
		FXUtils.onEscPressed(searchField, close::fire);

		searchBar.setAlignment(Pos.CENTER);
		searchBar.setPadding(new Insets(0, 5, 0, 5));
		searchBar.getChildren().setAll(searchField, close);

		getChildren().setAll(normalBar);
	}

	/**
	 * Sets action buttons displayed when the search bar is closed.
	 *
	 * @param buttons the action button nodes
	 */
	public void setButtons(Node... buttons) {
		normalBar.getChildren().setAll(buttons);
		normalBar.getChildren()
		         .add(ToolbarListPageSkin.createToolbarButton2(i18n("search"), SVG.SEARCH, this::showSearch));
	}

	/**
	 * Returns the current search filter string, or {@code null} if closed.
	 *
	 * @return the active filter text
	 */
	public @Nullable String filter() {
		return filter;
	}

	/**
	 * Checks whether the given name matches the active search filter.
	 *
	 * @param name the candidate name
	 * @return {@code true} if accepted, {@code false} otherwise
	 */
	public boolean accepts(String name) {
		if (filter == null || filter.isBlank()) {
			return true;
		}
		return name.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
	}

	private void reportSearchState(boolean open) {
		if (onSearchStateChanged != null) {
			onSearchStateChanged.accept(open);
		}
	}

	private void showSearch() {
		getChildren().setAll(searchBar);
		searchField.requestFocus();
		reportSearchState(true);
	}

	private void hideSearch() {
		searchField.clear();
		getChildren().setAll(normalBar);
		reportSearchState(false);
	}
}
