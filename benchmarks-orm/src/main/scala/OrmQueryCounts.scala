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

import java.nio.file.{Files, Paths}

import cats.effect.{IO, IOApp}
import cats.implicits._
import io.circe.Json
import org.hibernate.stat.Statistics

import grackle.benchmarks.sql.{AdventureWorksMapping, BenchmarkDb, JoinChain}
import grackle.doobie.DoobieMonitor

/**
 * Counts SQL statements per arm, per shape. Runs OUTSIDE JMH deliberately, for the same reason
 * `benchmarksSql`'s `SqlQueryCounts` does: enabling Hibernate statistics (or a
 * stats-accumulating DoobieMonitor) does per-statement bookkeeping that would pollute a timed
 * JMH region.
 *
 * The Grackle arm's count is fully deterministic (it always emits exactly one SQL statement per
 * shape), so no warmup/repetition is needed for it. The two ORM arms are NOT fully
 * deterministic: `OrmQueryCountsSuite` documents small run-to-run variance in their statement
 * counts (hence its tolerance on the untuned-shape parity check). That variance traces to
 * `java.util.HashSet` iteration order affecting `@BatchSize` IN-clause chunking — the entity
 * classes don't override `equals`/`hashCode`, so their `Set`-typed association fields iterate
 * in identity-hashcode order, which varies per JVM run. (HikariCP connection-pool warmup is not
 * a contributing factor here: Hikari's own connection setup never goes through Hibernate's
 * `getPrepareStatementCount`.)
 *
 * Uses `Statistics.getPrepareStatementCount()`, NOT `getQueryExecutionCount()` — the latter
 * only counts explicit JPQL/Criteria query executions, not the implicit SQL Hibernate issues
 * for lazy collection/entity loads, which is exactly the behavior under test.
 *
 * Expect `deep-narrow` and `deep-wide`'s naive/eager counts (and their timings in
 * `OrmVsGrackleBenchmark`) to come out identical: Hibernate always selects every mapped scalar
 * column of a row when it loads an entity, and `OrmJson.scalarsFor` only reads fields already
 * materialized in memory, so `Shape.wideFields` never changes how much SQL the ORM arms issue —
 * only which of Grackle's own selected columns change (`GrackleShapeQuery.nestWide` genuinely
 * selects extra fields at every hop for `deep-wide`). Identical `naive`/`eager` numbers across
 * those two shapes are therefore the CORRECT result, not a bug in this harness.
 *
 * sbt "benchmarksOrm/runMain grackle.benchmarks.orm.OrmQueryCounts"
 */
object OrmQueryCounts extends IOApp.Simple {

  final case class ArmCount(shape: String, statements: Int)

  val outputPath: String = "benchmarks-orm/query-counts.json"

  private def statisticsOf(factory: jakarta.persistence.EntityManagerFactory): Statistics =
    factory.unwrap(classOf[org.hibernate.SessionFactory]).getStatistics

  def countOrm(runArm: (jakarta.persistence.EntityManager, Shape, String) => io.circe.Json)
      : IO[List[ArmCount]] =
    IO.blocking {
      val factory = OrmDb.emf(Map("hibernate.generate_statistics" -> "true"))
      try {
        val stats = statisticsOf(factory)
        stats.setStatisticsEnabled(true)
        OrmQueryShapes.all.map { shape =>
          stats.clear()
          val em = factory.createEntityManager()
          try runArm(em, shape, JoinChain.defaultRootCode)
          finally em.close()
          // NOT a non-emptiness check on `runArm`'s returned document: unlike the old
          // `List[String]` return, the document is always non-empty once the root is found — it
          // always carries the root's own `countryRegionCode` scalar and its envelope — whether
          // or not the shape's depth ever reaches `"category"` (`shallowNarrow` depth 3 and
          // `untuned` depth 7 both stop short of the full 10-hop chain). That's true for every
          // arm, not a failure mode, so an emptiness check on the document would be vacuous — it
          // would never distinguish a shape that did real work from one that did none. What this
          // guard actually needs to catch is a shape that did no SQL work at all, which the
          // statement count (the metric under test) answers directly.
          val statements = stats.getPrepareStatementCount.toInt
          require(
            statements > 0,
            s"shape ${shape.name} issued no SQL statements — a zero-effort shape looks falsely cheap")
          ArmCount(shape.name, statements)
        }
      } finally factory.close()
    }

  def countGrackle: IO[List[ArmCount]] =
    DoobieMonitor.statsMonitor[IO].flatMap { monitor =>
      val mapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO], monitor)
      OrmQueryShapes.all.traverse { shape =>
        for {
          result <- mapping.compileAndRun(GrackleShapeQuery.queryFor(shape))
          _ = require(
            !result.hcursor.downField("errors").succeeded,
            s"GraphQL errors for shape ${shape.name}: $result")
          stats <- monitor.take
        } yield ArmCount(shape.name, stats.size)
      }
    }

  private def render(label: String, counts: List[ArmCount]): Json =
    Json.obj(
      "arm" -> Json.fromString(label),
      "counts" -> Json.arr(counts.map { c =>
        Json
          .obj("shape" -> Json.fromString(c.shape), "statements" -> Json.fromInt(c.statements))
      }: _*)
    )

  def run: IO[Unit] =
    for {
      grackle <- countGrackle
      naive <- countOrm(NaiveOrmArm.run)
      eager <- countOrm(EagerOrmArm.run)
      json = Json.obj(
        "grackle" -> render("grackle", grackle),
        "naive" -> render("naive", naive),
        "eager" -> render("eager", eager)
      )
      _ <- IO.blocking(Files.write(Paths.get(outputPath), json.spaces2.getBytes("UTF-8")))
      _ <- IO.println(s"wrote $outputPath")
    } yield ()
}
