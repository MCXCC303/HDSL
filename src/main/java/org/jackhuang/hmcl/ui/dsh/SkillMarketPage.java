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
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXListView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshSkillSource;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Browses the community's published skills and either installs one into an instance or
/// saves it as an archive.
///
/// The catalogue is the community's own index rather than GitHub's topic search. That is
/// the difference that matters here: the index lists **skills**, so one row is one skill
/// instead of a repository that may hold twenty, and it publishes an install count, which
/// is the ordering somebody browsing actually wants. It also means a search costs one
/// request to a service that is built to be searched, rather than one of the sixty an
/// hour GitHub allows an unauthenticated caller.
///
/// Saving is offered beside installing because a skill is a file and not only an
/// installation: an archive of it can be kept, read, unpacked by hand, or handed to
/// another tool, and none of that is the launcher's business to decide. What is offered
/// is the same bytes either way — the instance is written to only when installing.
@NotNullByDefault
public final class SkillMarketPage extends ListPageBase<DshSkillSource.Offering> implements Refreshable {
    /// How many skills a search returns.
    private static final int LIMIT = 50;

    /// The shortest query the registry answers, mirrored so the page can say so before
    /// asking.
    private static final int SHORTEST_QUERY = 2;

    /// Whether a search is already running.
    private boolean busy;

    /// The skin, held so the placeholder can say which of the two empty states this is:
    /// nothing searched for yet, or nothing found.
    private @Nullable SkillMarketPageSkin skin;

