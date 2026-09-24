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

// These unit tests exercise the wrapper without a Kafka client or live Redis.
package idempotent

import (
	"context"
	"errors"
	"testing"
	"time"
)

// fakeStore records transitions and injects errors for deterministic control-flow assertions.
type fakeStore struct {
	claim      Claim
	err        error
	complete   bool
	released   bool
	completed  bool
	releaseErr error
}

// Return the configured claim result without creating external state.
func (s *fakeStore) Claim(context.Context, string, string, time.Duration) (Claim, error) {
	return s.claim, s.err
}

// Record completion attempts separately from business handler calls.
func (s *fakeStore) Complete(context.Context, string, string, time.Duration) (bool, error) {
	s.completed = true
	return s.complete, s.err
}

// Expose cleanup failures so the original error can be checked with errors.Is.
func (s *fakeStore) Release(context.Context, string, string) (bool, error) {
	s.released = true
	return true, s.releaseErr
}

// TestOutcomes checks handler invocation counts and outcomes for every normal claim state.
func TestOutcomes(t *testing.T) {
	for _, tc := range []struct {
		name     string
		claim    Claim
		complete bool
		want     error
		calls    int
	}{
		{"new", Acquired, true, nil, 1}, {"duplicate", Completed, true, nil, 0}, {"busy", Busy, true, ErrBusy, 0}, {"expired", Acquired, false, ErrLeaseLost, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := &fakeStore{claim: tc.claim, complete: tc.complete}
			calls := 0
			h, err := Wrap(s, Config{"orders", time.Minute, time.Hour}, func([]byte) (string, error) { return "1", nil }, func(context.Context, []byte) error { calls++; return nil })
			if err != nil {
				t.Fatal(err)
			}
			if err = h(context.Background(), nil); !errors.Is(err, tc.want) {
				t.Fatalf("got %v want %v", err, tc.want)
			}
			if calls != tc.calls {
				t.Fatalf("calls %d", calls)
			}
		})
	}
}

// TestFailureReleasesAndPreservesErrors checks that business and cleanup errors are both retained.
func TestFailureReleasesAndPreservesErrors(t *testing.T) {
	failure := errors.New("business failed")
	cleanup := errors.New("cleanup failed")
	s := &fakeStore{claim: Acquired, releaseErr: cleanup}
	h, _ := Wrap(s, Config{"orders", time.Minute, time.Hour}, func([]byte) (string, error) { return "1", nil }, func(context.Context, []byte) error { return failure })
	err := h(context.Background(), nil)
	if !s.released || s.completed || !errors.Is(err, failure) || !errors.Is(err, cleanup) {
		t.Fatal("lost failure or invalid transition", err)
	}
}

// TestStoreUnavailableDoesNotProcess ensures store failures cannot execute business work.
func TestStoreUnavailableDoesNotProcess(t *testing.T) {
	failure := errors.New("offline")
	s := &fakeStore{err: failure}
	h, _ := Wrap(s, Config{"orders", time.Minute, time.Hour}, func([]byte) (string, error) { return "1", nil }, func(context.Context, []byte) error { t.Fatal("handler ran"); return nil })
	if !errors.Is(h(context.Background(), nil), failure) {
		t.Fatal("missing store error")
	}
}

// TestInvalidConfig rejects incomplete setup before a handler can be used.
func TestInvalidConfig(t *testing.T) {
	if _, err := Wrap(nil, Config{}, nil, nil); err == nil {
		t.Fatal("accepted invalid config")
	}
}
