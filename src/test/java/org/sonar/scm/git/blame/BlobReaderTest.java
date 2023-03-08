/*
 * Git Files Blame
 * Copyright (C) 2009-2023 SonarSource SA
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BlobReaderTest {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();
  private final ObjectReader objectReader = mock(ObjectReader.class);

  @Test
  public void loadText_whenObjectExists_thenReturnsNotEmptyObject() throws IOException {
    byte[] rawText = {51, 52, 53, 54, 55};
    ObjectId objectId = new ObjectId(1, 2, 3, 4, 5);
    ObjectLoader objectLoader = mock(ObjectLoader.class);

    when(objectReader.open(objectId, Constants.OBJ_BLOB)).thenReturn(objectLoader);
    when(objectLoader.getCachedBytes(Integer.MAX_VALUE)).thenReturn(rawText);

    FileCandidate fc = mock(FileCandidate.class);
    when(fc.getBlob()).thenReturn(objectId);

    byte[] rawContent = new BlobReader(mock(Repository.class)).loadText(objectReader, fc).getRawContent();

    assertThat(rawContent).isEqualTo(new RawText(rawText).getRawContent());
  }
}
