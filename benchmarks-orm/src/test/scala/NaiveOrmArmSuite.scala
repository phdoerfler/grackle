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

class NaiveOrmArmSuite extends FunSuite {
  val factory = OrmDb.emf()
  override def afterAll(): Unit = factory.close()

  // Same set AdventureWorksMappingSuite (benchmarksSql) checks against, for the same root/depth.
  val knownCategoryNames: Set[String] = Set("Bikes", "Components", "Clothing", "Accessories")

  OrmQueryShapes.all.foreach { shape =>
    test(s"naive arm reaches a known category name for shape ${shape.name}") {
      val em = factory.createEntityManager()
      try {
        val names = NaiveOrmArm.run(em, shape, grackle.benchmarks.sql.JoinChain.defaultRootCode)
        if (shape.depth == grackle.benchmarks.sql.JoinChain.maxDepth) {
          assert(names.exists(knownCategoryNames), s"no known category name in $names")
        }
      } finally em.close()
    }
  }
}
