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

func main() {
	client := redis.NewClient(&redis.Options{Addr: "localhost:6379"})
	defer client.Close()
	handler, err := idempotent.Wrap(redisstore.New(client), idempotent.Config{Namespace: "go-demo:orders:v1", Lease: time.Minute, Retention: 24 * time.Hour},
		func(payload []byte) (string, error) { return string(payload), nil },
		func(ctx context.Context, payload []byte) error {
			fmt.Println("Processed:", string(payload))
			return nil
		})
	if err != nil {
		log.Fatal(err)
	}
	for _, id := range []string{"order-1", "order-1", "order-2"} {
		if err := handler(context.Background(), []byte(id)); err != nil {
			log.Fatal(err)
		}
	}
}
