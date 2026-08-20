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
import munit.ScalaCheckSuite
import munit.catseffect.IOFixture
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import org.typelevel.doobie.Meta

import grackle.sql.test._

/**
 * Pure golden + scalacheck round-trip tests for the four custom enum codecs
 * (Genre/Feature/ItemType/EntityType). No DB: the mappings here are built with a `null`
 * transactor (never touched), pinning the pure, backend-independent wire-form logic on the enum
 * companions themselves.
 */
final class CustomCodecSuite extends DoobiePgDatabaseSuite with ScalaCheckSuite {
  // No-DB instances (null transactor never touched); supply the enum codec defs inline, exactly
  // as the concrete DB-backed suites in DoobiePgSuites.scala do, so each mapping compiles.
  lazy val movie: DoobiePgTestMapping[IO] with SqlMovieMapping[IO] =
    new DoobiePgTestMapping(null) with SqlMovieMapping[IO] {
      def genre: TestCodec[Genre] = (Meta[Int].imap(Genre.fromInt)(Genre.toInt), false)
      def feature: TestCodec[Feature] =
        (Meta[String].imap(Feature.fromString)(_.toString), false)
      def tagList: TestCodec[List[String]] = (Meta[Int].imap(Tags.fromInt)(Tags.toInt), false)
    }
  lazy val recur = new DoobiePgTestMapping(null) with SqlRecursiveInterfacesMapping[IO] {
    def itemType: TestCodec[ItemType] =
      (Meta[Int].timap(ItemType.fromInt)(ItemType.toInt), false)
  }
  lazy val iface = new DoobiePgTestMapping(null) with SqlInterfacesMapping[IO] {
    def entityType: TestCodec[EntityType] =
      (Meta[Int].timap(EntityType.fromInt)(EntityType.toInt), false)
  }

  override def munitFixtures: Seq[IOFixture[_]] = Nil

  // Golden: pin each enum's wire form independent of the codec (catches symmetric bugs).
  test("Genre wire form") {
    assertEquals(movie.Genre.toInt(movie.Genre.Drama), 1)
    assertEquals(movie.Genre.toInt(movie.Genre.Action), 2)
    assertEquals(movie.Genre.toInt(movie.Genre.Comedy), 3)
    assertEquals(movie.Genre.fromInt(1), movie.Genre.Drama: movie.Genre)
  }
  test("Feature wire form") {
    assertEquals(movie.Feature.fromString("hd"), movie.Feature.HD: movie.Feature)
    assertEquals(movie.Feature.HD.toString, "HD")
  }
  test("ItemType wire form") {
    assertEquals(recur.ItemType.toInt(recur.ItemType.ItemA), 1)
    assertEquals(recur.ItemType.toInt(recur.ItemType.ItemB), 2)
  }
  test("EntityType wire form") {
    assertEquals(iface.EntityType.toInt(iface.EntityType.Film), 1)
    assertEquals(iface.EntityType.toInt(iface.EntityType.Series), 2)
  }

  // scalacheck round-trip (catches asymmetric fromInt/toInt bugs) over enumerated values.
  implicit val arbGenre: Arbitrary[movie.Genre] =
    Arbitrary(Gen.oneOf(movie.Genre.Drama, movie.Genre.Action, movie.Genre.Comedy))
  property("Genre fromInt . toInt is identity") {
    forAll { (g: movie.Genre) => movie.Genre.fromInt(movie.Genre.toInt(g)) == g }
  }
}
