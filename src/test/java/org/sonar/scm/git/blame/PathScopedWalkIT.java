/*
 * Git Files Blame
 * Copyright (C) SonarSource Sàrl
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
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.sonar.scm.git.blame.BlameResult.FileBlame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.scm.git.GitUtils.createFile;
import static org.sonar.scm.git.GitUtils.deleteFile;
import static org.sonar.scm.git.GitUtils.moveFile;

/**
 * The path-scoped walk is an optimization: it must produce exactly the same blame as the regular walk, only faster.
 * These tests build histories that exercise the tricky cases - lots of commits that don't touch the blamed
 * subfolder (which get skipped), a merge, and a rename inside the subfolder - and assert both walks agree
 * line-for-line.
 */
class PathScopedWalkIT extends AbstractGitIT {

  @Test
  void pathScopedWalk_producesSameBlameAsRegularWalk_withNoiseCommitsAndInFolderRename() throws IOException, GitAPIException {
    createFile(baseDir, "src/a.txt", "a-l1", "a-l2", "a-l3");
    createFile(baseDir, "src/b.txt", "b-l1", "b-l2");
    createFile(baseDir, "root.txt", "root-l1");
    commit("src/a.txt", "src/b.txt", "root.txt");

    // A long run of commits that never touch src/ - exactly what the path-scoped walk should skip over.
    for (int i = 0; i < 20; i++) {
      createFile(baseDir, "docs/doc" + i + ".txt", "doc " + i + " content");
      commit("docs/doc" + i + ".txt");
    }

    createFile(baseDir, "src/a.txt", "a-l1", "a-l2-changed", "a-l3");
    commit("src/a.txt");

    for (int i = 0; i < 20; i++) {
      createFile(baseDir, "root.txt", "root-l1", "root change " + i);
      commit("root.txt");
    }

    // Rename inside the blamed subfolder, with a content change so it isn't a trivial blob-id match.
    moveFile(baseDir, "src/b.txt", "src/b_renamed.txt");
    createFile(baseDir, "src/b_renamed.txt", "b-l1", "b-l2", "b-l3-added");
    commit("src/b.txt", "src/b_renamed.txt");

    Set<String> blamed = Set.of("src/a.txt", "src/b_renamed.txt");
    assertSameBlame(blamed);
  }

  @Test
  void pathScopedWalk_producesSameBlameAsRegularWalk_acrossAMerge() throws IOException, GitAPIException {
    createFile(baseDir, "src/a.txt", "a-l1", "a-l2");
    createFile(baseDir, "src/c.txt", "c-l1");
    String base = commit("src/a.txt", "src/c.txt");

    // Feature branch touches src/, then diverges from main which only touches unrelated files.
    createFile(baseDir, "src/a.txt", "a-l1-feature", "a-l2");
    commit("src/a.txt");
    String feature = git.getRepository().resolve("HEAD").getName();

    resetHard(base);
    for (int i = 0; i < 10; i++) {
      createFile(baseDir, "other/o" + i + ".txt", "o " + i);
      commit("other/o" + i + ".txt");
    }
    createFile(baseDir, "src/c.txt", "c-l1", "c-l2-main");
    commit("src/c.txt");

    merge(feature);

    assertSameBlame(Set.of("src/a.txt", "src/c.txt"));
  }

  /**
   * A diamond where both sides of a merge leave the blamed subfolder untouched, so both merge parents collapse
   * towards the same ancestor. Exercises the case where naively collapsing a merge's parents would create
   * duplicate parents and corrupt the region merging.
   */
  @Test
  void pathScopedWalk_producesSameBlameAsRegularWalk_whenBothMergeSidesLeaveFolderUntouched() throws IOException, GitAPIException {
    createFile(baseDir, "src/a.txt", "a-l1", "a-l2", "a-l3");
    String base = commit("src/a.txt");

    // Branch 1: only touches other/, never src/.
    for (int i = 0; i < 5; i++) {
      createFile(baseDir, "other/o" + i + ".txt", "o " + i);
      commit("other/o" + i + ".txt");
    }
    String branch1 = git.getRepository().resolve("HEAD").getName();

    // Branch 2 from base: only touches docs/, never src/.
    resetHard(base);
    for (int i = 0; i < 5; i++) {
      createFile(baseDir, "docs/d" + i + ".txt", "d " + i);
      commit("docs/d" + i + ".txt");
    }

    merge(branch1);

    assertSameBlame(Set.of("src/a.txt"));
  }

