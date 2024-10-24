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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
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

    try (var treeWalk = new TreeWalk(repository)) {
      prepareTreeWalk(treeWalk);
      treeWalk.setFilter(PathFilter.create(path));

      if (treeWalk.next()) {
        var iter = treeWalk.getTree(0, AbstractTreeIterator.class);
        if ((iter.getEntryRawMode() & TYPE_MASK) == TYPE_FILE) {
          return loadText(path, treeWalk, iter);
        }
      }
    }
    throw new IllegalStateException("Failed to find file in the working directory: " + path);
  }

  private void prepareTreeWalk(TreeWalk treeWalk) throws IOException {
    treeWalk.setRecursive(true);

    if (repository.isBare()) {
      // Use RevWalk to find the commit and get the tree
      try (RevWalk revWalk = new RevWalk(repository)) {
        var headId = repository.resolve(Constants.HEAD);
        RevCommit commit = revWalk.parseCommit(headId);
        treeWalk.addTree(commit.getTree().getId());
      }
    } else {
      treeWalk.addTree(new FileTreeIterator(repository));
    }
  }

  private RawText loadText(String path, TreeWalk treeWalk, AbstractTreeIterator iter) throws IOException {
    return ofNullable(fileContentProvider)
      .map(fcp -> fcp.apply(path))
      //use the given content
      .map(fileContent -> new RawText(fileContent.getBytes(StandardCharsets.UTF_8)))
      //read from repo
      .orElse(loadTextFromRepo(treeWalk, iter));
  }

  private RawText loadTextFromRepo(TreeWalk treeWalk, AbstractTreeIterator iter) throws IOException {
    if (repository.isBare()) {
      var loader = repository.open(treeWalk.getObjectId(0));
      return new RawText(loader.getBytes());
    }
    return loadTextFromFile((FileTreeIterator) iter);
  }

  private static RawText loadText(ObjectReader objectReader, ObjectId objectId) throws IOException {
    // No support for git Large File Storage (LFS). See implementation in Candidate#loadText
    ObjectLoader open = objectReader.open(objectId, Constants.OBJ_BLOB);
    return new RawText(open.getCachedBytes(Integer.MAX_VALUE));
  }

  private static RawText loadTextFromFile(FileTreeIterator iter) throws IOException {
    try (InputStream is = iter.openEntryStream()) {
      return new RawText(is.readAllBytes());
    }
  }

  Map<String, Integer> getFileSizes(Set<String> files) throws IOException {
    Map<String, Integer> result = new HashMap<>();
    try (var treeWalk = new TreeWalk(repository)) {
      prepareTreeWalk(treeWalk);
      while (treeWalk.next()) {
        AbstractTreeIterator iter = treeWalk.getTree(0, AbstractTreeIterator.class);
        if (files.contains(iter.getEntryPathString()) && (iter.getEntryRawMode() & TYPE_MASK) == TYPE_FILE) {
          var rawText = loadTextFromRepo(treeWalk, iter);
          result.put(iter.getEntryPathString(), rawText.size());
        }
      }
    }

    return result;
  }
}
