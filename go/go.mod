// Copyright 2026 ReconcileFlow
// Author: Kunal Gandhre
// SPDX-License-Identifier: Apache-2.0
// Licensed under the Apache License, Version 2.0; see LICENSE.
// https://www.apache.org/licenses/LICENSE-2.0

// Module identity must match the repository subdirectory used by consumer imports.
module github.com/kunal-gandhre/reconcileflow-idempotency/go

go 1.23.0

// Redis protocol client; checksum records remain machine-managed in go.sum.
require github.com/redis/go-redis/v9 v9.7.3

require (
	github.com/cespare/xxhash/v2 v2.2.0 // indirect
	github.com/dgryski/go-rendezvous v0.0.0-20200823014737-9f7001d12a5f // indirect
)
