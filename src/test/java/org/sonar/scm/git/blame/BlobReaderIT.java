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

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BlobReaderIT extends AbstractGitIT {
  @Test
  public void loadText_whenWorkDirectoryAndFileDoesntExist_thenThrowISE() {
    BlobReader reader = new BlobReader(git.getRepository());
    ObjectReader objectReader = git.getRepository().newObjectReader();
    FileCandidate fc = mock(FileCandidate.class);
    when(fc.getBlob()).thenReturn(ObjectId.zeroId());
    when(fc.getOriginalPath()).thenReturn("invalid");

    Assert.assertThrows(IllegalStateException.class, () -> reader.loadText(objectReader, fc));
  }
}