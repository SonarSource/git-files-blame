/*
 * Git Files Blame
 * Copyright (C) SonarSource Sàrl
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
package org.sonar.scm.git.blame.compare;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

/**
 * Real-world coverage tier: clones the actual Linux kernel repository at a pinned, immutable tag and blames a
 * couple of long-lived, heavily-edited scheduler files. This is the slowest scenario in the suite - the kernel's
 * full history is multi-gigabyte - so, like {@link OpenJdkBlameComparisonIT}, it's excluded from the default
 * {@code integrationTest} task and only runs via {@code integrationTestFullQa}, gated behind the {@code full-qa}
 * PR label or a push to master.
 */
@Tag("fullQa")
class LinuxKernelBlameComparisonIT extends AbstractBlameComparisonIT {

  /**
   * Lines where this library attributes kernel/sched/core.c to a different commit than native git -
   * tracked as <a href="https://sonarsource.atlassian.net/browse/GFB-52">GFB-52</a>. Both algorithms'
   * region tracking through the one-to-many split of the old monolithic kernel/sched.c into
   * kernel/sched/{core,fair,...}.c (391e43da797a, 2011) pick a different, real ancestor for these
   * specific lines; unlike {@link OpenJdkBlameComparisonIT}'s known-ambiguous-lines map, this isn't a
   * case of two equally valid answers - it's the actual bug GFB-52 tracks, recorded here so the IT
   * documents the known gap instead of failing on it.
   */
  private static final NativeLineBlame CFS_REWRITE = new NativeLineBlame(
    "dd41f596cda0d7d6e4a8b139ffdfabcefdd46528", "mingo@elte.hu", Instant.parse("2007-07-09T16:51:59Z"));
  private static final NativeLineBlame C_02e4bac = new NativeLineBlame(
    "02e4bac2a5b097e23d757bf2953740b3d51b7976", "mingo@elte.hu", Instant.parse("2007-10-15T15:00:11Z"));
  private static final NativeLineBlame C_2c9a98d = new NativeLineBlame(
    "2c9a98d3bc808717ab63ad928a2b568967775388", "peterz@infradead.org", Instant.parse("2021-02-17T13:12:42Z"));
  private static final NativeLineBlame C_2dd73a4 = new NativeLineBlame(
    "2dd73a4f09beacadde827a032cf15fd8b1fa3d48", "pwil3058@bigpond.net.au", Instant.parse("2006-06-28T00:32:44Z"));
  private static final NativeLineBlame C_2ddbf95 = new NativeLineBlame(
    "2ddbf952508fb9911036c484a87f6351106b917c", "h-shimamoto@ct.jp.nec.com", Instant.parse("2007-10-15T15:00:11Z"));
  private static final NativeLineBlame C_3117df0 = new NativeLineBlame(
    "3117df0453828bd045c16244e6f50e5714667a8a", "mingo@elte.hu", Instant.parse("2006-12-13T17:05:50Z"));
  private static final NativeLineBlame C_3a101d0 = new NativeLineBlame(
    "3a101d0548e925ab16ca6aaa8cf4f767d322ddb0", "tj@kernel.org", Instant.parse("2010-06-08T19:40:36Z"));
  private static final NativeLineBlame C_41a2d6c = new NativeLineBlame(
    "41a2d6cfa3f77ec469e7e5f06b4d7ffd031f9c0e", "mingo@elte.hu", Instant.parse("2007-12-05T14:46:09Z"));
  private static final NativeLineBlame C_434d53b = new NativeLineBlame(
    "434d53b00d6bb7be0a1d3dcc0d0d5df6c042e164", "travis@sgi.com", Instant.parse("2008-04-19T17:44:58Z"));
  private static final NativeLineBlame C_476f353 = new NativeLineBlame(
    "476f35348eb8d2a827765992899fea78b7dcc46f", "clameter@engr.sgi.com", Instant.parse("2007-05-07T19:12:51Z"));
  private static final NativeLineBlame C_5c1e176 = new NativeLineBlame(
    "5c1e176781f43bc902a51e5832f789756bff911b", "npiggin@suse.de", Instant.parse("2006-10-03T15:04:06Z"));
  private static final NativeLineBlame C_6d337ea = new NativeLineBlame(
    "6d337eab041d56bb8f0e7794f39906c21054c512", "peterz@infradead.org", Instant.parse("2020-11-10T17:39:00Z"));
  private static final NativeLineBlame C_6f505b1 = new NativeLineBlame(
    "6f505b16425a51270058e4a93441fe64de3dd435", "a.p.zijlstra@chello.nl", Instant.parse("2008-01-25T20:08:30Z"));
  private static final NativeLineBlame C_7835b98 = new NativeLineBlame(
    "7835b98bc6de2ca10afa45572d272304b000b048", "clameter@sgi.com", Instant.parse("2006-12-10T17:55:42Z"));
  private static final NativeLineBlame C_8707d8b = new NativeLineBlame(
    "8707d8b8c0cbdf4441507f8dded194167da896c7", "menage@google.com", Instant.parse("2007-10-19T18:53:41Z"));
  private static final NativeLineBlame C_95c354f = new NativeLineBlame(
    "95c354fe9f7d6decc08a92aa26eb233ecc2155bf", "npiggin@suse.de", Instant.parse("2008-01-30T12:31:20Z"));
  private static final NativeLineBlame C_99cf983 = new NativeLineBlame(
    "99cf983cc8bca4adb461b519664c939a565cfd4d", "mark.rutland@arm.com", Instant.parse("2022-02-19T10:11:08Z"));
  private static final NativeLineBlame C_a0f98a1 = new NativeLineBlame(
    "a0f98a1cb7d27c656de450ba56efd31bdc59065e", "mingo@elte.hu", Instant.parse("2007-06-18T18:52:55Z"));
  private static final NativeLineBlame C_a4c410f = new NativeLineBlame(
    "a4c410f00f7ca4bd448b0d63f6f882fd244dc991", "a.p.zijlstra@chello.nl", Instant.parse("2006-12-07T16:39:36Z"));
  private static final NativeLineBlame C_b29739f = new NativeLineBlame(
    "b29739f902ee76a05493fb7d2303490fc75364f4", "mingo@elte.hu", Instant.parse("2006-06-28T00:32:46Z"));
  private static final NativeLineBlame C_b50f60c = new NativeLineBlame(
    "b50f60ceeef2e38e529737c0260d9543939915ad", "heiko.carstens@de.ibm.com", Instant.parse("2006-07-31T20:28:41Z"));
  private static final NativeLineBlame C_b9dc29e = new NativeLineBlame(
    "b9dc29e72fd3dc2a739ce8eafd958220d0745734", "efault@gmx.de", Instant.parse("2009-06-17T16:34:17Z"));
  private static final NativeLineBlame C_c21761f = new NativeLineBlame(
    "c21761f168894b356626c847fe13be39605d76b4", "jbaron@redhat.com", Instant.parse("2006-01-19T03:20:22Z"));
  private static final NativeLineBlame C_c9819f4 = new NativeLineBlame(
    "c9819f4593e8d052b41a89f47140f5c5e7e30582", "clameter@sgi.com", Instant.parse("2006-12-10T17:55:42Z"));
  private static final NativeLineBlame C_cb25176 = new NativeLineBlame(
    "cb2517653fccaf9f9b4ae968c7ee005c1bbacdc5", "mgorman@techsingularity.net", Instant.parse("2016-02-09T10:54:23Z"));
  private static final NativeLineBlame C_d84b313 = new NativeLineBlame(
    "d84b31313ef8a8de55a2cbfb72f76f36d8c927fb", "frederic@kernel.org", Instant.parse("2018-02-21T08:49:09Z"));
  private static final NativeLineBlame C_da19ab5 = new NativeLineBlame(
    "da19ab510343c6496fe8b8f890091296032025c9", "srostedt@redhat.com", Instant.parse("2009-08-02T12:26:08Z"));
  private static final NativeLineBlame C_e107be3 = new NativeLineBlame(
    "e107be36efb2a233833e8c9899039a370e4b2318", "avi@qumranet.com", Instant.parse("2007-07-26T11:40:43Z"));
  private static final NativeLineBlame C_e761b77 = new NativeLineBlame(
    "e761b7725234276a802322549cee5255305a0930", "maxk@qualcomm.com", Instant.parse("2008-07-18T11:22:25Z"));
  private static final NativeLineBlame C_e8f1417 = new NativeLineBlame(
    "e8f14172c6b11e9a86c65532497087f8eb0f91b1", "patrick.bellasi@arm.com", Instant.parse("2019-06-24T17:23:45Z"));
  private static final NativeLineBlame C_ec7dc8a = new NativeLineBlame(
    "ec7dc8ac73e4a56ed03b673f026f08c0d547f597", "dhaval@linux.vnet.ibm.com", Instant.parse("2008-04-19T17:44:59Z"));
  private static final NativeLineBlame C_fa85ae2 = new NativeLineBlame(
    "fa85ae2418e6843953107cd6a06f645752829bc0", "a.p.zijlstra@chello.nl", Instant.parse("2008-01-25T20:08:29Z"));
  private static final NativeLineBlame C_feb245e = new NativeLineBlame(
    "feb245e304f343cf5e4f9123db36354144dce8a4", "htejun@gmail.com", Instant.parse("2016-06-24T06:26:53Z"));
  private static final NativeLineBlame INITIAL_IMPORT = new NativeLineBlame(
    "1da177e4c3f41524e886b7f1b8a0c1fc7321cac2", "torvalds@ppc970.osdl.org", Instant.parse("2005-04-16T22:20:36Z"));

