// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// Copyright (c) 2016-2025 Grackle Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package grackle.sql.test

import scala.collection.mutable

/**
 * Tracks which tables have already been seeded in this test JVM.
 *
 * Seeding happens once per table per JVM rather than once per suite. Each backend forks its own
 * test JVM against its own database, so the first suite that needs a table fills it and every
 * later suite — including suites that read the table but declare no seed data of their own —
 * sees the same rows in the same order. That matters for two reasons:
 *
 *   - a table is often read by more than one suite (`entities` by the two interfaces suites,
 *     `t_observation` by the embedding2 and embedding3 suites), and
 *   - a query without an explicit `ORDER BY` depends on physical row order, which repeated
 *     truncate-and-reinsert cycles can change.
 */
object SqlTestDataSeeder {

  private val seeded = mutable.Set.empty[String]

  /**
   * Claims `key` for seeding, returning true if the caller should seed it — that is, if no
   * earlier caller in this JVM has already claimed it.
   */
  def claim(key: String): Boolean = synchronized(seeded.add(key))
}
