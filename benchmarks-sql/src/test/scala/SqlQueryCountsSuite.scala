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

package grackle.benchmarks.sql

import munit.CatsEffectSuite

class SqlQueryCountsSuite extends CatsEffectSuite {

  test("query count does not grow with result-set size (N+1 immunity)") {
    for {
      fr <- SqlQueryCounts.countsFor("FR") // ~5.6k line items
      us <- SqlQueryCounts.countsFor("US") // ~21.4k line items, ~4x the rows
    } yield {
      assertEquals(
        fr.map(c => c.depth -> c.queries),
        us.map(c => c.depth -> c.queries),
        "SQL query count must depend only on query depth, never on how many rows match")

      assert(
        us.map(_.rows).sum > fr.map(_.rows).sum,
        "sanity check: US really should fetch more rows than FR")
    }
  }

  test("query count is at most linear in depth") {
    SqlQueryCounts.countsFor(JoinChain.defaultRootCode).map { counts =>
      assertEquals(counts.map(_.depth), (1 to JoinChain.maxDepth).toList)
      counts.foreach { c =>
        assert(
          c.queries <= c.depth + 1,
          s"depth ${c.depth} emitted ${c.queries} queries — N+1 behaviour would grow with rows")
      }
    }
  }
}
