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
