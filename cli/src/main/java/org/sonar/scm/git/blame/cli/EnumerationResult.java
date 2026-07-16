package org.sonar.scm.git.blame.cli;

import java.util.Set;

/**
 * Outcome of enumerating the files to blame under a given folder.
 *
 * @param filesToBlame     repository-root-relative paths that will be blamed
 * @param totalFilesInRepo total number of tracked files in the whole repository (mono-repo denominator)
 * @param filesUnderFolder number of tracked files located under the target folder (before exclusions)
 * @param filesExcluded    number of files under the folder that were dropped by exclusion globs
 */
public record EnumerationResult(Set<String> filesToBlame, int totalFilesInRepo, int filesUnderFolder, int filesExcluded) {
}
