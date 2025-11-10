# Biff starter project

This is the starter project for Biff.

Run `clj -M:dev dev` to get started. See `clj -M:dev --help` for other commands.

Consider adding `alias biff='clj -M:dev'` to your `.bashrc`.

## Testing

This project includes a comprehensive test suite. You can run tests using the following commands:

### Run All Tests
```bash
clj -M:dev test
```
Runs the complete test suite including both application tests and domain logic tests.

### Run Domain Tests Only
```bash
clj -M:dev test-domain
```
Runs only the domain logic tests (hardcorefunctionalprojection tests).

### Test Structure
- `test/com/barbiff_test.clj` - Main application tests (message sending, chat functionality)
- `test/com/domain/hardcorefunctionalprojection_test.clj` - Domain logic tests for the projection system

The tests use Biff's built-in testing utilities including `test-xtdb-node` for database testing and Malli generators for test data generation.
