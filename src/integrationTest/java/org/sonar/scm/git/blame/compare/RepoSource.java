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

import java.nio.file.Path;

/**
 * Where a comparison scenario clones its repository from: either a local bare repository (offline, fast,
 * used by the synthetic-repo scenario) or a remote URL (used by the OpenJDK/Linux kernel scenarios).
 */
public sealed interface RepoSource {

  String cloneUrl();

  record Local(Path bareRepoPath) implements RepoSource {
    @Override
    public String cloneUrl() {
      return bareRepoPath.toUri().toString();
    }
  }

  record Remote(String url) implements RepoSource {
    @Override
    public String cloneUrl() {
      return url;
    }
  }
}
