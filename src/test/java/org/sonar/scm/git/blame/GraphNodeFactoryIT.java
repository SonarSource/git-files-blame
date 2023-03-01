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
import java.nio.file.Files;
import org.apache.commons.lang3.SystemUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Assume;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.sonar.scm.git.GitUtils.createFile;

public class GraphNodeFactoryIT extends AbstractGitIT {

  @Test
  public void create_ignores_symlinks() throws IOException, GitAPIException {
    Assume.assumeFalse(SystemUtils.IS_OS_WINDOWS);
    String fileName = "fileA";
    String symlinkName = "symlink";

    createFile(baseDir, fileName, "line1");
    Files.createSymbolicLink(baseDir.resolve(symlinkName), baseDir.resolve(fileName));
    commit(fileName, symlinkName);

    GraphNodeFactory underTest = new GraphNodeFactory(git.getRepository(), null);
    TreeWalk treeWalk = new TreeWalk(git.getRepository().newObjectReader());
    CommitGraphNode commit = underTest.createForCommit(treeWalk, getHead());

    assertThat(commit.getAllPaths()).containsOnly(fileName);
  }

  @Test
  public void create_for_working_directory() throws IOException {
    createFile(baseDir, "fileA", "line1");
    createFile(baseDir, "fileB", "line2");

    GraphNodeFactory underTest = new GraphNodeFactory(git.getRepository(), null);
    TreeWalk treeWalk = new TreeWalk(git.getRepository().newObjectReader());
    RevCommit parent = mock(RevCommit.class);
    GraphNode commit = underTest.createForWorkingDir(treeWalk, parent);

    assertThat(commit.getAllPaths()).containsOnly("fileA", "fileB");
    assertThat(commit.getParentCommit(0)).isEqualTo(parent);
  }

  private RevCommit getHead() throws IOException {
    ObjectId head = git.getRepository().resolve(Constants.HEAD);
    return git.getRepository().parseCommit(head);
  }

}