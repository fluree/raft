# Util.clj Refactoring Summary

This document summarizes the shared patterns that have been moved to `util.clj` to reduce code duplication across the Jepsen Raft test codebase.

## Patterns Moved to util.clj

### 1. Test Operations (Lines 185-207)
- `random-key`: Generate random test keys (used in operations.clj, test_performance.clj)
- `random-value`: Generate random test values (used in operations.clj, test_performance.clj)
- `generate-test-command`: Generate complete random test commands (consolidates logic from test_performance.clj)

**Files that can be refactored to use these:**
- `operations.clj`: Can use `util/random-key` and `util/random-value` instead of local definitions
- `test_performance.clj`: Can use `util/generate-test-command` instead of local `generate-command`

### 2. HTTP Client Utilities (Lines 209-243)
- `node->http-url`: Convert node to HTTP URL (pattern from http_client.clj)
- `with-http-timeout`: Standardized HTTP request with timeout and error handling

**Files that can be refactored to use these:**
- `http_client.clj`: Can use `util/with-http-timeout` to reduce duplication
- `client.clj`: Can benefit from standardized error handling

### 3. Process Management (Lines 245-295)
- `start-process`: Standardized process startup with error checking
- `stop-process`: Graceful process shutdown with forced kill fallback

**Files that can be refactored to use these:**
- `db.clj`: Can use these instead of inline process management code

### 4. Docker Utilities (Lines 297-326)
- `docker-compose`: Execute docker-compose commands with standard logging

**Files that can be refactored to use these:**
- `db_docker.clj`: Can use `util/docker-compose` instead of local `docker-exec`

### 5. Retry and Wait Utilities (Lines 328-383)
- `retry-with-backoff`: Exponential backoff retry logic
- `wait-for-condition`: Generic condition waiting with timeout

**Files that can be refactored to use these:**
- `raft_node.clj`: Can use for connection retry logic
- `db.clj`: Can use for health check waiting
- `http_client.clj`: Can use `wait-for-condition` instead of local `wait-for-ready`

### 6. Operation Result Helpers (Lines 385-416)
- `operation-result`: Base function for creating Jepsen operation results
- `success-result`: Create :ok results
- `failure-result`: Create :fail results with error
- `info-result`: Create :info results (uncertain outcomes)

**Files that can be refactored to use these:**
- `client.clj`: Can use these helpers instead of manual result construction
- `nemesis_docker.clj`: Can use for consistent result formatting

## Benefits of This Refactoring

1. **Reduced Code Duplication**: Common patterns are now centralized
2. **Consistent Error Handling**: Standardized approaches across the codebase
3. **Easier Maintenance**: Changes to common logic only need to be made in one place
4. **Better Testing**: Utility functions can be unit tested independently
5. **Improved Readability**: Higher-level abstractions make code intent clearer

## Next Steps

To complete the refactoring, the following files should be updated to use the new utility functions:

1. Update `operations.clj` to use `util/random-key` and `util/random-value`
2. Update `test_performance.clj` to use `util/generate-test-command`
3. Update `http_client.clj` to use `util/with-http-timeout` and `util/wait-for-condition`
4. Update `client.clj` to use operation result helpers
5. Update `db.clj` to use process management utilities
6. Update `db_docker.clj` to use `util/docker-compose`
7. Update `raft_node.clj` to use retry utilities where applicable
8. Update `nemesis_docker.clj` to use operation result helpers

This will result in cleaner, more maintainable code with less duplication across the test suite.