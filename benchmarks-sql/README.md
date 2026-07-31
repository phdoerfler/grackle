# SQL join-depth benchmarks

Benchmarks how Grackle's compile+execute time scales as GraphQL query nesting
walks deeper down a real 11-table join chain (AdventureWorks-for-Postgres: `CountryRegion
→ StateProvince → Address → BusinessEntityAddress → Person → Customer → SalesOrderHeader
→ SalesOrderDetail → Product → ProductSubcategory → ProductCategory`), against a
dedicated Postgres instance seeded via Docker.

Out of scope for this phase: network-latency simulation, chart publishing, CI wiring.

`benchmarksSql` is a standalone sbt project, not aggregated into the root build (see
`build.sbt`): a plain `sbt test` / `sbt compile` at the repo root will not touch it,
and `benchmark-postgres` is behind the `benchmarks` Docker Compose profile so a plain
`docker compose up` will not build or start it either. Use the `benchmarksSql`-scoped
sbt tasks below instead.

## Running

    sbt benchPgUp                      # starts benchmark-postgres; first run takes
                                        # several minutes to build the image and load
                                        # the AdventureWorks dataset
    sbt "benchmarksSql/Jmh/run -rf json -rff results.json"

`SqlJoinDepthBenchmark` carries explicit `@Fork(3)`, `@Warmup(iterations = 5)`, and
`@Measurement(iterations = 10)` annotations, so a bare run above already uses settings
sized for trustworthy results: 3 forks (a single fork would hide JIT profile pollution)
x (5 warmup + 10 measurement) iterations x 5 `depth` params. Those annotations set
iteration *counts* only; JMH's default iteration *time* of 10s still applies on top, so
the bare run above costs roughly 3 x 15 x 10s x 5 = 2,250s of measurement alone, plus 15
JVM fork launches — **approximately 40 minutes end to end**. Add `-prof gc` for
allocation-per-operation figures, which are near-deterministic and so remain meaningful
on a machine too noisy for trustworthy wall-clock numbers. For a quick sanity check
while iterating on the benchmark itself, override the annotations with explicit flags,
e.g. `sbt "benchmarksSql/Jmh/run -f 1 -wi 1 -i 1 -r 1s -w 1s SqlJoinDepthBenchmark"`.
See the doc comment on `SqlJoinDepthBenchmark` for more.

