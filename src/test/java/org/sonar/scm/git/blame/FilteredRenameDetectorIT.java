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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.sonar.scm.git.GitUtils.createFile;

public class FilteredRenameDetectorIT extends AbstractGitIT {
  private FilteredRenameDetector filteredRenameDetector;

  @Before
  public void before() {
    filteredRenameDetector = new FilteredRenameDetector(git.getRepository());
  }

  @Test
  public void detect_exact_rename_and_similar_rename_from_same_file() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2");
    String c1 = commit("fileA");

    rm("fileA");
    createFile(baseDir, "fileB", "line1", "line2", "line3");
    createFile(baseDir, "fileC", "line1", "line2");

    String c2 = commit("fileB", "fileC");

    Collection<DiffEntry> diffEntries = getDiffEntries(c1, c2);
    Collection<DiffEntry> compute = filteredRenameDetector.compute(diffEntries, Set.of("fileB", "fileC"));
    assertThat(compute).extracting(DiffEntry::getChangeType, DiffEntry::getOldPath, DiffEntry::getNewPath)
      .containsOnly(tuple(DiffEntry.ChangeType.RENAME, "fileA", "fileB"),
        tuple(DiffEntry.ChangeType.RENAME, "fileA", "fileC"));
  }

  private Collection<DiffEntry> getDiffEntries(String parentCommit, String childCommit) throws IOException {
    RevCommit parent = git.getRepository().parseCommit(ObjectId.fromString(parentCommit));
    RevCommit child = git.getRepository().parseCommit(ObjectId.fromString(childCommit));
    TreeWalk treeWalk = new TreeWalk(git.getRepository());
    treeWalk.setFilter(TreeFilter.ANY_DIFF);
    treeWalk.reset(parent.getTree(), child.getTree());
    return DiffEntry.scan(treeWalk);
  }
}