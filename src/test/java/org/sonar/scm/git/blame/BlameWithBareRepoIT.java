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

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.groups.Tuple.tuple;
import static org.sonar.scm.git.GitUtils.createFile;

public class BlameWithBareRepoIT extends AbstractGitIT {

  private RepositoryBlameCommand bareRepoBlame;

  @Before
  public void prepareForBare() throws IOException, GitAPIException, URISyntaxException {
    var bareDir = createNewTempFolder();
    var bareGit = Git.init().setBare(true).setDirectory(bareDir.toFile()).call();

    git.remoteAdd()
      .setName("origin")
      .setUri(new URIish(bareDir.toUri().toString()))
      .call();

    bareRepoBlame = new RepositoryBlameCommand(bareGit.getRepository());
  }

  private void push() throws GitAPIException {
    git.push().setRemote("origin").call();
  }

  @Test
  public void blame_whenRemoteRepoIsBare() throws IOException, GitAPIException {
    createFile(baseDir, "file1", "line1", "line2");
    var c1 = commit("file1");
    push();

    var result = bareRepoBlame.setFilePaths(Set.of("file1")).call();
    assertThat(result.getFileBlames()).extracting(BlameResult.FileBlame::getPath, BlameResult.FileBlame::getCommitHashes)
      .containsOnly(tuple("file1", new String[]{c1, c1}));

    createFile(baseDir, "file1", "line1", "line2Modified");
    var c2 = commit("file1");
    push();

    result = bareRepoBlame.setFilePaths(Set.of("file1")).call();
    assertThat(result.getFileBlames()).extracting(BlameResult.FileBlame::getPath, BlameResult.FileBlame::getCommitHashes)
      .containsOnly(tuple("file1", new String[]{c1, c2}));
  }


  @Test
  public void getFileSize_noError() throws IOException, GitAPIException {
    createFile(baseDir, "file1", "line1");
    createFile(baseDir, "file2", "line1", "line2");

    //This file will be present in the repository, but we will not ask for its size
    createFile(baseDir, "file3", "line1", "line2", "line3");

    commit("file1", "file2");
    push();

    var reader = new BlobReader(bareRepoBlame.getRepository());
    reader.getFileSizes(Set.of("file1", "file2"));

    assertThat(reader.getFileSizes(Set.of("file1", "file2"))).size().isEqualTo(2);
    assertThat(reader.getFileSizes(Set.of("file1", "file2"))).containsOnly(entry("file1", 1), entry("file2", 2));
  }
}
