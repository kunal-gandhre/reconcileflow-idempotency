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

// Command json demonstrates deduplication by eventId while retaining the whole JSON payload.
package main

import (
	"context"
	"fmt"
	idempotent "github.com/kunal-gandhre/reconcileflow-idempotency/go"
	"github.com/kunal-gandhre/reconcileflow-idempotency/go/redisstore"
	"github.com/redis/go-redis/v9"
	"log"
	"time"
)

// main sends three JSON messages with two distinct identities through the Redis-backed wrapper.
func main() {
	client := redis.NewClient(&redis.Options{Addr: "localhost:6379"})
	defer client.Close()
	handler, err := idempotent.Wrap(redisstore.New(client),
		idempotent.Config{Namespace: "go-json-demo:orders:v1", Lease: time.Minute, Retention: 24 * time.Hour},
		idempotent.JSONKey("eventId"), func(_ context.Context, payload []byte) error {
			fmt.Println("Processed JSON:", string(payload))
			return nil
		})
	if err != nil {
		log.Fatal(err)
	}
	// Fresh IDs allow repeated demo runs without clearing unrelated Redis records.
	id := fmt.Sprintf("json-%d", time.Now().UnixNano())
	for _, payload := range []string{
		fmt.Sprintf(`{"eventId":%q,"amount":10}`, id),
		fmt.Sprintf(`{ "amount": 10, "eventId": %q }`, id),
		fmt.Sprintf(`{"eventId":%q,"amount":20}`, id+"-second"),
	} {
		if err := handler(context.Background(), []byte(payload)); err != nil {
			log.Fatal(err)
		}
	}
}
