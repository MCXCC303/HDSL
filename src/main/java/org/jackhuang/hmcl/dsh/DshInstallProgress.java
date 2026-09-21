package org.jackhuang.hmcl.dsh;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Reads what a package manager prints into something worth showing.
///
/// The launcher does not download packages itself — npm and pnpm do — so it
/// cannot report bytes fetched. It can report what those programs say they have
/// done, which is where their output comes in.
///
/// Two shapes are read. pnpm prints a running tally:
///
/// ```
/// Progress: resolved 43, reused 30, downloaded 13, added 40
/// ```
///
/// which gives both a sentence and a fraction. npm prints a line per package
/// without ever stating a total, so it gives the sentence and leaves the bar
/// alone.
@NotNullByDefault
public final class DshInstallProgress {
    /// pnpm's running tally.
    private static final Pattern PNPM = Pattern.compile(
            "Progress: resolved (\\d+), reused (\\d+), downloaded (\\d+), added (\\d+)");

    /// npm's line for a request it answered from its cache.
    private static final Pattern NPM_CACHE = Pattern.compile("npm http cache ");

    /// npm's line for a request it made over the network.
    private static final Pattern NPM_FETCH = Pattern.compile("npm http fetch ");

    /// The line to show, which starts as the launcher's own word for work in
    /// progress and becomes what the package manager reports.
    private final SimpleStringProperty message =
            new SimpleStringProperty(i18n("message.doing"));

    /// How far along the work is, or -1 while that cannot be said.
    private final SimpleDoubleProperty fraction = new SimpleDoubleProperty(-1);

    /// How many npm requests have been seen, from the cache and over the network.
    private int npmSeen;

    /// How many of those npm answered from its cache.
    private int npmCached;

    /// The line to show.
    ///
    /// @return the property, which the dialog binds to
    public ReadOnlyStringProperty messageProperty() {
        return message;
    }

    /// How far along the work is.
    ///
    /// @return the property, which is -1 while the fraction is unknown
    public ReadOnlyDoubleProperty fractionProperty() {
        return fraction;
    }

    /// Reads one line of a package manager's output.
    ///
    /// Called from the thread running the package manager. What it produces is
    /// shown in the dialog, and a label whose text is changed from another
    /// thread is not laid out again — the dialog would keep showing the first
    /// line it was given. So the line is worked out here and handed to the
    /// interface thread to be shown.
    ///
    /// Lines that say nothing about progress are ignored, which is most of them.
    ///
    /// @param line the line
    public void accept(String line) {
        Matcher pnpm = PNPM.matcher(line);
        if (pnpm.find()) {
            int resolved = Integer.parseInt(pnpm.group(1));
            int reused = Integer.parseInt(pnpm.group(2));
            int downloaded = Integer.parseInt(pnpm.group(3));
            int added = Integer.parseInt(pnpm.group(4));

            show(i18n("dsh.install.progress.pnpm", added, resolved, downloaded, reused));
            if (resolved > 0) {
                fraction.set(Math.min(1.0, (double) added / resolved));
            }
            return;
        }

        if (NPM_FETCH.matcher(line).find()) {
            npmSeen++;
        } else if (NPM_CACHE.matcher(line).find()) {
            npmSeen++;
            npmCached++;
        } else {
            return;
        }

        // npm never says how many it will ask for, so there is no fraction to
        // give; the count is still worth reading, because a run served entirely
        // from the cache looks the same as a run that is doing nothing.
        show(i18n("dsh.install.progress.npm", npmSeen, npmSeen - npmCached, npmCached));
    }

    /// Shows a line, from the interface thread.
    ///
    /// @param line the line
    private void show(String line) {
        Platform.runLater(() -> message.set(line));
    }
}
