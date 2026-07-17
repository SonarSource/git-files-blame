package org.sonar.scm.git.blame.cli;

import java.nio.file.Path;
import org.eclipse.jgit.lib.Repository;

/**
 * A git repository together with the location of the folder we want to blame inside it.
 *
 * @param repository the opened JGit repository (caller is responsible for closing it)
 * @param workTree   the absolute, canonical path of the repository working tree root
 * @param pathPrefix the target folder expressed as a repository-root-relative prefix using '/' separators.
 *                   Empty when the target folder is the repository root, otherwise it ends with '/'.
 */
public record LocatedRepository(Repository repository, Path workTree, String pathPrefix) {
}
