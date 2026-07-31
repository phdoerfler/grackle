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

import java.util.concurrent.TimeUnit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import org.typelevel.doobie._
import org.typelevel.doobie.hikari.HikariTransactor
import org.typelevel.doobie.implicits._

import grackle.Mapping
import grackle.doobie.DoobieMonitor

/**
 * Splits `SqlJoinDepthBenchmark`'s timed region into its two components: JDBC result-set
 * transfer from Postgres, and everything Grackle itself does above that (intermediate
 * structures plus building the `Json` result tree).
 *
 * Both arms run the exact same SQL statement against the exact same warmed-up connection pool
 * in the same trial:
 *
 *   - Arm A (`runFullGrackle`) is `SqlJoinDepthBenchmark`'s existing measurement:
 *     `mapping.compileAndRun(query).unsafeRunSync()`.
 *   - Arm B (`runRawJdbc`) executes the SQL Grackle itself emitted for this depth via plain
 *     JDBC — bypassing doobie's `Read`/`Query0` decoding, not just Grackle's own layer — reading
 *     every column of every row into a `Blackhole`.
 *
 * Arm A minus Arm B is attributable to Grackle's own work, since both arms move identical rows
 * off identical SQL in the same JVM.
 *
 * The SQL is captured programmatically in `@Setup`, not hand-copied: a second, monitored mapping
 * sharing this trial's transactor runs the depth's query once through `DoobieMonitor.statsMonitor`,
 * and `SqlStats.sql` / `SqlStats.args` are read back. This keeps both arms honest if the mapping
 * or query shape ever changes, and lets `@Setup` assert Arm B's row count against Grackle's own
 * `SqlStats.rows` before any timed sample runs.
 *
 * Diagnostic only — depth 10 (the full join chain), one param, short iteration counts. Requires
 * `benchmark-postgres` to be running (`sbt benchPgUp`).
 *
 * sbt "benchmarksSql/Jmh/run -f 1 -wi 5 -i 10 -prof gc RawVsGrackleBenchmark"
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(3)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
class RawVsGrackleBenchmark {

  @Param(Array("10"))
  var depth: Int = _

  var mapping: Mapping[IO] = _
  private var transactor: HikariTransactor[IO] = _
  // Null until `setup` assigns it; `teardown` guards against that so a `setup` failure (e.g. the
  // seeded database missing a function `@Setup` depends on) surfaces its own exception instead of
  // being masked by a NullPointerException from `teardown`.
  private var releaseTransactor: IO[Unit] = _

  // Captured from Grackle's own emitted SQL for this trial's `depth` — see the class doc comment.
  private var capturedSql: String = _
  private var capturedArgs: List[Any] = _
  private var expectedRows: Int = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val (xa, release) = BenchmarkDb.transactorResource[IO].allocated.unsafeRunSync()
    transactor = xa
    releaseTransactor = release
    mapping = AdventureWorksMapping.mkMapping[IO](xa)
    BenchmarkDb.prewarm[IO](xa).unsafeRunSync()

    val monitor = DoobieMonitor.statsMonitor[IO].unsafeRunSync()
    val monitoredMapping = AdventureWorksMapping.mkMapping[IO](xa, monitor)
    val query = JoinChain.queryForDepth(depth)
    val result = monitoredMapping.compileAndRun(query).unsafeRunSync()
    require(
      !result.hcursor.downField("errors").succeeded,
      s"GraphQL errors while capturing SQL at depth $depth: $result")

    val stats = monitor.take.unsafeRunSync()
    require(
      stats.size == 1,
      s"expected exactly one SQL statement at depth $depth, got ${stats.size}: $stats")
    val stat = stats.head
    capturedSql = stat.sql
    capturedArgs = stat.args
    expectedRows = stat.rows

    // Arm B must move the same rows Grackle reported, or the comparison is meaningless.
    val actualRows = runRaw(_ => ())
    require(
      actualRows == expectedRows,
      s"row-count mismatch at depth $depth: Grackle reported $expectedRows rows, " +
        s"raw JDBC found $actualRows")
  }

  @TearDown(Level.Trial)
  def teardown(): Unit =
    if (releaseTransactor != null) releaseTransactor.unsafeRunSync()

  /**
   * Executes `capturedSql` via plain JDBC (doobie's raw-connection escape hatch, `FC.raw`), NOT
   * doobie's `Read`/`Query0` decoding, and feeds every column of every row to `consume`. Returns
   * the row count so `setup` can cross-check it against Grackle's own report.
   */
  private def runRaw(consume: Any => Unit): Int = {
    val io: ConnectionIO[Int] = FC.raw { conn =>
      val ps = conn.prepareStatement(capturedSql)
      try {
        capturedArgs.zipWithIndex.foreach { case (arg, i) => ps.setObject(i + 1, arg) }
        val rs = ps.executeQuery()
        try {
          val cols = rs.getMetaData.getColumnCount
          var rows = 0
          while (rs.next()) {
            var col = 1
            while (col <= cols) {
              consume(rs.getObject(col))
              col += 1
            }
            rows += 1
          }
          rows
        } finally rs.close()
      } finally ps.close()
    }
    io.transact(transactor).unsafeRunSync()
  }

  /** Arm A: full Grackle, exactly as `SqlJoinDepthBenchmark.runJoinDepthQuery` measures it. */
  @Benchmark
  def runFullGrackle(blackhole: Blackhole): Unit = {
    val query = JoinChain.queryForDepth(depth)
    val result = mapping.compileAndRun(query).unsafeRunSync()
    blackhole.consume(result)
  }

  /** Arm B: the JDBC floor underneath Arm A — same SQL, same connection pool, no Grackle. */
  @Benchmark
  def runRawJdbc(blackhole: Blackhole): Unit = {
    val rows = runRaw(v => blackhole.consume(v))
    blackhole.consume(rows)
  }
}
