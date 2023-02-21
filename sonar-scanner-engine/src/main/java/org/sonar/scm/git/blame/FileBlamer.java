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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
  public void initialize(ObjectReader objectReader, StatefulCommit commit) {
    this.objectReader = objectReader;
    for (FileCandidate fileCandidate : commit.getAllFiles()) {
      RawText rawText = fileReader.loadText(objectReader, fileCandidate.getBlob());

      fileCandidate.setRegionList(new Region(0, 0, rawText.size()));
      blameResult.initialize(fileCandidate.getPath(), rawText.size());
    }
    fileTreeComparator.initialize(objectReader, commit);
  }

  /**
   * Blame all remaining regions to the commit
   */
  public void processResult(StatefulCommit source) {
    for (FileCandidate sourceFile : source.getAllFiles()) {
      if (sourceFile.getRegionList() != null) {
        blameResult.process(source.getCommit(), sourceFile);
      }
    }
  }

  public List<StatefulCommit> blameParents(List<RevCommit> parentCommits, StatefulCommit child) throws IOException {
    List<List<DiffFile>> fileTreeDiffs = new ArrayList<>(parentCommits.size());
    List<StatefulCommit> parentStatefulCommits = new ArrayList<>(parentCommits.size());

    // first try to find matches that are unmodified
    for (RevCommit parentCommit : parentCommits) {
      StatefulCommit parentStatefulCommit = new StatefulCommit(parentCommit, child.getAllFiles().size());
      parentStatefulCommits.add(parentStatefulCommit);

      // diff files will include added,modified,rename,copy. It will not include unmodified files.
      fileTreeDiffs.add(fileTreeComparator.compute(parentCommit, child.getCommit(), child.getAllPaths()));
    }

    // Detect unmodified files (same path)
    for (int i = 0; i < parentCommits.size(); i++) {
      Set<String> diffFilePaths = fileTreeDiffs.get(i).stream().map(DiffFile::getNewPath).collect(Collectors.toSet());
      for (FileCandidate f : child.getAllFiles()) {
        if (!diffFilePaths.contains(f.getPath())) {
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

  /**
   * Move a copied, renamed or unmodified file to the parent.
   * The child and parent files have the same BLOB
   */
  private static void moveFileToParent(StatefulCommit parent, FileCandidate childFile, String parentPath) {
    if (childFile.getRegionList() != null) {
      FileCandidate parentFile = new FileCandidate(childFile.getOriginalPath(), parentPath, childFile.getBlob(), childFile.getRegionList());
      parent.addFile(parentFile);
      childFile.setRegionList(null);
    }
  }

  public StatefulCommit blameParent(RevCommit parentCommit, StatefulCommit child) throws IOException {
    List<DiffFile> diffFiles = fileTreeComparator.compute(parentCommit, child.getCommit(), child.getAllPaths());
    StatefulCommit parent = new StatefulCommit(parentCommit, child.getAllFiles().size());
    blameWithFileDiffs(parent, child, diffFiles);
    return parent;
  }

  private void blameWithFileDiffs(StatefulCommit parent, StatefulCommit child, List<DiffFile> diffFiles) {
    Set<String> modifiedFilePaths = new HashSet<>();
    List<Future<FileCandidate>> tasks = new ArrayList<>();

    for (DiffFile file : diffFiles) {
      modifiedFilePaths.add(file.getNewPath());
      if (file.getOldPath() != null) {
        // added files don't have an old path
        child.getFilesByPath(file.getNewPath())
          .forEach(modifiedFile -> tasks.add(executor.submit(() -> splitBlameWithParent(file.getOldPath(), file.getOldObjectId(), modifiedFile))));
      }
    }

    // move unmodified files to the parent
    for (FileCandidate f : child.getAllFiles()) {
      if (!modifiedFilePaths.contains(f.getPath())) {
        moveFileToParent(parent, f, f.getPath());
      }
    }

    waitForTasks(parent, tasks);
  }

  private static void waitForTasks(StatefulCommit statefulParent, Collection<Future<FileCandidate>> tasks) {
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

    EditList editList = diffAlgorithm.diff(textComparator,
      fileReader.loadText(reader, parent.getBlob()),
      fileReader.loadText(reader, source.getBlob()));
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
