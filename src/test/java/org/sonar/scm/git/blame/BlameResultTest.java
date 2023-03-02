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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class BlameResultTest {

  private final static Date ANY_DATE = Date.from(LocalDateTime.now().toInstant(ZoneOffset.UTC));

  @Test
  public void process_whenFileCandidateHasOneRegionWithTwoLines_thenFileBlameContainsTwoLines() {
    BlameResult blameResult = new BlameResult();

    FileCandidate fileCandidate = new FileCandidate("path", "path", null);
    fileCandidate.setRegionList(new Region(0, 0, 2));
    blameResult.initialize("path", 2);

    blameResult.process("hash", ANY_DATE, "email", fileCandidate);

    assertThat(blameResult.getFileBlames()).hasSize(1);
    assertThat(blameResult.getFileBlameByPath().get("path").lines()).isEqualTo(2);
  }

  @Test
  public void process_whenFileCandidateHasTwoRegionsWithOneLineEach_thenFileBlameContainsTwoLines() {
    BlameResult blameResult = new BlameResult();

    FileCandidate fileCandidate = new FileCandidate("path", "path", null);
    Region regionHead = new Region(0, 0, 1);
    regionHead.next = new Region(1, 0, 1);
    fileCandidate.setRegionList(regionHead);
    blameResult.initialize("path", 2);

    blameResult.process("hash", ANY_DATE, "email", fileCandidate);

    assertThat(blameResult.getFileBlames()).hasSize(1);
    assertThat(blameResult.getFileBlameByPath().get("path").lines()).isEqualTo(2);
  }
}
