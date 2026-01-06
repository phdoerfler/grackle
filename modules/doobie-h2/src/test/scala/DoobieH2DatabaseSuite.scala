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

package grackle.doobie.h2
package test

import java.sql.{Time, Timestamp}
import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import java.util.UUID
import scala.util.Try
import cats.effect.{IO, Resource, Sync}
import cats.syntax.all.*
import doobie.{Meta, Transactor}
import doobie.enumerated.JdbcType
import doobie.util.meta.MetaConstructors.Basic
import doobie.util.update.Update0
import io.circe.{Json, Decoder as CDecoder, Encoder as CEncoder}
import io.circe.syntax.*
import io.circe.parser.parse
import munit.catseffect.*
import grackle.doobie.DoobieMonitor
import grackle.doobie.test.DoobieDatabaseSuite
import grackle.sql.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import doobie.implicits._

trait DoobieH2DatabaseSuite extends DoobieDatabaseSuite {
  abstract class DoobieH2TestMapping[F[_]: Sync](transactor: Transactor[F], monitor: DoobieMonitor[F] = DoobieMonitor.noopMonitor[IO])
    extends DoobieH2Mapping[F](transactor, monitor) with DoobieTestMapping[F] with SqlTestMapping[F] {
      def mkTestCodec[T](meta: Meta[T]): TestCodec[T] = (meta, false)

      val uuid: TestCodec[UUID] =
        mkTestCodec(Meta[String].tiemap(s => Try(UUID.fromString(s)).toEither.leftMap(_.getMessage))(_.toString))

      val localTime: TestCodec[LocalTime] = {
        mkTestCodec(Meta[Time].timap(t => LocalTime.ofNanoOfDay(t.toLocalTime.toNanoOfDay))(lt => Time.valueOf(lt)))
      }

      val localDate: TestCodec[LocalDate] =
        (Basic.oneObject(JdbcType.Date, None, classOf[LocalDate]), false)

      // Forget precise time zone for compatibility with Postgres. Nb. this is specific to this test suite.
      val offsetDateTime: TestCodec[OffsetDateTime] =
        mkTestCodec(Meta[Timestamp].timap(t => OffsetDateTime.ofInstant(t.toInstant, ZoneId.of("UTC")))(o => Timestamp.from(o.toInstant)))

      val nvarchar: TestCodec[String] = mkTestCodec(Meta[String])

      val jsonb: TestCodec[Json] =
        mkTestCodec(Meta[String].tiemap(s => parse(s).leftMap(_.getMessage))(_.noSpaces))

      override def list[T: CDecoder : CEncoder](c: TestCodec[T]): TestCodec[List[T]] = {
        def put(ts: List[T]): String = ts.asJson.noSpaces
        def get(s: String): Either[String, List[T]] = parse(s).map(_.as[List[T]].toOption.get).leftMap(_.getMessage)

        mkTestCodec(Meta[String].tiemap(get)(put))
      }
    }

  case class H2ConnectionInfo(host: String, port: Int) {
    val driverClassName = "org.h2.Driver"
    val databaseName = "test"
    val username = "sa"
    val password = "Test_123_Test"
    val jdbcUrl = s"jdbc:h2:~/$databaseName"
  }

  object H2ConnectionInfo {
    val DefaultPort = 1433
  }

  val h2ConnectionInfo: H2ConnectionInfo =
    H2ConnectionInfo("localhost", H2ConnectionInfo.DefaultPort)

//  def transactorResource: Resource[IO, Transactor[IO]] = {
//    val connInfo = h2ConnectionInfo
//    import connInfo._
//
//    val props = new java.util.Properties()
//    Resource.pure(
//      Transactor.fromDriverManager[IO](
//        driverClassName,
//        jdbcUrl,
//        props,
//        None
//      )
//    ).flatMap(withTestData)
//  }

  def transactorResource: Resource[IO, Transactor[IO]] = {
    val connInfo = h2ConnectionInfo
    import connInfo._

    val xa =
      Transactor.fromDriverManager[IO](
        driverClassName,
        jdbcUrl,
        new java.util.Properties(),
        None
      )

    Resource
      .pure(xa)
      .evalTap(initializeTestData)
  }

  def initializeTestData(xa: Transactor[IO]): IO[Unit] = {
    import scala.jdk.CollectionConverters._

    Files
      .list(Paths.get("testdata", "h2"))
      .iterator()
      .asScala
      .toList
      .traverse_ { path =>
        val content = Files.readString(path, StandardCharsets.UTF_8)
        singleTestFile(content).run.transact(xa).void
      }
  }

//  def withTestData(xa: Transactor[IO]): Resource[IO, Transactor[IO]] = {
//    import scala.jdk.CollectionConverters._
//    val things = for {
//      sql <- Files.list(Paths.get("testdata", "h2")).iterator().asScala.toList
//      content = Files.readString(sql, StandardCharsets.UTF_8)
//      update = singleTestFile(content)
//    } yield update.run
//
//    val all = things.reduceLeft(_ *> _)
////    val all = things.sequence
//
//    Resource(all.transact(xa))
//  }

  def singleTestFile(content: String): Update0 =
    sql"$content".update

  val transactorFixture: IOFixture[Transactor[IO]] = ResourceSuiteLocalFixture("h2pg", transactorResource)
  override def munitFixtures: Seq[IOFixture[_]] = Seq(transactorFixture)

  def transactor: Transactor[IO] = transactorFixture()
}
