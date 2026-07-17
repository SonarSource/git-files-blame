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

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileTreeComparatorTest {

  @Test
  void commonSubfolder_whenNoCommonSubfolder_thenEmpty() {
    assertThat(FileTreeComparator.commonSubfolder(null)).isEmpty();
    assertThat(FileTreeComparator.commonSubfolder(Set.of())).isEmpty();
    assertThat(FileTreeComparator.commonSubfolder(Set.of("fileA.txt"))).isEmpty();
    assertThat(FileTreeComparator.commonSubfolder(Set.of("domainA/fileA.txt", "domainB/fileB.txt"))).isEmpty();
    assertThat(FileTreeComparator.commonSubfolder(Set.of("domainA/fileA.txt", "domainAB/fileB.txt"))).isEmpty();
  }

  @Test
  void commonSubfolder_whenSingleFileInSubfolder_thenReturnsItsFolder() {
    assertThat(FileTreeComparator.commonSubfolder(Set.of("domain/fileA.txt"))).contains("domain");
  }

  @Test
  void commonSubfolder_whenNestedCommonFolder_thenReturnsDeepestSharedFolder() {
    assertThat(FileTreeComparator.commonSubfolder(Set.of("net/ethtool/a.c", "net/ethtool/b.c"))).contains("net/ethtool");
    assertThat(FileTreeComparator.commonSubfolder(Set.of("net/ethtool/a.c", "net/ethtool/sub/c.c"))).contains("net/ethtool");
    assertThat(FileTreeComparator.commonSubfolder(Set.of("domain/fileA.txt", "domain/fileB.txt", "domain/sub/fileC.txt")))
      .contains("domain");
  }

  @Test
  void commonSubfolder_ignoresNameOnlyPrefixThatIsNotADirectory() {
    // "a/main..." and "a/maintenance..." share the string prefix "a/main" but their common directory is "a"
    assertThat(FileTreeComparator.commonSubfolder(Set.of("a/main/x.c", "a/maintenance/y.c"))).contains("a");
    assertThat(FileTreeComparator.commonSubfolder(Set.of("a/main/x.c", "a/maintenance/y.c"))).isNotEqualTo(Optional.of("a/main"));
  }

  @Test
  void isUnderCommonSubfolder_whenFilePathsIsNull_thenFalse() {
    assertThat(FileTreeComparator.isUnderCommonSubfolder(null)).isFalse();
  }

  @Test
  void isUnderCommonSubfolder_whenFilePathsIsEmpty_thenFalse() {
    assertThat(FileTreeComparator.isUnderCommonSubfolder(Set.of())).isFalse();
  }

  @Test
  void isUnderCommonSubfolder_whenSingleFileAtRoot_thenFalse() {
    assertThat(FileTreeComparator.isUnderCommonSubfolder(Set.of("fileA.txt"))).isFalse();
  }

  @Test
  void isUnderCommonSubfolder_whenSingleFileInASubfolder_thenTrue() {
    assertThat(FileTreeComparator.isUnderCommonSubfolder(Set.of("domain/fileA.txt"))).isTrue();
  }

  @Test
  void isUnderCommonSubfolder_whenAllFilesShareASubfolder_thenTrue() {
    Set<String> filePaths = Set.of("domain/fileA.txt", "domain/fileB.txt", "domain/sub/fileC.txt");

    assertThat(FileTreeComparator.isUnderCommonSubfolder(filePaths)).isTrue();
  }

  @Test
  void isUnderCommonSubfolder_whenFilesAreInDifferentTopLevelFolders_thenFalse() {
    Set<String> filePaths = Set.of("domainA/fileA.txt", "domainB/fileB.txt");

    assertThat(FileTreeComparator.isUnderCommonSubfolder(filePaths)).isFalse();
  }

  @Test
  void isUnderCommonSubfolder_whenFilesAreScatteredAtRoot_thenFalse() {
    Set<String> filePaths = Set.of("fileA.txt", "fileB.txt", "domain/fileC.txt");

    assertThat(FileTreeComparator.isUnderCommonSubfolder(filePaths)).isFalse();
  }

  @Test
  void isUnderCommonSubfolder_whenOneFileIsAtRootAndOthersAreNotDirectSiblings_thenFalse() {
    Set<String> filePaths = Set.of("fileA.txt", "domain/fileB.txt", "domain/fileC.txt");

    assertThat(FileTreeComparator.isUnderCommonSubfolder(filePaths)).isFalse();
  }

  @Test
  void isUnderCommonSubfolder_whenFilesShareANamePrefixButNotADirectory_thenFalse() {
    // "domainA/..." and "domainB/..." share the string prefix "domain" but not a common directory
    Set<String> filePaths = Set.of("domainA/fileA.txt", "domainAB/fileB.txt");

    assertThat(FileTreeComparator.isUnderCommonSubfolder(filePaths)).isFalse();
  }
}
