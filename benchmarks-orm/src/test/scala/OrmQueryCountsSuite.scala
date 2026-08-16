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

package grackle.benchmarks.orm

import scala.concurrent.duration._

import munit.CatsEffectSuite

import grackle.benchmarks.sql.ChartData

class OrmQueryCountsSuite extends CatsEffectSuite {
  override val munitIOTimeout: Duration = 5.minutes

  test(
    "grackle arm statement count is 1 at every shape; naive is not; eager's tuned shapes beat naive") {
    for {
      grackle <- OrmQueryCounts.countGrackle
      naive <- OrmQueryCounts.countOrm(NaiveOrmArm.run)
      eager <- OrmQueryCounts.countOrm(EagerOrmArm.run)
    } yield {
      grackle.foreach(c =>
        assertEquals(c.statements, 1, s"grackle shape ${c.shape} was not 1 query"))

      // Cross-check the measured counts against `charts/chart-data.json`, the source the README
      // charts are generated from, so a chart cannot drift from what the arms actually issue.
      // Grackle is deterministic and pinned exactly; the ORM arms jitter by a handful of statements
      // per run (see the untuned note below), so they're held within a generous tolerance that
      // still rules out a stale or fabricated chart figure.
      val chart = ChartData.load
      val ormTolerance = 30

      OrmQueryShapes.all.foreach { shape =>
        val i = chart.statementsPerShape.shapes.indexOf(shape.name)
        assert(i >= 0, s"shape ${shape.name} is missing from chart-data.json")
        val grackleCount = grackle.find(_.shape == shape.name).get.statements
        val naiveCount = naive.find(_.shape == shape.name).get.statements
        val eagerCount = eager.find(_.shape == shape.name).get.statements

        assertEquals(
          grackleCount,
          chart.statementsPerShape.grackle(i),
          s"shape ${shape.name}: grackle count drifted from chart-data.json")
        assert(
          Math.abs(naiveCount - chart.statementsPerShape.naive(i)) <= ormTolerance,
          s"shape ${shape.name}: naive count $naiveCount is more than $ormTolerance off " +
            s"chart-data.json's ${chart.statementsPerShape.naive(i)}"
        )
        assert(
          Math.abs(eagerCount - chart.statementsPerShape.eager(i)) <= ormTolerance,
          s"shape ${shape.name}: eager count $eagerCount is more than $ormTolerance off " +
            s"chart-data.json's ${chart.statementsPerShape.eager(i)}"
        )

        // The headline "Grackle 1, ORM many" claim this whole harness exists to demonstrate:
        // real observed ratios are roughly 260:1 and 1800:1 (naive:grackle) across shapes, so a
        // 10x floor is a safe margin, not a flaky threshold, while still ruling out a naive count
        // that's merely "a little more than 1" (e.g. 2), which the test's name promised but
        // nothing previously asserted.
        assert(
          naiveCount > 10 * grackleCount,
          s"shape ${shape.name}: expected naive ($naiveCount) to badly out-query grackle " +
            s"($grackleCount), i.e. naive > 10x grackle"
        )

        if (shape.tuned) {
          assert(
            eagerCount < naiveCount,
            s"shape ${shape.name}: expected tuned eager arm ($eagerCount) to beat naive ($naiveCount)")
        } else {
          // untuned: eager has no entity graph, so it should behave like naive — for this
          // shape, `EagerOrmArm.run` and `NaiveOrmArm.run` execute the literal same code (a
          // plain `find`, then `NaiveOrmArm`'s own walk), just against separately created
          // `EntityManagerFactory`/connection-pool instances. Live-verified across 5+ repeated
          // runs: the two counts still differ by a handful of statements run to run (observed
          // range roughly -8 to +3, out of a ~260 baseline) — not a behavioral difference, but
          // ordinary execution noise (`@BatchSize` IN-clause batch boundaries that depend on
          // `java.util.HashSet` iteration order across separate JVM object layouts — the entity
          // classes don't override `equals`/`hashCode`, so iteration order follows identity
          // hashcodes, which vary per JVM run). An exact `assertEquals` would
          // be flaky by construction here, so this allows a tolerance well above the observed
          // noise band while still being a real regression guard: if eager's untuned behavior
          // ever structurally diverges from naive's (e.g. the kind of bug this suite exists to
          // catch — see the `loadgraph` vs `fetchgraph` fix this file's git history documents),
          // the gap will be orders of magnitude larger than this tolerance, not a handful of
          // statements.
          val diff = Math.abs(eagerCount - naiveCount)
          assert(
            diff <= 20,
            s"untuned shape ${shape.name} should behave like naive: naive=$naiveCount eager=$eagerCount (diff $diff)")
        }
      }
    }
  }

  test("naive per-depth statement counts track charts/chart-data.json") {
    val chart = ChartData.load
    val tolerance = 30
    OrmQueryCounts.countNaiveByDepth(chart.naiveStatementsByDepth.depths).map { measured =>
      measured
        .zip(chart.naiveStatementsByDepth.statements)
        .zip(chart.naiveStatementsByDepth.depths)
        .foreach {
          case ((m, expected), d) =>
            assert(
              Math.abs(m - expected) <= tolerance,
              s"depth $d: naive count $m is more than $tolerance off chart-data.json's $expected")
        }
    }
  }
}
