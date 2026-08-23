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
 * No Docker container is needed: `runCommands` is stubbed and never invoked by this suite.
 */
trait SqlTestDataFixture[F[_]] extends SqlTestData[F] {
  implicit def F: Applicative[F]
  def runCommands(fragments: List[Fragment]): F[Unit] = F.unit
  def seedData: List[SeedTable] = Nil

  def readRowsForTest(resourcePath: String): (List[String], List[List[String]]) =
    readRows(resourcePath)

  object films extends TableDef("films") {
    val title = col("title", text)
    val synopsisShort = col("synopsis_short", nullable(text))
    val synopsisLong = col("synopsis_long", nullable(text))
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
  def fixture: DoobiePgTestMapping[IO] with SqlTestDataFixture[IO] =
    new DoobiePgTestMapping[IO](transactor) with SqlTestDataFixture[IO] {
      def F: Applicative[IO] = Applicative[IO]
      override def runCommands(fragments: List[Fragment]): IO[Unit] =
        super[SqlTestDataFixture].runCommands(fragments)
    }

  test("readRows splits header and body on |") {
    val m = fixture
    val (header, rows) = m.readRowsForTest("embedding/films.csv")
    assertEquals(header, List("title", "synopsis_short", "synopsis_long"))
    assertEquals(
      rows,
      List(
        List("Film 1", "Short film 1", "Long film 1"),
        List("Film 2", "Short film 2", "Long film 2"),
        List("Film 3", "Short film 3", "Long film 3")
      )
    )
  }

  test("seedTable raises a clear error when a row has more fields than the header") {
    val m = fixture
    val err = intercept[RuntimeException] {
      m.seedTable(m.films, "embedding/films_malformed.csv")
    }
    assert(clue(err.getMessage).contains("row 1"))
    assert(clue(err.getMessage).contains("4 field(s)"))
    assert(clue(err.getMessage).contains("expected 3"))
  }
}
