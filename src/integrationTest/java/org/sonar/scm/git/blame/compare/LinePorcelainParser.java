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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the output of {@code git blame --line-porcelain}. Unlike plain {@code --porcelain}, {@code
 * --line-porcelain} repeats the full commit metadata block for every line, so no state needs to be kept across
 * lines - each block is self-contained.
 */
public final class LinePorcelainParser {

  private static final Pattern HEADER_LINE = Pattern.compile("^[0-9a-f]{40} \\d+ \\d+(?: \\d+)?$");

  private LinePorcelainParser() {
  }

  public static List<NativeLineBlame> parse(String porcelainOutput) {
    List<NativeLineBlame> result = new ArrayList<>();
    String[] lines = porcelainOutput.split("\n", -1);
    int i = 0;
    while (i < lines.length && !lines[i].isEmpty()) {
      Matcher headerMatcher = HEADER_LINE.matcher(lines[i]);
      if (!headerMatcher.matches()) {
        throw new IllegalStateException("Expected a commit header line, got: " + lines[i]);
      }
      String commitHash = lines[i].substring(0, 40);
      i++;

      Map<String, String> fields = new HashMap<>();
      while (i < lines.length && !lines[i].startsWith("\t")) {
        int separator = lines[i].indexOf(' ');
        String key = separator >= 0 ? lines[i].substring(0, separator) : lines[i];
        String value = separator >= 0 ? lines[i].substring(separator + 1) : "";
        fields.put(key, value);
        i++;
      }
      // Skip the tab-prefixed content line.
      i++;

      result.add(toLineBlame(commitHash, fields));
    }
    return result;
  }

  private static NativeLineBlame toLineBlame(String commitHash, Map<String, String> fields) {
    String authorEmail = stripAngleBrackets(fields.get("author-mail"));
    Instant committerInstant = Instant.ofEpochSecond(Long.parseLong(fields.get("committer-time")));
    return new NativeLineBlame(commitHash, authorEmail, committerInstant);
  }

  private static String stripAngleBrackets(String authorMail) {
    if (authorMail.startsWith("<") && authorMail.endsWith(">")) {
      return authorMail.substring(1, authorMail.length() - 1);
    }
    return authorMail;
  }
}
