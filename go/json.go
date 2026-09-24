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

package idempotent

import (
	"encoding/json"
	"errors"
	"strings"
)

// JSONKey extracts a nonblank string identity from a JSON object using literal field names.
// JSONKey("eventId") selects a root field; JSONKey("event", "id") selects a nested field.
// It validates the complete document before Wrap claims anything; the handler gets original bytes.
func JSONKey(fields ...string) KeyExtractor {
	// Own the path so the caller cannot mutate a live extractor's configuration.
	path := append([]string(nil), fields...)
	return func(payload []byte) (string, error) {
		if len(path) == 0 {
			return "", errors.New("JSON key requires a field path")
		}
		var value json.RawMessage = payload
		for _, field := range path {
			var object map[string]json.RawMessage
			if err := json.Unmarshal(value, &object); err != nil || object == nil {
				return "", errors.New("JSON key path requires an object")
			}
			next, ok := object[field]
			if !ok {
				return "", errors.New("JSON key field is missing")
			}
			value = next
		}
		var id string
		if err := json.Unmarshal(value, &id); err != nil || strings.TrimSpace(id) == "" {
			return "", errors.New("JSON idempotency key must be a nonempty string")
		}
		return id, nil
	}
}
