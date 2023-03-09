/*
 * Git Files Blame
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
import java.util.Set;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jgit.lib.FileMode.TYPE_FILE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GraphNodeFactoryTest {
  private final Repository repo = mock(Repository.class);
  private final RevCommit revCommit = mock(RevCommit.class);

  @Test
  public void create_whenCommitNotIncludedInPathsToBlame_thenReturnNoFiles() throws IOException {
    GraphNodeFactory statefulCommitFactory = new GraphNodeFactory(Set.of("path"));

    TreeWalk treeWalk = mock(TreeWalk.class);
    when(treeWalk.next()).thenReturn(true).thenReturn(false);
    when(treeWalk.getPathString()).thenReturn("path2");

    CommitGraphNode statefulCommit = statefulCommitFactory.createForCommit(treeWalk, revCommit);

    assertThat(statefulCommit.getAllPaths()).isEmpty();
  }

  @Test
  public void create_whenCommitIncludedInPathsToBlame_thenReturnOneFile() throws IOException {
    GraphNodeFactory statefulCommitFactory = new GraphNodeFactory(Set.of("path"));

    TreeWalk treeWalk = mock(TreeWalk.class);
    when(treeWalk.next()).thenReturn(true).thenReturn(false);
    when(treeWalk.getPathString()).thenReturn("path");
    when(treeWalk.getRawMode(0)).thenReturn(TYPE_FILE);

    CommitGraphNode statefulCommit = statefulCommitFactory.createForCommit(treeWalk, revCommit);

    assertThat(statefulCommit.getAllPaths()).hasSize(1);
  }

  @Test
  public void create_whenNoFilesToBlame_thenReturnOneFile() throws IOException {
    GraphNodeFactory statefulCommitFactory = new GraphNodeFactory(null);

    TreeWalk treeWalk = mock(TreeWalk.class);
    when(treeWalk.next()).thenReturn(true).thenReturn(false);
    when(treeWalk.getPathString()).thenReturn("path");
    when(treeWalk.getRawMode(0)).thenReturn(TYPE_FILE);

    CommitGraphNode statefulCommit = statefulCommitFactory.createForCommit(treeWalk, revCommit);

    assertThat(statefulCommit.getAllPaths()).hasSize(1);
  }

}
