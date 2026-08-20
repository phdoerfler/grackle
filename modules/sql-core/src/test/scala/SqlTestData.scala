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

import scala.io.Source

import cats.syntax.all._

/**
 * Loads shared, backend-agnostic seed data into a table via `SqlTestMapping`'s column registry.
 *
 * Each row of a `|`-delimited CSV resource is decoded through the `CellDecoder`s registered by
 * `col` and bound into an INSERT via the backend's own `SqlFragment` algebra, so no backend-
 * specific SQL is hard-coded here. Only the final execution step (`runCommand`) is left to the
 * concrete backend.
 */
trait SqlTestData[F[_]] extends SqlTestMapping[F] {

  /**
   * Execute a data-manipulation fragment, discarding results. Backend-specific.
   */
  def runCommand(fragment: Fragment): F[Unit]

  /** A table's delete (truncate) and insert steps, kept separate so `loadAll` can run all
   *  deletes before all inserts and thereby respect foreign keys across a dataset's tables.
   */
  // Plain class, not a case class: a nested case class in this F-parameterised trait would
  // synthesise an `equals` with an outer-reference type test (fatal under CI). Not needed here.
  final class SeedTable(val delete: F[Unit], val insert: F[Unit])

  /**
   * Each dataset overrides this with its `seedTable(...)` calls, parent tables first.
   */
  def seedData: List[SeedTable]

  /** Delete every table (children first, i.e. reverse of the parent-first `seedData` order),
   *  then insert every table (parents first). This keeps re-seeding idempotent on a persistent
   *  container without tripping foreign-key constraints.
   */
  def loadAll: F[Unit] =
    seedData.reverse.traverse_(_.delete) >> seedData.traverse_(_.insert)

  protected def readRows(resourcePath: String): (List[String], List[List[String]]) = {
    val full = s"data/$resourcePath"
    val src = Source.fromResource(full)
    try {
      val lines = src.getLines().filter(_.nonEmpty).toList
      val header :: body = lines: @unchecked
      (header.split("\\|", -1).toList, body.map(_.split("\\|", -1).toList))
    } finally src.close()
  }

  def seedTable(table: TableDef, resourcePath: String): SeedTable = {
    val tableName = table.tableName
    val cols = seedColumnsFor(tableName)
    val (header, rows) = readRows(resourcePath)
    val ordered = header.map(h =>
      cols.getOrElse(
        h,
        sys.error(s"seed file $resourcePath: column '$h' not in table ${tableName.name}")))
    val F0 = Fragments

    val truncate: Fragment = F0.const(s"DELETE FROM ${tableName.name}")

    val insertRows: List[Fragment] = rows.zipWithIndex.map {
      case (cells, idx) =>
        if (cells.length != ordered.length)
          sys.error(
            s"seed file $resourcePath: row ${idx + 1} has ${cells.length} field(s), " +
              s"expected ${ordered.length} (header: ${header.mkString("|")}); " +
              s"row content: ${cells.mkString("|")}")
        val bound = ordered.zip(cells).map {
          case (sc, cell) =>
            val codec = sc.columnRef.codec
            val enc = toEncoder(codec)
            val nullable = isNullable(codec)
            if (cell == "\\N") {
              if (!nullable)
                sys.error(
                  s"seed file $resourcePath: row ${idx + 1}: NULL ('\\N') for non-nullable " +
                    s"column '${sc.name}'")
              F0.bind(enc, None)
            } else {
              val decoded = sc.decode(cell)
              // Nullable codecs are wrapped as `Option[T]` by the backend (e.g. `.opt`), even
              // though `col`'s `TestCodec[T]` keeps the Scala type `T` at the call site.
              F0.bind(enc, if (nullable) Some(decoded) else decoded)
            }
        }
        val values = bound.reduce((a, b) => F0.combine(F0.combine(a, F0.const(", ")), b))
        val colList = header.mkString(", ")
        F0.combine(
          F0.const(s"INSERT INTO ${tableName.name} ($colList) VALUES ("),
          F0.combine(values, F0.const(")")))
    }

    new SeedTable(runCommand(truncate), insertRows.traverse_(runCommand))
  }
}
