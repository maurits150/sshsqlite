# JDBC Method Support

## Purpose

The JDBC method-support matrix is a mandatory production artifact. The implementation is not production-plannable or releaseable until every public method in every targeted Java 11 `java.sql` interface has an explicit behavior row.

This document defines the required matrix format and MVP classifications. The exhaustive table should be generated from the configured JDK by reflection into `docs/jdbc-method-support.generated.md` before implementation begins, then reviewed and committed with the code.

## Required Generated Artifact

`docs/jdbc-method-support.generated.md` must be generated from the configured Java 11-compatible build:

```text
Java runtime: Java 11+
Compile target: --release 11
Interfaces: Driver, Connection, Statement, PreparedStatement, ResultSet, ResultSetMetaData, DatabaseMetaData, ParameterMetaData if exposed, Wrapper
```

Generation requirements:

- Reflect every public method declared or inherited by each targeted interface.
- Include overload signatures, not only method names.
- Include default methods from the target Java release.
- Include methods inherited through `Wrapper`.
- Fail generation if the runtime cannot cover the documented Java 11 JDBC method set.
- Fail CI if any row has missing status, exception policy, phase, or test owner.

Required columns:

| Column | Meaning |
| --- | --- |
| Interface | `Connection`, `Statement`, and so on, either as a table column or as the enclosing per-interface section heading |
| Method signature | Full Java signature including overload parameters |
| MVP status | One of the statuses below |
| Behavior | Exact return value, state transition, or remote operation |
| Failure policy | Exception class and SQLState |
| Phase | Implementation phase that owns it |
| Test | Unit, integration, reflection, tool smoke, or generated coverage |

MVP statuses:

- `supported`.
- `conservative-constant`.
- `local-advisory-state`.
- `empty-metadata-result`.
- `populated-metadata-result`.
- `supported-in-prepared-milestone`.
- `unsupported-feature`.
- `invalid-state-error`.
- `closed-object-error`.
- `not-exposed`.

## Baseline Classification Rules

Use these defaults when generating the first matrix.

| Interface | Default MVP classification |
| --- | --- |
| `Driver` | URL matching, connection creation, version, property info supported; JDBC compliance false |
| `Connection` | Core lifecycle, statements, transactions, metadata, validation, readonly, schema, warnings, network timeout documented; advanced object factories unsupported |
| `Statement` | `execute()`/`executeQuery()`/update execution and result state supported for normal SQLite read/write usage when `readonly=false`; batches and updatable results unsupported |
| `PreparedStatement` | Indexed scalar setters and fixed-SQL execution supported; inherited Statement SQL overloads invalid; stream/object factory setters remain unsupported until implemented |
| `ResultSet` | Forward-only read-only getters supported; scrolling/updating unsupported |
| `ResultSetMetaData` | Conservative metadata supported for all returned result sets |
| `DatabaseMetaData` | Conservative constants or correctly shaped empty result sets preferred over throwing |
| `ParameterMetaData` | Conservative object or `SQLFeatureNotSupportedException`, but never misleading parameter types |
| `Wrapper` | `unwrap`/`isWrapperFor` implemented consistently for every object |

## Release Gate

Before MVP release:

- `docs/jdbc-method-support.generated.md` exists.
- Every row has a non-empty MVP status.
- Every `unsupported-feature` row maps to `SQLFeatureNotSupportedException` when the signature permits `SQLException`.
- Every public JDBC method is covered by reflection tests proving no `AbstractMethodError`, `NoSuchMethodError`, or accidental `UnsupportedOperationException`.
- DBeaver trace logs are checked against the matrix; any called method must be `supported`, `conservative-constant`, `local-advisory-state`, `empty-metadata-result`, or `populated-metadata-result` unless the tool workflow is explicitly unsupported.

If the generated matrix is absent or stale, release is blocked.
