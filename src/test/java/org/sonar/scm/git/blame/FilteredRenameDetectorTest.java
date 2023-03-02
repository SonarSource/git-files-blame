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
import java.util.Collection;
import java.util.Set;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RenameDetector;
import org.junit.Ignore;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FilteredRenameDetectorTest {

  @Test
  public void compute_whenDeleteAndAddDiffEntry_thenOnlyOneDiffEntryIsReturned() throws IOException {
    RenameDetector renameDetector = mock(RenameDetector.class);
    FilteredRenameDetector filteredRenameDetector = new FilteredRenameDetector(renameDetector);

    DiffEntry deleteDiffEntry = mockedDiffEntry("pathA", DiffEntry.ChangeType.DELETE);
    DiffEntry addDiffEntry = mockedDiffEntry("pathB", DiffEntry.ChangeType.ADD);
    Collection<DiffEntry> changes = Set.of(deleteDiffEntry, addDiffEntry);
    Set<String> paths = Set.of("pathA", "pathB");

    Collection<DiffEntry> possibleRenames = filteredRenameDetector.compute(changes, paths);

    assertThat(possibleRenames).hasSize(1);
  }

  @Test
  public void compute_whenTwoDiffsTypeModifyEndNoPathsPassed_thenReturnAllDiffs() throws IOException {
    RenameDetector renameDetector = mock(RenameDetector.class);
    FilteredRenameDetector filteredRenameDetector = new FilteredRenameDetector(renameDetector);

    DiffEntry deleteDiffEntry = mockedDiffEntry("pathA", DiffEntry.ChangeType.MODIFY);
    DiffEntry addDiffEntry = mockedDiffEntry("pathB", DiffEntry.ChangeType.MODIFY);
    Collection<DiffEntry> changes = Set.of(deleteDiffEntry, addDiffEntry);

    Collection<DiffEntry> possibleRenames = filteredRenameDetector.compute(changes, Set.of());

    assertThat(possibleRenames).hasSize(2);
  }

  @Ignore //TODO shouldn't the compute method take into account Paths in the line 46?
  @Test
  public void compute_whenOnlyOnePathOutOfTwoIncluded_thenReturnOneDiff() throws IOException {
    RenameDetector renameDetector = mock(RenameDetector.class);
    FilteredRenameDetector filteredRenameDetector = new FilteredRenameDetector(renameDetector);

    Collection<DiffEntry> changes = Set.of(mockedDiffEntry("pathA"), mockedDiffEntry("pathB"));
    Set<String> paths = Set.of("pathA");

    Collection<DiffEntry> possibleRenames = filteredRenameDetector.compute(changes, paths);

    assertThat(possibleRenames).hasSize(1);
  }

  private DiffEntry mockedDiffEntry(String path) {
    return mockedDiffEntry(path, DiffEntry.ChangeType.ADD);
  }

  private DiffEntry mockedDiffEntry(String path, DiffEntry.ChangeType type) {
    DiffEntry diffEntry = mock(DiffEntry.class);
    when(diffEntry.getChangeType()).thenReturn(type);
    return when(diffEntry.getNewPath()).thenReturn(path).getMock();
  }
}
