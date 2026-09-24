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

// Package redisstore implements token-owned state transitions in Redis.
package redisstore

import (
	"context"
	"errors"
	idempotent "github.com/kunal-gandhre/reconcileflow-idempotency/go"
	"github.com/redis/go-redis/v9"
	"time"
)

type Store struct{ client redis.Scripter }

func New(client redis.Scripter) *Store { return &Store{client: client} }

var claim = redis.NewScript(`
local value = redis.call('GET', KEYS[1])
if not value then
 redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
 return 1
end
if value == 'DONE' then return 2 end
return 0`)
var complete = redis.NewScript(`
if redis.call('GET', KEYS[1]) == ARGV[1] then
 redis.call('SET', KEYS[1], 'DONE', 'PX', ARGV[2])
 return 1
end
return 0`)
var release = redis.NewScript(`
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
return 0`)

func (s *Store) Claim(ctx context.Context, key, owner string, lease time.Duration) (idempotent.Claim, error) {
	if owner == "" || lease < time.Millisecond {
		return idempotent.Busy, errors.New("owner and positive lease required")
	}
	n, err := claim.Run(ctx, s.client, []string{key}, "PROCESSING:"+owner, lease.Milliseconds()).Int()
	if err != nil {
		return idempotent.Busy, err
	}
	if n < 0 || n > 2 {
		return idempotent.Busy, errors.New("invalid Redis claim result")
	}
	return idempotent.Claim(n), nil
}
func (s *Store) Complete(ctx context.Context, key, owner string, retention time.Duration) (bool, error) {
	if owner == "" || retention < time.Millisecond {
		return false, errors.New("owner and positive retention required")
	}
	n, err := complete.Run(ctx, s.client, []string{key}, "PROCESSING:"+owner, retention.Milliseconds()).Int()
	return n == 1, err
}
func (s *Store) Release(ctx context.Context, key, owner string) (bool, error) {
	if owner == "" {
		return false, errors.New("owner required")
	}
	n, err := release.Run(ctx, s.client, []string{key}, "PROCESSING:"+owner).Int()
	return n == 1, err
}
