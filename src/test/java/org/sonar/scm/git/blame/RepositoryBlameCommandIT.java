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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Test;
import org.sonar.scm.git.blame.BlameResult.FileBlame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.sonar.scm.git.GitUtils.copyFile;
import static org.sonar.scm.git.GitUtils.createFile;
import static org.sonar.scm.git.GitUtils.deleteFile;
import static org.sonar.scm.git.GitUtils.moveFile;

public class RepositoryBlameCommandIT extends AbstractGitIT {
  @Test
  public void blame_whenUncommittedFiles_thenThereIsNoBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");

    createFile(baseDir, "fileB", "line2");

    BlameResult result = blame.setFilePaths(Set.of("fileB")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileB", new String[] {null}));
  }

  @Test
  public void blame_whenUncommittedDeletedFiles_thenThereIsNoBlame() throws GitAPIException, IOException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");

    deleteFile(baseDir, "fileA");

    BlameResult result = blame.setFilePaths(Set.of("fileA")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .isEmpty();
  }

  @Test
  public void test() throws IOException, GitAPIException {

    FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
    repositoryBuilder.setMustExist( true );
    repositoryBuilder.setGitDir(new File("/home/leo.geoffroy/dev/tmp/testCRLF2/.git"));
    Repository repository = repositoryBuilder.build();

    BlameResult result = new RepositoryBlameCommand(repository)
      .setTextComparator(RawTextComparator.DEFAULT)
      .call();

    org.eclipse.jgit.blame.BlameResult result2 = new BlameCommand(repository)
      .setTextComparator(RawTextComparator.DEFAULT)
      .setFilePath("test.txt").call();

    System.out.println(result);
    System.out.println(result2);
  }

  @Test
  public void blame_whenUncommittedRenamedFiles_thenThereIsNoBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");

    deleteFile(baseDir, "fileA");
    createFile(baseDir, "fileB", "line1");

    BlameResult result = blame.setFilePaths(Set.of("fileA", "fileB")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileB", new String[] {null}));
  }

  @Test
  public void blame_whenUncommittedLines_thenLinesHaveNullBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line3");
    String c1 = commit("fileA");

    createFile(baseDir, "fileA", "line1", "line2", "line3");

