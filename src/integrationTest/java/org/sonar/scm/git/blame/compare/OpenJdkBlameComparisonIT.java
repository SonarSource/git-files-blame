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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

/**
 * Real-world coverage tier: clones the actual OpenJDK repository at a pinned, immutable tag (never a moving
 * branch, so only the clone - not the blame - is the expensive part) and blames a handful of long-lived,
 * heavily-edited files. Heavy (a full clone of OpenJDK's history), so it's excluded from the default {@code
 * integrationTest} task and only runs via {@code integrationTestFullQa} (see the {@code fullQa} tag), which CI
 * gates behind the {@code full-qa} PR label or a push to master.
 *
 * <p>Native git is invoked with flags that match this library's blame engine exactly (see {@link GitCli}
 * and {@code README.md}, "Blame semantics vs native git"), so String.java, Integer.java and Object.java all
 * match native git line-for-line - except for the three String.java lines below.
 */
@Tag("fullQa")
class OpenJdkBlameComparisonIT extends AbstractBlameComparisonIT {

  /**
   * Three String.java lines where JGit's {@code RawTextComparator.WS_IGNORE_ALL} and native git's {@code -w}
   * fold whitespace-only differences slightly differently, leaving a hunk-boundary tie that the two
   * implementations break the other way. L1931/L1934 are a straight swap between the 2007 import and Jim Gish's
   * 2012 revision; L2167 is a single line the library carries to Tobias Hartmann's 2015 revision where native
   * keeps it on the 2007 import. All are real commits that legitimately contain that exact (whitespace-folded)
   * line - this records the specific alternate the library reports rather than asserting a bogus divergence.
   */
  private static final NativeLineBlame IMPORT_COMMIT = new NativeLineBlame(
    "319a3b994703aac84df7bcde272adfcb3cdbbbf0", "duke@openjdk.org", Instant.parse("2007-12-01T00:00:00Z"));
  private static final NativeLineBlame GISH_2012_REVISION = new NativeLineBlame(
    "558e1362a9436e61319ddaaf03defe16c3f0465d", "jim.gish@oracle.com", Instant.parse("2012-07-27T20:17:11Z"));
  private static final NativeLineBlame HARTMANN_2015_REVISION = new NativeLineBlame(
    "4ed5b73f3df0ba2309a0500a019e4aa597ea094a", "thartmann@openjdk.org", Instant.parse("2015-11-03T08:42:11Z"));

  private static final Map<Integer, NativeLineBlame> STRING_JAVA_KNOWN_AMBIGUOUS_LINES = Map.of(
    1931, IMPORT_COMMIT,
    1934, GISH_2012_REVISION,
    2167, HARTMANN_2015_REVISION);

  private static final ComparisonScenario SCENARIO = new ComparisonScenario(
    "openjdk",
    new RepoSource.Remote("https://github.com/openjdk/jdk.git"),
    "jdk-21+35",
    List.of("src/java.base/share/classes/java/lang"),
    List.of(
      "src/java.base/share/classes/java/lang/Object.java",
      "src/java.base/share/classes/java/lang/String.java",
      "src/java.base/share/classes/java/lang/Integer.java"),
    Map.of("src/java.base/share/classes/java/lang/String.java", STRING_JAVA_KNOWN_AMBIGUOUS_LINES));

  @Override
  protected ComparisonScenario scenario() {
    return SCENARIO;
  }

  /**
   * This library has no on-demand promisor-fetch (see {@link AbstractBlameComparisonIT#supportsStrategy}), so a
   * blobless clone reliably throws {@code MissingObjectException} rather than surfacing a blame divergence -
   * documented in {@code README.md}, "Blame semantics vs native git".
   */
  @Override
  protected boolean supportsStrategy(CloneStrategy strategy) {
    return strategy != CloneStrategy.PARTIAL_SPARSE;
  }
}
