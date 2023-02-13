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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;

public class FileBlamer {
  private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  private final BlobReader fileReader;
  private final DiffAlgorithm diffAlgorithm;
  private final RawTextComparator textComparator;
  private final BlameResult blameResult;

  public FileBlamer(DiffAlgorithm diffAlgorithm, RawTextComparator rawTextComparator, BlobReader fileReader, BlameResult blameResult) {
    this.diffAlgorithm = diffAlgorithm;
    this.textComparator = rawTextComparator;
    this.fileReader = fileReader;
    this.blameResult = blameResult;
  }

  /**
   * Read all file's contents to get the number of lines in each file. With that, we can initialize regions and
   * also the arrays that will contain the blame results
   */
  public void initialize(ObjectReader objectReader, StatefulCommit commit) {

    for (FileCandidate fileCandidate : commit.getFiles()) {
      RawText rawText = fileReader.loadText(objectReader, fileCandidate.getBlob());

      fileCandidate.setRegionList(new Region(0, 0, rawText.size()));
      blameResult.initialize(fileCandidate.getPath(), rawText.size());
    }
  }

  /**
   * Blame all remaining regions to the commit
   */
  public void blameLastCommit(StatefulCommit source) {
    for (FileCandidate sourceFile : source.getFiles()) {
      if (sourceFile.getRegionList() != null) {
        blameResult.process(source.getCommit(), sourceFile);
      }
    }
  }

  public void blame(ObjectReader objectReader, StatefulCommit parent, StatefulCommit source) {
    List<Future<Void>> tasks = new ArrayList<>();
    for (FileCandidate sourceFile : source.getFiles()) {
      tasks.add(executor.submit(() -> blameFile(objectReader, sourceFile, parent, source.getCommit())));
    }
    try {
      for (Future<Void> f : tasks) {
        f.get(1, TimeUnit.HOURS);
      }
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new IllegalStateException(e);
    }

    parent.removeFilesWithoutRegions();
  }

  private Void blameFile(ObjectReader objectReader, FileCandidate sourceFile, StatefulCommit parent, RevCommit commit) {
    FileCandidate parentFile = fileMatchingFileFromParent(sourceFile, parent);

    if (parentFile != null) {
      splitBlameWithParent(objectReader, parentFile, sourceFile);
    }

    if (sourceFile.getRegionList() != null) {
      blameResult.process(commit, sourceFile);
    }
    return null;
  }

  @Nullable
  private FileCandidate fileMatchingFileFromParent(FileCandidate file, StatefulCommit parentCommit) {
    // TODO file rename detection
    return parentCommit.getFile(file.getPath());
  }

  public void splitBlameWithParent(ObjectReader objectReader, FileCandidate parent, FileCandidate source) {
    if (parent.getBlob().equals(source.getBlob())) {
      parent.setRegionList(source.getRegionList());
      source.setRegionList(null);
      return;
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
      return;
    }

    parent.takeBlame(editList, source);
  }
}
