# Copyright 2026 ReconcileFlow
# Author: Kunal Gandhre
# SPDX-License-Identifier: Apache-2.0
# Licensed under the Apache License, Version 2.0; see LICENSE.
# https://www.apache.org/licenses/LICENSE-2.0

<#
.SYNOPSIS
Recreates this project's local test infrastructure with empty data volumes.
.DESCRIPTION
Deletes Kafka messages, topics and consumer offsets, all Redis records, and
RedisInsight settings. Stop host-side producers and consumers before running.
Only the reconcileflow-dev Compose project is targeted; no global prune is used.
#>
$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '..\compose.yml'

# Resolve this repository's configuration regardless of the caller's directory.
$composeFile = (Resolve-Path -LiteralPath $composeFile).Path
Write-Host 'Resetting reconcileflow-dev: Kafka, Redis and RedisInsight data will be deleted.'
& docker compose -f $composeFile -p reconcileflow-dev config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed; nothing was reset.' }

# Removing volumes clears persisted data that a normal restart intentionally retains.
& docker compose -f $composeFile -p reconcileflow-dev down --volumes
if ($LASTEXITCODE -ne 0) { throw 'Reset failed; inspect Docker output before retrying.' }

# Wait for broker/store health checks before returning control to the test runner.
& docker compose -f $composeFile -p reconcileflow-dev up -d --wait --wait-timeout 120
if ($LASTEXITCODE -ne 0) { throw 'Data was reset, but startup failed; inspect Compose logs.' }
Write-Host 'Fresh test infrastructure is ready. Recreate test topics before producing messages.'
