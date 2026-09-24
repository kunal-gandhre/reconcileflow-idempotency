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
	t.Cleanup(func() { _ = client.Close() })
	if err := client.Ping(context.Background()).Err(); err != nil {
		t.Fatal(err)
	}
	return New(client), fmt.Sprintf("rf:go-test:%d", time.Now().UnixNano())
}
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
func TestRedisExpiredOwnerCannotMutateNewClaim(t *testing.T) {
	s, key := integrationStore(t)
	ctx := context.Background()
	if _, e := s.Claim(ctx, key, "old", 50*time.Millisecond); e != nil {
		t.Fatal(e)
	}
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
func TestRedisConcurrentClaim(t *testing.T) {
	s, key := integrationStore(t)
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
