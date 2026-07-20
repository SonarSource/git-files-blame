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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.sonar.scm.git.blame.FileTreeComparator.DiffFile;

import static java.util.Objects.requireNonNull;

public class FileBlamer {
  static final int NB_FILES_THRESHOLD_ONE_TREE_WALK = 50;

  private final ExecutorService executor;
  private final BlobReader fileReader;
  private final DiffAlgorithm diffAlgorithm;
  private final RawTextComparator textComparator;
  private final BlameResult blameResult;
  private final FileTreeComparator fileTreeComparator;

  private ObjectReader objectReader;

  public FileBlamer(FileTreeComparator fileTreeComparator, DiffAlgorithm diffAlgorithm, RawTextComparator rawTextComparator, BlobReader fileReader,
    BlameResult blameResult, boolean multithreading) {
    this.diffAlgorithm = diffAlgorithm;
    this.textComparator = rawTextComparator;
    this.fileReader = fileReader;
    this.blameResult = blameResult;
    this.fileTreeComparator = fileTreeComparator;
    this.executor = multithreading ? Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), new BlameThreadFactory()) : SameThreadExecutorService.INSTANCE;
  }

  /**
   * Read all file's contents to get the number of lines in each file. With that, we can initialize regions and
   * also the arrays that will contain the blame results
   */
  public void initialize(ObjectReader objectReader, GraphNode commit) {
    this.objectReader = objectReader;
    if (commit.getAllFiles().size() < NB_FILES_THRESHOLD_ONE_TREE_WALK) {
      initializeForSmallFileSet(objectReader, commit);
    } else {
      // When we have more than NB_FILES_THRESHOLD_ONE_TREE_WALK we use this implementation which walks only once the entire tree
      initializeForLargeFileSet(objectReader, commit);
    }
  }

  private void initializeForSmallFileSet(ObjectReader objectReader, GraphNode commit) {
    for (FileCandidate fileCandidate : commit.getAllFiles()) {
      RawText rawText = fileReader.loadText(objectReader, fileCandidate);
      fileCandidate.setRegionList(new Region(0, 0, rawText.size()));
      fileCandidate.setCachedText(rawText);
      blameResult.initialize(fileCandidate.getPath(), rawText.size());
    }
    fileTreeComparator.initialize(objectReader);
  }

  private void initializeForLargeFileSet(ObjectReader objectReader, GraphNode commit) {
    try {
      Map<String, Integer> fileSizes = fileReader.getFileSizes(objectReader, commit.getAllFiles());
      for (FileCandidate fileCandidate : commit.getAllFiles()) {
        Integer fileSize = fileSizes.get(fileCandidate.getPath());
        if (fileSize == null) {
          throw new IllegalStateException("Failed to find file in the working directory: " + fileCandidate.getPath());
        }
        fileCandidate.setRegionList(new Region(0, 0, fileSize));
        blameResult.initialize(fileCandidate.getPath(), fileSize);
      }
      fileTreeComparator.initialize(objectReader);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Blame all remaining regions to the commit
   *
   * @param source - commit that will be used to associate blame data with remaining regions in files
   */
  public void saveBlameDataForFilesInCommit(GraphNode source) {
    RevCommit commit = source.getCommit();
    String commitHash = commit != null ? commit.getName() : null;
    String authorEmail = commit != null ? commit.getAuthorIdent().getEmailAddress() : null;
    Instant commitDate = commit != null ? commit.getCommitterIdent().getWhenAsInstant() : null;
    for (FileCandidate sourceFile : source.getAllFiles()) {
      if (sourceFile.getRegionList() != null) {
        blameResult.saveBlameDataForFile(commitHash, commitDate, authorEmail, sourceFile);
      }
    }
  }

  public GraphNode blameParent(RevCommit parentCommit, GraphNode child) throws IOException {
    List<DiffFile> diffFiles = fileTreeComparator.findMovedFiles(parentCommit, child.getCommit(), child.getAllPaths());
    GraphNode parent = new CommitGraphNode(parentCommit, child.getAllFiles().size());
    blameWithFileDiffs(parent, child, diffFiles);
    return parent;
  }

  public List<GraphNode> blameParents(List<RevCommit> parentCommits, GraphNode child) throws IOException {
    // the working directory should always have a single parent
    RevCommit childCommit = requireNonNull(child.getCommit());

    List<GraphNode> parentStatefulCommits = new ArrayList<>(parentCommits.size());
    for (RevCommit parentCommit : parentCommits) {
      parentStatefulCommits.add(new CommitGraphNode(parentCommit, child.getAllFiles().size()));
    }

    // diff files will include added,modified,rename,copy. It will not include unmodified files.
    List<List<DiffFile>> fileTreeDiffs = fileTreeComparator.supportsCheapDiff()
      ? computeMergeDiffsWithScopedFallback(parentCommits, child, childCommit)
      : computeMergeDiffs(parentCommits, childCommit, child.getAllPaths());

    moveUnmodifiedSamePathFilesToParents(child, fileTreeDiffs, parentStatefulCommits);
    moveUnmodifiedRenamedFilesToParents(child, fileTreeDiffs, parentStatefulCommits);

    // try to match regions with parents, using the file tree diffs that we already computed
    for (int i = 0; i < parentStatefulCommits.size(); i++) {
      blameWithFileDiffs(parentStatefulCommits.get(i), child, fileTreeDiffs.get(i));
    }
    return parentStatefulCommits;
  }

  private static void moveUnmodifiedSamePathFilesToParents(GraphNode child, List<List<DiffFile>> fileTreeDiffs, List<GraphNode> parentStatefulCommits) {
    for (int i = 0; i < parentStatefulCommits.size(); i++) {
      Set<String> diffNewPaths = fileTreeDiffs.get(i).stream().map(DiffFile::getNewPath).collect(Collectors.toSet());
      for (FileCandidate f : child.getAllFiles()) {
        if (!diffNewPaths.contains(f.getPath())) {
          // if file wasn't modified, it means it is unmodified. Move it to the parent.
          moveFileToParent(parentStatefulCommits.get(i), f, f.getPath());
        }
      }
    }
  }

  private static void moveUnmodifiedRenamedFilesToParents(GraphNode child, List<List<DiffFile>> fileTreeDiffs, List<GraphNode> parentStatefulCommits) {
    // Unmodified files with RENAME or COPY have the exact same BLOB but a different path.
    for (int i = 0; i < parentStatefulCommits.size(); i++) {
      for (DiffFile diffFile : fileTreeDiffs.get(i)) {
        for (FileCandidate f : child.getFilesByPath(diffFile.getNewPath())) {
          if (f.getBlob().equals(diffFile.getOldObjectId())) {
            moveFileToParent(parentStatefulCommits.get(i), f, diffFile.getOldPath());
          }
        }
      }
    }
  }

  private List<List<DiffFile>> computeMergeDiffs(List<RevCommit> parentCommits, RevCommit childCommit, Set<String> blamedPaths) throws IOException {
    List<List<DiffFile>> fileTreeDiffs = new ArrayList<>(parentCommits.size());
    for (RevCommit parentCommit : parentCommits) {
      fileTreeDiffs.add(fileTreeComparator.findMovedFiles(parentCommit, childCommit, blamedPaths));
    }
    return fileTreeDiffs;
  }

  /**
   * Computes each parent's diff for a merge while running the expensive full-repository rename fallback only when it
   * can actually affect the blame. At a merge, a file that looks added relative to one parent is very often carried
   * unchanged by another parent, which claims all of its regions before any diff-splitting happens (see the
   * unmodified-file handling in {@link #blameParents}). In that case resolving where the "added" file came from in
   * this parent is wasted work, so we replace it with a synthetic added-file entry, which drives the exact same
   * blame. The full fallback still runs for a parent whose added files are not carried by any other parent (a real
   * rename or add), so the result is identical to {@link #computeMergeDiffs}.
   */
  private List<List<DiffFile>> computeMergeDiffsWithScopedFallback(List<RevCommit> parentCommits, GraphNode child, RevCommit childCommit) throws IOException {
    int n = parentCommits.size();
    Set<String> blamedPaths = child.getAllPaths();

    List<FileTreeComparator.CheapDiff> cheapDiffs = new ArrayList<>(n);
    List<Set<String>> changedPathsPerParent = new ArrayList<>(n);
    for (RevCommit parentCommit : parentCommits) {
      FileTreeComparator.CheapDiff cheap = fileTreeComparator.cheapDiff(parentCommit, childCommit, blamedPaths);
      cheapDiffs.add(cheap);
      Set<String> changed = new HashSet<>(cheap.addedPaths());
      cheap.modified().forEach(diffFile -> changed.add(diffFile.getNewPath()));
      changedPathsPerParent.add(changed);
    }

    List<List<DiffFile>> fileTreeDiffs = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      FileTreeComparator.CheapDiff cheap = cheapDiffs.get(i);
      if (!cheap.hasAddedPaths()) {
        fileTreeDiffs.add(new ArrayList<>(cheap.modified()));
      } else if (allAddedPathsCarriedByAnotherParent(cheap.addedPaths(), i, changedPathsPerParent)) {
        fileTreeDiffs.add(withSyntheticAddedFiles(cheap));
      } else {
        fileTreeDiffs.add(fileTreeComparator.fullDiff(parentCommits.get(i), childCommit, blamedPaths));
      }
    }
    return fileTreeDiffs;
  }

  /**
   * @return true if every {@code addedPath} is unchanged (present with the same blob at the same path) in at least
   *         one parent other than {@code parentIndex}, which will therefore claim its regions regardless of what
   *         this parent's diff says.
   */
  private static boolean allAddedPathsCarriedByAnotherParent(Set<String> addedPaths, int parentIndex, List<Set<String>> changedPathsPerParent) {
    for (String addedPath : addedPaths) {
      boolean carried = false;
      for (int j = 0; j < changedPathsPerParent.size(); j++) {
        if (j != parentIndex && !changedPathsPerParent.get(j).contains(addedPath)) {
          carried = true;
          break;
        }
      }
      if (!carried) {
        return false;
      }
    }
    return true;
  }

  private static List<DiffFile> withSyntheticAddedFiles(FileTreeComparator.CheapDiff cheap) {
    List<DiffFile> diffFiles = new ArrayList<>(cheap.modified());
    for (String addedPath : cheap.addedPaths()) {
      // Mirror what the full diff produces for an added file with no rename source: a null old path.
      diffFiles.add(new DiffFile(addedPath, null, ObjectId.zeroId()));
    }
    return diffFiles;
  }

  public void close() {
    executor.shutdown();
    try {
      executor.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private void blameWithFileDiffs(GraphNode parent, GraphNode child, List<DiffFile> diffFiles) {
    Set<String> processedFilePaths = new HashSet<>();
    List<Future<FileCandidate>> tasks = new ArrayList<>();

    // compare files in diffFiles
    for (DiffFile file : diffFiles) {
      processedFilePaths.add(file.getNewPath());
      if (file.getOldPath() != null) {
        // added files don't have an old path
        child.getFilesByPath(file.getNewPath())
          .forEach(modifiedFile -> tasks.add(executor.submit(() -> splitBlameWithParent(file.getOldPath(), file.getOldObjectId(), modifiedFile))));
      }
    }

    // move unmodified files to the parent
    for (FileCandidate f : child.getAllFiles()) {
      if (!processedFilePaths.contains(f.getPath())) {
        moveFileToParent(parent, f, f.getPath());
      }
    }

    waitForTasks(parent, tasks);
  }

  /**
   * Move an unmodified file, which may have been copied or renamed, to the parent.
   * The child and parent files have the same BLOB
   */
  private static void moveFileToParent(GraphNode parent, FileCandidate childFile, @Nullable String parentPath) {
    // child's region could be null if it was already moved to another parent
    if (childFile.getRegionList() != null && parentPath != null) {
      FileCandidate parentFile = new FileCandidate(childFile.getOriginalPath(), parentPath, childFile.getBlob(), childFile.getRegionList());
      // same content as childFile, so any cached text is still valid and can be reused at the next hop
      parentFile.setCachedText(childFile.getCachedText());
      parent.addFile(parentFile);
      childFile.setRegionList(null);
    }
  }

  private static void waitForTasks(GraphNode statefulParent, Collection<Future<FileCandidate>> tasks) {
    try {
      for (Future<FileCandidate> f : tasks) {
        FileCandidate parent = f.get();
        if (parent != null) {
          statefulParent.addFile(parent);
        }
      }
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  @CheckForNull
  private FileCandidate splitBlameWithParent(String parentPath, ObjectId parentObjectId, FileCandidate source) {
    if (source.getRegionList() == null) {
      // all regions may have been moved to another parent
      return null;
    }
    FileCandidate parent = new FileCandidate(source.getOriginalPath(), parentPath, parentObjectId);

    if (parent.getBlob().equals(source.getBlob())) {
      moveUnmodifiedFileRegionsToParent(parent, source);
      // same content, so the cached text (if any) is still valid for the parent
      parent.setCachedText(source.getCachedText());
      return parent;
    }

    // ObjectReader is not thread safe, so we need to clone it
    ObjectReader reader = objectReader.newReader();

    // source's content was already read and cached when it was diffed as the parent of its child; a candidate is
    // only ever diffed as the source once, so the cache can be consumed here and cleared. It may also be null if
    // the JVM reclaimed the underlying SoftReference under memory pressure, in which case we re-read the blob.
    RawText sourceText = source.getCachedText() != null ? source.getCachedText() : fileReader.loadText(reader, source);
    source.setCachedText(null);
    RawText parentText = fileReader.loadText(reader, parent);

    EditList editList = diffAlgorithm.diff(textComparator, parentText, sourceText);
    if (editList.isEmpty()) {
      // Ignoring whitespace (or some other special comparator) can cause non-identical blobs to have an empty edit list
      moveUnmodifiedFileRegionsToParent(parent, source);
      parent.setCachedText(parentText);
      return parent;
    }

    parent.takeBlame(editList, source);
    // if the parent has nothing left to blame, don't return it
    if (parent.getRegionList() == null) {
      return null;
    }
    parent.setCachedText(parentText);
    return parent;
  }

  private static void moveUnmodifiedFileRegionsToParent(FileCandidate parent, FileCandidate child) {
    parent.setRegionList(child.getRegionList());
    child.setRegionList(null);
  }
}
