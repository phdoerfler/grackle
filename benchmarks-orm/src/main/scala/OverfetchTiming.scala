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

import grackle.benchmarks.sql.{AdventureWorksMapping, BenchmarkDb, JoinChain, Toxiproxy}

/**
 * Indicative timing for the over-fetch effect: the same query (which requests NEITHER the `heavy`
 * nor the `wide` column) run through Grackle and the tuned eager ORM arm, under conditions that
 * isolate each cost — full bandwidth to expose `heavy`'s compute, throttled bandwidth to expose
 * `wide`'s bytes, latency for round trips, and everything at once for the finale.
 *
 * Uses a POOLED transactor for Grackle (as the JMH benchmark does), so a reused connection pays
 * only each request's round trips, not a fresh TCP+auth handshake per query — otherwise the
 * unpooled transactor makes Grackle look absurdly latency-sensitive.
 *
 * NOT a JMH benchmark and NOT validated — quick medians for the illustrative charts only.
 */
object OverfetchTiming extends IOApp.Simple {

  private val shape = OrmQueryShapes.deepNarrow // reaches Person; requests neither heavy nor wide
  private val warmup = 3
  private val samples = 7

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
      val grackle = mapping.compileAndRun(GrackleShapeQuery.queryFor(shape)).void
      val eager = IO.blocking {
        val em = factory.createEntityManager()
        try { val _ = EagerOrmArm.run(em, shape, JoinChain.defaultRootCode); () }
        finally em.close()
      }

      def under(label: String, rttMs: Int, kbps: Int): IO[Unit] =
        for {
          _ <- IO.blocking(Toxiproxy.setConditions(rttMs, kbps))
          g <- medianMs(grackle)
          e <- medianMs(eager)
          _ <- IO.println(s">>>PANEL $label rtt=$rttMs kbps=$kbps grackle=$g eager=$e<<<")
        } yield ()

      for {
        _ <- BenchmarkDb.prewarm[IO](xa)
        _ <- grackle // prime both connection pools before any condition
        _ <- eager
        _ <- under("compute", 0, 0)
        _ <- under("bandwidth-2MBps", 0, 2000)
        _ <- under("latency-50", 50, 0)
        _ <- under("finale-50+2MBps", 50, 2000)
        _ <- IO.blocking(Toxiproxy.clearToxics())
        _ <- IO.blocking(factory.close())
      } yield ()
    }
}
