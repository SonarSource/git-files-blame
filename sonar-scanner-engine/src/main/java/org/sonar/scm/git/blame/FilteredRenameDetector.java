package org.sonar.scm.git.blame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.Repository;

public class FilteredRenameDetector {
  private final RenameDetector renameDetector;

  public FilteredRenameDetector(Repository repository) {
    this.renameDetector = new RenameDetector(repository);
  }

  public List<DiffEntry> compute(Collection<DiffEntry> changes, Set<String> paths) throws IOException {
    List<DiffEntry> filtered = new ArrayList<>();

    // For new path: skip ADD's that don't match given paths
    for (DiffEntry diff : changes) {
      DiffEntry.ChangeType changeType = diff.getChangeType();
      // TODO can't we just look at ADD and DELETED?
      if (changeType != DiffEntry.ChangeType.ADD || paths.contains(diff.getNewPath())) {
        filtered.add(diff);
      }
    }

    renameDetector.reset();
    renameDetector.addAll(filtered);
    return renameDetector.compute();
  }
}
