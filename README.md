# git-files-blame
A git command implemented with JGit that blames multiple files simultaneously.

## Usage


```java
BlameResult result = new RepositoryBlameCommand(repo) //JGit repository to be used for the blame
  .setFilePaths(List.of("fileA.java", "fileB.java")) //list of files to blame, all files of repository if not specified 
  .setMultithreading(true)
  .setStartCommit(commitId) //JGit commit id from where the blame will start, HEAD if not specified
  .call();
```

## Our idea

Currently, the git blame command is only applicable to a single file: We specify the file path to be blamed, and git returns the blame result for this single file by traversing the history of commits.  
In the event where we want to blame multiple files, we would need to call this command many times. Even though this operation can be parallelized, it has been proven to be slow on large volume of files: For each file, the original blame algorithm traverse all the commits from newest to oldest until the file is fully blamed.

Our tool propose to blame every file simultaneously, traversing the commit graph only once and saving the cost of operations that are normally done for each file.

## Optimizations compared to native git blame

The sections below describe how this library diverges from a plain `git blame` (or looping
JGit's `BlameCommand` over each file) to stay fast on large repositories and monorepos.

### One commit traversal for all files

This is the core idea. Native blame walks the whole history once *per file*; blaming _N_ files
means _N_ independent walks that each re-parse commits, re-open trees and re-run diffs. This
library walks the commit graph a **single** time, carrying the set of not-yet-blamed regions of
every file backwards together. Work that native blame repeats per file — parsing each commit,
loading trees, tree diffing, rename detection — is done once per commit hop and shared across
all blamed files.

### Patched JGit rename detection (the `diff` package)

Five classes under `org.sonar.scm.git.blame.diff` are copied from JGit to apply a fix that was
never merged upstream (the original Gerrit link is dead). Upstream JGit can report a single
deleted file as the rename source of *several* added files; when blaming many files at once,
that would make more than one file follow its history back to the same origin and corrupt the
result. The fix keeps the first match a `RENAME` and turns the rest into `COPY`. Full rationale
and the exact patch — kept in-repo so it survives the dead link — are in
[docs/jgit-rename-detector-fork.md](docs/jgit-rename-detector-fork.md).

### Rename detection restricted to the blamed files

`FilteredRenameDetector` narrows JGit's rename detection to only the files being blamed: added
files whose new path isn't among the blamed paths are dropped before rename scoring runs.
Rename detection is the most expensive step of a diff (content-similarity matching is roughly
quadratic in the number of adds × deletes), so restricting the destination set to what we
actually care about avoids a large amount of pointless similarity scoring.

### Reuse of blob content already read

Every file candidate's blob used to be read and line-split twice: once as the *parent* side of
an edit (or at the initial HEAD load) and again when the same object became the *source* side of
the diff at the next commit hop. The `RawText` is now cached on the candidate when read as the
parent/HEAD side and reused at the next hop. Measured **22–44% faster** on blob-loading-bound
histories (bigger gain the larger the files), negligible on rename-heavy ones.

### Path-scoped tree diff

When every blamed file lives under a common subfolder (e.g. analyzing one module of a monorepo),
the per-commit diff is scoped to that subfolder so JGit skips whole unchanged subtrees instead of
computing the full-repository diff and running rename detection over it. The decision is made
once from the request — whether the blamed files share a common subfolder — rather than from a
file-count threshold, so it still triggers when thousands of files are blamed as long as they are
a small slice of a much bigger tree.

### Path-scoped walk and merge-aware rename fallback

Two further exact optimizations kick in automatically when all blamed files share a common
subfolder:

- **Path-scoped walk** (`PathScopedWalk`): chains of single-parent commits whose subfolder tree
  is unchanged are collapsed, so commits that never touch the blamed subfolder are never enqueued
  or diffed. Big win on linear histories (JDK `src/java.xml`: ~51k → ~7k commit iterations).
- **Merge-aware rename fallback**: the expensive full-repository diff + rename detection that
  runs when a blamed file looks *added* is the real bottleneck on merge-heavy repositories. At a
  merge it is now skipped for a parent when the added file is carried unchanged by another parent
  (which claims its regions before any diff splitting, so the fallback result couldn't affect the
  blame anyway). Linux `net/ethtool`: ~100s → ~5s.

Both are exact — verified line-for-line against the unscoped walk — and merges that genuinely
combine subfolder changes still run the full fallback.

### Optional multithreading

`setMultithreading(true)` parallelizes the per-file work within each commit (diffing each
modified file against its parent blob) across a thread pool sized to the available processors.
The commit traversal itself stays single-threaded; only the independent per-file splits fan out.

## Blame semantics vs native git

Besides being faster, this library also makes a few **behavioral** choices that differ from a
plain `git blame`. They rarely matter, but on files with lots of repeated/boilerplate lines
(e.g. Javadoc) they change which commit a line is attributed to. To reproduce native git's
output line-for-line you must invoke `git blame` with the matching flags shown below (this is
exactly what the `*BlameComparisonIT` integration tests do — see `GitCli`).

| Behavior | This library | Plain `git blame` default | Flag to make native match |
| --- | --- | --- | --- |
| Diff algorithm | JGit `HistogramDiff` | Myers | `--diff-algorithm=histogram` |
| Indent heuristic | not applied (JGit's `HistogramDiff` has none) | applied (`diff.indentHeuristic=true`) | `-c diff.indentHeuristic=false` |
| Intra-file move/copy detection | **none** | none by default, but the tests' ground truth used `-M` | *drop* `-M` |
| Whitespace | set by the caller via `setTextComparator(...)`; the default is `RawTextComparator.DEFAULT` (whitespace-sensitive) | whitespace-sensitive | `-w` **iff** the caller uses `WS_IGNORE_ALL` |
| `.mailmap` | **not applied** - author identity is read straight off the commit object | applied by default | *(see below - the comparison ITs delete the checked-out `.mailmap` instead)* |

Notes:

- **Move/copy detection is the big one.** `git blame -M`/`-C` detect that a block of added lines
  is a move or copy of lines already present, and attribute them to the *original* source commit.
  This library does no such intra-file move/copy detection, so it attributes those lines to the
  commit that actually added them. Comparing against `git blame -M` therefore shows large,
  systematic (but expected) differences; comparing without `-M` does not.
- **Whitespace handling is delegated to the caller.** The library defaults to whitespace-sensitive
  blame. SonarQube's scanner engine (the primary consumer) opts into
  `setTextComparator(RawTextComparator.WS_IGNORE_ALL)`, which is equivalent to native `git blame -w`
  and matches its old native/JGit blame implementations. `WS_IGNORE_ALL` and `-w` agree except for
  a handful of whitespace-only hunk-boundary ties that the two implementations break differently.
- **No `.mailmap` support.** Native `git blame` rewrites historical author identities to their
  canonical form via `.mailmap` by default; this library has no equivalent because JGit doesn't
  implement one (tracked upstream as
  [eclipse-jgit/jgit#260](https://github.com/eclipse-jgit/jgit/issues/260)). On a repo with a
  real `.mailmap` (e.g. the Linux kernel), this alone accounts for hundreds of author-identity
  "divergences" that have nothing to do with which commit a line is attributed to. The comparison
  ITs delete the checked-out `.mailmap` before running native git so the two are compared on equal
  footing; `RepositoryBlameCommandIT#blame_whenRepoHasMailmap_thenAuthorEmailIsNotRemapped` is a
  canary that fails if a future JGit version adds mailmap resolution, as a reminder to revisit both
  this table and that IT workaround.

### Partial (blobless) clones are not supported

`RepositoryBlameCommand` reads objects directly through JGit's `ObjectReader`, which has no
on-demand promisor fetch. On a `--filter=blob:none` clone, blaming back through history past the
blobs actually checked out throws `MissingObjectException` instead of returning a (possibly
incomplete) blame. Native git transparently fetches missing blobs from the promisor remote in this
situation. `LinuxKernelBlameComparisonIT` and `OpenJdkBlameComparisonIT` therefore skip the
`PARTIAL_SPARSE` clone strategy against these real, network-backed remotes (see
`AbstractBlameComparisonIT#supportsStrategy`); tracked as
[SCANENGINE-23](https://sonarsource.atlassian.net/browse/SCANENGINE-23).

### kernel/sched/fair.c's native-git ground truth isn't stable in CI

`LinuxKernelBlameComparisonIT` blames `kernel/sched/fair.c` for its clone/blame timing but doesn't
assert the result matches native git line-for-line, unlike `kernel/sched/core.c`. Two full-QA CI
runs of the exact same commit disagreed with each other on ~149 lines of `fair.c`, even though
nothing in that commit's diff could affect `fair.c`'s code path. Both native git and this library
were independently confirmed fully deterministic everywhere this was tested locally (repeated
invocations, matching git version/build-options/architecture between the runner and a dev
machine) - the actual mechanism wasn't found. See
[GFB-54](https://sonarsource.atlassian.net/browse/GFB-54).

## Have Question or Feedback?

For support questions ("How do I?", "I got this error, why?", ...), please first read the [documentation](https://docs.sonarqube.org) and then head to the [SonarSource Community](https://community.sonarsource.com/c/help/sq/10). The answer to your question has likely already been answered! 🤓

Be aware that this forum is a community, so the standard pleasantries ("Hi", "Thanks", ...) are expected. And if you don't get an answer to your thread, you should sit on your hands for at least three days before bumping it. Operators are not standing by. 😄

## Contributing

If you would like to see a new feature, please create a new Community thread: ["Suggest new features"](https://community.sonarsource.com/c/suggestions/features).

Please be aware that we are not actively looking for feature contributions. The truth is that it's extremely difficult for someone outside SonarSource to comply with our roadmap and expectations. Therefore, we typically only accept minor cosmetic changes and typo fixes.

With that in mind, if you would like to submit a code contribution, please create a pull request for this repository. Please explain your motives to contribute this change: what problem you are trying to fix, what improvement you are trying to make.

Make sure that you follow our [code style](https://github.com/SonarSource/sonar-developer-toolset#code-style) and all tests are passing (Travis build is executed for each pull request).

Willing to contribute to SonarSource products? We are looking for smart, passionate, and skilled people to help us build world-class code quality solutions. Have a look at our current [job offers here](https://www.sonarsource.com/company/jobs/)!

### Build and Run Unit Tests

Execute from project base directory:

    ./gradlew build
