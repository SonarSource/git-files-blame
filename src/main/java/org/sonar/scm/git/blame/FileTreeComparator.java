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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.sonar.scm.git.blame.diff.DiffEntry;

import static org.eclipse.jgit.lib.FileMode.TYPE_FILE;
import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;

public class FileTreeComparator {
  private final MutableObjectId idBuf = new MutableObjectId();
  private final Repository repository;
  private final FilteredRenameDetector filteredRenameDetector;

  /**
   * Whether the files being blamed all live under some common subfolder of the repository, decided once from the
   * original request rather than re-derived on every commit. When true, a path-scoped tree diff is worth its own
   * setup cost: any other subfolder untouched by a given commit is skipped without even being looked at. When the
   * files being blamed are spread across the repository root (or it's a full-repository blame), most subfolders
   * are relevant anyway, so scoping the diff has no upside and only adds overhead.
   */
  private final boolean useFilteredDiff;

  /**
   * Whether path-scoping optimizations are enabled. When true, at a merge the expensive full-repository diff is run
   * for a parent only if a file added relative to it isn't already carried, unchanged, by another parent - see
   * {@link #supportsCheapDiff()} and {@link FileBlamer}. Always on in production; tests turn it off to compare
   * against the unscoped result.
   */
  private final boolean pathScoping;

  private TreeWalk treeWalk;
  private TreeFilter filesAndAnyDiffFilter = null;
  private Set<String> filterFilePaths = null;

  public FileTreeComparator(Repository repository, FilteredRenameDetector filteredRenameDetector, @Nullable Set<String> filePathsToBlame,
    boolean pathScoping) {
    this.repository = repository;
    this.filteredRenameDetector = filteredRenameDetector;
    this.useFilteredDiff = isUnderCommonSubfolder(filePathsToBlame);
    this.pathScoping = pathScoping;
  }

  /**
   * @return true if every path in {@code filePaths} lives under some shared directory of the repository, other
   *         than the repository root itself.
   */
  static boolean isUnderCommonSubfolder(@Nullable Set<String> filePaths) {
    return commonSubfolder(filePaths).isPresent();
  }

  /**
   * @return the deepest directory that every path in {@code filePaths} lives under, or empty if they only share the
   *         repository root. The returned path has no trailing slash (e.g. {@code "net/ethtool"}).
   */
  static Optional<String> commonSubfolder(@Nullable Set<String> filePaths) {
    if (filePaths == null || filePaths.isEmpty()) {
      return Optional.empty();
    }
    String prefix = longestCommonPrefix(filePaths);
    int lastSlash = prefix.lastIndexOf('/');
    return lastSlash < 0 ? Optional.empty() : Optional.of(prefix.substring(0, lastSlash));
  }

  private static String longestCommonPrefix(Set<String> paths) {
    String prefix = null;
    for (String path : paths) {
      if (prefix == null) {
        prefix = path;
        continue;
      }
      int len = Math.min(prefix.length(), path.length());
      int i = 0;
      while (i < len && prefix.charAt(i) == path.charAt(i)) {
        i++;
      }
      prefix = prefix.substring(0, i);
      if (prefix.isEmpty()) {
        return prefix;
      }
    }
    return prefix;
  }

  public void initialize(ObjectReader objectReader) {
    treeWalk = new TreeWalk(objectReader);
    treeWalk.setRecursive(true);
  }

  /**
   * Compare the working tree with a commit.
   * Returns all files, since we need to know the objectId of the unmodified files.
   */
  private List<DiffFile> computeForWorkingDir(RevCommit commit, Set<String> filePaths) throws IOException {
    List<DiffFile> matchedFiles = new ArrayList<>();
    treeWalk.reset();
    treeWalk.addTree(commit.getTree());
    if (!repository.isBare()) {
      treeWalk.addTree(new FileTreeIterator(repository));
    }
    treeWalk.setFilter(TreeFilter.ALL);

    while (treeWalk.next()) {
      if (filePaths.contains(treeWalk.getPathString())) {
        treeWalk.getObjectId(idBuf, 0);
        matchedFiles.add(new DiffFile(treeWalk.getPathString(), treeWalk.getPathString(), idBuf.toObjectId()));
      }
    }
    return matchedFiles;
  }

  /**
   * The strategy is to first try to find the files to blame in the parent commit, with the same paths.
   * If any file can't be found (meaning that it was added by the child commit), we need to compute all the files added and
   * removed between the parent and child commits, so that we can run the rename detector.
   */
  public List<DiffFile> findMovedFiles(RevCommit parent, @Nullable RevCommit child, Set<String> filePathsToInclude) throws IOException {
    if (child == null) {
      return computeForWorkingDir(parent, filePathsToInclude);
    }
    if (useFilteredDiff) {
      List<DiffFile> modifiedFiles = findMovedFilesWithPathFilter(parent, child, filePathsToInclude);
      if (modifiedFiles != null) {
        return modifiedFiles;
      }
    }

    return fullDiff(parent, child, filePathsToInclude);
  }

