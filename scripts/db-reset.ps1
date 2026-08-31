<#
.SYNOPSIS
    Drops and recreates an ApnaTutor database from scratch.

.DESCRIPTION
    Rebuilds the database with no schema history, so the next backend start
    replays every Flyway migration from V1. This is the guarantee that our
    migrations actually work from zero — not just as a series of increments
    applied to a long-lived local database.

    Migrations are forward-only and must never be edited once applied
    (SOURCE_OF_TRUTH.md §7). This script is how you recover when you need a
    clean slate anyway.

.PARAMETER Database
    Which database to reset: dev (default) or test.

.PARAMETER SuperUserPassword
    Password for the postgres superuser. Defaults to $env:PGPASSWORD, then to
    'postgres' (the local dev default).

.EXAMPLE
    .\scripts\db-reset.ps1
    .\scripts\db-reset.ps1 -Database test
#>
[CmdletBinding()]
param(
    [ValidateSet('dev', 'test')]
    [string]$Database = 'dev',

    [string]$SuperUserPassword = $(if ($env:PGPASSWORD) { $env:PGPASSWORD } else { 'postgres' })
)

$ErrorActionPreference = 'Stop'

$dbName = "apnatutor_$Database"

if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    throw "psql is not on PATH. Add the PostgreSQL bin directory (e.g. C:\Program Files\PostgreSQL\18\bin) to PATH and reopen the terminal."
}

Write-Host "About to DROP and recreate '$dbName'. All data in it will be lost." -ForegroundColor Yellow
$confirm = Read-Host "Type the database name to confirm"
if ($confirm -ne $dbName) {
    Write-Host "Aborted - confirmation did not match." -ForegroundColor Red
    exit 1
}

$env:PGPASSWORD = $SuperUserPassword

# Terminate live connections first; DROP DATABASE fails while any session is attached.
$terminate = @"
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = '$dbName' AND pid <> pg_backend_pid();
"@

Write-Host "Disconnecting existing sessions..." -ForegroundColor Cyan
psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c $terminate | Out-Null

Write-Host "Dropping $dbName..." -ForegroundColor Cyan
psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $dbName;"
if ($LASTEXITCODE -ne 0) { throw "Failed to drop $dbName" }

Write-Host "Creating $dbName..." -ForegroundColor Cyan
psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $dbName OWNER apnatutor ENCODING 'UTF8';"
if ($LASTEXITCODE -ne 0) { throw "Failed to create $dbName" }

Write-Host "Restoring extensions..." -ForegroundColor Cyan
psql -U postgres -d $dbName -v ON_ERROR_STOP=1 -c "CREATE EXTENSION IF NOT EXISTS pg_trgm; GRANT ALL ON SCHEMA public TO apnatutor;"
if ($LASTEXITCODE -ne 0) { throw "Failed to set up extensions on $dbName" }

Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "$dbName is empty and ready." -ForegroundColor Green
Write-Host "Flyway will replay all migrations on the next backend start:" -ForegroundColor Green
Write-Host "  cd backend; .\mvnw.cmd spring-boot:run" -ForegroundColor Green