    /// The instance an install goes into.
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
        SkillMarketPageSkin created = new SkillMarketPageSkin(this);
        skin = created;
        showPlaceholder(i18n("dsh.skills.market.prompt"));
        return created;
    }

    @Override
    public void refresh() {
        if (busy) {
            return;
        }
        String query = toolbar.filter() == null ? "" : toolbar.filter().trim();
        if (query.length() < SHORTEST_QUERY) {
            // Not a failure and not an empty result: nothing has been asked for yet. The
            // registry refuses anything shorter, so the page says what it wants instead
            // of asking and showing the refusal.
            getItems().clear();
            showPlaceholder(i18n("dsh.skills.market.prompt"));
            return;
        }

        busy = true;
        setLoading(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return DshSkillSource.find(query, LIMIT);
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((offerings, throwable) -> runInFX(() -> {
            busy = false;
            setLoading(false);
            if (throwable != null) {
                LOG.warning("Failed to search the skill registry", causeOf(throwable));
                setFailedReason(causeOf(throwable).getMessage());
                return;
            }
            getItems().setAll(offerings);
            showPlaceholder(i18n("dsh.skills.market.empty"));
        }));
    }

    /// Says what an empty list means.
    ///
    /// @param message the placeholder to show
    private void showPlaceholder(String message) {
        SkillMarketPageSkin current = skin;
        if (current != null) {
            current.placeholder(message);
        }
    }

    /// Returns the failure a future completed with.
    ///
    /// @param throwable what the future reported
    /// @return the cause, when the wrapper has one
    private static Throwable causeOf(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
    }

    /// Installs one skill into the instance the page is pointed at.
    ///
    /// @param offering the skill
    private void install(DshSkillSource.Offering offering) {
        DshInstance instance = instanceBox.getValue();
        if (instance == null) {
            Controllers.dialog(i18n("dsh.skills.market.no_instance"));
            return;
        }
        ProgressDialog.run(i18n("dsh.skills.market.install"), report -> {
            report.accept(offering.source());
            DshSkillSource.Bundle bundle = DshSkillSource.resolve(offering);
            DshSkillSource.install(instance.homeDirectory(), bundle, report::accept);
        }, null);
    }

    /// Saves one skill as an archive, where the user says to keep it.
    ///
    /// The launcher writes the file and stops there. What the archive is for — unpacking
    /// it into a skills directory, reading it first, keeping it for another machine — is
    /// the person's decision, which is the whole reason the archive exists beside the
    /// install.
    ///
    /// @param offering the skill
    private void save(DshSkillSource.Offering offering) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.skills.market.save"));
        chooser.setInitialFileName(offering.name() + ".zip");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.skills.market.save.filter"), "*.zip"));
        Path target = Controllers.showSaveDialog(chooser);
        if (target == null) {
            return;
        }
        ProgressDialog.run(i18n("dsh.skills.market.save"), report -> {
            report.accept(offering.source());
            DshSkillSource.Bundle bundle = DshSkillSource.resolve(offering);
            DshSkillSource.download(bundle, target, report::accept);
        }, null);
    }

    /// Holds the list the page is drawn in and gives it its toolbar.
    ///
    /// @param listView the page's list
    private void attachList(JFXListView<DshSkillSource.Offering> listView) {
        toolbar.setButtons(ToolbarListPageSkin.createToolbarButton2(
                i18n("button.refresh"), SVG.REFRESH, this::refresh));
    }

    /// The page's skin.
    private static final class SkillMarketPageSkin
            extends ToolbarListPageSkin<DshSkillSource.Offering, SkillMarketPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        SkillMarketPageSkin(SkillMarketPage control) {
            super(control);
            control.attachList(listView);
        }

        @Override
        protected List<Node> initializeToolbar(SkillMarketPage page) {
            return List.of(page.toolbarRow);
        }

        @Override
        protected ListCell<DshSkillSource.Offering> createListCell(
                JFXListView<DshSkillSource.Offering> listView) {
            return new OfferingCell(listView, getSkinnable());
        }

        /// Replaces what an empty list says.
        ///
        /// Kept here because the placeholder belongs to the list view, and the page has
        /// two different things to say with it: nothing searched for yet, and nothing
        /// found.
        ///
        /// @param message the placeholder
        void placeholder(String message) {
            setPlaceholder(message);
        }
    }

    /// One skill in the list.
    ///
    /// The shape is the original's download row: a mark, the name with what it belongs to
    /// beneath, and the actions on the trailing edge. Two actions rather than one,
    /// because a skill can be installed or kept, and the two are not the same act.
    private static final class OfferingCell extends MDListCell<DshSkillSource.Offering> {
        /// The page whose actions the row's buttons run.
        private final SkillMarketPage page;

        /// The skill's mark.
        private final ImageContainer icon = new ImageContainer(32);

        /// The name, source and install count.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that saves the skill as an archive.
        private final JFXButton download = FXUtils.newToggleButton4(SVG.ARCHIVE);

        /// The button that installs the skill into the instance.
        private final JFXButton install = FXUtils.newToggleButton4(SVG.ADD);

        /// Creates the cell.
        ///
        /// @param listView the owning list
        /// @param page     the page the row's actions belong to
        OfferingCell(JFXListView<DshSkillSource.Offering> listView, SkillMarketPage page) {
            super(listView);
            this.page = page;

            HBox container = new HBox(8);
            container.setPickOnBounds(false);
            container.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(content, Priority.ALWAYS);
            content.setMouseTransparent(true);
            setSelectable();

            icon.setImage(org.jackhuang.hmcl.dsh.DshInstanceIcon.COMMAND.load());
            FXUtils.installFastTooltip(download, i18n("dsh.skills.market.save"));
            FXUtils.installFastTooltip(install, i18n("dsh.skills.market.install"));

            container.getChildren().setAll(icon, content, download, install);
            StackPane.setMargin(container, new Insets(8));
            getContainer().getChildren().setAll(container);
        }

        @Override
        protected void updateControl(@Nullable DshSkillSource.Offering offering, boolean empty) {
            if (empty || offering == null) {
                return;
            }
            content.setTitle(offering.name());
            content.setSubtitle(offering.source());
            content.getTags().clear();
            content.addTag(i18n("dsh.skills.market.installs", String.valueOf(offering.installs())));

            download.setOnAction(event -> page.save(offering));
            install.setOnAction(event -> page.install(offering));
        }
    }
}
