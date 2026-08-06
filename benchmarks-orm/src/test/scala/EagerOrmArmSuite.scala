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

class EagerOrmArmSuite extends FunSuite {
  val factory = OrmDb.emf()
  override def afterAll(): Unit = factory.close()

  OrmQueryShapes.all.foreach { shape =>
    test(s"eager arm returns the same document as naive arm for shape ${shape.name}") {
      val em1 = factory.createEntityManager()
      val naiveDoc =
        try NaiveOrmArm.run(em1, shape, grackle.benchmarks.sql.JoinChain.defaultRootCode)
        finally em1.close()

      val em2 = factory.createEntityManager()
      val eagerDoc =
        try EagerOrmArm.run(em2, shape, grackle.benchmarks.sql.JoinChain.defaultRootCode)
        finally em2.close()

      assertEquals(
        JsonCanonical.canonicalize(eagerDoc),
        JsonCanonical.canonicalize(naiveDoc),
        s"documents differ for shape ${shape.name}")
    }
  }
}
