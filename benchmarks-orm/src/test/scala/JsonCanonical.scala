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

import io.circe.Json

/**
 * Test-only helpers for comparing the arms' response documents.
 *
 * Deliberately NOT in `src/main`: nothing here may ever run inside a benchmark's timed region.
 * Canonicalizing or re-walking a document during measurement would charge the ORM arms for work
 * the Grackle arm does not do — reintroducing an asymmetry while removing another.
 */
object JsonCanonical {

  /**
   * Every `category { name }` value reachable in `doc`, in document order. Works on both arms'
   * output because both emit the same envelope and hop names.
   */
  def categoryNames(doc: Json): List[String] = {
    def go(j: Json): List[String] =
      j.asArray match {
        case Some(elems) => elems.toList.flatMap(go)
        case None =>
          j.asObject match {
            case None => Nil
            case Some(obj) =>
              obj.toList.flatMap {
                case ("category", v) =>
                  v.hcursor.get[String]("name").toOption.toList ++ go(v)
                case (_, v) => go(v)
              }
          }
      }
    go(doc)
  }
}