  /**
   * The full-repository diff plus rename detection: needed to find where a blamed file that appears added came from,
   * since its rename source can be anywhere in the tree (even outside the blamed subfolder). This is the expensive
   * path - on a large repository it scans the entire parent-to-child diff.
   */
  List<DiffFile> fullDiff(RevCommit parent, RevCommit child, Set<String> filePathsToInclude) throws IOException {
    // to detect renames, we need to collect all modified files in the repo
    Collection<DiffEntry> diffEntries = getDiffEntries(parent, child);
    diffEntries = detectRenames(filePathsToInclude, diffEntries);

    // delete entries or any other entry that doesn't have one of the child paths as the newPath is irrelevant
    return diffEntries.stream()
      .filter(entry -> entry.getChangeType() != DiffEntry.ChangeType.DELETE)
      .filter(entry -> filePathsToInclude.contains(entry.getNewPath()))
      .map(entry -> new DiffFile(entry.getNewPath(), entry.getOldPath(), entry.getOldId().toObjectId()))
      .toList();
  }

  @CheckForNull
  private List<DiffFile> findMovedFilesWithPathFilter(RevCommit parent, RevCommit child, Set<String> filePaths) throws IOException {
    startPathFilteredWalk(parent, child, filePaths);

    List<DiffFile> movedFiles = new ArrayList<>(filePaths.size());

    while (treeWalk.next()) {
      if (filePaths.contains(treeWalk.getPathString())) {
        treeWalk.getObjectId(idBuf, 0);
        if (isAddedOrNotFile()) {
          // We found an added file. Abort
          return null;
        }
        movedFiles.add(new DiffFile(treeWalk.getPathString(), treeWalk.getPathString(), idBuf.toObjectId()));
      }
    }
    return movedFiles;
  }

  private void startPathFilteredWalk(RevCommit parent, RevCommit child, Set<String> filePaths) throws IOException {
    if (!filePaths.equals(filterFilePaths)) {
      // this is expensive to compute
      TreeFilter pathFilterGroup = PathFilterGroup.createFromStrings(filePaths);
      filesAndAnyDiffFilter = AndTreeFilter.create(pathFilterGroup, TreeFilter.ANY_DIFF);
      filterFilePaths = filePaths;
    }
    // With this filter, we'll traverse both trees, only visiting the files that are being blamed and that are different between both trees.
    treeWalk.setFilter(filesAndAnyDiffFilter);
    treeWalk.reset(parent.getTree(), child.getTree());
  }

  /**
   * @return true if the path-filtered diff can be used, i.e. all blamed files share a common subfolder so a filter
   *         on them is worth building. Only then can {@link #cheapDiff} split blamed files into modified vs added
   *         without the full-repository diff.
   */
  boolean supportsCheapDiff() {
    return useFilteredDiff && pathScoping;
  }

  /**
   * A path-filtered diff of the blamed files between {@code parent} and {@code child} that never falls back to the
   * full-repository diff: blamed files present in the parent (possibly modified) are resolved to their parent blob,
   * while blamed files absent from the parent are only reported as added paths - resolving where an added file came
   * from (its rename source) still needs {@link #fullDiff}.
   */
  CheapDiff cheapDiff(RevCommit parent, RevCommit child, Set<String> filePaths) throws IOException {
    startPathFilteredWalk(parent, child, filePaths);

    List<DiffFile> modified = new ArrayList<>();
    Set<String> addedPaths = new HashSet<>();
    while (treeWalk.next()) {
      String path = treeWalk.getPathString();
      if (filePaths.contains(path)) {
        treeWalk.getObjectId(idBuf, 0);
        if (isAddedOrNotFile()) {
          addedPaths.add(path);
        } else {
          modified.add(new DiffFile(path, path, idBuf.toObjectId()));
        }
      }
    }
    return new CheapDiff(modified, addedPaths);
  }

  /**
   * Outcome of {@link #cheapDiff}: the blamed files that changed between the two commits, split into files still
   * present in the parent ({@link #modified}, resolved to their parent blob) and files absent from the parent
   * ({@link #addedPaths}, whose rename source, if any, is not yet known). Blamed files in neither set are unchanged.
   */
  record CheapDiff(List<DiffFile> modified, Set<String> addedPaths) {
    boolean hasAddedPaths() {
      return !addedPaths.isEmpty();
    }
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
    return filteredRenameDetector.detectRenames(diffEntries, newFilePaths);
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
