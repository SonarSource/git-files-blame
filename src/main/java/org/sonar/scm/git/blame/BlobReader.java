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
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

/**
 * Reads the contents of an object from git storage (typically a file)
 */
public class BlobReader {
  private final Repository repository;

  public BlobReader(Repository repository) {
    this.repository = repository;
  }

  /**
   * Load the contents of the file represented by a {@link FileCandidate}.
   * If the objectId is not zero, it will be used to access the file contents. Otherwise, the path
   * is used, assuming that it represents a file in the working directory.
   */
  public RawText loadText(ObjectReader objectReader, FileCandidate fc) {
    try {
      if (ObjectId.zeroId().equals(fc.getBlob())) {
        return loadText(fc.getOriginalPath());
      } else {
        return loadText(objectReader, fc.getBlob());
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static RawText loadText(ObjectReader objectReader, ObjectId objectId) throws IOException {
    // No support for git Large File Storage (LFS). See implementation in Candidate#loadText
    ObjectLoader open = objectReader.open(objectId, Constants.OBJ_BLOB);
    return new RawText(open.getCachedBytes(Integer.MAX_VALUE));
  }

  private RawText loadText(String path) throws IOException {
    return new RawText(getBytes(path));
  }

  private byte[] getBytes(String path) throws IOException {
    Path filePath = repository.getWorkTree().toPath().resolve(path);
    if (Files.isSymbolicLink(filePath)) {
      filePath = Files.readSymbolicLink(filePath);
    }

    return Files.readAllBytes(filePath);
  }
}
