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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.scm.git.blame.BlameResult.FileBlame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.scm.git.GitUtils.copyFile;
import static org.sonar.scm.git.GitUtils.createFile;
import static org.sonar.scm.git.GitUtils.createRepository;
import static org.sonar.scm.git.GitUtils.moveFile;

public class RepositoryBlameCommandTest {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private Path baseDir;
  private Git git;
  private RepositoryBlameCommand blame;

  @Before
  public void prepare() throws IOException {
    baseDir = createNewTempFolder();
    git = createRepository(baseDir);
    blame = new RepositoryBlameCommand(git.getRepository());
  }

  /**
   * This test fails if the most recent commits (oldest timestamp) in the queue aren't taken first.
   * If that wasn't the case, it would cause the algorithm to traverse all commits twice.
   * I believe the final blame result would be the same, though.
   * <pre>
   *     c1(r1,r2)
   *        |
   * (100 commits changing an unrelated file)
   *       |
   *   c2(r1,r2)
   *    /    \
   * c3(r1)  c4(r2)
   *     \  /
   *  c5(r1,r2)  <--- HEAD
   * </pre>
   */
  @Test
  public void consumes_queue_in_reverse_commit_time_order() throws IOException, GitAPIException {
    // TODO how to assert that we are not iterating >200 times?
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4");
    String c1 = commit("fileA");

    String c2 = null;
    for (int i = 0; i < 100; i++) {
      createFile(baseDir, "fileC", "commit " + i);
      c2 = commit("fileC");
    }

    createFile(baseDir, "fileA", "line3", "line4");
    String c3 = commit("fileA");

    resetHard(c2);
    createFile(baseDir, "fileA", "line1", "line2");
    String c4 = commit("fileA");

    merge(c3);
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4");
    git.add().addFilepattern("fileA").call();
    git.commit().setAmend(true).call();

    BlameResult result = blame.call();

  }