  /**
   * The blamed subfolder didn't exist earlier in history: the files were moved into it by a directory rename. The
   * blame must follow the rename back to the old paths and attribute changes made there - the path-scoped walk must
   * not skip over those commits just because the (current) subfolder is absent then.
   */
  @Test
  void pathScopedWalk_producesSameBlameAsRegularWalk_whenSubfolderWasRenamedFromElsewhere() throws IOException, GitAPIException {
    createFile(baseDir, "old/dir/a.txt", "a-l1", "a-l2", "a-l3");
    commit("old/dir/a.txt");

    // A change made while the file still lived under old/dir - blame must attribute a line here, not skip it.
    createFile(baseDir, "old/dir/a.txt", "a-l1", "a-l2-changed-before-move", "a-l3");
    commit("old/dir/a.txt");

    // Directory rename: old/dir/a.txt -> new/dir/a.txt (the blamed subfolder new/dir appears only now).
    createFile(baseDir, "new/dir/a.txt", "a-l1", "a-l2-changed-before-move", "a-l3");
    deleteFile(baseDir, "old/dir/a.txt");
    rm("old/dir/a.txt");
    commit("old/dir/a.txt", "new/dir/a.txt");

    createFile(baseDir, "new/dir/a.txt", "a-l1", "a-l2-changed-before-move", "a-l3", "a-l4-after-move");
    commit("new/dir/a.txt");

    for (int i = 0; i < 10; i++) {
      createFile(baseDir, "unrelated/u" + i + ".txt", "u " + i);
      commit("unrelated/u" + i + ".txt");
    }

    assertSameBlame(Set.of("new/dir/a.txt"));
  }

  /**
   * A merge where the blamed file was created on one branch, so it looks added relative to the other parent and
   * triggers the rename fallback there - but is carried unchanged by the branch that created it. The path-scoped
   * rename fallback must recognise this and skip the full-repository diff, while still matching the regular blame.
   */
  @Test
  void pathScopedWalk_producesSameBlameAsRegularWalk_whenBlamedFileAddedOnOneMergeBranch() throws IOException, GitAPIException {
    createFile(baseDir, "src/existing.txt", "e-l1");
    createFile(baseDir, "other/orig.txt", "m-l1", "m-l2");
    String base = commit("src/existing.txt", "other/orig.txt");

    // Feature branch creates src/feature.txt (a blamed file) and edits it.
    createFile(baseDir, "src/feature.txt", "f-l1", "f-l2");
    commit("src/feature.txt");
    createFile(baseDir, "src/feature.txt", "f-l1", "f-l2-edited");
    commit("src/feature.txt");
    String feature = git.getRepository().resolve("HEAD").getName();

    // Main branch only renames an unrelated file across folders (noise the fallback would otherwise chase).
    resetHard(base);
    createFile(baseDir, "lib/moved.txt", "m-l1", "m-l2");
    deleteFile(baseDir, "other/orig.txt");
    rm("other/orig.txt");
    commit("lib/moved.txt", "other/orig.txt");

    merge(feature);

    assertSameBlame(Set.of("src/existing.txt", "src/feature.txt"));
  }

  /**
   * A file is renamed INTO an already-existing blamed subfolder from an outside path that was modified in a commit
   * the path-scoped walk would collapse. The rename source's content changed in the skipped commit, so resolving the
   * rename against the collapsed ancestor (instead of the real parent) would mis-attribute those lines. The
   * path-scoped walk must still match the regular blame.
   */
  @Test
  void pathScopedWalk_producesSameBlameAsRegularWalk_whenFileRenamedIntoFolderWithSourceEditedInCollapsedCommit() throws IOException, GitAPIException {
    createFile(baseDir, "lib/x.txt", "l1", "l2");
    createFile(baseDir, "src/keep.txt", "k1");
    commit("lib/x.txt", "src/keep.txt");

    // Edits the rename source while it still lives outside the blamed folder - this commit doesn't touch src/, so
    // the path-scoped walk collapses it.
    createFile(baseDir, "lib/x.txt", "l1", "l2-edited");
    commit("lib/x.txt");

    // Rename lib/x.txt -> src/x.txt into the pre-existing blamed folder, with no content change.
    moveFile(baseDir, "lib/x.txt", "src/x.txt");
    rm("lib/x.txt");
    commit("lib/x.txt", "src/x.txt");

    assertSameBlame(Set.of("src/x.txt", "src/keep.txt"));
  }

  private void assertSameBlame(Set<String> blamedPaths) throws GitAPIException {
    BlameResult unscoped = blameWith(blamedPaths, false);
    BlameResult scoped = blameWith(blamedPaths, true);
    assertSameAs(unscoped, scoped, "path-scoped");
  }

  private BlameResult blameWith(Set<String> blamedPaths, boolean pathScoping) throws GitAPIException {
    return new RepositoryBlameCommand(git.getRepository())
      .setFilePaths(blamedPaths)
      .setPathScoping(pathScoping)
      .call();
  }

  private static void assertSameAs(BlameResult expected, BlameResult actual, String mode) {
    Map<String, FileBlame> expectedByPath = expected.getFileBlameByPath();
    Map<String, FileBlame> actualByPath = actual.getFileBlameByPath();

    assertThat(actualByPath.keySet()).as("blamed paths (%s)", mode).isEqualTo(expectedByPath.keySet());
    for (String path : expectedByPath.keySet()) {
      FileBlame expectedBlame = expectedByPath.get(path);
      FileBlame actualBlame = actualByPath.get(path);
      assertThat(actualBlame.getCommitHashes()).as("commit hashes for %s (%s)", path, mode).isEqualTo(expectedBlame.getCommitHashes());
      assertThat(actualBlame.getAuthorEmails()).as("author emails for %s (%s)", path, mode).isEqualTo(expectedBlame.getAuthorEmails());
      assertThat(actualBlame.getCommitDates()).as("commit dates for %s (%s)", path, mode).isEqualTo(expectedBlame.getCommitDates());
    }
  }
}
