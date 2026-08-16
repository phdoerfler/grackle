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

package grackle.benchmarks.sql

import scala.io.Source

import io.circe.Decoder

/**
 * The single source of truth for the mermaid charts in `benchmarks-sql/README.md`, loaded from the
 * `charts/chart-data.json` classpath resource. `ChartGen` renders these numbers into the README's
 * marker-delimited chart blocks, and `SqlQueryCountsSuite` / `OrmQueryCountsSuite` assert that the
 * counts measured against the live database equal the values here — so the committed data cannot
 * silently drift from either the charts or reality.
 *
 * Read via the classpath rather than a file path so it resolves identically from the generator, the
 * `checkCharts` task, and the forked test JVMs, none of which share a working directory.
 */
final case class ChartData(
    statementsPerShape: StatementsPerShape,
    grackleStatementsByDepth: SeriesByDepth,
    rowsByDepth: RowsByDepth)

final case class StatementsPerShape(
    shapes: List[String],
    naive: List[Int],
    eager: List[Int],
    grackle: List[Int])

final case class SeriesByDepth(depths: List[Int], statements: List[Int])

final case class RowsByDepth(depths: List[Int], unfiltered: List[Int], filtered: List[Int])

object ChartData {
  val resourcePath = "/charts/chart-data.json"

  implicit val statementsPerShapeDecoder: Decoder[StatementsPerShape] =
    Decoder.forProduct4("shapes", "naive", "eager", "grackle")(StatementsPerShape.apply)

  implicit val seriesByDepthDecoder: Decoder[SeriesByDepth] =
    Decoder.forProduct2("depths", "statements")(SeriesByDepth.apply)

  implicit val rowsByDepthDecoder: Decoder[RowsByDepth] =
    Decoder.forProduct3("depths", "unfiltered", "filtered")(RowsByDepth.apply)

  implicit val chartDataDecoder: Decoder[ChartData] =
    Decoder.forProduct3("statementsPerShape", "grackleStatementsByDepth", "rowsByDepth")(
      ChartData.apply)

  def load: ChartData = {
    val stream = Option(getClass.getResourceAsStream(resourcePath))
      .getOrElse(sys.error(s"chart data resource not found on the classpath: $resourcePath"))
    val text =
      try Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()
    io.circe.parser.decode[ChartData](text).fold(throw _, identity)
  }
}
