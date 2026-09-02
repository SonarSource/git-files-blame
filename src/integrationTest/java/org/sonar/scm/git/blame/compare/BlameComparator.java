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
package org.sonar.scm.git.blame.compare;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.sonar.scm.git.blame.BlameResult.FileBlame;

/**
 * Compares this library's per-file blame against the native-git ground truth, line by line. Uses {@link
 * SoftAssertions} so a single run reports every mismatching line instead of failing on the first one.
 */
public final class BlameComparator {

  private BlameComparator() {
  }

  /**
   * @param knownAmbiguousLines 1-indexed line numbers where this library is documented to legitimately report a
   *                            different (but equally valid) attribution than native git - e.g. a whitespace-only
   *                            hunk boundary that JGit's {@code WS_IGNORE_ALL} and native git's {@code -w} break
   *                            the opposite way, so both point at a real commit that contains that exact
   *                            (whitespace-folded) line. A line here still has to match either native git's answer
   *                            or this documented alternate, so a genuinely new divergence still fails.
   */
  public static void assertSameBlame(String path, FileBlame actual, List<NativeLineBlame> expected, Map<Integer, NativeLineBlame> knownAmbiguousLines) {
    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.lines())
      .as("%s: number of blamed lines", path)
      .isEqualTo(expected.size());

    int lineCount = Math.min(actual.lines(), expected.size());
    for (int line = 0; line < lineCount; line++) {
      NativeLineBlame nativeLine = expected.get(line);
      NativeLineBlame actualLine = new NativeLineBlame(actual.getCommitHashes()[line], actual.getAuthorEmails()[line], actual.getCommitDates()[line]);
      if (actualLine.equals(nativeLine) || actualLine.equals(knownAmbiguousLines.get(line + 1))) {
        continue;
      }
      softly.assertThat(actualLine.commitHash())
        .as("%s:%d commit hash", path, line + 1)
        .isEqualTo(nativeLine.commitHash());
      softly.assertThat(actualLine.authorEmail())
        .as("%s:%d author email", path, line + 1)
        .isEqualTo(nativeLine.authorEmail());
      softly.assertThat(actualLine.committerInstant())
        .as("%s:%d committer date", path, line + 1)
        .isEqualTo(nativeLine.committerInstant());
    }
    softly.assertAll();
  }
}
