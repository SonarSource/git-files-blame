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


/**
 * Region of the result that still needs to be computed.
 * <p>
 * Regions are held in a singly-linked-list by {@link FileCandidate} using the
 * {@link FileCandidate#regionList} field. The list is kept in sorted order by
 * {@link #resultStart}.
 */
class Region {
  /**
   * Next entry in the region linked list.
   */
  Region next;

  /**
   * First position of this region in the result file blame is computing.
   */
  int resultStart;

  /**
   * First position in the {@link FileCandidate} that owns this Region.
   */
  int sourceStart;

  /**
   * Length of the region, always &gt;= 1.
   */
  int length;

  Region(int resultStart, int sourceStart, int length) {
    this.resultStart = resultStart;
    this.sourceStart = sourceStart;
    this.length = length;
  }

  /**
   * Split the region, assigning a new source position to the first half.
   *
   * @param newSource the new source position.
   * @param newLength    length of the new region.
   * @return the first half of the region, at the new source.
   */
  Region splitFirst(int newSource, int newLength) {
    return new Region(resultStart, newSource, newLength);
  }

  /**
   * Edit this region to remove the first {@code d} elements.
   *
   * @param elementsToRemove number of elements to remove from the start of this region.
   */
  void slideAndShrink(int elementsToRemove) {
    resultStart += elementsToRemove;
    sourceStart += elementsToRemove;
    length -= elementsToRemove;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder();
    Region r = this;
    do {
      if (r != this) {
        buf.append(',');
      }
      buf.append(r.resultStart);
      buf.append('-');
      buf.append(r.resultStart + r.length);
      r = r.next;
    } while (r != null);
    return buf.toString();
  }
}
