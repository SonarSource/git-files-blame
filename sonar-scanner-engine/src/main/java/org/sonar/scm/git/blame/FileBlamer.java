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
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

public class FileBlamer {
  private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  private final BlobReader fileReader;
  private final DiffAlgorithm diffAlgorithm;
  private final RawTextComparator textComparator;
  private final BlameResult blameResult;
  private final FilteredRenameDetector filteredRenameDetector;

  public FileBlamer(FilteredRenameDetector filteredRenameDetector, DiffAlgorithm diffAlgorithm, RawTextComparator rawTextComparator, BlobReader fileReader, BlameResult blameResult) {
    this.diffAlgorithm = diffAlgorithm;
    this.textComparator = rawTextComparator;
    this.fileReader = fileReader;
    this.blameResult = blameResult;
    this.filteredRenameDetector = filteredRenameDetector;
  }

  /**
   * Read all file's contents to get the number of lines in each file. With that, we can initialize regions and
   * also the arrays that will contain the blame results
   */
  public void initialize(ObjectReader objectReader, StatefulCommit commit) {
    for (FileCandidate fileCandidate : commit.getAllFiles()) {
      RawText rawText = fileReader.loadText(objectReader, fileCandidate.getBlob());

      fileCandidate.setRegionList(new Region(0, 0, rawText.size()));
      blameResult.initialize(fileCandidate.getPath(), rawText.size());
    }
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

  public StatefulCommit blame(ObjectReader objectReader, RevCommit parentCommit, StatefulCommit source) throws IOException {
    Collection<DiffEntry> diffEntries = getDiffEntries(objectReader, parentCommit, source.getCommit());
    diffEntries = detectRenames(source, diffEntries);

    Set<String> changedFilePaths = new HashSet<>();
    List<FileCandidate> parentFiles = new LinkedList<>();
    List<Future<?>> tasks = new ArrayList<>();

    for (DiffEntry entry : diffEntries) {
      changedFilePaths.add(entry.getNewPath());
      switch (entry.getChangeType()) {
        case DELETE:
        case ADD:
          // added files will be processed
          break;
        case COPY:
        case RENAME:
        case MODIFY:
          Collection<FileCandidate> modifiedFiles = source.getFilesByPath(entry.getNewPath());
          for (FileCandidate modifiedFile : modifiedFiles) {
            FileCandidate parentFile = new FileCandidate(modifiedFile.getOriginalPath(), entry.getOldPath(), entry.getOldId().toObjectId());
            parentFiles.add(parentFile);
            tasks.add(executor.submit(() -> splitBlameWithParent(objectReader, parentFile, modifiedFile)));
          }
          break;
      }
    }

    // move unmodified files to the parent
    for (FileCandidate f : source.getAllFiles()) {
      if (!changedFilePaths.contains(f.getPath())) {
        parentFiles.add(new FileCandidate(f.getOriginalPath(), f.getPath(), f.getBlob(), f.getRegionList()));
        f.setRegionList(null);
      }
    }

    waitForTasks(tasks);
    parentFiles.removeIf(f -> f.getRegionList() == null);
    return new StatefulCommit(parentCommit, parentFiles);
  }

  private List<DiffEntry> detectRenames(StatefulCommit source, Collection<DiffEntry> diffEntries) throws IOException {
    Set<String> newFilePaths = source.getAllFiles().stream().map(FileCandidate::getPath).collect(Collectors.toSet());
    return filteredRenameDetector.compute(diffEntries, newFilePaths);
  }

  private static void waitForTasks(Collection<Future<?>> tasks) {
    try {
      for (Future<?> f : tasks) {
        f.get();
      }
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  private Collection<DiffEntry> getDiffEntries(ObjectReader reader, RevCommit parent, RevCommit commit) throws IOException {
    TreeWalk treeWalk = new TreeWalk(reader);
    treeWalk.setRecursive(true);
    treeWalk.setFilter(TreeFilter.ANY_DIFF);
    treeWalk.reset(parent.getTree(), commit.getTree());
    return DiffEntry.scan(treeWalk);
  }

  @CheckForNull
  public Void splitBlameWithParent(ObjectReader objectReader, FileCandidate parent, FileCandidate source) {
    if (parent.getBlob().equals(source.getBlob())) {
      parent.setRegionList(source.getRegionList());
      source.setRegionList(null);
      return null;
    }

    // the ObjectReader is not thread safe, so we need to clone it
    // TODO could the fact that we are not using a common ObjectReader be causing bad performance
    //  when reading the file contents?
    ObjectReader reader = objectReader.newReader();

    EditList editList = diffAlgorithm.diff(textComparator,
      fileReader.loadText(reader, parent.getBlob()),
      fileReader.loadText(reader, source.getBlob()));
    if (editList.isEmpty()) {
      // Ignoring whitespace (or some other special comparator) can cause non-identical blobs to have an empty edit list
      parent.setRegionList(source.getRegionList());
      source.setRegionList(null);
      return null;
    }

    parent.takeBlame(editList, source);
    return null;
  }
}
