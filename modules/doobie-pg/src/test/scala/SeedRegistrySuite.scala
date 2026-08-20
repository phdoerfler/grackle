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

import cats.effect.IO

import grackle.sql.test.SqlTestMapping
import grackle.syntax._

/**
 * A minimal, standalone-mapping fixture for exercising `SqlTestMapping`'s seed-column registry.
 * No Docker container is needed: the `Transactor` built by `DoobiePgDatabaseSuite` doesn't open
 * a connection until a query actually runs, and populating the registry doesn't run one.
 */
trait SeedRegistryFixture[F[_]] extends SqlTestMapping[F] {

  object films extends TableDef("films") {
    val title = col("title", text)
    val synopsisShort = col("synopsis_short", nullable(text))
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

final class SeedRegistrySuite extends DoobiePgDatabaseSuite {
  test("col registrations are retrievable by table and name") {
    val m = new DoobiePgTestMapping[IO](transactor) with SeedRegistryFixture[IO]
    val _ = m.films.title // force `films` to initialize, populating the registry
    val cols = m.seedColumnsFor(m.TableName("films"))
    assertEquals(cols.keySet, Set("title", "synopsis_short"))
    assertEquals(cols("title").decode("Film 1"), "Film 1")
  }
}
