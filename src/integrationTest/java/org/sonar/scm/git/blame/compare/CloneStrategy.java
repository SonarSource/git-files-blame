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
 * How a {@link ComparisonScenario} is materialized into a real working copy on disk. {@link #CONE_SPARSE} only
 * scopes the worktree - the object database stays complete - so it is a determinism sanity check rather than a
 * real optimization. {@link #PARTIAL_SPARSE} additionally filters out blobs at clone time, closest to how a
 * bandwidth-conscious CI checkout of a huge monorepo actually looks.
 */
public enum CloneStrategy {

  FULL {
    @Override
    public void materialize(GitCli git, RepoSource source, Path dest, ComparisonScenario scenario) {
      git.clone(source.cloneUrl(), dest, false, false);
      git.checkout(dest, scenario.ref());
    }
  },
  CONE_SPARSE {
    @Override
    public void materialize(GitCli git, RepoSource source, Path dest, ComparisonScenario scenario) {
      git.clone(source.cloneUrl(), dest, true, false);
      git.sparseCheckoutInitCone(dest);
      git.sparseCheckoutSet(dest, scenario.sparsePaths());
      git.checkout(dest, scenario.ref());
    }
  },
  PARTIAL_SPARSE {
    @Override
    public void materialize(GitCli git, RepoSource source, Path dest, ComparisonScenario scenario) {
      git.clone(source.cloneUrl(), dest, true, true);
      git.sparseCheckoutInitCone(dest);
      git.sparseCheckoutSet(dest, scenario.sparsePaths());
      git.checkout(dest, scenario.ref());
    }
  };

  public abstract void materialize(GitCli git, RepoSource source, Path dest, ComparisonScenario scenario);
}
