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

// Integration tests opt into a real Redis server using REDIS_INTEGRATION=true.
package redisstore

import (
	"context"
	"fmt"
	idempotent "github.com/kunal-gandhre/reconcileflow-idempotency/go"
	"github.com/redis/go-redis/v9"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// integrationStore connects to Redis and allocates a unique, expiring key per test.
func integrationStore(t *testing.T) (*Store, string) {
	t.Helper()
	if os.Getenv("REDIS_INTEGRATION") != "true" {
		t.Skip("set REDIS_INTEGRATION=true to test real Redis")
	}
	port := os.Getenv("REDIS_PORT")
	if port == "" {
		port = "6379"
	}
	client := redis.NewClient(&redis.Options{Addr: "localhost:" + port})
	// Close this test's connection without deleting unrelated database contents.
	t.Cleanup(func() { _ = client.Close() })
	if err := client.Ping(context.Background()).Err(); err != nil {
		t.Fatal(err)
	}
	return New(client), fmt.Sprintf("rf:go-test:%d", time.Now().UnixNano())
}

// TestRedisOwnershipAndCompletion covers state transitions and rejection of the wrong owner.
func TestRedisOwnershipAndCompletion(t *testing.T) {
	s, key := integrationStore(t)
	ctx := context.Background()
	if c, e := s.Claim(ctx, key, "a", time.Second); e != nil || c != idempotent.Acquired {
		t.Fatal(c, e)
	}
	if c, e := s.Claim(ctx, key, "b", time.Second); e != nil || c != idempotent.Busy {
		t.Fatal(c, e)
	}
	if ok, e := s.Release(ctx, key, "b"); e != nil || ok {
		t.Fatal(ok, e)
	}
	if ok, e := s.Complete(ctx, key, "b", time.Second); e != nil || ok {
		t.Fatal(ok, e)
	}
	if ok, e := s.Complete(ctx, key, "a", time.Second); e != nil || !ok {
		t.Fatal(ok, e)
	}
	if c, e := s.Claim(ctx, key, "c", time.Second); e != nil || c != idempotent.Completed {
		t.Fatal(c, e)
	}
	if ok, e := s.Release(ctx, key, "a"); e != nil || ok {
		t.Fatal(ok, e)
	}
}

// TestRedisExpiredOwnerCannotMutateNewClaim proves expired workers cannot mutate replacement claims.
func TestRedisExpiredOwnerCannotMutateNewClaim(t *testing.T) {
	s, key := integrationStore(t)
	ctx := context.Background()
	if _, e := s.Claim(ctx, key, "old", 50*time.Millisecond); e != nil {
		t.Fatal(e)
	}
	// Cross the deliberately short lease boundary before attempting a replacement claim.
	time.Sleep(100 * time.Millisecond)
	if c, e := s.Claim(ctx, key, "new", time.Second); e != nil || c != idempotent.Acquired {
		t.Fatal(c, e)
	}
	if ok, e := s.Release(ctx, key, "old"); e != nil || ok {
		t.Fatal(ok, e)
	}
	if ok, e := s.Complete(ctx, key, "old", time.Second); e != nil || ok {
		t.Fatal(ok, e)
	}
}

// TestRedisConcurrentClaim checks that exactly one of 32 contenders acquires the live lease.
func TestRedisConcurrentClaim(t *testing.T) {
	s, key := integrationStore(t)
	// Multiple goroutines report wins; use an atomic counter to keep the test race-free.
	var wins atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			c, e := s.Claim(context.Background(), key, fmt.Sprint(i), 10*time.Second)
			if e != nil {
				t.Error(e)
			}
			if c == idempotent.Acquired {
				wins.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if wins.Load() != 1 {
		t.Fatalf("winners: %d", wins.Load())
	}
}
