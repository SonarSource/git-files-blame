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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sonar.scm.git.GitUtils.createFile;
import static org.sonar.scm.git.GitUtils.deleteFile;
import static org.sonar.scm.git.GitUtils.moveFile;

/**
 * Verifies that blaming with a {@link BoundaryBlame} cache produces the exact same result as a full blame,
 * for the scenarios the short-circuit needs to get right: lines untouched since before the boundary, lines
 * rewritten after the boundary, files renamed after the boundary, files added after the boundary, and files
 * left completely untouched since before the boundary (moved to the boundary commit without ever being diffed).
 * <p>
 * Every scenario round-trips the cache through {@link BoundaryBlame#toByteArray()}/{@link BoundaryBlame#fromByteArray(byte[])}
 * before using it, since that's how a real caller (e.g. reading it back from a {@code ReadCache}) would obtain it.
 */
class BoundaryBlameIT extends AbstractGitIT {

  @Test
  void incrementalBlame_matchesFullBlame_forLinesRewrittenAfterBoundary() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2", "line3");
    String c1 = commit("fileA");
    createFile(baseDir, "fileA", "line1", "line2-v2", "line3");
    String boundaryCommit = commit("fileA");

    BoundaryBlame boundary = captureBoundary(boundaryCommit);

    createFile(baseDir, "fileA", "line1", "line2-v3", "line3");
    String c3 = commit("fileA");

    assertIncrementalMatchesFull(boundary);
  }

  @Test
  void incrementalBlame_matchesFullBlame_forFileUntouchedSinceBeforeBoundary() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2");
    String c1 = commit("fileA");
    createFile(baseDir, "fileB", "other");
    String boundaryCommit = commit("fileB");

    BoundaryBlame boundary = captureBoundary(boundaryCommit);

    // fileA isn't touched after the boundary; its FileCandidate should be moved unmodified all the way to the
    // boundary commit and resolved from the cache without ever being diffed again
    createFile(baseDir, "fileB", "other-v2");
    commit("fileB");

    assertIncrementalMatchesFull(boundary);
  }

  @Test
  void incrementalBlame_matchesFullBlame_forFileRenamedAfterBoundary() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2", "line3");
    String c1 = commit("fileA");
    createFile(baseDir, "fileA", "line1", "line2-v2", "line3");
    String boundaryCommit = commit("fileA");

    BoundaryBlame boundary = captureBoundary(boundaryCommit);

    moveFile(baseDir, "fileA", "fileA-renamed");
    createFile(baseDir, "fileA-renamed", "line1", "line2-v2", "line3-v2");
    rm("fileA");
    commit("fileA-renamed");

    assertIncrementalMatchesFull(boundary);
  }

  @Test
  void incrementalBlame_matchesFullBlame_forFileAddedAfterBoundary() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String boundaryCommit = commit("fileA");

    BoundaryBlame boundary = captureBoundary(boundaryCommit);

    createFile(baseDir, "fileB", "brand new file");
    commit("fileB");

    assertIncrementalMatchesFull(boundary);
  }

  @Test
  void incrementalBlame_matchesFullBlame_forFileDeletedThenRecreatedAfterBoundary() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2");
    String c1 = commit("fileA");
    createFile(baseDir, "fileB", "other");
    String boundaryCommit = commit("fileB");

    BoundaryBlame boundary = captureBoundary(boundaryCommit);

    deleteFile(baseDir, "fileA");
    rm("fileA");
    commit("fileA");
    createFile(baseDir, "fileA", "brand new content");
    commit("fileA");

    assertIncrementalMatchesFull(boundary);
  }

  private BoundaryBlame captureBoundary(String boundaryCommit) throws GitAPIException, IOException {
    ObjectId boundaryId = git.getRepository().resolve(boundaryCommit);
    BlameResult blameAtBoundary = new RepositoryBlameCommand(git.getRepository()).setStartCommit(boundaryId).call();
    BoundaryBlame captured = BoundaryBlame.capture(boundaryId, blameAtBoundary);
    return BoundaryBlame.fromByteArray(captured.toByteArray());
  }

  @Test
  void readFrom_whenNotABoundaryBlameCacheEntry_thenThrows() {
    assertThatThrownBy(() -> BoundaryBlame.readFrom(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5})))
      .isInstanceOf(IOException.class);
  }

  @Test
  void readFrom_whenFormatVersionIsNewer_thenThrows() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1");
    String boundaryCommit = commit("fileA");
    ObjectId boundaryId = git.getRepository().resolve(boundaryCommit);
    BlameResult blameAtBoundary = new RepositoryBlameCommand(git.getRepository()).setStartCommit(boundaryId).call();
    byte[] serialized = BoundaryBlame.capture(boundaryId, blameAtBoundary).toByteArray();

    // corrupt the format version byte (5th byte, right after the 4-byte magic header) to simulate a cache entry
    // written by a future, incompatible version of this library
    serialized[4] = Byte.MAX_VALUE;

    assertThatThrownBy(() -> BoundaryBlame.fromByteArray(serialized))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("version");
  }

  private void assertIncrementalMatchesFull(BoundaryBlame boundary) throws GitAPIException {
    BlameResult fullResult = new RepositoryBlameCommand(git.getRepository()).call();
    BlameResult incrementalResult = new RepositoryBlameCommand(git.getRepository()).setBoundaryBlame(boundary).call();

    assertThat(incrementalResult.getFileBlameByPath())
      .usingRecursiveComparison()
      .isEqualTo(fullResult.getFileBlameByPath());
  }
}
