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

import java.nio.file.{Files, Paths}

import cats.effect.{IO, IOApp}
import cats.implicits._
import io.circe.Json

import grackle.doobie.DoobieMonitor

/**
 * Counts the SQL queries Grackle emits per GraphQL query depth.
 *
 * This runs OUTSIDE JMH deliberately: `statsMonitor` accumulates an unbounded list and does
 * per-query work, so inside a timed region it would both pollute the measurements and grow
 * memory across iterations. Counts are fully deterministic, so no warmup or repetition is
 * needed — which is exactly why they are the most publishable number this suite produces.
 *
 * sbt "benchmarksSql/runMain grackle.benchmarks.sql.SqlQueryCounts"
 */
object SqlQueryCounts extends IOApp.Simple {

  // `sql` carries the normalized (whitespace-collapsed) text of every SQL statement Grackle
  // emitted at this depth — diagnostic only, and not part of the pinned query-count invariants
  // (`queries`, `rows`) that `SqlQueryCountsSuite` checks.
  final case class DepthCount(depth: Int, queries: Int, rows: Int, sql: List[String])

  // Unlike `Jmh / run`, plain `Compile / run` (what `runMain` uses) is not forked with the
  // module's base directory as its working directory — it inherits sbt's own cwd, the build
  // root. So the path is spelled out relative to the repo root, matching the documented
  // invocation (`sbt "benchmarksSql/runMain ..."`, run from the repo root) and mirroring
  // where `.gitignore` expects the file to land.
  val outputPath: String = "benchmarks-sql/query-counts.json"

  // Diagnostic dump of the emitted SQL itself, kept separate from `outputPath` (the published
  // query-counts.json metric) — see this object's doc comment. Same working-directory caveat
  // applies: written relative to the repo root, not the module base directory.
  val sqlOutputPath: String = "benchmarks-sql/query-sql.txt"

  def countsFor(rootCode: String): IO[List[DepthCount]] =
    DoobieMonitor.statsMonitor[IO].flatMap { monitor =>
      val mapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO], monitor)

      (1 to JoinChain.maxDepth).toList.traverse { depth =>
        for {
          result <- mapping.compileAndRun(JoinChain.queryForDepth(depth, rootCode))
          _ = require(
            !result.hcursor.downField("errors").succeeded,
            s"GraphQL errors at depth $depth for root $rootCode: $result")
          // `take` snapshots and clears atomically, so each depth is counted independently.
          stats <- monitor.take
          _ = require(
            stats.nonEmpty,
            s"no SQL statements recorded at depth $depth for root $rootCode — " +
              "a zero-query depth would silently look like perfect N+1 immunity"
          )
        } yield DepthCount(depth, stats.size, stats.map(_.rows).sum, stats.map(_.normalize.sql))
      }
    }

  def render(rootCode: String, counts: List[DepthCount]): Json =
    Json.obj(
      "rootCode" -> Json.fromString(rootCode),
      "counts" -> Json.arr(counts.map { c =>
        Json.obj(
          "depth" -> Json.fromInt(c.depth),
          "queries" -> Json.fromInt(c.queries),
          "rows" -> Json.fromInt(c.rows)
        )
      }: _*)
    )

  /**
   * Render one clearly-delimited, depth-labelled section per depth, each containing every SQL
   * statement Grackle emitted at that depth (normalized). Diagnostic only — this is what backs
   * `sqlOutputPath`, not `outputPath`.
   */
  def renderSql(rootCode: String, counts: List[DepthCount]): String =
    counts
      .map { c =>
        val header = s"-- depth ${c.depth}, root $rootCode " + "-" * 40
        val body = c
          .sql
          .zipWithIndex
          .map {
            case (sql, i) if c.sql.size > 1 => s"-- statement ${i + 1} of ${c.sql.size}\n$sql"
            case (sql, _) => sql
          }
          .mkString("\n\n")
        s"$header\n$body"
      }
      .mkString("\n\n")

  def run: IO[Unit] =
    for {
      counts <- countsFor(JoinChain.defaultRootCode)
      json = render(JoinChain.defaultRootCode, counts)
      _ <- IO.blocking(Files.write(Paths.get(outputPath), json.spaces2.getBytes("UTF-8")))
      _ <- IO.blocking(
        Files.write(
          Paths.get(sqlOutputPath),
          (renderSql(JoinChain.defaultRootCode, counts) + "\n").getBytes("UTF-8")))
      _ <- counts.traverse_(c =>
        IO.println(f"depth ${c.depth}%2d  queries ${c.queries}%3d  rows ${c.rows}%7d"))
      _ <- IO.println(s"wrote $outputPath")
      _ <- IO.println(s"wrote $sqlOutputPath")
    } yield ()
}
