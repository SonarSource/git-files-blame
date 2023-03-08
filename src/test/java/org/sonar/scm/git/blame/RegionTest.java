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

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RegionTest {

  @Test
  public void splitFirst_whenCalled_thenDontChangeResultStart() {
    Region region = new Region(0, 1, 2);

    Region newRegion = region.splitFirst(3, 4);

    assertThat(newRegion.resultStart).isEqualTo(region.resultStart);
    assertThat(newRegion.length).isEqualTo(4);
  }

  @Test
  public void slideAndShrink_whenDifferenceIsPositiveInteger_thenReduceLength() {
    Region region = new Region(10, 10, 20);

    region.slideAndShrink(5);

    assertThat(region.length).isEqualTo(15);
    assertThat(region.resultStart).isEqualTo(15);
  }

  @Test
  public void slideAndShrink_whenDifferenceIsZero_thenDontChangeTheObject() {
    Region region = new Region(10, 10, 20);

    region.slideAndShrink(0);

    assertThat(region.length).isEqualTo(20);
    assertThat(region.resultStart).isEqualTo(10);
    assertThat(region.sourceStart).isEqualTo(10);
  }

}
