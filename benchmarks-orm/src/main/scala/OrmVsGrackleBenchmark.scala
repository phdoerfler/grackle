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

import java.util.concurrent.TimeUnit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import jakarta.persistence.{EntityManager, EntityManagerFactory}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import grackle.Mapping
import grackle.benchmarks.sql.{AdventureWorksMapping, BenchmarkDb, JoinChain, Toxiproxy}

/**
 * Combined timing comparison: Grackle vs Hibernate-naive vs Hibernate-eager, across the four
 * curated shapes (`OrmQueryShapes.all`), all three arms in one JMH run so their numbers share
 * warmup/JIT/GC conditions — avoids the cross-run comparability problems the BigDecimal.equals
 * measurement work hit twice (see `topic/sql-benchmarks` history).
 *
 * Query-count instrumentation is deliberately NOT part of this class — see `OrmQueryCounts` and
 * this plan's deviation #3. This class measures wall-clock time only.
 *
 * `shape.wideFields` (`deep-narrow` vs `deep-wide`) only varies the Grackle arm's query shape:
 * `GrackleShapeQuery.nestWide` genuinely selects extra fields at every hop, but Hibernate
 * always loads every mapped scalar column of a row regardless of which fields the naive/eager
 * arms' traversal code happens to read afterward, so those two arms do IDENTICAL work — and
 * should show near-identical timings — for `deep-narrow` and `deep-wide`. See
 * `OrmQueryCounts`'s class doc for the same point on statement counts.
 *
 * Requires `benchmark-postgres` running (`sbt benchPgUp`).
 *
 * sbt "benchmarksOrm/Jmh/run -f 1 -wi 3 -i 5 OrmVsGrackleBenchmark"
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
// Reduced from phase 2's @Fork(3)/5/10 at JMH's 10s default iteration time. The parameter matrix
// grew from 12 combinations to 48, and the naive arm's deep shapes are far slower under load —
// roughly 270 statements at 50ms round-trip is about 13.5s of pure network time per invocation.
// The effect being measured here is enormous (hundreds of milliseconds against tens of seconds,
// growing with RTT), so tight confidence intervals matter far less than they did in phase 1's
// noise-bound work. Override on the command line for a publishable-tier run.
@Fork(1)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
class OrmVsGrackleBenchmark {

  @Param(Array("shallow-narrow", "deep-narrow", "deep-wide", "untuned"))
  var shapeName: String = _

  /**
   * Target round-trip time in milliseconds, injected between this JVM and `benchmark-postgres`
   * by Toxiproxy (see `Toxiproxy` and the README's topology section). 0 still goes through the
   * proxy — with no toxics installed — so the baseline includes the proxy's own hop cost and is
   * apples-to-apples with the delayed levels rather than measuring a different topology.
   *
   * 5/20/50 stand in for same-availability-zone, cross-region, and wide-area round trips. The
   * naive arm's N+1 penalty IS a round-trip penalty, so its cost should grow roughly linearly
   * in this parameter while the Grackle arm's, at one statement per query, stays nearly flat.
   */
  @Param(Array("0", "5", "20", "50"))
  var latencyMs: Int = _

  private var shape: Shape = _
  private var grackleMapping: Mapping[IO] = _
  private var entityManagerFactory: EntityManagerFactory = _
  // Fresh per invocation, not per trial: the seed data never changes, so a reused
  // EntityManager's first-level cache would make lazy loads resolve from memory instead of
  // issuing SQL after the first invocation, collapsing the naive arm's behavior toward the
  // eager arm's and inverting the comparison. Mirrors real per-request EntityManager scoping.
  private var entityManager: EntityManager = _
  // Null until `setupTrial` assigns it; `teardownTrial` guards against that so a `setupTrial`
  // failure (e.g. the seeded database missing something the Grackle arm depends on) surfaces its
  // own exception instead of being masked by a NullPointerException from `teardownTrial`.
  private var releaseTransactor: IO[Unit] = _

  @Setup(Level.Trial)
  def setupTrial(): Unit = {
    // First, before anything opens a connection: the pooled transactor and EntityManagerFactory
    // built below establish their connections during this method, and those must be established
    // under the same network conditions the benchmark will run under. Trial level, not iteration
    // or invocation level — changing a toxic mid-measurement would mix samples taken under
    // different network conditions into one result.
    Toxiproxy.setLatency(latencyMs)
    shape = OrmQueryShapes
      .all
      .find(_.name == shapeName)
      .getOrElse(throw new IllegalArgumentException(s"unknown shape: $shapeName"))
    // Pooled transactor, not `BenchmarkDb.transactor` (`Transactor.fromDriverManager`): the
    // latter opens/closes a fresh Postgres connection — forking a backend process — on every
    // transaction, which would put several ms of high-variance, non-Grackle work inside every
    // timed sample. The ORM arms below already run against a pooled HikariCP
    // `EntityManagerFactory` (`persistence.xml`'s `hibernate.hikari.maximumPoolSize=4`), so pool
    // parity with Grackle's arm matters for this class's headline comparison. Mirrors
    // `SqlJoinDepthBenchmark`/`RawVsGrackleBenchmark`'s `setup` in `benchmarksSql`.
    val (transactor, release) = BenchmarkDb.transactorResource[IO].allocated.unsafeRunSync()
    releaseTransactor = release
    grackleMapping = AdventureWorksMapping.mkMapping[IO](transactor)
    BenchmarkDb.prewarm[IO](transactor).unsafeRunSync()
    // No `hibernate.generate_statistics` override here: default is off, keeping this run
    // timing-only (see deviation #3 / OrmQueryCounts for the query-count harness).
    entityManagerFactory = OrmDb.emf()
  }

  @TearDown(Level.Trial)
  def teardownTrial(): Unit = {
    // Before the closes below, not after: if one of them throws, an uncleared toxic would leak
    // into the next trial and silently add its latency on top of that trial's own.
    Toxiproxy.clearToxics()
    if (entityManagerFactory != null) entityManagerFactory.close()
    if (releaseTransactor != null) releaseTransactor.unsafeRunSync()
  }

  @Setup(Level.Invocation)
  def setupInvocation(): Unit =
    entityManager = entityManagerFactory.createEntityManager()

  @TearDown(Level.Invocation)
  def teardownInvocation(): Unit =
    if (entityManager != null) entityManager.close()

  @Benchmark
  def grackleArm(blackhole: Blackhole): Unit = {
    val result = grackleMapping.compileAndRun(GrackleShapeQuery.queryFor(shape)).unsafeRunSync()
    blackhole.consume(result)
  }

  @Benchmark
  def naiveArm(blackhole: Blackhole): Unit = {
    val names = NaiveOrmArm.run(entityManager, shape, JoinChain.defaultRootCode)
    blackhole.consume(names)
  }

  @Benchmark
  def eagerArm(blackhole: Blackhole): Unit = {
    val names = EagerOrmArm.run(entityManager, shape, JoinChain.defaultRootCode)
    blackhole.consume(names)
  }
}
