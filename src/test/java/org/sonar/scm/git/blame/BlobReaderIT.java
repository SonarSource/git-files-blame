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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.junit.Assert;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BlobReaderIT extends AbstractGitIT {
  @Test
  public void loadText_whenWorkDirectoryAndFileDoesntExist_shouldThrowISE() {
    BlobReader reader = new BlobReader(git.getRepository());
    ObjectReader objectReader = git.getRepository().newObjectReader();
    FileCandidate fc = mock(FileCandidate.class);
    when(fc.getBlob()).thenReturn(ObjectId.zeroId());
    when(fc.getOriginalPath()).thenReturn("invalid");

    Assert.assertThrows(IllegalStateException.class, () -> reader.loadText(objectReader, fc));
  }

  @Test
  public void loadText_whenWorkDirectoryHasDirectories_shouldIgnoreDirs() throws IOException {
    Path fileInDir = baseDir.resolve("dir/file");
    Files.createDirectories(fileInDir.getParent());
    Files.write(fileInDir, List.of("line1"));
    BlobReader reader = new BlobReader(git.getRepository());
    ObjectReader objectReader = git.getRepository().newObjectReader();
    FileCandidate fc = mock(FileCandidate.class);
    when(fc.getBlob()).thenReturn(ObjectId.zeroId());
    when(fc.getOriginalPath()).thenReturn("dir/file");
    assertThatNoException().isThrownBy(() -> reader.loadText(objectReader, fc));
  }

  @Test
  public void getFileSize_noError() throws IOException {
    Path file1 = baseDir.resolve("file1");
    Files.write(file1, List.of("line1"));
    Path file2 = baseDir.resolve("file2");
    Files.write(file2, List.of("line1", "line2"));

    //This file will be present in the repository, but we will not ask for its size
    Path file3 = baseDir.resolve("file3");
    Files.write(file3, List.of("line1", "line2", "line3"));


    BlobReader reader = new BlobReader(git.getRepository());
    reader.getFileSizes(Set.of("file1", "file2"));

    assertThat(reader.getFileSizes(Set.of("file1", "file2"))).size().isEqualTo(2);
    assertThat(reader.getFileSizes(Set.of("file1", "file2"))).containsOnly(entry("file1", 1), entry("file2", 2));
  }
}
