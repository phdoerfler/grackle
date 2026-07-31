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

import cats.effect.{Async, Resource}
import org.typelevel.doobie.hikari.{Config, HikariTransactor}
import org.typelevel.doobie.util.transactor.Transactor

object BenchmarkDb {
  val jdbcUrl: String = "jdbc:postgresql://localhost:5433/benchmark"
  val username: String = "benchmark"
  val password: String = "benchmark"

  def transactor[F[_]: Async]: Transactor[F] =
    Transactor.fromDriverManager[F]("org.postgresql.Driver", jdbcUrl, username, password, None)

  /**
   * Small and fixed: the benchmark is single-threaded, so this only needs to cover Grackle's
   * per-level queries. Stated explicitly rather than left to HikariCP's default of 10 so the
   * configuration is deterministic.
   */
  val maxPoolSize: Int = 4

  /**
   * Pooled transactor for the benchmark. `Transactor.fromDriverManager` opens and closes a
   * connection per transaction — Postgres forks a backend process each time — which would put
   * several milliseconds of high-variance, non-Grackle work inside every timed sample.
   */
  def transactorResource[F[_]: Async]: Resource[F, HikariTransactor[F]] =
    HikariTransactor.fromConfig[F](
      Config(
        jdbcUrl = jdbcUrl,
        username = Some(username),
        password = Some(password),
        driverClassName = Some("org.postgresql.Driver"),
        maximumPoolSize = maxPoolSize,
        minimumIdle = maxPoolSize
      )
    )
}
