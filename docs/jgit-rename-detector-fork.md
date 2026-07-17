# Forked JGit rename-detection classes

The package `org.sonar.scm.git.blame.diff` contains five classes copied verbatim from JGit:

| Copy | Upstream original |
| --- | --- |
| `RenameDetector` | `org.eclipse.jgit.diff.RenameDetector` |
| `SimilarityRenameDetector` | `org.eclipse.jgit.diff.SimilarityRenameDetector` |
| `DiffEntry` | `org.eclipse.jgit.diff.DiffEntry` |
| `ContentSource` | `org.eclipse.jgit.diff.ContentSource` |
| `SimilarityIndex` | `org.eclipse.jgit.diff.SimilarityIndex` |

Each carries the header `Copied from JGit to apply the fix https://git.eclipse.org/r/c/jgit/jgit/+/200218/1 / Do not modify it`.

## Why they are copied

JGit's rename detection lives in package-private classes (`SimilarityRenameDetector`,
`SimilarityIndex`) and constructs itself around a package-private `RenameDetector` state. To
apply a fix that upstream never merged (Gerrit change 200218, whose link is now dead), the
whole cluster had to be duplicated into a package we own: `RenameDetector` needs the patched
`SimilarityRenameDetector`, which needs `SimilarityIndex` and `ContentSource`, and all of them
speak in terms of `DiffEntry`. They are kept byte-for-byte identical to upstream except for
the fix below, so re-syncing against a newer JGit stays mechanical — hence the "Do not modify
it" header.

Note that `FilteredRenameDetector` (in the parent package, **not** copied — it is ours) wraps
the forked `RenameDetector` to restrict rename detection to the blamed paths; see the README.

## The fix (Gerrit 200218): one deleted source must not become several renames

Upstream JGit can pair a single **deleted** source with several **added** destinations and
report *every* pairing as a `RENAME`. For a normal one-file diff that never surfaces, but this
library blames many files at once, so several blamed destinations can legitimately match the
same deleted blob. Reporting each as a RENAME makes more than one blamed file follow its
history back to the same deleted origin, corrupting the blame result.

The fix makes the **first** pairing of a given deleted source a `RENAME` and every subsequent
pairing of that same source a `COPY`, and removes each deleted entry from the leftover set only
once. Concretely, relative to pristine JGit:

### `RenameDetector`

- New field tracking which deleted (source) paths a rename has already consumed:
  ```java
  // Old paths of deleted that have been matched in renames. If the corresponding
  // deleted are matched again with other added, they'll be considered to be copies
  // instead of renames.
  private Set<String> matchedDeletedPaths;
  ```
- `reset()` initializes it (`matchedDeletedPaths = new HashSet<>();`).
- `findExactRenames()` records the source path each time it emits an exact rename (the
  one-add/one-delete, one-add/many-deletes and many-adds/many-deletes branches), and in the
  many-to-many branch uses `matchedDeletedPaths.add(...)` to decide `RENAME` vs `COPY`.
- `findContentRenames()` passes the shared set into the similarity detector:
  `new SimilarityRenameDetector(reader, deleted, added, matchedDeletedPaths)`.
- `compute()` drops the consumed deletes exactly once, instead of unconditionally:
  ```java
  deleted.removeIf(d -> matchedDeletedPaths.contains(d.getOldPath()));
  matchedDeletedPaths = null;
  ```

### `SimilarityRenameDetector`

- Constructor takes the shared `matchedSrcsPaths` set.
- In `compute()`, the first content match of a source is a `RENAME`, the rest are `COPY`:
  ```java
  ChangeType type;
  if (matchedSrcsPaths.add(s.getOldPath())) {
    type = ChangeType.RENAME;
  } else {
    type = ChangeType.COPY;
  }
  ```

`DiffEntry`, `ContentSource` and `SimilarityIndex` are copied unchanged; they are only present
so the two patched classes have their package-private collaborators reachable in our package.

## Re-syncing with a newer JGit

When bumping the JGit dependency, re-copy the five upstream classes, then re-apply the two
edits above (the `matchedDeletedPaths` field/plumbing in `RenameDetector` and the
`matchedSrcsPaths` RENAME-vs-COPY branch in `SimilarityRenameDetector`). The copy-vs-rename
cases in `FilteredRenameDetectorTest` / `FilteredRenameDetectorIT` guard the behaviour.
