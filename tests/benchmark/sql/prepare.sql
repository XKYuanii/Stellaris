-- Canonical SQL entry point for the isolated V5 fixture.
-- Run from the repository root with a mysql client that supports SOURCE.
-- The PowerShell entry point is preferred because it checks active orders/Redis first:
--   .\tests\benchmark\prepare-benchmark.ps1
--
-- This SOURCE keeps the already verified initialization SQL in one place.
SOURCE tests/benchmark/prepare-v5-test.sql;
