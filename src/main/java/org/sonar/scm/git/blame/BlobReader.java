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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import static java.util.Optional.ofNullable;
import static org.eclipse.jgit.lib.FileMode.TYPE_FILE;
import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;

/**
 * Reads the contents of an object from git storage (typically a file)
 */
public class BlobReader {
  private final Repository repository;
  private final UnaryOperator<String> fileContentProvider;

  public BlobReader(Repository repository) {
    this(repository, null);
  }

  public BlobReader(Repository repository, @Nullable UnaryOperator<String> fileContentProvider) {
    this.repository = repository;
    this.fileContentProvider = fileContentProvider;
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

  private RawText loadText(String path) throws IOException {
    // we use a TreeWalk to find the file, instead of simply accessing the file in the FS, so that we can use the
    // FileTreeIterator's InputStream, which filters certain characters. For example, it removes windows lines terminators
    // in files checked out on Windows.
    TreeWalk treeWalk = new TreeWalk(repository);
    treeWalk.addTree(new FileTreeIterator(repository));
    treeWalk.setRecursive(true);
    treeWalk.setFilter(PathFilter.create(path));
    if (treeWalk.next()) {
      FileTreeIterator iter = treeWalk.getTree(0, FileTreeIterator.class);
      if ((iter.getEntryRawMode() & TYPE_MASK) == TYPE_FILE) {
        return loadText(path, iter);
      }
    }
    throw new IllegalStateException("Failed to find file in the working directory: " + path);
  }

  private static RawText loadText(ObjectReader objectReader, ObjectId objectId) throws IOException {
    // No support for git Large File Storage (LFS). See implementation in Candidate#loadText
    ObjectLoader open = objectReader.open(objectId, Constants.OBJ_BLOB);
    return new RawText(open.getCachedBytes(Integer.MAX_VALUE));
  }

  private RawText loadText(String path, FileTreeIterator iter) throws IOException {
    String fileContent = getFileContent(path);
    if (fileContent != null) {
      //use the given content
      return new RawText(fileContent.getBytes(StandardCharsets.UTF_8));
    }
    //read from disk
    return loadTextFromFile(iter);
  }

  private String getFileContent(String path) {
    return ofNullable(fileContentProvider)
      .map(fcp -> fcp.apply(path))
      .orElse(null);
  }

  private static RawText loadTextFromFile(FileTreeIterator iter) throws IOException {
    try (InputStream is = iter.openEntryStream()) {
      return new RawText(is.readAllBytes());
    }
  }

  Map<String, Integer> getFileSizes(Set<String> files) throws IOException {
    Map<String, Integer> result = new HashMap<>();
    TreeWalk treeWalk = new TreeWalk(repository);
    treeWalk.addTree(new FileTreeIterator(repository));
    treeWalk.setRecursive(true);

    while (treeWalk.next()) {
      FileTreeIterator iter = treeWalk.getTree(0, FileTreeIterator.class);
      if (files.contains(iter.getEntryPathString()) && (iter.getEntryRawMode() & TYPE_MASK) == TYPE_FILE) {
        try (InputStream is = iter.openEntryStream()) {
          RawText rawText = new RawText(is.readAllBytes());
          result.put(iter.getEntryPathString(), rawText.size());
        }
      }
    }
    return result;
  }
}
