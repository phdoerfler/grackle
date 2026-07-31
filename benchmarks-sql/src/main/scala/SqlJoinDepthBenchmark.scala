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

import grackle.Mapping

/**
 * Measures how Grackle's compile+execute time scales with GraphQL query nesting depth against a
 * real 11-table join chain. Requires `benchmark-postgres` to be running (`sbt benchPgUp`).
 *
 * JMH's defaults (5 forks x (5 warmup + 5 measurement) iterations x 10s x 5 `depth` params) add
 * up to 40+ minutes here, since the depth-10 operation alone takes hundreds of milliseconds.
 * For a quick sanity run, pass explicit flags:
 *
 * sbt "benchmarksSql/Jmh/run -f 1 -wi 3 -w 2s -i 5 -r 2s -rf json -rff results.json"
 *
 * Which means "1 fork", "3 warm-up iterations of 2s", "5 measurement iterations of 2s". That
 * takes on the order of a couple of minutes and is fine for iterating on the benchmark itself,
 * but for results anyone will rely on, use heavier settings (more forks and iterations, e.g.
 * JMH's own defaults) to get a trustworthy spread.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class SqlJoinDepthBenchmark {

  @Param(Array("2", "4", "6", "8", "10"))
  var depth: Int = _

  var mapping: Mapping[IO] = _
  private var releaseTransactor: IO[Unit] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val (transactor, release) = BenchmarkDb.transactorResource[IO].allocated.unsafeRunSync()
    releaseTransactor = release
    mapping = AdventureWorksMapping.mkMapping[IO](transactor)
    BenchmarkDb.prewarm[IO](transactor).unsafeRunSync()
  }

  @TearDown(Level.Trial)
  def teardown(): Unit =
    releaseTransactor.unsafeRunSync()

  @Benchmark
  def runJoinDepthQuery(blackhole: Blackhole): Unit = {
    val query = JoinChain.queryForDepth(depth)
    val result = mapping.compileAndRun(query).unsafeRunSync()
    blackhole.consume(result)
  }
}
