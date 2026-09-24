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

import java.time.Duration;

/**
 * Storage boundary for bounded event deduplication.
 * Each operation must be atomic; only the current owner may change a processing claim.
 * Storage failures must propagate so callers never acknowledge an uncertain outcome.
 *
 * @author Kunal Gandhre
 */
public interface IdempotencyStore {
    /** ACQUIRED permits work; BUSY requires retry; COMPLETED permits skipping work. */
    enum Claim { ACQUIRED, BUSY, COMPLETED }
    /** Creates a leased claim when absent, otherwise reports the existing state. */
    Claim claim(String key, String owner, Duration lease);
    /** Replaces this owner's live claim with completion retention; false means ownership was lost. */
    boolean complete(String key, String owner, Duration retention);
    /** Removes only this owner's active claim; never removes a completed record. */
    boolean release(String key, String owner);
}
