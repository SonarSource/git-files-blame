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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
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
    for (FileCandidate fileCandidate : commit.getAllFiles()) {
      RawText rawText = fileReader.loadText(objectReader, fileCandidate);
      fileCandidate.setRegionList(new Region(0, 0, rawText.size()));
      blameResult.initialize(fileCandidate.getPath(), rawText.size());
    }
    fileTreeComparator.initialize(objectReader);
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
    Date commitDate = commit != null ? commit.getCommitterIdent().getWhen() : null;
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
    requireNonNull(child.getCommit());

    List<List<DiffFile>> fileTreeDiffs = new ArrayList<>(parentCommits.size());
    List<GraphNode> parentStatefulCommits = new ArrayList<>(parentCommits.size());

    // first compute differences compared to each parent
    for (RevCommit parentCommit : parentCommits) {
      GraphNode parentStatefulCommit = new CommitGraphNode(parentCommit, child.getAllFiles().size());
      parentStatefulCommits.add(parentStatefulCommit);

      // diff files will include added,modified,rename,copy. It will not include unmodified files.
      fileTreeDiffs.add(fileTreeComparator.findMovedFiles(parentCommit, child.getCommit(), child.getAllPaths()));
    }

    // Detect unmodified files (same path)
    for (int i = 0; i < parentCommits.size(); i++) {
      Set<String> diffNewPaths = fileTreeDiffs.get(i).stream().map(DiffFile::getNewPath).collect(Collectors.toSet());
      for (FileCandidate f : child.getAllFiles()) {
        if (!diffNewPaths.contains(f.getPath())) {
          // if file wasn't modified, it means it is unmodified. Move it to the parent.
          moveFileToParent(parentStatefulCommits.get(i), f, f.getPath());
        }
      }
    }

    // Detect unmodified files with RENAME or COPY. They have the exact same BLOB but different paths
    for (int i = 0; i < parentCommits.size(); i++) {
      for (DiffFile diffFile : fileTreeDiffs.get(i)) {
        Collection<FileCandidate> fileCandidates = child.getFilesByPath(diffFile.getNewPath());
        for (FileCandidate f : fileCandidates) {
          if (f.getBlob().equals(diffFile.getOldObjectId())) {
            moveFileToParent(parentStatefulCommits.get(i), f, diffFile.getOldPath());
          }
        }
      }
    }

    // try to match regions with parents, using the file tree diffs that we already computed
    for (int i = 0; i < parentStatefulCommits.size(); i++) {
      blameWithFileDiffs(parentStatefulCommits.get(i), child, fileTreeDiffs.get(i));
    }
    return parentStatefulCommits;
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
  private static void moveFileToParent(GraphNode parent, FileCandidate childFile, String parentPath) {
    // child's region could be null if it was already moved to another parent
    if (childFile.getRegionList() != null) {
      FileCandidate parentFile = new FileCandidate(childFile.getOriginalPath(), parentPath, childFile.getBlob(), childFile.getRegionList());
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
      return parent;
    }

    // ObjectReader is not thread safe, so we need to clone it
    ObjectReader reader = objectReader.newReader();

    EditList editList = diffAlgorithm.diff(textComparator, fileReader.loadText(reader, parent), fileReader.loadText(reader, source));
    if (editList.isEmpty()) {
      // Ignoring whitespace (or some other special comparator) can cause non-identical blobs to have an empty edit list
      moveUnmodifiedFileRegionsToParent(parent, source);
      return parent;
    }

    parent.takeBlame(editList, source);
    // if the parent has nothing left to blame, don't return it
    return parent.getRegionList() != null ? parent : null;
  }

  private static void moveUnmodifiedFileRegionsToParent(FileCandidate parent, FileCandidate child) {
    parent.setRegionList(child.getRegionList());
    child.setRegionList(null);
  }
}
