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

package grackle.benchmarks.orm

import munit.FunSuite

class AdventureWorksEntitiesSuite extends FunSuite {
  val factory = OrmDb.emf()

  override def afterAll(): Unit = factory.close()

  test("EntityManagerFactory validates all 11 entity mappings against the live schema") {
    // Metadata validation (column/table existence, association FK resolution) happens at
    // factory-build time; reaching this line at all is the assertion. Also exercises a real
    // find() to confirm the mapping isn't just syntactically valid but semantically correct.
    val em = factory.createEntityManager()
    try {
      val fr = em.find(classOf[CountryRegionEntity], "FR")
      assertEquals(fr.countryRegionCode, "FR")
      assertEquals(fr.name, "France")
    } finally em.close()
  }
}
