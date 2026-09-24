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
	"context"
	"testing"
	"time"
)

// TestJSONKey covers object paths, literal field names, invalid data and string-only identities.
func TestJSONKey(t *testing.T) {
	for _, tc := range []struct {
		name, payload, want string
		path                []string
	}{
		{"root", `{"eventId":"one","amount":10}`, "one", []string{"eventId"}},
		{"nested", `{"event":{"id":"two"}}`, "two", []string{"event", "id"}},
		{"literal dot", `{"event.id":"three"}`, "three", []string{"event.id"}},
		{"unicode", `{"eventId":"注文-1"}`, "注文-1", []string{"eventId"}},
		{"missing path", `{}`, "", nil},
		{"malformed", `{`, "", []string{"eventId"}},
		{"missing", `{}`, "", []string{"eventId"}},
		{"blank", `{"eventId":" "}`, "", []string{"eventId"}},
		{"number", `{"eventId":123}`, "", []string{"eventId"}},
		{"bool", `{"eventId":true}`, "", []string{"eventId"}},
		{"null id", `{"eventId":null}`, "", []string{"eventId"}},
		{"object id", `{"eventId":{}}`, "", []string{"eventId"}},
		{"array id", `{"eventId":[]}`, "", []string{"eventId"}},
		{"null root", `null`, "", []string{"eventId"}},
		{"array root", `[]`, "", []string{"eventId"}},
		{"trailing", `{"eventId":"one"} {}`, "", []string{"eventId"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := JSONKey(tc.path...)([]byte(tc.payload))
			if tc.want == "" {
				if err == nil {
					t.Fatal("expected invalid payload error")
				}
				return
			}
			if err != nil || got != tc.want {
				t.Fatalf("got %q, %v; want %q", got, err, tc.want)
			}
		})
	}
}

// jsonStore models completion so this checks actual hashed identities across repeat deliveries.
type jsonStore struct {
	fakeStore
	seen   map[string]bool
	claims int
}

func (s *jsonStore) Claim(_ context.Context, key, _ string, _ time.Duration) (Claim, error) {
	s.claims++
	if s.seen[key] {
		return Completed, nil
	}
	return Acquired, nil
}
func (s *jsonStore) Complete(_ context.Context, key, _ string, _ time.Duration) (bool, error) {
	s.seen[key] = true
	return true, nil
}

// TestJSONWrap proves formatting-independent identity and validation before storage access.
func TestJSONWrap(t *testing.T) {
	s := &jsonStore{seen: make(map[string]bool)}
	var received []string
	h, err := Wrap(s, Config{"json-tests", time.Minute, time.Hour}, JSONKey("eventId"),
		func(_ context.Context, payload []byte) error {
			received = append(received, string(payload))
			return nil
		})
	if err != nil {
		t.Fatal(err)
	}
	original := `{"eventId":"one","amount":10}`
	for _, payload := range []string{original, `{ "amount": 10, "eventId": "one" }`, `{"eventId":"two"}`} {
		if err := h(context.Background(), []byte(payload)); err != nil {
			t.Fatal(err)
		}
	}
	if len(received) != 2 || received[0] != original {
		t.Fatalf("unexpected business deliveries: %v", received)
	}
	claims := s.claims
	if err := h(context.Background(), []byte(`{"eventId":null}`)); err == nil {
		t.Fatal("expected error")
	}
	if s.claims != claims || len(received) != 2 {
		t.Fatal("invalid input reached store or handler")
	}
}
