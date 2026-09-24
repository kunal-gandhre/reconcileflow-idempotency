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

// Package idempotent provides bounded deduplication for synchronous handlers.
package idempotent

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Claim is the atomic store decision: busy, newly acquired, or already completed.
type Claim int

const (
	// Busy means another owner is still processing; callers must retry, not acknowledge.
	Busy Claim = iota
	// Acquired grants this invocation a time-limited processing lease.
	Acquired
	// Completed permits skipping work while the completion record remains retained.
	Completed
)

// ErrBusy signals contention on an active claim.
var ErrBusy = errors.New("event is processing; retry later")

// ErrLeaseLost means completion could not be recorded after business work; reconcile before unsafe retries.
var ErrLeaseLost = errors.New("processing lease lost; reconcile business outcome")

// Store supplies atomic transitions. Implementations must propagate storage failures.
// Completion and release must compare the supplied owner against the current live token.
type Store interface {
	// Claim creates a leased record if absent, otherwise reports Busy or Completed.
	Claim(context.Context, string, string, time.Duration) (Claim, error)
	// Complete replaces this owner's live claim with completion retention.
	Complete(context.Context, string, string, time.Duration) (bool, error)
	// Release removes only this owner's processing record after failure.
	Release(context.Context, string, string) (bool, error)
}

// Handler completes all business work synchronously; nil permits offset commit.
type Handler func(context.Context, []byte) error

// KeyExtractor returns a stable event identity, shared across repeat deliveries.
type KeyExtractor func([]byte) (string, error)

// Config separates consumer identity, processing lease and completed-event retention.
type Config struct {
	// Namespace separates logical consumers/topics that may reuse the same event ID.
	Namespace string
	// Lease bounds claim ownership; Retention bounds deduplication after completion.
	Lease, Retention time.Duration
}

// Wrap returns a synchronous handler. Commit Kafka offsets only after nil.
func Wrap(store Store, cfg Config, extract KeyExtractor, next Handler) (Handler, error) {
	// Reject invalid setup before constructing a handler that could process unprotected work.
	if store == nil || extract == nil || next == nil || strings.TrimSpace(cfg.Namespace) == "" || cfg.Lease < time.Millisecond || cfg.Retention < time.Millisecond {
		return nil, errors.New("store, handlers, namespace and positive millisecond durations are required")
	}
	return func(ctx context.Context, payload []byte) error {
		// Derive identity before touching Redis; invalid payloads remain the caller's error.
		id, err := extract(payload)
		if err != nil {
			return err
		}
		if strings.TrimSpace(id) == "" {
			return errors.New("empty event key")
		}
		// Match Java's UTF-8 byte-length namespace prefix before hashing the storage key.
		sum := sha256.Sum256([]byte(fmt.Sprintf("%d:%s:%s", len(cfg.Namespace), cfg.Namespace, id)))
		key := "rf:" + hex.EncodeToString(sum[:])
		// A fresh random owner prevents an expired worker from changing a later Redis claim.
		token := make([]byte, 16)
		if _, err = rand.Read(token); err != nil {
			return err
		}
		owner := hex.EncodeToString(token)
		claim, err := store.Claim(ctx, key, owner, cfg.Lease)
		if err != nil {
			return err
		}
		// Only a completed record is safe to skip; unknown results fail closed.
		switch claim {
		case Completed:
			return nil
		case Busy:
			return ErrBusy
		case Acquired:
		default:
			return errors.New("invalid store claim result")
		}
		// On panic, retain the lease until expiry and propagate the panic.
		if err = next(ctx, payload); err != nil {
			// Preserve a bounded cleanup opportunity even if the business context was cancelled.
			cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 3*time.Second)
			defer cancel()
			_, releaseErr := store.Release(cleanup, key, owner)
			return errors.Join(err, releaseErr)
		}
		// Business work has succeeded. Do not delete the claim if completion is uncertain.
		completed, err := store.Complete(ctx, key, owner, cfg.Retention)
		if err != nil {
			return err
		}
		if !completed {
			return ErrLeaseLost
		}
		return nil
	}, nil
}
