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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/**
 * Speeds up blaming files that all live under a common subfolder by letting the blame walk skip over the (often
 * large) majority of commits that never touch that subfolder.
 *
 * <p>The blame algorithm walks the whole commit ancestry of the start commit, carrying the regions still to be
 * blamed backwards commit by commit. On a big repository where only a small subfolder is blamed (a monorepo where
 * one module is analyzed, say), most commits touch other parts of the tree and leave the blamed subfolder
 * untouched. Those commits contribute nothing to the blame - every region simply moves unchanged to their parent -
 * yet each one is still visited, diffed and enqueued.
 *
 * <p>{@link #collapse(RevCommit)} detects such commits cheaply, by comparing the object id of the subfolder's tree
 * between a commit and its parent (a commit that didn't touch the subfolder has the exact same subfolder tree id as
 * its parent). It then follows the chain of single-parent ancestors that left the subfolder untouched and returns
 * the first ancestor that changed it (or a merge or root commit). Skipping a commit is safe precisely because its
 * subfolder tree is identical to the returned ancestor's, so moving the regions across the skipped span is a no-op.
 *
 * <p>The subfolder tree ids are read on demand and cached, so each ancestor is inspected at most once - there is no
 * separate up-front history pass. Renames <em>within</em> the subfolder are handled naturally: they change the
 * subfolder's tree, so the commit is kept as a boundary and diffed as usual.
 *
 * <p>Renames <em>into</em> the subfolder from an outside path need more care. Skipping is only safe for content that
 * stayed inside the subfolder for the whole skipped span; a file renamed in from elsewhere carries content whose
 * source lived outside the subfolder and may have been modified by a commit we would otherwise skip. Two guards keep
 * the result exact: {@link #simplifiedParents(RevCommit, Set)} does not collapse the parent of a commit that adds a
 * blamed path to the subfolder (its rename source, resolved by the diff, could be an outside path touched in the
 * skipped span), and it stops collapsing altogether once any blamed file has moved outside the subfolder, letting the
 * normal walk follow that content wherever it goes.
 *
 * <p>Merges are NOT simplified - all their parents are kept and diffed as usual, so the result stays identical to
 * the unscoped walk. Skipping a merge parent based on the subfolder tree is tempting but not exact: blame works at
 * line granularity, and a line can match a parent whose overall subfolder tree differs from the merge, so the
 * unscoped walk can follow that line into a parent a folder-tree comparison would have pruned. That means this
 * optimization only reduces work between merges; on a history whose mainline is a near-unbroken series of merges
 * (the Linux kernel, say) there is little to skip.
 */
class PathScopedWalk {
  private final RevWalk revPool;
  private final ObjectReader objectReader;
  private final String folder;
  private final Map<ObjectId, ObjectId> folderTreeIdCache = new HashMap<>();
  private final Map<ObjectId, RevCommit> collapseCache = new HashMap<>();

  /**
   * @param revPool the walk's revision pool, reused to parse the ancestors visited while collapsing
   * @param folder the common subfolder shared by every blamed file (no trailing slash)
   */
  PathScopedWalk(RevWalk revPool, String folder) {
    this.revPool = revPool;
    this.objectReader = revPool.getObjectReader();
    this.folder = folder;
  }

  /**
   * The parents the blame walk should actually descend into for {@code commit}. A single-parent commit yields its
   * collapsed parent, skipping the chain of ancestors that don't touch the blamed subfolder. A merge yields all its
   * parents unchanged (see the class javadoc for why merges can't be simplified exactly).
   *
   * @param blamedPaths the current paths of the files still being blamed at {@code commit}
   */
  List<RevCommit> simplifiedParents(RevCommit commit, Set<String> blamedPaths) throws IOException {
    int parentCount = commit.getParentCount();
    if (parentCount != 1) {
      // Merges keep all their parents and are diffed as usual: skipping a merge parent (even one whose subfolder tree
      // matches the merge) is not exact, because blame works at line granularity and a line can match a parent the
      // merge's subfolder tree differs from - the unscoped walk follows those line-level matches into that parent.
      List<RevCommit> parents = new ArrayList<>(parentCount);
      for (int i = 0; i < parentCount; i++) {
        parents.add(parseParent(commit, i));
      }
      return parents;
    }

    RevCommit parent = parseParent(commit, 0);
    // A blamed file living outside the subfolder was renamed in from elsewhere and is now followed by its old path;
    // the commits that touched it don't touch the subfolder, so collapsing would wrongly skip them. Fall back to the
    // real parent so the normal walk follows that content.
    if (!allUnderFolder(blamedPaths)) {
      return List.of(parent);
    }

    RevCommit collapsed = collapse(parent);
    // When collapse skipped commits, this commit is diffed against a further-back ancestor instead of its real
    // parent. That is exact for content that stayed in the subfolder, but not for a file added to the subfolder here
    // by a rename: its source can be an outside path modified in a skipped commit, which the ancestor no longer
    // reflects. Diff such a commit against its real parent instead. When nothing was skipped there is no such gap.
    if (collapsed != parent && addsBlamedPathToFolder(commit, parent)) {
      return List.of(parent);
    }
    return List.of(collapsed);
  }

