/*
 * SonarQube
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

import java.io.IOException;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;

/**
 * Reads the contents of a BLOB object (typically a file)
 */
public class BlobReader {
  public RawText loadText(ObjectReader objectReader, ObjectId objectId) {
    try {
      // TODO applySmudgeFilter? See implementation in Candidate#loadText. Apparently only used for the support of
      // git Large File Storage (LFS)
      ObjectLoader open = objectReader.open(objectId, Constants.OBJ_BLOB);
      return new RawText(open.getCachedBytes(Integer.MAX_VALUE));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
