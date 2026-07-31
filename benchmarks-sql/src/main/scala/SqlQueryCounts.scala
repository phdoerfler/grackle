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

  /**
   * Shared counting body for both the filtered and unfiltered paths: they differ only in which
   * query string is built per depth and in the label used for failure messages.
   */
  private def countsForQuery(
      label: String,
      queryForDepth: Int => String): IO[List[DepthCount]] =
    DoobieMonitor.statsMonitor[IO].flatMap { monitor =>
      val mapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO], monitor)

      (1 to JoinChain.maxDepth).toList.traverse { depth =>
        for {
          result <- mapping.compileAndRun(queryForDepth(depth))
          _ = require(
            !result.hcursor.downField("errors").succeeded,
            s"GraphQL errors at depth $depth for $label: $result")
          // `take` snapshots and clears atomically, so each depth is counted independently.
          stats <- monitor.take
          _ = require(
            stats.nonEmpty,
            s"no SQL statements recorded at depth $depth for $label — " +
              "a zero-query depth would silently look like perfect N+1 immunity"
          )
        } yield DepthCount(depth, stats.size, stats.map(_.rows).sum, stats.map(_.normalize.sql))
      }
    }

  def countsFor(rootCode: String): IO[List[DepthCount]] =
    countsForQuery(s"root $rootCode", depth => JoinChain.queryForDepth(depth, rootCode))

  /**
   * Every country region, unfiltered — ~238 regions and ~60k rows at the deeper depths, versus
   * ~5.6k for a single filtered region. The query count should not move: that invariance is the
   * whole point of measuring it against a workload nothing about the query shape was chosen to
   * flatter.
   */
  def countsForUnfiltered: IO[List[DepthCount]] =
    countsForQuery("unfiltered root", JoinChain.queryForDepthUnfiltered)

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
   * Same shape as `render`, but explicitly labelled `scope` rather than `rootCode` — there is
   * no root code to report, since this dataset spans every country region unfiltered.
   */
  def renderUnfiltered(counts: List[DepthCount]): Json =
    Json.obj(
      "scope" -> Json.fromString("unfiltered (all country regions)"),
      "counts" -> Json.arr(counts.map { c =>
        Json.obj(
          "depth" -> Json.fromInt(c.depth),
          "queries" -> Json.fromInt(c.queries),
          "rows" -> Json.fromInt(c.rows)
        )
      }: _*)
    )

  /**
   * Combines both datasets under distinctly-named top-level keys so a reader looking only at
   * `query-counts.json` can tell them apart without cross-referencing this source file:
   * `filtered` keeps `render`'s existing shape (labelled by `rootCode`), `unfiltered` is
   * labelled explicitly via `scope`.
   */
  def renderAll(
      rootCode: String,
      filtered: List[DepthCount],
      unfiltered: List[DepthCount]): Json =
    Json.obj(
      "filtered" -> render(rootCode, filtered),
      "unfiltered" -> renderUnfiltered(unfiltered)
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

  private def printTable(label: String, counts: List[DepthCount]): IO[Unit] =
    IO.println(label) *>
      counts.traverse_(c =>
        IO.println(f"depth ${c.depth}%2d  queries ${c.queries}%3d  rows ${c.rows}%7d")) *>
      IO.println("")

  def run: IO[Unit] =
    for {
      filtered <- countsFor(JoinChain.defaultRootCode)
      unfiltered <- countsForUnfiltered
      json = renderAll(JoinChain.defaultRootCode, filtered, unfiltered)
      _ <- IO.blocking(Files.write(Paths.get(outputPath), json.spaces2.getBytes("UTF-8")))
      _ <- IO.blocking(
        Files.write(
          Paths.get(sqlOutputPath),
          (renderSql(JoinChain.defaultRootCode, filtered) + "\n").getBytes("UTF-8")))
      _ <- printTable(s"Filtered (root ${JoinChain.defaultRootCode}):", filtered)
      _ <- printTable("Unfiltered (all country regions):", unfiltered)
      _ <- IO.println(s"wrote $outputPath")
      _ <- IO.println(s"wrote $sqlOutputPath")
    } yield ()
}
