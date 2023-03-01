/*
 * SonarQube
 * Copyright (C) 2009-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scm.git.blame;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;

public class FilteredRenameDetector {
  private final RenameDetector renameDetector;

  public FilteredRenameDetector(Repository repository) {
    this.renameDetector = new RenameDetector(repository);
  }

  /**
   * Based on a given collection of ADD and REMOVE file changes and a set of file paths being blamed, this method
   * computes possible RENAME and COPY entries which have a new path that is part of the files being blamed.
   */
  public Collection<DiffEntry> compute(Collection<DiffEntry> changes, Set<String> pathsBeingBlamed) throws IOException {
    // added files. We'll call RenameDetector separately for each of these files
    List<DiffEntry> addsBeingBlamed = changes.stream()
      .filter(c -> c.getChangeType() == ChangeType.ADD)
      .filter(c -> pathsBeingBlamed.contains(c.getNewPath()))
      .collect(Collectors.toList());

    if (addsBeingBlamed.isEmpty()) {
      // no point in continuing since RenameDetector won't be called
      return changes;
    }

    Map<String, DiffEntry> diffsPerNewPath = changes.stream()
      .filter(d -> d.getChangeType() != ChangeType.DELETE)
      .collect(Collectors.toMap(DiffEntry::getNewPath, d -> d));

    // deleted files. We keep them aside because we need to fix them after each call to RenameDetector
    List<DeleteDiffEntry> deletes = changes.stream()
      .filter(e -> e.getChangeType() == ChangeType.DELETE)
      .map(DeleteDiffEntry::new)
      .collect(Collectors.toList());

    // Remaining files
    List<DiffEntry> nonAddOrDelete = changes.stream()
      .filter(e -> e.getChangeType() != ChangeType.ADD && e.getChangeType() != ChangeType.DELETE)
      .collect(Collectors.toList());

    // Unfortunately, RenameDetector will give different results when we pass a single file added (for which we want to detect renames and
    // copies) compared to passing all the files added at once. This is most likely a bug.
    //
    // The reason is that internally, RenameDetector first tries to detect exact renames (where the file content doesn't change), and then it tries to
    // detect content renames (where the file content changes but is similar).
    // If a deleted file is matched to an added file in the first step, it won't be used again in the second step. So the problem is that one
    // deleted file can match exactly an added file and at the same time match with similar content another added file.
    //
    // For that reason, we need to run RenameDetector separately for each added file, always feeding the initial list of deleted files.
    List<DiffEntry> entries = new ArrayList<>(nonAddOrDelete.size() + deletes.size() + 1);

    for (DiffEntry add : addsBeingBlamed) {
      entries.clear();
      fixDeletes(deletes);
      entries.addAll(nonAddOrDelete);
      entries.addAll(deletes);
      entries.add(add);

      renameDetector.reset();
      renameDetector.addAll(entries);
      List<DiffEntry> result = renameDetector.compute();

      // overwrite ADDED entries in the initial list with COPY and RENAME, by overwriting entries with the same new path.
      result.stream().filter(r -> r.getChangeType() == ChangeType.COPY || r.getChangeType() == ChangeType.RENAME)
        .forEach(r -> diffsPerNewPath.put(r.getNewPath(), r));
    }
    return diffsPerNewPath.values();
  }

  /**
   * Unfortunately, DiffEntry is mutable (within its package) and RenameDetector will change DELETE entries that match an ADD to a RENAME.
   * We need to set it back to delete, so that we can use it again.
   * @param deletes
   */
  private static void fixDeletes(List<DeleteDiffEntry> deletes) {
    deletes.forEach(DeleteDiffEntry::fixChangeType);
  }

  private static class DeleteDiffEntry extends DiffEntry {
    public DeleteDiffEntry(DiffEntry e) {
      super();
      this.oldId = e.getOldId();
      this.oldMode = FileMode.REGULAR_FILE;
      this.oldPath = e.getOldPath();

      this.newId = e.getNewId();
      this.newMode = FileMode.MISSING;
      this.newPath = DEV_NULL;
      this.changeType = ChangeType.DELETE;
    }

    public void fixChangeType() {
      this.changeType = ChangeType.DELETE;
    }
  }
}
