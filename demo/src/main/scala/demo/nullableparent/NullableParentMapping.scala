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

package demo.nullableparent

import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.util.meta.Meta
import org.typelevel.doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import grackle.doobie.DoobieMonitor
import grackle.doobie.postgres.DoobiePgMapping
import grackle.syntax._

import demo.world.WorldData.{mkTransactor, PostgresConnectionInfo}

/**
 * An interactive playground for the INNER-JOIN-under-LEFT-JOIN bug.
 *
 * NOT the authoritative artifact. That is
 * `modules/doobie-pg/src/test/scala/NullableParentInnerJoinSuite.scala`, which asserts the
 * behaviour. This is a deliberate second copy of the same schema and mapping so the demo module —
 * which depends on `doobiepg`, not the reverse, and so cannot see its test scope — can serve it
 * over HTTP. Edit this one freely while exploring; the test is what states the claim.
 *
 * `type A { b: B }` is nullable and `type B { c: C! }` is not, so descending into `c` makes
 * Grackle emit an INNER JOIN nested inside the LEFT JOIN for `b`, and rows whose `b` is absent are
 * eliminated instead of being returned with `"b": null`.
 *
 * Try, against a running `postgres` (`sbt pgUp`), with `sbt demo/run`:
 *
 * {{{
 * # both rows, one with "b": null
 * curl -s 'http://localhost:8080/nullrepro?query={as{name b{name}}}'
 *
 * # only a-with-b — a-without-b vanishes
 * curl -s 'http://localhost:8080/nullrepro?query={as{name b{name c{name}}}}'
 * }}}
 *
 * The mapping is wired with `DoobieMonitor.loggerMonitor`, so each request logs the SQL it
 * emitted — watch the join flip from a lone LEFT JOIN to LEFT + INNER between those two queries.
 */
trait NullableParentMapping[F[_]] extends DoobiePgMapping[F] {

  object aTable extends TableDef("nulltest_a") {
    val id = col("id", Meta[Int])
    val bId = col("b_id", Meta[Int], true)
    val name = col("name", Meta[String])
  }

  object bTable extends TableDef("nulltest_b") {
    val id = col("id", Meta[Int])
    val cId = col("c_id", Meta[Int])
    val name = col("name", Meta[String])
  }

  object cTable extends TableDef("nulltest_c") {
    val id = col("id", Meta[Int])
    val name = col("name", Meta[String])
  }

  val schema =
    schema"""
      type Query {
        as: [A!]!
      }
      type A {
        name: String!
        b: B
      }
      type B {
        name: String!
        c: C!
      }
      type C {
        name: String!
      }
    """

  val QueryType = schema.ref("Query")
  val AType = schema.ref("A")
  val BType = schema.ref("B")
  val CType = schema.ref("C")

  val typeMappings =
    TypeMappings(
      ObjectMapping(QueryType)(
        SqlObject("as")
      ),
      ObjectMapping(AType)(
        SqlField("id", aTable.id, key = true, hidden = true),
        SqlField("bId", aTable.bId, hidden = true),
        SqlField("name", aTable.name),
        SqlObject("b", Join(aTable.bId, bTable.id))
      ),
      ObjectMapping(BType)(
        SqlField("id", bTable.id, key = true, hidden = true),
        SqlField("cId", bTable.cId, hidden = true),
        SqlField("name", bTable.name),
        SqlObject("c", Join(bTable.cId, cTable.id))
      ),
      ObjectMapping(CType)(
        SqlField("id", cTable.id, key = true, hidden = true),
        SqlField("name", cTable.name)
      )
    )
}

object NullableParentMapping {

  def mkMapping[F[_]: Sync](
      transactor: Transactor[F],
      monitor: DoobieMonitor[F]): NullableParentMapping[F] =
    new DoobiePgMapping(transactor, monitor) with NullableParentMapping[F]

  def mkMappingFromTransactor[F[_]: Sync](
      transactor: Transactor[F]): NullableParentMapping[F] = {
    val logger: Logger[F] = Slf4jLogger.getLoggerFromName[F]("SqlQueryLogger")
    mkMapping(transactor, DoobieMonitor.loggerMonitor[F](logger))
  }

  /**
   * Recreated on every startup so the playground is reproducible after any amount of poking at
   * the tables by hand. Dropped and rebuilt rather than `create if not exists`, deliberately.
   */
  private def seed[F[_]: Sync](transactor: Transactor[F]): F[Unit] = {
    val statements =
      List(
        sql"drop table if exists nulltest_a, nulltest_b, nulltest_c cascade",
        sql"create table nulltest_c (id int primary key, name text not null)",
        sql"""create table nulltest_b (
                id int primary key,
                c_id int not null references nulltest_c(id),
                name text not null)""",
        sql"""create table nulltest_a (
                id int primary key,
                b_id int null references nulltest_b(id),
                name text not null)""",
        sql"insert into nulltest_c values (1, 'cat-1')",
        sql"insert into nulltest_b values (10, 1, 'sub-10')",
        sql"""insert into nulltest_a values
                (100, 10, 'a-with-b'),
                (200, null, 'a-without-b')"""
      )
    statements.traverse_(_.update.run.transact(transactor)).void
  }

  def apply[F[_]: Async]: Resource[F, NullableParentMapping[F]] = {
    val connInfo = PostgresConnectionInfo("localhost", PostgresConnectionInfo.DefaultPort)
    for {
      transactor <- mkTransactor[F](connInfo)
      _ <- Resource.eval(seed(transactor))
    } yield mkMappingFromTransactor[F](transactor)
  }
}