  private boolean allUnderFolder(Set<String> paths) {
    String folderPrefix = folder + "/";
    return paths.stream().allMatch(path -> path.startsWith(folderPrefix));
  }

  /**
   * @return true if {@code commit} adds a file to the blamed subfolder relative to {@code parent}. A path-filtered
   *         diff restricted to the subfolder, so it only visits the files that changed there.
   */
  private boolean addsBlamedPathToFolder(RevCommit commit, RevCommit parent) throws IOException {
    try (TreeWalk treeWalk = new TreeWalk(objectReader)) {
      treeWalk.addTree(parent.getTree());
      treeWalk.addTree(commit.getTree());
      treeWalk.setRecursive(true);
      treeWalk.setFilter(AndTreeFilter.create(PathFilter.create(folder), TreeFilter.ANY_DIFF));
      while (treeWalk.next()) {
        // Absent in the parent (tree 0) but present in the commit (tree 1): a file added into the subfolder.
        if (treeWalk.getRawMode(0) == 0) {
          return true;
        }
      }
      return false;
    }
  }

  private RevCommit parseParent(RevCommit commit, int i) throws IOException {
    RevCommit parent = commit.getParent(i);
    revPool.parseHeaders(parent);
    return parent;
  }

  /**
   * Follows single-parent ancestors that leave the blamed subfolder untouched and returns the first ancestor that
   * changes it, or the first merge or root commit reached. The returned commit has the exact same subfolder tree as
   * {@code commit}, so the blame regions can be moved onto it directly.
   */
  RevCommit collapse(RevCommit commit) throws IOException {
    RevCommit cached = collapseCache.get(commit.getId());
    if (cached != null) {
      return cached;
    }

    List<ObjectId> skipped = new ArrayList<>();
    RevCommit current = commit;
    boolean canSkip = true;
    while (canSkip && current.getParentCount() == 1) {
      RevCommit alreadyResolved = collapseCache.get(current.getId());
      if (alreadyResolved != null) {
        current = alreadyResolved;
        canSkip = false;
      } else if (canSkipCurrent(current)) {
        skipped.add(current.getId());
        current = current.getParent(0);
      } else {
        canSkip = false;
      }
    }

    for (ObjectId id : skipped) {
      collapseCache.put(id, current);
    }
    return current;
  }

  /**
   * @return true if {@code current} left the blamed subfolder untouched relative to its single parent, so the blame
   *         regions can be moved straight onto that parent. Parses the parent's headers as a side effect.
   */
  private boolean canSkipCurrent(RevCommit current) throws IOException {
    RevCommit parent = current.getParent(0);
    revPool.parseHeaders(parent);
    ObjectId currentFolder = folderTreeId(current);
    // Don't skip when the subfolder doesn't exist at current: the blamed files lived elsewhere back then (a directory
    // rename/reorg), and only the normal walk follows content across that boundary. Don't skip when current changed
    // the subfolder relative to its parent either - it must be diffed.
    return !currentFolder.equals(ObjectId.zeroId()) && currentFolder.equals(folderTreeId(parent));
  }

  /**
   * @return the object id of the subfolder's tree in the given commit, or {@link ObjectId#zeroId()} if the
   *         subfolder doesn't exist there.
   */
  private ObjectId folderTreeId(RevCommit commit) throws IOException {
    ObjectId cached = folderTreeIdCache.get(commit.getId());
    if (cached != null) {
      return cached;
    }
    ObjectId id;
    try (TreeWalk treeWalk = TreeWalk.forPath(objectReader, folder, commit.getTree())) {
      id = treeWalk != null ? treeWalk.getObjectId(0) : ObjectId.zeroId();
    }
    folderTreeIdCache.put(commit.getId(), id);
    return id;
  }
}