  private static final Map<Integer, NativeLineBlame> CORE_C_KNOWN_DIVERGENT_LINES = Map.ofEntries(
    Map.entry(80, INITIAL_IMPORT),
    Map.entry(82, INITIAL_IMPORT),
    Map.entry(84, INITIAL_IMPORT),
    Map.entry(85, INITIAL_IMPORT),
    Map.entry(86, INITIAL_IMPORT),
    Map.entry(1870, C_e8f1417),
    Map.entry(2671, C_6d337ea),
    Map.entry(3577, C_feb245e),
    Map.entry(4345, INITIAL_IMPORT),
    Map.entry(4667, C_cb25176),
    Map.entry(4724, C_b9dc29e),
    Map.entry(4725, C_b9dc29e),
    Map.entry(4726, C_2ddbf95),
    Map.entry(4727, C_2ddbf95),
    Map.entry(4729, INITIAL_IMPORT),
    Map.entry(4731, INITIAL_IMPORT),
    Map.entry(4733, INITIAL_IMPORT),
    Map.entry(4734, INITIAL_IMPORT),
    Map.entry(4735, INITIAL_IMPORT),
    Map.entry(4737, INITIAL_IMPORT),
    Map.entry(4738, INITIAL_IMPORT),
    Map.entry(4739, INITIAL_IMPORT),
    Map.entry(4740, INITIAL_IMPORT),
    Map.entry(4741, INITIAL_IMPORT),
    Map.entry(4742, INITIAL_IMPORT),
    Map.entry(4745, INITIAL_IMPORT),
    Map.entry(4746, INITIAL_IMPORT),
    Map.entry(4747, INITIAL_IMPORT),
    Map.entry(4748, CFS_REWRITE),
    Map.entry(4750, CFS_REWRITE),
    Map.entry(4751, CFS_REWRITE),
    Map.entry(4752, CFS_REWRITE),
    Map.entry(4753, CFS_REWRITE),
    Map.entry(4754, CFS_REWRITE),
    Map.entry(4755, CFS_REWRITE),
    Map.entry(4758, CFS_REWRITE),
    Map.entry(4759, CFS_REWRITE),
    Map.entry(4760, CFS_REWRITE),
    Map.entry(4761, CFS_REWRITE),
    Map.entry(4762, C_02e4bac),
    Map.entry(4763, C_b29739f),
    Map.entry(4764, C_b29739f),
    Map.entry(4765, C_b29739f),
    Map.entry(4771, C_b29739f),
    Map.entry(5313, C_da19ab5),
    Map.entry(5651, C_7835b98),
    Map.entry(5665, CFS_REWRITE),
    Map.entry(5735, C_d84b313),
    Map.entry(6898, C_2c9a98d),
    Map.entry(6900, C_2c9a98d),
    Map.entry(6911, C_2c9a98d),
    Map.entry(6912, C_99cf983),
    Map.entry(7357, INITIAL_IMPORT),
    Map.entry(7961, INITIAL_IMPORT),
    Map.entry(7962, INITIAL_IMPORT),
    Map.entry(7963, INITIAL_IMPORT),
    Map.entry(7966, C_41a2d6c),
    Map.entry(7967, C_41a2d6c),
    Map.entry(7969, C_c21761f),
    Map.entry(7970, C_c21761f),
    Map.entry(7971, C_c21761f),
    Map.entry(7972, C_c21761f),
    Map.entry(7973, INITIAL_IMPORT),
    Map.entry(7974, INITIAL_IMPORT),
    Map.entry(7975, INITIAL_IMPORT),
    Map.entry(7979, INITIAL_IMPORT),
    Map.entry(7980, INITIAL_IMPORT),
    Map.entry(8054, INITIAL_IMPORT),
    Map.entry(8055, INITIAL_IMPORT),
    Map.entry(8056, INITIAL_IMPORT),
    Map.entry(8365, INITIAL_IMPORT),
    Map.entry(8369, INITIAL_IMPORT),
    Map.entry(8370, INITIAL_IMPORT),
    Map.entry(8372, INITIAL_IMPORT),
    Map.entry(8376, INITIAL_IMPORT),
    Map.entry(8380, INITIAL_IMPORT),
    Map.entry(8382, INITIAL_IMPORT),
    Map.entry(8384, INITIAL_IMPORT),
    Map.entry(8385, INITIAL_IMPORT),
    Map.entry(8386, INITIAL_IMPORT),
    Map.entry(8387, INITIAL_IMPORT),
    Map.entry(8389, INITIAL_IMPORT),
    Map.entry(8390, INITIAL_IMPORT),
    Map.entry(8391, INITIAL_IMPORT),
    Map.entry(8392, INITIAL_IMPORT),
    Map.entry(8395, INITIAL_IMPORT),
    Map.entry(8396, INITIAL_IMPORT),
    Map.entry(8397, INITIAL_IMPORT),
    Map.entry(8399, INITIAL_IMPORT),
    Map.entry(8400, INITIAL_IMPORT),
    Map.entry(8402, C_8707d8b),
    Map.entry(8636, C_95c354f),
    Map.entry(9629, C_e761b77),
    Map.entry(9642, C_3a101d0),
    Map.entry(9644, C_3a101d0),
    Map.entry(9670, C_5c1e176),
    Map.entry(9771, C_6f505b1),
    Map.entry(9772, C_6f505b1),
    Map.entry(9851, C_6f505b1),
    Map.entry(9852, C_6f505b1),
    Map.entry(9854, C_ec7dc8a),
    Map.entry(9860, C_fa85ae2),
    Map.entry(9862, C_476f353),
    Map.entry(9863, C_434d53b),
    Map.entry(9864, CFS_REWRITE),
    Map.entry(9866, CFS_REWRITE),
    Map.entry(9868, CFS_REWRITE),
    Map.entry(9883, C_476f353),
    Map.entry(9884, CFS_REWRITE),
    Map.entry(9885, CFS_REWRITE),
    Map.entry(9886, INITIAL_IMPORT),
    Map.entry(9888, C_2dd73a4),
    Map.entry(9889, C_b50f60c),
    Map.entry(9890, C_e107be3),
    Map.entry(9891, C_e107be3),
    Map.entry(9892, C_e107be3),
    Map.entry(9893, C_e107be3),
    Map.entry(9894, C_c9819f4),
    Map.entry(9895, C_476f353),
    Map.entry(9896, C_c9819f4),
    Map.entry(9907, C_b50f60c),
    Map.entry(9908, C_b50f60c),
    Map.entry(9952, INITIAL_IMPORT),
    Map.entry(9973, CFS_REWRITE),
    Map.entry(9974, CFS_REWRITE),
    Map.entry(9978, INITIAL_IMPORT),
    Map.entry(10014, C_a4c410f),
    Map.entry(10018, C_3117df0),
    Map.entry(10019, C_3117df0),
    Map.entry(10021, INITIAL_IMPORT),
    Map.entry(10040, C_a0f98a1),
    Map.entry(10058, C_a0f98a1),
    Map.entry(10060, CFS_REWRITE),
    Map.entry(10180, INITIAL_IMPORT),
    Map.entry(10181, INITIAL_IMPORT),
    Map.entry(10182, INITIAL_IMPORT),
    Map.entry(10257, INITIAL_IMPORT));

  private static final ComparisonScenario SCENARIO = new ComparisonScenario(
    "linux-kernel",
    new RepoSource.Remote("https://github.com/torvalds/linux.git"),
    "v6.6",
    List.of("kernel/sched"),
    List.of("kernel/sched/core.c", "kernel/sched/fair.c"),
    Map.of("kernel/sched/core.c", CORE_C_KNOWN_DIVERGENT_LINES));

  @Override
  protected ComparisonScenario scenario() {
    return SCENARIO;
  }

  /**
   * This library has no on-demand promisor-fetch (see {@link AbstractBlameComparisonIT#supportsStrategy}), so a
   * blobless clone of the kernel's full history reliably throws {@code MissingObjectException} rather than
   * surfacing a blame divergence - documented in {@code README.md}, "Blame semantics vs native git".
   */
  @Override
  protected boolean supportsStrategy(CloneStrategy strategy) {
    return strategy != CloneStrategy.PARTIAL_SPARSE;
  }
}
