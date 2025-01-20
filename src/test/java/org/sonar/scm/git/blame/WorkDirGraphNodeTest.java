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

import java.util.List;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WorkDirGraphNodeTest {
  private final RevCommit commit = mock(RevCommit.class);
  private WorkDirGraphNode underTest;

  @Before
  public void setup() {
    FileCandidate fc = mock(FileCandidate.class);
    when(fc.getPath()).thenReturn("path");
    underTest = new WorkDirGraphNode(commit, List.of(fc));
  }

  @Test
  public void getCommit_returns_null() {
    assertThat(underTest.getCommit()).isNull();
  }

  @Test
  public void getParentCount_returns_one() {
    assertThat(underTest.getParentCount()).isOne();
  }

  @Test
  public void getParent_returns_parent_commit() {
    assertThat(underTest.getParentCommit(0)).isEqualTo(commit);
  }

  @Test
  public void getTime_returns_int_max() {
    assertThat(underTest.getTime()).isEqualTo(Integer.MAX_VALUE);
  }

}
