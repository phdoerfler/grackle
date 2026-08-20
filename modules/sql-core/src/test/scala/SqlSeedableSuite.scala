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

package grackle.sql.test

import cats.effect.{IO, Resource}
import munit.{AnyFixture, CatsEffectSuite}
import munit.catseffect.IOFixture

import grackle.Mapping

/**
 * Mixed into a per-dataset suite trait to seed its mapping's data once before tests.
 *
 * Seeding runs through a suite-local `IOFixture` rather than a blocking `beforeAll`, since
 * `sql-core` is cross-built to Scala.js/Native, where `IO#unsafeRunSync` isn't available.
 */
trait SqlSeedableSuite extends CatsEffectSuite {
  def mapping: Mapping[IO]

  private val seedFixture: IOFixture[Unit] =
    ResourceSuiteLocalFixture(
      "sql-seed",
      Resource.eval(IO.defer(mapping match {
        case m: SqlTestData[IO] @unchecked => m.loadAll
        case _ => IO.unit
      }))
    )

  override def munitFixtures: Seq[AnyFixture[_]] = super.munitFixtures :+ seedFixture
}
