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
import munit.catseffect.IOFixture
import org.typelevel.doobie.Meta

import grackle.sql.test._

/**
 * `Tags` is the codec behind the movies table's bit-packed `tags` column, so it is only
 * exercised end to end by a query that reads or writes that column. These tests pin it
 * directly, on a mapping with no database behind it.
 */
final class TagsSuite extends DoobiePgDatabaseSuite {
  // No database is needed: nothing here runs a query, so the null transactor is never used.
  lazy val mapping = new DoobiePgTestMapping(null) with SqlMovieMapping[IO] {
    def genre: TestCodec[Genre] = (Meta[Int].imap(Genre.fromInt)(Genre.toInt), false)
    def feature: TestCodec[Feature] =
      (Meta[String].imap(Feature.fromString)(_.toString), false)
    def tagList: TestCodec[List[String]] = (Meta[Int].imap(Tags.fromInt)(Tags.toInt), false)
  }
  override def munitFixtures: Seq[IOFixture[_]] = Nil

  test("every bit pattern round-trips") {
    (0 to 7).foreach { i =>
      assertEquals(mapping.Tags.toInt(mapping.Tags.fromInt(i)), i, s"bit pattern $i")
    }
  }

  test("each bit selects its own tag") {
    assertEquals(mapping.Tags.fromInt(1), List("tag1"))
    assertEquals(mapping.Tags.fromInt(2), List("tag2"))
    assertEquals(mapping.Tags.fromInt(4), List("tag3"))
    assertEquals(mapping.Tags.fromInt(5), List("tag1", "tag3"))
  }

  test("a selection shorter than the tag list encodes without running off the end") {
    assertEquals(mapping.Tags.toInt(Nil), 0)
    assertEquals(mapping.Tags.toInt(List("tag1")), 1)
    assertEquals(mapping.Tags.toInt(List("tag3")), 4)
  }

  test("an unknown tag contributes no bits") {
    assertEquals(mapping.Tags.toInt(List("nope")), 0)
    assertEquals(mapping.Tags.toInt(List("tag2", "nope")), 2)
  }
}
