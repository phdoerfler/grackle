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
 * real 11-table join chain, rooted at a single country region. Requires `benchmark-postgres` to
 * be running (`sbt benchPgUp`).
 *
 * The annotations above are the settings a bare run uses: 3 forks (a single fork hides JIT
 * profile pollution) x (5 warmup + 10 measurement) iterations x 5 `depth` params. JMH's default
 * iteration *time* of 10s applies on top of those counts, so a bare run costs roughly 3 x 15 x
 * 10s x 5 = 2,250s of measurement alone, plus 15 JVM fork launches — approximately 40 minutes
 * end to end.
 *
 * sbt "benchmarksSql/Jmh/run -rf json -rff results.json"
 *
 * For a quick sanity check while iterating on the benchmark itself, override them:
 *
 * sbt "benchmarksSql/Jmh/run -f 1 -wi 1 -i 1 -r 1s -w 1s SqlJoinDepthBenchmark"
 *
 * Add `-prof gc` for allocation-per-operation figures, which are near-deterministic and so
 * remain meaningful on a machine too noisy for trustworthy wall-clock numbers.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(3)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
class SqlJoinDepthBenchmark {

  @Param(Array("2", "4", "6", "8", "10"))
  var depth: Int = _

  var mapping: Mapping[IO] = _
  // Null until `setup` assigns it; `teardown` guards against that so a `setup` failure (e.g. the
  // seeded database missing a function `@Setup` depends on) surfaces its own exception instead of
  // being masked by a NullPointerException from `teardown`.
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
    if (releaseTransactor != null) releaseTransactor.unsafeRunSync()

  @Benchmark
  def runJoinDepthQuery(blackhole: Blackhole): Unit = {
    val query = JoinChain.queryForDepth(depth)
    val result = mapping.compileAndRun(query).unsafeRunSync()
    blackhole.consume(result)
  }
}
