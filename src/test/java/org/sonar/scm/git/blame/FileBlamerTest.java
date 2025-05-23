/*
 * Git Files Blame
 * Copyright (C) 2009-2025 SonarSource SA
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.scm.git.blame.FileBlamer.NB_FILES_THRESHOLD_ONE_TREE_WALK;

public class FileBlamerTest {

  private static final Instant ANY_DATE = LocalDateTime.now().toInstant(ZoneOffset.UTC);
  private static final Instant ANOTHER_DATE = LocalDateTime.now().minusDays(1).toInstant(ZoneOffset.UTC);
  private static final String ANY_EMAIL = "email@email.com";
  private static final String ANY_COMMIT_NAME = "commit-name";

  private final BlameResult blameResult = mock(BlameResult.class);
  private final FileTreeComparator fileTreeComparator = mock(FileTreeComparator.class);
  private final BlobReader fileReader = mock(BlobReader.class);
  private final RevCommit revCommit = mock(RevCommit.class);
  private final FileCandidate fileCandidate = mock(FileCandidate.class);

  @Before
  public void before() {
    when(revCommit.getName()).thenReturn(ANY_COMMIT_NAME);

    PersonIdent personIdent = mock(PersonIdent.class);
    when(revCommit.getAuthorIdent()).thenReturn(personIdent);
    when(personIdent.getEmailAddress()).thenReturn(ANY_EMAIL);
    when(personIdent.getWhenAsInstant()).thenReturn(ANY_DATE);

    PersonIdent personIdent2 = mock(PersonIdent.class);
    when(revCommit.getCommitterIdent()).thenReturn(personIdent2);
    when(personIdent2.getEmailAddress()).thenReturn("another@email.com");
    when(personIdent2.getWhenAsInstant()).thenReturn(ANOTHER_DATE);
  }

  @Test
  public void saveBlameDataForFilesInCommit_whenCommitContainsFileCandidate_thenCallBlameResult() {
    FileBlamer fileBlamer = new FileBlamer(null, null, null, null, blameResult, false);
    when(fileCandidate.getRegionList()).thenReturn(new Region(0, 0, 2));

    CommitGraphNode statefulCommit = new CommitGraphNode(revCommit, 1);
    statefulCommit.addFile(fileCandidate);

    fileBlamer.saveBlameDataForFilesInCommit(statefulCommit);

    verify(blameResult).saveBlameDataForFile(ANY_COMMIT_NAME, ANOTHER_DATE, ANY_EMAIL, fileCandidate);
  }

  @Test
  public void initialize_thenInitializeBlameResultAndComparator() {
    FileBlamer fileBlamer = new FileBlamer(fileTreeComparator, null, null, fileReader, blameResult, false);

    ObjectReader objectReader = mock(ObjectReader.class);
    RawText rawText = mock(RawText.class);

    when(fileReader.loadText(any(ObjectReader.class), any(FileCandidate.class))).thenReturn(rawText);
    when(rawText.size()).thenReturn(2);
    when(fileCandidate.getBlob()).thenReturn(mock(ObjectId.class));
    when(fileCandidate.getPath()).thenReturn("path");

    CommitGraphNode statefulCommit = new CommitGraphNode(revCommit, 1);
    statefulCommit.addFile(fileCandidate);

    fileBlamer.initialize(objectReader, statefulCommit);

    verify(blameResult).initialize(anyString(), anyInt());
    verify(fileTreeComparator).initialize(objectReader);
  }

  @Test
  public void initializeWithLargeAmountOfFiles_thenInitializeBlameResultAndComparator() throws IOException {
    FileBlamer fileBlamer = new FileBlamer(fileTreeComparator, null, null, fileReader, blameResult, false);

    ObjectReader objectReader = mock(ObjectReader.class);

    CommitGraphNode statefulCommit = new CommitGraphNode(revCommit, NB_FILES_THRESHOLD_ONE_TREE_WALK);
    addFileCandidates(NB_FILES_THRESHOLD_ONE_TREE_WALK, statefulCommit);

    Map<String, Integer> filesize = statefulCommit.getAllFiles().stream().collect(Collectors.toMap(FileCandidate::getPath, f -> 30));
    when(fileReader.getFileSizes(anySet())).thenReturn(filesize);

    fileBlamer.initialize(objectReader, statefulCommit);

    verify(blameResult, times(NB_FILES_THRESHOLD_ONE_TREE_WALK)).initialize(anyString(), anyInt());
    verify(fileTreeComparator).initialize(objectReader);
  }

  @Test(expected = IllegalStateException.class)
  public void initializeWithLargeAmountOfFiles_throwsWhenError() throws IOException {
    FileBlamer fileBlamer = new FileBlamer(fileTreeComparator, null, null, fileReader, blameResult, false);

    ObjectReader objectReader = mock(ObjectReader.class);
    CommitGraphNode statefulCommit = new CommitGraphNode(revCommit, NB_FILES_THRESHOLD_ONE_TREE_WALK);
    addFileCandidates(NB_FILES_THRESHOLD_ONE_TREE_WALK, statefulCommit);

    when(fileReader.getFileSizes(anySet())).thenThrow(new IOException());

    fileBlamer.initialize(objectReader, statefulCommit);
  }

  @Test (expected = IllegalStateException.class)
  public void initializeWithLargeAmountOfFiles_throwsWhenFileNotInRepository() throws IOException {
    FileBlamer fileBlamer = new FileBlamer(fileTreeComparator, null, null, fileReader, blameResult, false);

    int nbFilesInRepo = NB_FILES_THRESHOLD_ONE_TREE_WALK + 10;
    ObjectReader objectReader = mock(ObjectReader.class);

    CommitGraphNode statefulCommit = new CommitGraphNode(revCommit, nbFilesInRepo);
    addFileCandidates(nbFilesInRepo, statefulCommit);

    Map<String, Integer> filesize = statefulCommit.getAllFiles().stream().collect(Collectors.toMap(FileCandidate::getPath, f -> 30));
    // Simulate a file is not present in the repository
    filesize.keySet().removeAll(filesize.keySet().stream().limit(1).collect(Collectors.toSet()));
    when(fileReader.getFileSizes(anySet())).thenReturn(filesize);

    fileBlamer.initialize(objectReader, statefulCommit);
  }

  private static void addFileCandidates(int numberOfFiles, CommitGraphNode statefulCommit) {

    for (int i = 0; i < numberOfFiles; i++) {
      FileCandidate fileCandidate = new FileCandidate("path" + i, "path" + i, mock(ObjectId.class));
      statefulCommit.addFile(fileCandidate);
    }
  }

}
