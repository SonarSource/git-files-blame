package org.sonar.scm.git.blame.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Provides the version from the jar manifest ({@code Implementation-Version}), falling back to
 * "dev" when running outside a packaged jar (e.g. from the IDE or tests).
 */
public class ManifestVersionProvider implements IVersionProvider {

  static String version() {
    String version = ManifestVersionProvider.class.getPackage().getImplementationVersion();
    return version != null ? version : "dev";
  }

  @Override
  public String[] getVersion() {
    return new String[] {"git-files-blame CLI " + version()};
  }
}
