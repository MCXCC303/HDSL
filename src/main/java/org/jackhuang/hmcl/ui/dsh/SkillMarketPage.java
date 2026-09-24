/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXListView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshSkill;
import org.jackhuang.hmcl.dsh.DshSkillSource;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Browses the skill packs published on GitHub and installs them into an instance.
///
/// The original's resource-pack download page browses a catalogue that a publisher
/// maintains. There is no such catalogue for skills, and there does not need to be one:
/// a skill is a directory with a SKILL.md in it, so a collection of skills is already a
/// git repository. GitHub's agent-skills topic is therefore the catalogue, its search is
/// the search, and repository stars are the only ordering available.
///
/// Installing is per pack, not per repository: the repositories worth browsing hold
/// twenty packs each, and a person wants one of them.
@NotNullByDefault
public final class SkillMarketPage extends ListPageBase<DshSkillSource.Repo> implements Refreshable {
    /// How many repositories the topic search returns.
    private static final int LIMIT = 60;

    /// Whether a load is already running.
    private boolean busy;

    /// The instance packs are installed into.
    private final JFXComboBox<DshInstance> instanceBox = new JFXComboBox<>();

    /// The page's toolbar, which swaps itself for a search field.
    private final ListSearchBar toolbar = new ListSearchBar(this::refresh);

    /// The row the instance chooser and the toolbar share.
    private final HBox toolbarRow = new HBox(8);

    /// Creates the page.
    public SkillMarketPage() {
        instanceBox.setMaxWidth(Double.MAX_VALUE);
        instanceBox.setConverter(FXUtils.stringConverter(
                instance -> instance == null ? i18n("dsh.market.no_instance") : instance.id()));
        instanceBox.getItems().setAll(DshInstanceManager.list());
        instanceBox.setValue(GameDirectoryManager.selectedInstanceProperty().get());
        HBox.setHgrow(toolbar, Priority.ALWAYS);
        toolbarRow.setAlignment(Pos.CENTER_LEFT);
        toolbarRow.getChildren().setAll(toolbar, instanceBox);
        refresh();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SkillMarketPageSkin(this);
    }

