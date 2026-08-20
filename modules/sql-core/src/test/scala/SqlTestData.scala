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

import cats.Monad
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

  /** Execute a data-manipulation fragment, discarding results. Backend-specific. */
  def runCommand(fragment: Fragment): F[Unit]

  /** Each dataset overrides this with its `seedTable(...)` calls. */
  def seedData: List[F[Unit]]

  def loadAll(implicit F: Monad[F]): F[Unit] = seedData.sequence_

  protected def readRows(resourcePath: String): (List[String], List[List[String]]) = {
    val full = s"data/$resourcePath"
    val src = Source.fromResource(full)
    try {
      val lines = src.getLines().filter(_.nonEmpty).toList
      val header :: body = lines: @unchecked
      (header.split('|').toList, body.map(_.split("\\|", -1).toList))
    } finally src.close()
  }

  def seedTable(table: TableDef, resourcePath: String)(implicit F: Monad[F]): F[Unit] = {
    val tableName = table.tableName
    val cols = seedColumnsFor(tableName)
    val (header, rows) = readRows(resourcePath)
    val ordered = header.map(h =>
      cols.getOrElse(
        h,
        sys.error(s"seed file $resourcePath: column '$h' not in table ${tableName.name}")))
    val F0 = Fragments

    val truncate: Fragment = F0.const(s"DELETE FROM ${tableName.name}")

    val insertRows: List[Fragment] = rows.map { cells =>
      val bound = ordered.zip(cells).map { case (sc, cell) =>
        val enc = toEncoder(sc.columnRef.codec)
        if (cell == "\\N") F0.bind(enc, None)
        else F0.bind(enc, sc.decode(cell))
      }
      val values = bound.reduce((a, b) => F0.combine(F0.combine(a, F0.const(", ")), b))
      val colList = header.mkString(", ")
      F0.combine(
        F0.const(s"INSERT INTO ${tableName.name} ($colList) VALUES ("),
        F0.combine(values, F0.const(")")))
    }

    (truncate :: insertRows).traverse_(runCommand)
  }
}
