/*
 * Copyright 2026 ReconcileFlow
 * Author: Kunal Gandhre
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reconcileflow.idempotent;

/**
 * Signals an in-progress claim or lost lease that must not be acknowledged as a duplicate.
 * A lost lease can follow a completed side effect; retries still require business idempotency.
 *
 * @author Kunal Gandhre
 */
public class RetryableIdempotencyException extends RuntimeException {
    /** Describes why the consumer must follow its retry or reconciliation policy. */
    public RetryableIdempotencyException(String message) { super(message); }
}
