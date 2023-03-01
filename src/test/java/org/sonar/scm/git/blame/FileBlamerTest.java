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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FileBlamerTest {

  private final static Date ANY_DATE = Date.from(LocalDateTime.now().toInstant(ZoneOffset.UTC));
  private final static Date ANOTHER_DATE = Date.from(LocalDateTime.now().minusDays(1).toInstant(ZoneOffset.UTC));
  private final static String ANY_EMAIL = "email@email.com";
  private final static String ANY_COMMIT_NAME = "commit-name";

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
    when(personIdent.getWhen()).thenReturn(ANY_DATE);

    PersonIdent personIdent2 = mock(PersonIdent.class);
    when(revCommit.getCommitterIdent()).thenReturn(personIdent2);
    when(personIdent2.getEmailAddress()).thenReturn("another@email.com");
    when(personIdent2.getWhen()).thenReturn(ANOTHER_DATE);
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
}
