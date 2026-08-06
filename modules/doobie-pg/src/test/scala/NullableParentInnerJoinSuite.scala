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

package grackle.doobie.postgres.test

import cats.effect.{IO, Sync}
import io.circe.Json
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.util.meta.Meta
import org.typelevel.doobie.util.transactor.Transactor

import grackle._
import grackle.doobie.DoobieMonitor
import grackle.doobie.postgres.DoobiePgMapping
import grackle.syntax._

/**
 * A non-null field nested beneath a nullable parent generates an `INNER JOIN` in the same flat
 * join chain as the parent's `LEFT JOIN`, which eliminates rows whose nullable parent is
 * absent. Those rows should come back with the parent as `null`: `c`'s non-null-ness only
 * applies when a `B` exists at all.
 *
 * The emitted SQL for `{ as { name b { name c { name } } } }` is:
 *
 * {{{
 * SELECT ... FROM nulltest_a
 * LEFT JOIN nulltest_b ON ((nulltest_b.id = nulltest_a.b_id))
 * INNER JOIN nulltest_c ON ((nulltest_c.id = nulltest_b.c_id))
 * }}}
 *
 * Each join is right on its own — `b` is nullable so `LEFT`, `c` is non-null so `INNER` — but
 * they share one flat chain, so an `a` row with a NULL `b_id` gets NULL `b` columns from the
 * left join and is then eliminated by the inner join below it.
 *
 * The join type appears to be decided by `SqlMapping`'s `SqlSelect.nest`:
 * `val inner = !context.tpe.isNullable && !context.tpe.isList` — derived from the field's own
 * type, with no reference to the `parentContext` it is handed, so a nullable ancestor anywhere
 * along the join path is not accounted for.
 *
 * This suite creates and drops its own tables, so it needs only a running `postgres` service
 * (`docker compose up -d postgres`) and no changes to `testdata/pg`.
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
      monitor: DoobieMonitor[F] = DoobieMonitor.noopMonitor[IO]): Mapping[F] =
    new DoobiePgMapping[F](transactor, monitor) with NullableParentMapping[F]
}

final class NullableParentInnerJoinSuite extends DoobiePgDatabaseSuite {

  private val ddl =
    List(
      sql"drop table if exists nulltest_a, nulltest_b, nulltest_c cascade".update.run,
      sql"create table nulltest_c (id int primary key, name text not null)".update.run,
      sql"""create table nulltest_b (
              id int primary key,
              c_id int not null references nulltest_c(id),
              name text not null)""".update.run,
      sql"""create table nulltest_a (
              id int primary key,
              b_id int null references nulltest_b(id),
              name text not null)""".update.run,
      sql"insert into nulltest_c values (1, 'cat-1')".update.run,
      sql"insert into nulltest_b values (10, 1, 'sub-10')".update.run,
      sql"""insert into nulltest_a values
              (100, 10, 'a-with-b'),
              (200, null, 'a-without-b')""".update.run
    )

  private def setup: IO[Unit] =
    ddl.foldLeft(IO.unit)((acc, stmt) => acc *> stmt.transact(transactor).void)

  // Control. Same mapping, same data, but the query stops at `b` so no non-null object is
  // selected beneath the nullable one. If this passes while the next test fails, the mapping is
  // sound and the trigger is specifically a non-null object nested under a nullable one.
  test("control: without descending into a non-null child, the null-parent row is returned") {
    val mapping = NullableParentMapping.mkMapping[IO](transactor)

    setup *>
      mapping.compileAndRun("query { as { name b { name } } }").map { result =>
        val names = result
          .hcursor
          .downField("data")
          .downField("as")
          .focus
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)
          .flatMap(_.hcursor.get[String]("name").toOption)
          .toList
          .sorted

        assertEquals(names, List("a-with-b", "a-without-b"), s"control failed: $result")
      }
  }

  test("a row whose nullable parent is null is dropped from the result") {
    val mapping = NullableParentMapping.mkMapping[IO](transactor)

    setup *>
      mapping.compileAndRun("query { as { name b { name c { name } } } }").map { result =>
        assert(
          !result.hcursor.downField("errors").succeeded,
          s"unexpected GraphQL errors: $result")

        val as = result
          .hcursor
          .downField("data")
          .downField("as")
          .focus
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)

        val names = as.flatMap(_.hcursor.get[String]("name").toOption).toList.sorted

        // `a-without-b` has no `b`, so it should come back with `"b": null` rather than vanish.
        // Observed: only `a-with-b` is returned.
        assertEquals(
          names,
          List("a-with-b", "a-without-b"),
          s"a row whose nullable parent is null was dropped entirely: $result")

        val withoutB =
          as.find(_.hcursor.get[String]("name").toOption.contains("a-without-b")).get
        assertEquals(
          withoutB.hcursor.downField("b").focus,
          Some(Json.Null),
          s"""expected "b": null for the row with no b: $withoutB""")
      }
  }
}
