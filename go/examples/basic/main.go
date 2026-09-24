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
