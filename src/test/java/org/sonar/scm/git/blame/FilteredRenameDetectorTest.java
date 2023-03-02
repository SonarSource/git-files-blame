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
import org.junit.Ignore;
import org.junit.Test;
import org.sonar.scm.git.blame.diff.DiffEntry;
import org.sonar.scm.git.blame.diff.RenameDetector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FilteredRenameDetectorTest {
  private final RenameDetector renameDetector = mock(RenameDetector.class);
  private final FilteredRenameDetector filteredRenameDetector = new FilteredRenameDetector(renameDetector);

  @Test
  public void compute_whenChangesHaveAddsNotInPaths_thenFilterThem() throws IOException {
    DiffEntry addDiffEntry1 = mockedDiffEntry("pathA", DiffEntry.ChangeType.ADD);
    DiffEntry addDiffEntry2 = mockedDiffEntry("pathB", DiffEntry.ChangeType.ADD);
    Collection<DiffEntry> changes = Set.of(addDiffEntry1, addDiffEntry2);

    filteredRenameDetector.compute(changes, Set.of("pathA"));
    verify(renameDetector).addAll(List.of(addDiffEntry1));
  }

  @Test
  public void compute_returns_results_of_renameDetector() throws IOException {
    DiffEntry diffEntry1 = mockedDiffEntry("pathA", DiffEntry.ChangeType.ADD);
    DiffEntry diffEntry2 = mockedDiffEntry("pathB", DiffEntry.ChangeType.MODIFY);
    DiffEntry diffEntry3 = mockedDiffEntry("pathC", DiffEntry.ChangeType.DELETE);

    Collection<DiffEntry> changes = List.of(diffEntry1, diffEntry2, diffEntry3);
    List<DiffEntry> expected = List.of(mock(DiffEntry.class));
    when(renameDetector.compute()).thenReturn(expected);

    Collection<DiffEntry> result = filteredRenameDetector.compute(changes, Set.of("pathA"));

    verify(renameDetector).addAll(List.of(diffEntry1, diffEntry2, diffEntry3));
    assertThat(result).isEqualTo(expected);
  }

  private DiffEntry mockedDiffEntry(String path, DiffEntry.ChangeType type) {
    DiffEntry diffEntry = mock(DiffEntry.class);
    when(diffEntry.getChangeType()).thenReturn(type);
    return when(diffEntry.getNewPath()).thenReturn(path).getMock();
  }
}