    @Override
    public void refresh() {
        if (busy) {
            return;
        }
        busy = true;
        setLoading(true);

        String query = toolbar.filter() == null ? "" : toolbar.filter();
        CompletableFuture.supplyAsync(() -> {
            try {
                return DshSkillSource.search(query, LIMIT);
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((repos, throwable) -> runInFX(() -> {
            busy = false;
            setLoading(false);
            if (throwable != null) {
                LOG.warning("Failed to search the skill repositories", causeOf(throwable));
                setFailedReason(causeOf(throwable).getMessage());
                return;
            }
            getItems().setAll(repos);
        }));
    }

    /// Returns the failure a future completed with.
    ///
    /// @param throwable what the future reported
    /// @return the cause, when the wrapper has one
    private static Throwable causeOf(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
    }

    /// Holds the list the page is drawn in and gives it its toolbar.
    ///
    /// @param listView the page's list
    private void attachList(JFXListView<DshSkillSource.Repo> listView) {
        toolbar.setButtons(ToolbarListPageSkin.createToolbarButton2(
                i18n("button.refresh"), SVG.REFRESH, this::refresh));
    }

    /// Asks GitHub what packs a repository holds and offers them.
    ///
    /// @param repo the repository to open
    private void showBundles(DshSkillSource.Repo repo) {
        setLoading(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return DshSkillSource.bundles(repo);
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((bundles, throwable) -> runInFX(() -> {
            setLoading(false);
            if (throwable != null) {
                LOG.warning("Failed to list the packs in " + repo.fullName(), causeOf(throwable));
                Controllers.dialog(causeOf(throwable).getMessage(),
                        i18n("message.error"), MessageType.ERROR);
                return;
            }
            if (bundles.isEmpty()) {
                Controllers.dialog(i18n("dsh.skills.market.no_packs", repo.fullName()));
                return;
            }
            Controllers.dialog(new BundleDialog(this, repo, bundles));
        }));
    }

    /// Installs the chosen packs into the instance the page is pointed at.
    ///
    /// @param bundles the packs to install
    private void install(List<DshSkillSource.Bundle> bundles) {
        DshInstance instance = instanceBox.getValue();
        if (instance == null) {
            Controllers.dialog(i18n("dsh.skills.market.no_instance"));
            return;
        }
        ProgressDialog.run(i18n("dsh.skills.market.install"), report -> {
            for (DshSkillSource.Bundle bundle : bundles) {
                report.accept(i18n("dsh.skills.market.fetching", bundle.name()));
                try {
                    DshSkillSource.install(instance.homeDirectory(), bundle, file -> { });
                } catch (DshException e) {
                    throw new DshException(bundle.name() + ": " + e.getMessage(), e);
                }
            }
        }, null);
    }

    /// The page's skin.
    private static final class SkillMarketPageSkin
            extends ToolbarListPageSkin<DshSkillSource.Repo, SkillMarketPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        SkillMarketPageSkin(SkillMarketPage control) {
            super(control);
            setPlaceholder(i18n("dsh.skills.market.empty"));
            control.attachList(listView);
        }

        @Override
        protected List<Node> initializeToolbar(SkillMarketPage page) {
            return List.of(page.toolbarRow);
        }

        @Override
        protected ListCell<DshSkillSource.Repo> createListCell(JFXListView<DshSkillSource.Repo> listView) {
            return new RepoCell(listView, getSkinnable());
        }
    }

    /// One repository in the list.
    ///
    /// The shape is the original's download row: a mark, the name with its description
    /// beneath, and the action on the trailing edge.
    private static final class RepoCell extends MDListCell<DshSkillSource.Repo> {
        /// The page whose actions the row's button runs.
        private final SkillMarketPage page;

        /// The repository's mark.
        private final ImageContainer icon = new ImageContainer(32);

        /// The name, description and star count.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that opens the repository's packs.
        private final JFXButton open = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);

        /// Creates the cell.
        ///
        /// @param listView the owning list
        /// @param page     the page the row's action belongs to
        RepoCell(JFXListView<DshSkillSource.Repo> listView, SkillMarketPage page) {
            super(listView);
            this.page = page;

            HBox container = new HBox(8);
            container.setPickOnBounds(false);
            container.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(content, Priority.ALWAYS);
            content.setMouseTransparent(true);
            setSelectable();

            icon.setImage(org.jackhuang.hmcl.dsh.DshInstanceIcon.COMMAND.load());
            FXUtils.installFastTooltip(open, i18n("dsh.skills.market.open"));
            container.getChildren().setAll(icon, content, open);
            StackPane.setMargin(container, new Insets(8));
            getContainer().getChildren().setAll(container);
        }

        @Override
        protected void updateControl(@Nullable DshSkillSource.Repo repo, boolean empty) {
            if (empty || repo == null) {
                return;
            }
            content.setTitle(repo.fullName());
            content.setSubtitle(repo.description());
            content.getTags().clear();
            content.addTag(i18n("dsh.skills.market.stars", String.valueOf(repo.stars())));

            open.setOnAction(event -> page.showBundles(repo));
            setOnMouseClicked(event -> page.showBundles(repo));
        }
    }

    /// Offers the packs one repository holds.
    ///
    /// A dialog rather than a page of its own: a repository's packs are a short list and
    /// the choice is one or a few of them. They are ticked rather than clicked so that
    /// one install run covers a person who wants three.
    private static final class BundleDialog extends JFXDialogLayout {
        /// Creates the dialog.
        ///
        /// @param page    the page the install belongs to
        /// @param repo    the repository being offered
        /// @param bundles the packs it holds
        BundleDialog(SkillMarketPage page, DshSkillSource.Repo repo, List<DshSkillSource.Bundle> bundles) {
            Label title = new Label(repo.fullName());
            title.getStyleClass().add("title");
            setHeading(title);

            VBox rows = new VBox(4);
            List<JFXCheckBox> boxes = new ArrayList<>();
            for (DshSkillSource.Bundle bundle : bundles) {
                JFXCheckBox box = new JFXCheckBox();
                box.setSelected(true);
                boxes.add(box);

                Label name = new Label(bundle.name());
                Label path = new Label(bundle.path().isEmpty() ? repo.fullName() : bundle.path());
                path.getStyleClass().add("subtitle");
                VBox text = new VBox(name, path);
                HBox.setHgrow(text, Priority.ALWAYS);

                HBox row = new HBox(8, box, text);
                row.setAlignment(Pos.CENTER_LEFT);
                rows.getChildren().add(row);
            }

            ScrollPane scroll = new ScrollPane(rows);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefViewportHeight(Math.min(400, bundles.size() * 56 + 16));
            // After the content, never before: the original's smooth scrolling wraps the
            // scroll pane's own skin, and a pane with no content yet has none.
            FXUtils.smoothScrolling(scroll);
            setBody(scroll);

            JFXButton install = new JFXButton();
            install.getStyleClass().add("dialog-accept");
            install.setText(i18n("dsh.skills.market.install_selected"));
            install.setOnAction(event -> {
                List<DshSkillSource.Bundle> chosen = new ArrayList<>();
                for (int i = 0; i < boxes.size(); i++) {
                    if (boxes.get(i).isSelected()) {
                        chosen.add(bundles.get(i));
                    }
                }
                if (!chosen.isEmpty()) {
                    fireEvent(new DialogCloseEvent());
                    page.install(chosen);
                }
            });
            JFXButton close = new JFXButton();
            close.setText(i18n("button.cancel"));
            close.setOnAction(event -> fireEvent(new DialogCloseEvent()));
            getActions().setAll(install, close);
        }
    }
}
