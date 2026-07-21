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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.sonar.scm.git.blame.BlameResult.FileBlame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.sonar.scm.git.GitUtils.copyFile;
import static org.sonar.scm.git.GitUtils.createFile;
import static org.sonar.scm.git.GitUtils.deleteFile;
import static org.sonar.scm.git.GitUtils.moveFile;

class RepositoryBlameCommandIT extends AbstractGitIT {
  @Test
  void blame_whenCommittedSymlink_thenReturnNoBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");
    Path link = Files.createSymbolicLink(baseDir.resolve("fileB"), baseDir.resolve("fileA"));
    String c2 = commit("fileB");

    BlameResult result = blame.setFilePaths(Set.of("fileB")).call();
    org.eclipse.jgit.blame.BlameResult jgitResult = new BlameCommand(git.getRepository()).setFilePath("fileB").call();

    assertThat(jgitResult).isNull();
    assertThat(result.getFileBlames()).isEmpty();
  }

  @Test
  void blame_whenUncommittedSymlink_thenReturnNoBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");
    Path link = Files.createSymbolicLink(baseDir.resolve("fileB"), baseDir.resolve("fileA"));

    BlameResult result = blame.setFilePaths(Set.of("fileB")).call();
    org.eclipse.jgit.blame.BlameResult jgitResult = new BlameCommand(git.getRepository()).setFilePath("fileB").call();

    assertThat(jgitResult).isNull();
    assertThat(result.getFileBlames()).isEmpty();
  }

  @Test
  void blame_whenUncommittedFiles_thenReadWorkingDirWithFilteredInputStream() throws IOException, GitAPIException {
    git.getRepository().getConfig().setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null, "safecrlf", true);
    git.getRepository().getConfig().setString(ConfigConstants.CONFIG_CORE_SECTION, null, "autocrlf", "input");

    git.getRepository().getConfig().save();
    createFile(baseDir, "fileA", "line1\nline2");
    String c1 = commit("fileA");

    // change line termination, but the filtered input string should ignore the \r
    createFile(baseDir, "fileA", "line1\r\nline2");

    BlameResult result = blame.call();
    org.eclipse.jgit.blame.BlameResult jgitResult = new BlameCommand(git.getRepository()).setFilePath("fileA").call();
    List<String> jgitHashes = List.of(jgitResult.getSourceCommit(0).getName(), jgitResult.getSourceCommit(1).getName());

    assertThat(jgitHashes).containsExactly(c1, c1);
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[] {c1, c1}));
  }

  @Test
  void blame_whenUncommittedFiles_thenThereIsNoBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");

    createFile(baseDir, "fileB", "line2");

    BlameResult result = blame.setFilePaths(Set.of("fileB")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileB", new String[] {null}));
  }

  @Test
  void blame_whenUncommittedDeletedFiles_thenThereIsNoBlame() throws GitAPIException, IOException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");

    deleteFile(baseDir, "fileA");

    BlameResult result = blame.setFilePaths(Set.of("fileA")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .isEmpty();
  }

  @Test
  void blame_whenUncommittedRenamedFiles_thenThereIsNoBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");

    deleteFile(baseDir, "fileA");
    createFile(baseDir, "fileB", "line1");

    BlameResult result = blame.setFilePaths(Set.of("fileA", "fileB")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileB", new String[] {null}));
  }

  @Test
  void blame_whenUncommittedLines_thenLinesHaveNullBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line3");
    String c1 = commit("fileA");

    createFile(baseDir, "fileA", "line1", "line2", "line3");

    BlameResult result = blame.setFilePaths(Set.of("fileA")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[] {c1, null, c1}));
  }

  @Test
  void blame_whenUncommittedChangesIgnoredByTextComparator_thenHasNoEffectOnBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2");
    String c1 = commit("fileA");

    // had whitespace, which our diff formatter ignores
    createFile(baseDir, "fileA", "line1", "line2 ");

    BlameResult result = blame.setTextComparator(RawTextComparator.WS_IGNORE_ALL).setFilePaths(Set.of("fileA")).call();
    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[] {c1, c1}));
  }

  @Test
  void blame_whenFileRenamedAndFileFilterUsed_thenDetectRename() throws IOException, GitAPIException {
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
  void blame_whenFileMatchesTwoParents_thenPreferParentWithSameFilenameOverParentWithSameFileContent() throws GitAPIException, IOException {
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
  void blame_whenParentHasFileWithSameContent_thenFollowThatParent() throws IOException, GitAPIException {
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
  void blame_whenParentHasHasRenamedFileWithSameContent_thenFollowThatParent() throws IOException, GitAPIException {
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
  void blame_whenFilterUsed_thenOnlyBlameFilesInFilter() throws IOException, GitAPIException {
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
   * (5 commits changing an unrelated file)
   *       |
   *   c2(r1,r2)
   *    /    \
   * c3(r1)  c4(r2)
   *     \  /
   *  c5(r1,r2)  <--- HEAD
   * </pre>
   */
  @Test
  void blame_whenThereAreMultipleNodesInQueue_thenPickInReverseCommitTimeOrder() throws IOException, GitAPIException {
    long time = 10_000;
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4");
    String c1 = commit(time - 1000, "fileA");

    String c2 = null;
    for (int i = 0; i <5; i++) {
      createFile(baseDir, "fileC", "commit " + i);
      c2 = commit(time + i*1000, "fileC");
    }

    createFile(baseDir, "fileA", "line3", "line4");
    String c3 = commit(time + 20000, "fileA");

    resetHard(c2);
    createFile(baseDir, "fileA", "line1", "line2");
    String c4 = commit(time + 30000, "fileA");

    merge(c3);

    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4");
    git.add().addFilepattern("fileA").call();
    String c5 = git.commit().call().getName();

    MutableInt processedCommits = new MutableInt(0);
    blame.setProgressCallBack((iterationNb, commitHash) -> processedCommits.increment()).call();
    assertThat(processedCommits.intValue())
      .as("We shouldn't process more commits than the total of commits in the repo")
      // work dir + 4 + 5
      .isLessThan(11);
  }

  /**
   * If there are no more parents, blame the commit for all remaining regions
   */
  @Test
  void blame_whenInitialCommitCreatedChanges_thenBlameInitialCommit() throws GitAPIException, IOException {
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
  void blame_whenThereIsRenameAndCopy_thenBlameOriginalFile() throws IOException, GitAPIException {
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
  void blame_whenFileBlameEndsInMultiplePathsWithARename_thenFinalPathMapsToMultiplePaths() throws IOException, GitAPIException {
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
  void blame_whenFileBlameEndsInMultipleFiles_thenFinalPathMapsToMultiplePaths() throws IOException, GitAPIException {
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
  void blame_whenRegionsFromTwoCommitsEndInCommonParent_thenRegionsShouldBeMerged() throws GitAPIException, IOException {
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

  /**
   * Reproduces https://sonarsource.atlassian.net/browse/GFB-7 : a "Duplicate key" IllegalStateException
   * thrown by BlameGenerator.push() when a single blameParent call maps two different current-path
   * candidates for the same original path onto the same ancestor path.
   * <pre>
   *      c0(root,w)
   *      /        \
   * c1a(fileA,fileB)  c1w(w modified, root deleted)
   *   /        \                |
   * c2(fileA)  c3(fileB)        |
   *      \      \               |
   *       \    c3b(fileB renamed to fileA)
   *        \    /                |
   *        c4(fileA)-------------
   *              \
   *              c5(fileA,w)  <--- HEAD
   * </pre>
   * root is deleted in c1a and replaced by two exact copies of its content, fileA and fileB: the rename
   * detector pairs one of them as a RENAME and the other as a COPY, but both share the same old path
   * "root". fileA's blamed regions independently split across c2 and c3/c3b, then reconverge at c4, so c1a
   * ends up holding two candidates for the same original path at two different current paths (fileA and
   * fileB). When c1a is diffed against its own parent c0 in a single blameParent call, both of those
   * candidates independently resolve to path "root", producing two FileCandidate entries with an identical
   * (path, originalPath) key before push()'s queue-revisit merge (designed only for collisions arriving
   * from two separate calls) ever gets a chance to run.
   */
  @Test
  void blame_whenTwoDifferentPathsIndependentlyRenameFromTheSameAncestorPath_thenRegionsShouldMerge() throws IOException, GitAPIException {
    // Explicit, strictly increasing timestamps: BlameGenerator's traversal queue orders commits by time,
    // and same-second (default "now") timestamps on rapid-fire test commits can tie, breaking revisit
    // detection in a way unrelated to the bug this test targets.
    long t = 1_700_000_000_000L;

    createFile(baseDir, "root", "line1", "line2", "line3", "line4");
    createFile(baseDir, "w", "w1");
    String c0 = commit(t += 60_000, ".");

    rm("root");
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4");
    createFile(baseDir, "fileB", "line1", "line2", "line3", "line4");
    commit(t += 60_000, ".");
    String c1a = git.getRepository().resolve(Constants.HEAD).getName();

    createFile(baseDir, "fileA", "line1", "line2");
    rm("fileB");
    String c2 = commit(t += 60_000, ".");

    resetHard(c1a);
    createFile(baseDir, "fileB", "line3", "line4");
    rm("fileA");
    commit(t += 60_000, ".");

    rm("fileB");
    createFile(baseDir, "fileA", "line3", "line4");
    commit(t += 60_000, "fileA");

    merge(c2);
    rm("fileB");
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4");
    git.add().addFilepattern("fileA").call();
    String c4 = commit(t += 60_000);

    resetHard(c0);
    rm("root");
    createFile(baseDir, "w", "w1", "w2");
    String c1w = commit(t += 60_000, ".");

    resetHard(c4);
    merge(c1w);

    BlameResult result = blame.call();

    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(
        tuple("fileA", new String[] {c0, c0, c0, c0}),
        tuple("w", new String[] {c0, c1w}));
  }

  @Test
  void blame_whenContentGiven_thenLinesHaveNullBlame() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String c1 = commit("fileA");
    String unsavedContent = "line1\nnewLine\n";
    UnaryOperator<String> fileAContentProvider = filePath -> "fileA".equals(filePath) ? unsavedContent : null;

    BlameResult result = blame
      .setFilePaths(Set.of("fileA"))
      .setFileContentProvider(fileAContentProvider)
      .call();

    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .containsOnly(tuple("fileA", new String[]{c1, null}));
  }

  @Test
  void blame_whenLargeFileSetIncludesTrackedFileUnderGitignoredFolder_thenBlameIt() throws IOException, GitAPIException {
    // A committed file living under a .gitignore'd folder is invisible to a working directory walk, but it is still
    // part of the commit tree. When the number of files exceeds NB_FILES_THRESHOLD_ONE_TREE_WALK, initialization
    // must read its size from the object store, not from the working directory (which prunes ignored folders).
    createFile(baseDir, "src/WEB-INF/classes/tracked", "line1");
    String trackedCommit = commit("src/WEB-INF/classes/tracked");

    createFile(baseDir, ".gitignore", "classes/");
    commit(".gitignore");

    List<String> paths = new ArrayList<>();
    for (int i = 0; i < FileBlamer.NB_FILES_THRESHOLD_ONE_TREE_WALK; i++) {
      createFile(baseDir, "file" + i, "line1");
      paths.add("file" + i);
    }
    commit(paths.toArray(new String[0]));

    ObjectId head = git.getRepository().resolve(Constants.HEAD);
    BlameResult result = blame.setStartCommit(head).call();

    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getCommitHashes)
      .contains(tuple("src/WEB-INF/classes/tracked", new String[]{trackedCommit}));
  }

  /**
   * Author identity is read straight off the commit object; unlike native {@code git blame}, no {@code .mailmap}
   * resolution is applied (JGit has none - see <a href="https://github.com/eclipse-jgit/jgit/issues/260">jgit#260</a>).
   * If this test starts failing, JGit likely added mailmap support: update the README's "Blame semantics vs native
   * git" section and the native-blame-comparison ITs' mailmap neutralization ({@code ScenarioRunner}) accordingly.
   */
  @Test
  void blame_whenRepoHasMailmap_thenAuthorEmailIsNotRemapped() throws IOException, GitAPIException {
    createFile(baseDir, ".mailmap", "Canonical Name <canonical@example.com> <email@email.com>");
    commit(".mailmap");
    createFile(baseDir, "fileA", "line1");
    commit(1_000L, "fileA");

    BlameResult result = blame.setFilePaths(Set.of("fileA")).call();

    assertThat(result.getFileBlames()).extracting(FileBlame::getPath, FileBlame::getAuthorEmails)
      .containsOnly(tuple("fileA", new String[]{"email@email.com"}));
  }

  private static void assertAllBlameCommits(BlameResult result, String expectedCommit) {
    Collection<String> allBlameCommits = result.getFileBlames().stream()
      .flatMap(f -> Arrays.stream(f.getCommitHashes()))
      .toList();

    assertThat(allBlameCommits).containsOnly(expectedCommit);
  }
}
