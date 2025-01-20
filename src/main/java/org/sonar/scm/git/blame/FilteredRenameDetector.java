/*
 * Git Files Blame
 * Copyright (C) 2009-2025 SonarSource SA
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.sonar.scm.git.blame.diff.DiffEntry;
import org.sonar.scm.git.blame.diff.RenameDetector;

public class FilteredRenameDetector {
  private final RenameDetector renameDetector;

  public FilteredRenameDetector(RenameDetector renameDetector) {
    this.renameDetector = renameDetector;
  }

  /**
   * Based on a given collection of ADD and REMOVE file changes and a set of file paths being blamed, this method
   * computes possible RENAME and COPY entries which have a new path that is part of the files being blamed.
   */
  public List<DiffEntry> detectRenames(Collection<DiffEntry> changes, Set<String> paths) throws IOException {
    List<DiffEntry> filtered = new ArrayList<>();

    // For new path: skip ADD's that don't match given paths
    for (DiffEntry diff : changes) {
      DiffEntry.ChangeType changeType = diff.getChangeType();
      if (changeType != DiffEntry.ChangeType.ADD || paths.contains(diff.getNewPath())) {
        filtered.add(diff);
      }
    }

    renameDetector.reset();
    renameDetector.addAll(filtered);
    return renameDetector.compute();
  }
}
