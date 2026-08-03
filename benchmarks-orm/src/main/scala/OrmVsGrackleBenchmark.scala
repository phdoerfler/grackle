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
import grackle.benchmarks.sql.{AdventureWorksMapping, BenchmarkDb, JoinChain}

/**
 * Combined timing comparison: Grackle vs Hibernate-naive vs Hibernate-eager, across the four
 * curated shapes (`OrmQueryShapes.all`), all three arms in one JMH run so their numbers share
 * warmup/JIT/GC conditions — avoids the cross-run comparability problems the BigDecimal.equals
 * measurement work hit twice (see `topic/sql-benchmarks` history).
 *
 * Query-count instrumentation is deliberately NOT part of this class — see `OrmQueryCounts` and
 * this plan's deviation #3. This class measures wall-clock time only.
 *
 * Requires `benchmark-postgres` running (`sbt benchPgUp`).
 *
 * sbt "benchmarksOrm/Jmh/run -f 1 -wi 3 -i 5 OrmVsGrackleBenchmark"
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(3)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
class OrmVsGrackleBenchmark {

  @Param(Array("shallow-narrow", "deep-narrow", "deep-wide", "untuned"))
  var shapeName: String = _

  private var shape: Shape = _
  private var grackleMapping: Mapping[IO] = _
  private var entityManagerFactory: EntityManagerFactory = _
  // Fresh per invocation, not per trial: the seed data never changes, so a reused
  // EntityManager's first-level cache would make lazy loads resolve from memory instead of
  // issuing SQL after the first invocation, collapsing the naive arm's behavior toward the
  // eager arm's and inverting the comparison. Mirrors real per-request EntityManager scoping.
  private var entityManager: EntityManager = _

  @Setup(Level.Trial)
  def setupTrial(): Unit = {
    shape = OrmQueryShapes
      .all
      .find(_.name == shapeName)
      .getOrElse(throw new IllegalArgumentException(s"unknown shape: $shapeName"))
    grackleMapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO])
    // No `hibernate.generate_statistics` override here: default is off, keeping this run
    // timing-only (see deviation #3 / OrmQueryCounts for the query-count harness).
    entityManagerFactory = OrmDb.emf()
  }

  @TearDown(Level.Trial)
  def teardownTrial(): Unit =
    if (entityManagerFactory != null) entityManagerFactory.close()

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
