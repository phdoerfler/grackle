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

  /**
   * A deterministic form of `j`: arrays sorted by their rendered contents, object keys sorted,
   * and numbers normalized so scale does not matter.
   *
   * Array sorting is required because the entities use `java.util.Set` collections, so the ORM
   * traversal order is not the SQL row order Grackle's document follows — the two documents
   * hold the same data in different sequence. Number normalization is required because Grackle
   * decodes `DECIMAL(19,4)` through doobie while Hibernate yields a `java.math.BigDecimal`:
   * `3953.9884` and `3953.98840` are equal as decimals and unequal as JSON text.
   *
   * Sorting keys as well makes the result independent of circe's own object-equality semantics,
   * so the comparison rests on this function rather than on library behaviour.
   */
  def canonicalize(j: Json): Json =
    j.arrayOrObject(
      j.asNumber
        .fold(j)(n =>
          n.toBigDecimal.fold(j)(d => Json.fromBigDecimal(d.bigDecimal.stripTrailingZeros))),
      arr => Json.arr(arr.map(canonicalize).sortBy(_.noSpaces): _*),
      obj => Json.obj(obj.toList.map { case (k, v) => k -> canonicalize(v) }.sortBy(_._1): _*)
    )
}
