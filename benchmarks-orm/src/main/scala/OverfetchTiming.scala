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

import cats.effect.{IO, IOApp}
import cats.implicits._

import grackle.benchmarks.sql.{AdventureWorksMapping, BenchmarkDb, Toxiproxy}

/**
 * Indicative timing for the over-fetch effect. Every panel fetches the same Person rows through
 * the same `person.person_heavy` view; the only difference is which columns leave the database.
 * Grackle projects just `firstName` in all of them, so it is the constant baseline; the ORM
 * loads whichever entity the panel names, and Hibernate selects exactly that entity's mapped
 * scalars — so each panel over-fetches exactly one thing and isolates one cost:
 *
 *   - compute: `PersonComputeEntity` (adds `heavy`), full bandwidth — pure CPU
 *   - bandwidth: `PersonBytesEntity` (adds `wide`), throttled — pure bytes
 *   - finale: `PersonBothEntity` (adds both), throttled + RTT — everything
 *
 * These entities and the `people` root exist only here, so the expensive columns never touch
 * the latency/statement benchmarks. Uses a POOLED transactor for Grackle (as the JMH benchmark
 * does), so a reused connection pays only each request's round trips, not a fresh handshake per
 * query. NOT a JMH benchmark and NOT validated — quick medians for the illustrative chart only.
 */
object OverfetchTiming extends IOApp.Simple {

  private val warmup = 2
  private val samples = 5

  private def median(xs: List[Long]): Long = xs.sorted.apply(xs.length / 2)

  private def timeMs(io: IO[_]): IO[Long] =
    for {
      t0 <- IO.monotonic
      _ <- io
      t1 <- IO.monotonic
    } yield (t1 - t0).toMillis

  private def medianMs(io: IO[_]): IO[Long] =
    List.fill(warmup)(io).sequence_ *> List.fill(samples)(timeMs(io)).sequence.map(median)

  def run: IO[Unit] =
    BenchmarkDb.transactorResource[IO].use { xa =>
      val mapping = AdventureWorksMapping.mkMapping[IO](xa)
      val factory = OrmDb.emf()
      val grackle = mapping.compileAndRun("query { people { firstName } }").void
      def fetchOrm(entity: String): IO[Unit] =
        IO.blocking {
          val em = factory.createEntityManager()
          try { val _ = em.createQuery(s"select p from $entity p").getResultList(); () }
          finally em.close()
        }

      def under(label: String, rttMs: Int, kbps: Int, entity: String): IO[Unit] =
        for {
          _ <- IO.blocking(Toxiproxy.setConditions(rttMs, kbps))
          g <- medianMs(grackle)
          e <- medianMs(fetchOrm(entity))
          _ <- IO.println(s">>>PANEL $label rtt=$rttMs kbps=$kbps grackle=$g eager=$e<<<")
        } yield ()

      for {
        _ <- BenchmarkDb.prewarm[IO](xa)
        _ <- grackle // prime both connection pools before any condition
        _ <- fetchOrm("PersonBothEntity")
        _ <- under("compute", 0, 0, "PersonComputeEntity")
        _ <- under("bandwidth", 0, 8000, "PersonBytesEntity")
        _ <- under("finale", 50, 8000, "PersonBothEntity")
        _ <- IO.blocking(Toxiproxy.clearToxics())
        _ <- IO.blocking(factory.close())
      } yield ()
    }
}
