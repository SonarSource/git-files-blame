/*
 * Git Files Blame
 * Copyright (C) 2009-2025 SonarSource Sàrl
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

import java.util.stream.IntStream;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class FileCandidateTest {

  private final static String ANY_PATH = "ANY";
  private final static ObjectId ANY_OBJECT_ID = ObjectId.fromRaw(new int[]{1,2,3,4,5});

  @Test
  public void takeBlame_whenNoRegionLeft_thenDontAssignAnyRegion() {
    FileCandidate child = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate parent = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);

    EditList editList = new EditList();
    editList.add(new Edit(1, 2, 1, 2));
    parent.takeBlame(editList, child);

    assertThat(child.getRegionList()).isNull();
    assertThat(parent.getRegionList()).isNull();
  }

  @Test
  public void takeBlame_whenEditNotInsideTheCandidateRegion_thenDontAssignAnyRegion() {
    FileCandidate child = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate parent = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    Region regionLeftToBlame = new Region(1, 0, 1);
    child.setRegionList(regionLeftToBlame);

    EditList editList = new EditList();
    editList.add(new Edit(0, 1, 0, 1));
    parent.takeBlame(editList, child);

    assertThat(child.getRegionList()).isEqualTo(regionLeftToBlame);
    assertThat(parent.getRegionList()).isNull();
  }

  /**
   * $ git blame file.js
   * ^ce463e1 (Author 2023-02-24 09:43:08 +0100 1) parent commit
   * 4e984bd7 (Author 2023-02-24 09:43:53 +0100 2) child commit
   */
  @Test
  public void takeBlame_whenChildSingleLineEditAtTheEndOfFile_thenSplitBlame() {
    FileCandidate child = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate parent = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    child.setRegionList(new Region(0, 0, 2));

    EditList editList = new EditList();
    editList.add(new Edit(0, 1, 0, 1));
    parent.takeBlame(editList, child);

    assertThat(child.getRegionList().length).isEqualTo(1);
    assertThat(child.getRegionList().resultStart).isZero();
    assertThat(parent.getRegionList().length).isEqualTo(1);
    assertThat(parent.getRegionList().resultStart).isEqualTo(1);
  }

  @Test
  public void takeBlame_whenEditExceedsRegion_thenParentsRegionShouldBeNull() {
    FileCandidate child = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate parent = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    child.setRegionList(new Region(0, 0, 2));

    EditList editList = new EditList();
    editList.add(new Edit(0, 3, 0, 3));
    parent.takeBlame(editList, child);

    assertThat(child.getRegionList().length).isEqualTo(2);
    assertThat(child.getRegionList().resultStart).isZero();
    assertThat(parent.getRegionList()).isNull();
  }

  @Test
  public void takeBlame_whenTwoEditsTenLinesEachInTheMiddleOfTheFile_thenChildAndParentShouldHaveTwoRegions() {
    FileCandidate child = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate parent = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    child.setRegionList(new Region(0, 0, 100));

    EditList editList = new EditList();
    editList.add(new Edit(0, 10, 0, 10));
    editList.add(new Edit(50, 60, 50, 60));
    parent.takeBlame(editList, child);

    assertThat(child.getRegionList().resultStart).isZero();
    assertThat(child.getRegionList().length).isEqualTo(10);
    assertThat(child.getRegionList().next.resultStart).isEqualTo(50);
    assertThat(child.getRegionList().next.length).isEqualTo(10);

    assertThat(parent.getRegionList().resultStart).isEqualTo(10);
    assertThat(parent.getRegionList().length).isEqualTo(40);
    assertThat(parent.getRegionList().next.resultStart).isEqualTo(60);
    assertThat(parent.getRegionList().next.length).isEqualTo(40);
  }

  @Test
  public void takeBlame_whenManyContinuousEditsCoveringWholeFile_thenParentShouldNotHaveAnyRegionsLeft() {
    FileCandidate child = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate parent = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    child.setRegionList(new Region(0, 0, 10));

    EditList editList = new EditList();
    IntStream.rangeClosed(0, 10).forEach(i -> editList.add(new Edit(i, i + 1, i, i + 1)));
    parent.takeBlame(editList, child);

    assertThat(child.getRegionList()).isNotNull();
    assertThat(child.getRegionList().length).isEqualTo(10);
    assertThat(parent.getRegionList()).isNull();
  }

  @Test
  public void takeBlame_sequenceANotEqualToSequenceB_sourceStartInParentIsNegative() {
    FileCandidate child = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate parent = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    child.setRegionList(new Region(0, 0, 10));

    EditList editList = new EditList();
    editList.add(new Edit(1, 2, 3, 4));
    parent.takeBlame(editList, child);

    assertThat(child.getRegionList()).isNotNull();
    assertThat(child.getRegionList().length).isEqualTo(1);
    assertThat(child.getRegionList().sourceStart).isEqualTo(3);
    assertThat(parent.getRegionList()).isNotNull();
    assertThat(parent.getRegionList().length).isEqualTo(3);
    assertThat(parent.getRegionList().sourceStart).isEqualTo(-2);
  }

  @Test
  public void mergeRegions_whenRegionsOverlappingAndStartAtTheSameLine_thenSecondRegionIsNull() {
    FileCandidate first = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate second = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);

    first.setRegionList(new Region(0, 0, 5));
    second.setRegionList(new Region(0, 0, 10));

    first.mergeRegions(second);


    assertThat(first.getRegionList()).isNotNull();
    assertThat(first.getRegionList().sourceStart).isZero();

    assertThat(second.getRegionList()).isNull();
  }

  @Test
  public void mergeRegions_whenNoRegions_expectException() {
    FileCandidate first = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate second = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);

    first.mergeRegions(second);

    assertThat(first.getRegionList()).isNull();
    assertThat(second.getRegionList()).isNull();
  }

  @Test
  public void mergeRegions_whenRegionsNotOverlappingAndStartAtTheSameLine_thenResultHasTwoRegions() {
    FileCandidate first = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate second = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);

    first.setRegionList(new Region(0, 0, 5));
    second.setRegionList(new Region(5, 0, 5));

    first.mergeRegions(second);

    assertThat(first.getRegionList()).isNotNull();
    assertThat(first.getRegionList().resultStart).isZero();
    assertThat(first.getRegionList().next.resultStart).isEqualTo(5);

    assertThat(second.getRegionList()).isNull();
  }

  @Test
  public void mergeRegions_whenRegionsAreTheSame_thenResultHasTwoRegions() {
    FileCandidate first = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate second = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);

    first.setRegionList(new Region(0, 2, 1));
    second.setRegionList(new Region(0, 2, 1));

    first.mergeRegions(second);

    assertThat(first.getRegionList()).isNotNull();
    assertThat(first.getRegionList().resultStart).isZero();
    assertThat(first.getRegionList().length).isEqualTo(1);
    //assertThat(first.getRegionList().next).isNull(); TODO - shouldn't this be null?

    assertThat(second.getRegionList()).isNull();
  }

  @Test
  public void mergeRegions_whenOneRegionInsideAnother_thenResultHasTwoRegions() {
    FileCandidate first = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);
    FileCandidate second = new FileCandidate(ANY_PATH, ANY_PATH, ANY_OBJECT_ID);

    first.setRegionList(new Region(0, 0, 10));
    second.setRegionList(new Region(4, 0, 4));

    first.mergeRegions(second);

    assertThat(first.getRegionList()).isNotNull();
    assertThat(first.getRegionList().resultStart).isZero();
    assertThat(first.getRegionList().length).isEqualTo(10);
    assertThat(first.getRegionList().next.resultStart).isEqualTo(4);
    assertThat(first.getRegionList().next.length).isEqualTo(4);
  }
}
