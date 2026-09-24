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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/**
 * Parses raw JSON for read-only key evaluation without changing the handler
 * argument. No polymorphic type activation or application class instantiation
 * is enabled.
 *
 * @author Kunal Gandhre
 */
final class JsonPayload {
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	private JsonPayload() {
	}

	/**
	 * Require one object; malformed data fails before any store claim or business
	 * work.
	 */
	static Map<?, ?> read(Object payload) {
		try {
			Object decoded;
			if (payload instanceof String text)
				decoded = MAPPER.readValue(text, Map.class);
			else if (payload instanceof byte[] bytes)
				decoded = MAPPER.readValue(bytes, Map.class);
			else
				throw new IllegalArgumentException("JSON payload must be a String or byte[]");
			if (decoded == null)
				throw new IllegalArgumentException("JSON payload must be an object");
			return (Map<?, ?>) decoded;
		} catch (IOException invalid) {
			// Keep payload content out of the public error message.
			throw new IllegalArgumentException("Invalid JSON object payload");
		}
	}
}
