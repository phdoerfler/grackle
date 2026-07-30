# SQL join-depth benchmarks

Benchmarks how Grackle's compile+execute time scales as GraphQL query nesting
walks deeper down a real 11-table join chain (AdventureWorks-for-Postgres: `CountryRegion
→ StateProvince → Address → BusinessEntityAddress → Person → Customer → SalesOrderHeader
→ SalesOrderDetail → Product → ProductSubcategory → ProductCategory`), against a
dedicated Postgres instance seeded via Docker.

Out of scope for this phase: network-latency simulation, chart publishing, CI wiring.

## Running

    sbt benchPgUp                      # starts benchmark-postgres; first run takes
                                        # several minutes to build the image and load
                                        # the AdventureWorks dataset
    sbt "benchmarksSql/Jmh/run -rf json -rff results.json"

Results land in `benchmarks-sql/results.json` (JMH resolves the `-rff` path relative
to the module's base directory, not the repo root), in JMH's built-in JSON format,
one entry per `depth` param (2, 4, 6, 8, 10), with `SampleTime` percentiles.

    sbt benchPgStop                    # stop the container when done

## Testing

    sbt benchmarksSql/test

Runs `JoinChainSuite` (query-generator unit tests) and `AdventureWorksMappingSuite`
(mapping/execution smoke tests) against `benchmark-postgres`, which is started
automatically as part of the test setup.
