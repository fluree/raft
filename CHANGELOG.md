# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2025-01-11

### Added
- CI/CD pipeline with GitHub Actions
- Code linting with clj-kondo and Eastwood
- Code formatting with cljfmt
- Build tooling with tools.build
- Docker support with updated base images (Java 17)
- Jepsen testing documentation and results

### Changed
- Updated all dependencies to latest versions
- Dockerfile to use Java 17
- Improved Makefile with additional development targets
- Standardized code formatting across codebase

### Fixed
- All clj-kondo linting errors and warnings
- All Eastwood static analysis warnings
- Reflection warnings in log.clj
- Malformed cond expressions in leader.clj
- Unused bindings and redundant code blocks

### Performance
- Validated 5-minute stress tests with 27,725 operations showing perfect linearizability
- Demonstrated performance under network failures and partitions