    BlameResult result = blame.setFilePaths(Set.of("fileA")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[] {c1, null, c1}));
  }

  @Test
  public void blame_whenUncommittedChangesIgnoredByTextComparator_thenHasNoEffectOnBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2");
    String c1 = commit("fileA");

    // had whitespace, which our diff formatter ignores
    createFile(baseDir, "fileA", "line1", "line2 ");

    BlameResult result = blame.setTextComparator(RawTextComparator.WS_IGNORE_ALL).setFilePaths(Set.of("fileA")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[] {c1, c1}));
  }

  @Test
  public void blame_whenFileRenamedAndFileFilterUsed_thenDetectRename() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    createFile(baseDir, "fileB", "line2");
    String c1 = commit("fileA", "fileB");

    createFile(baseDir, "fileC", "line2");
    rm("fileB");
    String c2 = commit("fileC");

    BlameResult result = blame.setFilePaths(Set.of("fileC")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileC", new String[] {c1}));
  }

  /**
   * When a file in a merge content has files in multiple parents, we should prefer to match it with a parent where the matching file
   * has the same name, if there's any. That should be the case even when it's not the first parent.
   * All files have the same content:
   * <pre>
   *         c1
   *        /  \
   * c2 (fileA) \
   *      \     c3 (fileB)
   *       \  /
   *         c4 (fileA)
   * </pre>
   */
  @Test
  public void blame_whenFileMatchesTwoParents_thenPreferParentWithSameFilenameOverParentWithSameFileContent() throws GitAPIException, IOException {
    String c1 = commit();

    createFile(baseDir, "fileA", "line1", "line2");
    String c2 = commit("fileA");

    resetHard(c1);
    createFile(baseDir, "fileB", "line1", "line2");
    String c3 = commit("fileB");

    merge(c2);
    rm("fileB");
    BlameResult result = blame.setFilePaths(Set.of("fileA")).call();

    // prefer to pick c2 (same content and same file name) over c3 (same content but different file name)
    assertThat(result.getFileBlames())
      .extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[] {c2, c2}));
  }

  /**
   * If a file in a merge commit matches the file in one of its parents, all the regions should move to that parent.
   * In other words, if any single parent exactly matches the merge, follow only that one parent through history.
   * <pre>
   *            c1 ()
   *           |    \
   * c2 (line1,line3) \
   *       |        c3 (line1,line2)
   *       |     /
   *       | /
   *       c4 (line1,line2) <----- HEAD
   * </pre>
   * In this test, all regions should be moved to c4. Line1 should not be moved to c3.
   */
  @Test
  public void blame_whenParentHasFileWithSameContent_thenFollowThatParent() throws IOException, GitAPIException {
    String c1 = commit();

    createFile(baseDir, "fileA", "line1", "line3");
    String c2 = commit("fileA");

    // branch from c1
    resetHard(c1);
    createFile(baseDir, "fileA", "line1", "line2");
    String c3 = commit("fileA");

    // merge
    resetHard(c2);
    String c4 = merge(c3);
    createFile(baseDir, "fileA", "line1", "line2");
    git.add().addFilepattern("fileA").call();
    git.commit().setMessage("merge").call();

    BlameResult result = blame.setFilePaths(Set.of("fileA")).call();

    assertThat(result.getFileBlames())
      .extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[] {c3, c3}));
  }

  /**
   * If a file in a merge commit matches the file in one of its parents, all the regions should move to that parent, even if it was renamed.
   * In other words, if any single parent exactly matches the merge, follow only that one parent through history.
   * <pre>
   *            c1 ()
   *           |    \
   * c2 (line1,line3) \
   *       |        c3 (line1,line2)
   *       |     /
   *       | /
   *       c4 (line1,line2) <----- HEAD
   * </pre>
   * In this test, all regions should be moved to c4. Line1 should not be moved to c3.
   */
  @Test
  public void blame_whenParentHasHasRenamedFileWithSameContent_thenFollowThatParent() throws IOException, GitAPIException {
    String c1 = commit();

    createFile(baseDir, "fileA", "line1", "line3");
    String c2 = commit("fileA");

    // branch from c1
    resetHard(c1);
    createFile(baseDir, "fileB", "line1", "line2");
    String c3 = commit("fileB");

    // merge
    resetHard(c2);
    String c4 = merge(c3);
    rm("fileB");
    createFile(baseDir, "fileA", "line1", "line2");
    git.add().addFilepattern("fileA").call();
    git.commit().setAmend(true).setMessage("merge").call();

    BlameResult result = blame.setFilePaths(Set.of("fileA")).call();

    assertThat(result.getFileBlames())
      .extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[] {c3, c3}));
  }

  @Test
  public void blame_whenFilterUsed_thenOnlyBlameFilesInFilter() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    createFile(baseDir, "fileB", "line1");
    createFile(baseDir, "fileC", "line1");
    String c1 = commit("fileA", "fileB", "fileC");
    BlameResult result = blame.setFilePaths(Set.of("fileA", "fileB")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath).containsOnly("fileA", "fileB");
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
  public void blame_whenThereAreMultipleNodesInQueue_thenPickInReverseCommitTimeOrder() throws IOException, GitAPIException {
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

    MutableInt processedCommits = new MutableInt(0);
    blame.setProgressCallBack((iterationNb, commitHash) -> processedCommits.increment())
      .call();
    assertThat(processedCommits.getValue())
      .as("We shouldn't process more commits than the total of commits in the repo")
      .isLessThan(105);

  }

  /**
   * If there are no more parents, blame the commit for all remaining regions
   */
  @Test
  public void blame_whenInitialCommitCreatedChanges_thenBlameInitialCommit() throws GitAPIException, IOException {
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
  public void blame_whenThereIsRenameAndCopy_thenBlameOriginalFile() throws IOException, GitAPIException {
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
   *       c4(fileA) [conflict solved by merging fileA and fileB into fileA]  <--- HEAD
   * </pre>
   */
  @Test
  public void blame_whenFileBlameEndsInMultiplePathsWithARename_thenFinalPathMapsToMultiplePaths() throws IOException, GitAPIException {
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
  public void blame_whenFileBlameEndsInMultipleFiles_thenFinalPathMapsToMultiplePaths() throws IOException, GitAPIException {
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
  public void blame_whenRegionsFromTwoCommitsEndInCommonParent_thenRegionsShouldBeMerged() throws GitAPIException, IOException {
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
      .flatMap(f -> Arrays.stream(f.getCommitHashes()))
      .collect(Collectors.toList());

    assertThat(allBlameCommits).containsOnly(expectedCommit);
  }
}
