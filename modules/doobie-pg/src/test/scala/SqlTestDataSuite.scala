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

import cats.Applicative
import cats.effect.IO

import grackle.sql.test.SqlTestData
import grackle.syntax._

/**
 * A minimal, standalone-mapping fixture for exercising `SqlTestData`'s pure CSV-parsing logic.
 * No Docker container is needed: `runCommand` is stubbed and never invoked by this suite.
 */
trait SqlTestDataFixture[F[_]] extends SqlTestData[F] {
  implicit def F: Applicative[F]
  def runCommand(fragment: Fragment): F[Unit] = F.unit
  def seedData: List[F[Unit]] = Nil

  def readRowsForTest(resourcePath: String): (List[String], List[List[String]]) =
    readRows(resourcePath)

  object films extends TableDef("films") {
    val title = col("title", text)
  }

  val schema =
    schema"""
      type Query {
        films: [Film!]!
      }
      type Film {
        title: String!
      }
    """

  val QueryType = schema.ref("Query")
  val FilmType = schema.ref("Film")

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings = List(SqlObject("films"))
      ),
      ObjectMapping(
        tpe = FilmType,
        fieldMappings = List(SqlField("title", films.title, key = true))
      )
    )
}

final class SqlTestDataSuite extends DoobiePgDatabaseSuite {
  test("readRows splits header and body on |") {
    val m = new DoobiePgTestMapping[IO](transactor) with SqlTestDataFixture[IO] {
      def F: Applicative[IO] = Applicative[IO]
    }
    val (header, rows) = m.readRowsForTest("embedding/films.csv")
    assertEquals(header, List("title", "synopsis_short", "synopsis_long"))
    assert(rows.nonEmpty)
  }
}
