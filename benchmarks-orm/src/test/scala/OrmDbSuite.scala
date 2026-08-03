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

class OrmDbSuite extends FunSuite {
  test("EntityManagerFactory builds and can reach the seeded database") {
    val factory = OrmDb.emf()
    try {
      val em = factory.createEntityManager()
      try {
        val count = em.createNativeQuery("SELECT 1").getSingleResult
        assertEquals(count.toString, "1")
      } finally em.close()
    } finally factory.close()
  }
}