Results land in `benchmarks-sql/results.json` (JMH resolves the `-rff` path relative
to the module's base directory, not the repo root), in JMH's built-in JSON format,
one entry per `depth` param (2, 4, 6, 8, 10), with `SampleTime` percentiles.

    sbt benchPgStop                    # stop the container when done

## Testing

    sbt benchmarksSql/test

Runs `JoinChainSuite` (query-generator unit tests), `AdventureWorksMappingSuite`
(mapping/execution smoke tests), and `SqlQueryCountsSuite` (pins the query-count
invariants below — exactly 1 query per depth, plus a weaker N+1 bound as a safety net)
against `benchmark-postgres`, which is started automatically as part of the test setup.
This is scoped to the `benchmarksSql` project deliberately — a plain, unscoped `sbt
test` does not run these tests.

## Query counts (the headline metric)

    sbt "benchmarksSql/runMain grackle.benchmarks.sql.SqlQueryCounts"

Runs outside JMH entirely — it just wires `AdventureWorksMapping` up to
`DoobieMonitor.statsMonitor` and counts how many SQL statements Grackle issues per
`depth`, using the unpooled `BenchmarkDb.transactor` since nothing here is timed. It
measures two datasets at every depth 1-10:

- **Unfiltered** (`JoinChain.queryForDepthUnfiltered`, `SqlQueryCounts.countsForUnfiltered`):
  `countryRegions` with no `code` argument, so Grackle's `SelectElaborator` leaves the
  root unfiltered and the query fans out across all 238 country regions — up to
  **60,459 rows** at depth 8-10. This is the stronger claim: nothing about the query
  shape was chosen to flatter the result, since it is simply the same query with the
  root filter omitted entirely.
- **Filtered** (`JoinChain.queryForDepth`, `SqlQueryCounts.countsFor`): the original,
  narrower measurement rooted at a single country region (default `FR`, ~5,600 rows at
  depth 8-10), kept alongside the unfiltered one as a smaller, easier-to-eyeball
  cross-check.

**Result: exactly 1 SQL query per depth, for both datasets, regardless of nesting depth
or row count.** Both tables print to stdout and are written to
`benchmarks-sql/query-counts.json` (gitignored, like `results.json`) under top-level
`"unfiltered"` and `"filtered"` keys — the unfiltered entry is labelled by an explicit
`"scope"` field, the filtered entry by its `"rootCode"`. It also writes
`benchmarks-sql/query-sql.txt` (gitignored too, diagnostic-only, not a published
metric, and covering the filtered dataset only): the normalized text of every SQL
statement Grackle emitted at each depth, one labelled section per depth — see
`SqlQueryCounts.renderSql`. Run it from the repo root as shown above: unlike
`Jmh / run`, plain `Compile / run` (what `runMain` uses) is not forked with the
module's base directory as its working directory, so the output path is spelled out
relative to the repo root rather than relying on sbt to resolve it.

Also unlike `Jmh / run`, `runMain` has no `benchPgUp` dependency wired in `build.sbt` —
run `sbt benchPgUp` first, or the harness will fail to connect.

The `rows` column reports the total rows Grackle's SQL fetched at each depth, and in
both datasets it is not monotonic in `depth`: filtered fetches 5,677 rows at depth 7
then drops to 5,558 for depths 8-10; unfiltered fetches 61,898 at depth 7 then drops to
60,459 for depths 8-10. `query-sql.txt` (filtered only) settles the mechanism for the
smaller dataset: every join up through depth 7 (down to `SalesOrderDetail`) is a
`LEFT JOIN`, so the depth-7 query returns one row per FR line item (5,558) *plus* one
null-padded row for every upstream chain that never reaches an order — 78 FR state
provinces with no address, 40 addresses with no person, and 1 person with no customer
(78+40+1 = 119; 5,558+119 = 5,677, both confirmed by running the captured depth-7 SQL
directly against the fixture). Depth 8 adds
`INNER JOIN production.product ON product.productid = salesorderdetail.productid` —
the first `INNER JOIN` anywhere in the chain — which drops all 119 null-padded rows
(their `salesorderdetail`, and so `productid`, is `NULL`, and `NULL` never satisfies an
inner join's equality predicate), landing back on the true count of 5,558. That figure
holds through depths 9-10 because none of FR's specific products or subcategories
happen to be among the catalog-wide products lacking a subcategory. The unfiltered
dataset is generated by the identical query shape (same joins, same `LEFT`/`INNER`
placement, just without the root filter), so the same depth-7→8 mechanism is the
overwhelmingly likely explanation for its analogous dip — that has not been re-verified
against `query-sql.txt` since the diagnostic dump only covers the filtered run. Either
way, this has nothing to do with query count, which stays at 1 throughout in both
datasets (see above).

Query counts are fully deterministic: no JIT warmup, no GC, no scheduling noise, so a
single run needs no repetition and is exactly reproducible. That determinism is what
makes them the headline number for demonstrating N+1 immunity — the depth→time curve
below is useful for comparing *shape*, but query counts are the number that settles
the argument outright, independent of machine noise.

## Measurement caveats

Numbers from this benchmark are useful for comparing the *shape* of the depth→time
curve, but are not a clean measurement of Grackle's own cost in isolation:

- Connection setup is deliberately excluded from every timed sample: `@Setup(Level.Trial)`
  allocates a pooled `HikariTransactor` once per trial (max pool size 4, see
  `BenchmarkDb.transactorResource`) and `@TearDown` releases it, so TCP connect + auth —
  a significant source of non-Grackle variance — never runs inside the measured region.
  `BenchmarkDb.transactor` still exposes an unpooled `Transactor.fromDriverManager`; the
  query-count harness (below) and the test suite both use it, since neither times
  anything, but the benchmark itself always goes through the pooled transactor.
- Postgres's buffer cache remains external state that JMH itself has no way to reset
  between `depth` settings, but `@Setup(Level.Trial)` deliberately prewarms it
  (`BenchmarkDb.prewarm`, backed by `pg_prewarm`) across all 11 chain tables before every
  trial, so each `depth` value starts from a hot cache rather than an arbitrary one.
  `pg_prewarm(regclass)` only warms a table's heap — the chain tables' heaps total ~36MB
  of the ~42MB grand total, against the default 128MB `shared_buffers`; the remaining
  ~6MB of indexes are not prewarmed by this call and instead get warmed incidentally
  during warmup iterations.
- The query is rooted at a single country region (default `FR`) rather than spanning
  all of them, to keep the per-operation payload small. This benchmark uses
  `Mode.SampleTime`, where each iteration is time-bounded (10s by default), so wall-clock
  cost is `forks x iterations x iterationTime` regardless of per-operation cost — a
  smaller payload buys no extra forks or iterations. What it does buy is more *samples*:
  phase 1's depth-10 cost was ~354ms/op, so a 10s iteration collected only ~28 samples,
  far too few to support the p99 this README reports elsewhere. Cutting the payload
  roughly 10x yields hundreds of samples per iteration, which is what makes the
  percentile numbers meaningful. The depth→time curve should not be read as covering the
  dataset's full result-set size.

## Rebuilding after a seed-script or Dockerfile change

`testdata/benchmark-pg/install.sh` (which seeds the AdventureWorks data and, as of this
phase, also creates the `pg_prewarm` extension) only runs on a fresh, uninitialized
PGDATA. `docker compose up` neither rebuilds a `build:`-based service when its
Dockerfile changes nor re-runs initdb, and the seeded data lives in an anonymous volume
that outlives both container recreation and image rebuilds. So **anyone who ran an
earlier phase of this benchmark and then pulls a later one** — not just after editing
the Dockerfile — can end up with a stale volume whose seed script never ran the newer
steps. The concrete symptom of this phase's addition specifically is `@Setup` failing
with `function pg_prewarm(character varying) does not exist`. Fresh clones are
unaffected — this only bites existing checkouts with an already-seeded volume, so don't
do the rebuild dance unless you're actually upgrading one or hit that error.

Force a clean rebuild:

    docker compose --profile benchmarks down -v benchmark-postgres
    docker compose --profile benchmarks build benchmark-postgres

then `sbt benchPgUp` as usual.
