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
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// A list page's toolbar, which swaps itself for a search field.
///
/// The original gives its list pages a search button beside their other buttons;
/// pressing it replaces the row with a field and a close button, and pressing
/// close restores the row and clears what was typed. The instance list and the
/// plugin list both work that way, and building it twice made them drift — the
/// second one did not stretch, because the two pages put the row in different
/// containers.
@NotNullByDefault
public final class ListSearchBar extends StackPane {
    /// The name the list is narrowed to, or `null` when the search is closed.
    private @Nullable String filter;

    /// The field, shown in place of the buttons.
    private final JFXTextField searchField = new JFXTextField();

    /// The page's buttons.
    private final HBox normalBar = new HBox();

    /// The field and its close button.
    private final HBox searchBar = new HBox();

    /// Run whenever the name being searched for changes, and once when the
    /// search is closed with the filter cleared.
    private final Runnable onFilterChanged;

    /// Called when the search field opens or closes.
    ///
    /// A page that says "nothing here" has to know which of the two it means: an
    /// empty folder and a search that matched nothing read very differently.
    private final java.util.function.@Nullable Consumer<Boolean> onSearchStateChanged;

    /// Creates a bar whose search state nobody watches.
    ///
    /// @param onFilterChanged run whenever the filter changes
    public ListSearchBar(Runnable onFilterChanged) {
        this(onFilterChanged, null);
    }

    /// Creates the bar.
    ///
    /// @param onFilterChanged      run whenever the filter changes
    /// @param onSearchStateChanged run when the search field opens or closes, or `null`
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

        // The original builds this with the same factory as the buttons it
        // replaces, so it has the same height and the same icon size. A toggle
        // button of the same apparent shape is a different class with different
        // metrics, and lands a few pixels from where the original's does.
        JFXButton close = ToolbarListPageSkin.createToolbarButton2(null, SVG.CLOSE, this::hideSearch);
        FXUtils.installFastTooltip(close, i18n("button.cancel"));
        FXUtils.onEscPressed(searchField, close::fire);

        // The original pads the search row horizontally and centres it; the
        // padding is horizontal only, so it costs the row no height.
        searchBar.setAlignment(Pos.CENTER);
        searchBar.setPadding(new Insets(0, 5, 0, 5));
        searchBar.getChildren().setAll(searchField, close);

        getChildren().setAll(normalBar);
    }

    /// Sets the buttons the row shows when the search is closed.
    ///
    /// The search button is added here rather than by each page, so a page cannot
    /// offer a search bar it has no way to open.
    ///
    /// @param buttons the page's own buttons, in order
    public void setButtons(Node... buttons) {
        normalBar.getChildren().setAll(buttons);
        normalBar.getChildren().add(
                ToolbarListPageSkin.createToolbarButton2(i18n("search"), SVG.SEARCH, this::showSearch));
    }

    /// Returns the name being searched for, or `null` when the search is closed.
    ///
    /// @return the filter, lower-cased
    public @Nullable String filter() {
        return filter;
    }

    /// Reports whether a name passes the filter.
    ///
    /// @param name the name to test
    /// @return whether it is shown
    public boolean accepts(String name) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return name.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
    }

    /// Reports that the search field opened or closed.
    ///
    /// @param open whether the search field is now the one being shown
    private void reportSearchState(boolean open) {
        if (onSearchStateChanged != null) {
            onSearchStateChanged.accept(open);
        }
    }

    /// Replaces the buttons with the search field.
    private void showSearch() {
        getChildren().setAll(searchBar);
        searchField.requestFocus();
        reportSearchState(true);
    }

    /// Restores the buttons and clears the filter.
    private void hideSearch() {
        searchField.clear();
        getChildren().setAll(normalBar);
        reportSearchState(false);
    }
}