  /**
   * If there are no more parents, blame the commit for all remaining regions
   */
  @Test
  public void blames_initial_commit() throws GitAPIException, IOException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");
    BlameResult result = blame.call();
    assertAllBlameCommits(result, c1);
  }

  /**
   * fileA gets copied to fileC, and also renamed to fileB. One will be a RENAME, the other will be a COPY.
   * We should blame everything to the first commit that originally created fileA.
   * <pre>
   *      c1(fileA)
   *         |
   * c2(fileB,fileC)  <--- HEAD
   * </pre>
   */
  @Test
  public void detects_rename_and_copy() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4", "line5", "line6", "line7");
    String c1 = commit("fileA");

    copyFile(baseDir, "fileA", "fileB");
    moveFile(baseDir, "fileA", "fileC");
    rm("fileA");
    String c2 = commit("fileB", "fileC");

    BlameResult result = blame.call();

    assertThat(result.getFileBlames()).extracting(FileBlame::getPath).containsOnly("fileB", "fileC");
    assertAllBlameCommits(result, c1);
  }

  /**
   * In this scenario, a rename in the merge commit (while solving conflicts) shoudl not prevent the algorithm to follow the regions into both parents, ending up in the
   * same file. All blame should be on the first commit.
   * <pre>
   *            c1(fileA,fileB)
   *                /      \
   * c2(fileA[modified])  c3(fileB)
   *         \           /
   *         \       /
   *       c4(fileA) [conflict solved by merging fileA and fileB into fileA. Native git detects this as a rename of fileB]  <--- HEAD
   * </pre>
   */
  @Test
  public void maps_original_path_to_multiple_paths_with_rename_in_merge_commit() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2");
    createFile(baseDir, "fileB", "line3", "line4");
    String c1 = commitMsg("Create fileA and fileB", ".");

    rm("fileB");
    createFile(baseDir, "fileA", "line1");
    String c2 = commitMsg("rm fileB and delete line in fileA", ".");

    resetHard(c1);
    rm("fileA");
    String c3 = commitMsg("rm fileA", ".");

    merge(c2);

    rm("fileB");
    createFile(baseDir, "fileA", "line1", "line3", "line4");
    git.add().addFilepattern("fileA").call();
    String c4 = git.commit().call().getName();

    BlameResult result = blame.call();

    assertThat(result.getFileBlames()).extracting(FileBlame::getPath).containsOnly("fileA");
    assertAllBlameCommits(result, c1);
  }

  /**
   * In this scenario, regions in a file being blamed end up in multiple files in the same commit. All blame should be on the first commit.
   * Regions in a merge commit are blamed into both parents. Then, due to a rename, they end up in different files.
   * <pre>
   *  c1(fileA,fileB)
   *     /      \
   * c2(fileA)  c3(fileB)
   *     \     /
   *     |   c4(fileB renamed to fileA)
   *     \ /
   *    c5(fileA)  <--- HEAD
   * </pre>
   */
  @Test
  public void maps_original_path_to_multiple_paths() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2");
    createFile(baseDir, "fileB", "line3", "line4");
    String c1 = commit(".");

    rm("fileB");
    createFile(baseDir, "fileA", "line1");
    String c2 = commit(".");

    resetHard(c1);
    rm("fileA");
    String c3 = commit(".");

    rm("fileB");
    createFile(baseDir, "fileA", "line3", "line4");
    String c4 = commit("fileA");

    merge(c2);
    createFile(baseDir, "fileA", "line1", "line3", "line4");
    git.add().addFilepattern("fileA").call();
    String c5 = git.commit().call().getName();

    BlameResult result = blame.call();

    assertThat(result.getFileBlames()).extracting(FileBlame::getPath).containsOnly("fileA");
    assertAllBlameCommits(result, c1);
  }

  /**
   * In this scenario, there's a single file in all commits.
   * <pre>
   *   c1(r1,r2)
   *    /    \
   * c2(r1)  c3(r2)
   *     \  /
   *  c4(r1,r2)  <--- HEAD
   * </pre>
   */
  @Test
  public void merges_regions_from_two_commits_to_common_parent() throws GitAPIException, IOException {
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4");
    String c1 = commit("fileA");

    createFile(baseDir, "fileA", "line3", "line4");
    String c2 = commit("fileA");

    resetHard(c1);
    createFile(baseDir, "fileA", "line1", "line2");
    String c3 = commit("fileA");

    merge(c2);
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4");
    git.add().addFilepattern("fileA").call();
    git.commit().setAmend(true).call();

    BlameResult result = blame.call();

    assertThat(result.getFileBlames()).extracting(FileBlame::getPath).containsOnly("fileA");
    assertAllBlameCommits(result, c1);
  }

  private static void assertAllBlameCommits(BlameResult result, String expectedCommit) {
    Collection<String> allBlameCommits = result.getFileBlames().stream()
      .flatMap(f -> Arrays.stream(f.getCommits()))
      .map(AnyObjectId::getName)
      .collect(Collectors.toList());

    assertThat(allBlameCommits).containsOnly(expectedCommit);
  }

  /**
   * For whatever reason we can't add deleted files to the index with 'git add'. It needs to be done explicitly with 'git rm'.
   */
  private void rm(String... paths) throws GitAPIException {
    RmCommand add = git.rm();
    for (String p : paths) {
      add.addFilepattern(p);
    }
    add.call();
  }

  /**
   * returns null if there's a conflict
   */
  @CheckForNull
  private String merge(String commit) throws IOException, GitAPIException {
    ObjectId c = git.getRepository().resolve(commit);
    ObjectId newHead = git.merge().include(c).call().getNewHead();
    return newHead != null ? newHead.getName() : null;
  }

  private void resetHard(String commit) throws GitAPIException {
    git.reset()
      .setMode(ResetCommand.ResetType.HARD)
      .setRef(commit)
      .call();
  }

  private String commitMsg(String msg, String... paths) throws GitAPIException {
    AddCommand add = git.add();
    for (String p : paths) {
      add.addFilepattern(p);
    }
    add.call();
    RevCommit commit = git.commit().setCommitter("joe", "email@email.com").setMessage(msg).call();
    return commit.getName();
  }

  private String commit(String... paths) throws GitAPIException {
    AddCommand add = git.add();
    for (String p : paths) {
      add.addFilepattern(p);
    }
    add.call();
    RevCommit commit = git.commit().setCommitter("joe", "email@email.com").setMessage("msg").call();
    return commit.getName();
  }

  private Path createNewTempFolder() throws IOException {
    // This is needed for Windows, otherwise the created File point to invalid (shortened by Windows) temp folder path
    return temp.newFolder().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
  }
}
