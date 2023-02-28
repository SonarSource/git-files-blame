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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import static org.eclipse.jgit.lib.FileMode.TYPE_FILE;
import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;

public class FileTreeComparator {
  /**
   * If the number of files we are interested in is smaller than this threshold, create a filter to only look
   * for these files. Creating the filter is expensive and is not worth it for large number of files.
   */
  private final static int THRESHOLD_FILTER_FILES = 100;

  private final MutableObjectId idBuf = new MutableObjectId();
  private final FilteredRenameDetector filteredRenameDetector;

  private TreeWalk treeWalk;
  private TreeFilter filesAndAnyDiffFilter = null;
  private Set<String> filterFilePaths = null;

  public FileTreeComparator(FilteredRenameDetector filteredRenameDetector) {
    this.filteredRenameDetector = filteredRenameDetector;
  }

  public void initialize(ObjectReader objectReader) {
    treeWalk = new TreeWalk(objectReader);
    treeWalk.setRecursive(true);
  }

  /**
   * The strategy is to first try to find the files to blame in the parent commit, with the same paths.
   * If any file can't be found (meaning that it was added by the child commit), we need to compute all the files added and
   * removed between the parent and child commits, so that we can run the rename detector.
   */
  public List<DiffFile> compute(RevCommit parent, RevCommit child, Set<String> filePaths) throws IOException {
    if (filePaths.size() < THRESHOLD_FILTER_FILES) {
      List<DiffFile> modifiedFiles = find(parent, child, filePaths);
      if (modifiedFiles != null) {
        return modifiedFiles;
      }
    }

    // to detect renames, we need to collect all modified files in the repo
    Collection<DiffEntry> diffEntries = getDiffEntries(parent, child);
    diffEntries = detectRenames(filePaths, diffEntries);

    // delete entries or any other entry that doesn't have one of the child paths as the newPath is irrelevant
    return diffEntries.stream()
      .filter(entry -> entry.getChangeType() != DiffEntry.ChangeType.DELETE)
      .filter(entry -> filePaths.contains(entry.getNewPath()))
      .map(entry -> new DiffFile(entry.getNewPath(), entry.getOldPath(), entry.getOldId().toObjectId()))
      .collect(Collectors.toList());
  }

  @CheckForNull
  private List<DiffFile> find(RevCommit parent, RevCommit child, Set<String> filePaths) throws IOException {
    if (!filePaths.equals(filterFilePaths)) {
      // this is expensive to compute
      TreeFilter pathFilterGroup = PathFilterGroup.createFromStrings(filePaths);
      filesAndAnyDiffFilter = AndTreeFilter.create(pathFilterGroup, TreeFilter.ANY_DIFF);
      filterFilePaths = filePaths;
    }

    // With this filter, we'll traverse both trees, only visiting the files that are being blamed and that are different between both trees.
    treeWalk.setFilter(filesAndAnyDiffFilter);
    treeWalk.reset(parent.getTree(), child.getTree());

    List<DiffFile> modifiedFiles = new ArrayList<>(filePaths.size());

    while (treeWalk.next()) {
      if (filePaths.contains(treeWalk.getPathString())) {
        treeWalk.getObjectId(idBuf, 0);
        if (isAddedOrNotFile()) {
          // We found an added file. Abort
          return null;
        }
        modifiedFiles.add(new DiffFile(treeWalk.getPathString(), treeWalk.getPathString(), idBuf.toObjectId()));
      }
    }
    return modifiedFiles;
  }

  private boolean isAddedOrNotFile() {
    return idBuf.equals(ObjectId.zeroId()) || !isFile(treeWalk.getRawMode(0));
  }

  private static boolean isFile(int rawMode) {
    return (rawMode & TYPE_MASK) == TYPE_FILE;
  }

  // Gets the full list of added/modified/deleted files between the parent and child commits
  private Collection<DiffEntry> getDiffEntries(RevCommit parent, RevCommit child) throws IOException {
    treeWalk.setFilter(TreeFilter.ANY_DIFF);
    treeWalk.reset(parent.getTree(), child.getTree());
    return DiffEntry.scan(treeWalk);
  }

  private Collection<DiffEntry> detectRenames(Set<String> newFilePaths, Collection<DiffEntry> diffEntries) throws IOException {
    return filteredRenameDetector.compute(diffEntries, newFilePaths);
  }

  public static class DiffFile {
    private final String newPath;
    private final String oldPath;
    private final ObjectId oldObjectId;

    public DiffFile(String newPath, @Nullable String oldPath, ObjectId oldObjectId) {
      this.newPath = newPath;
      this.oldObjectId = oldObjectId;
      this.oldPath = ObjectId.zeroId().equals(oldObjectId) ? null : oldPath;
    }

    public String getNewPath() {
      return newPath;
    }

    /**
     * If the file was added, there's no old path and this returns null.
     *
     * @return null if the file was added, otherwise the path of the file in the parent commit.
     */
    @CheckForNull
    public String getOldPath() {
      return oldPath;
    }

    public ObjectId getOldObjectId() {
      return oldObjectId;
    }
  }
}
