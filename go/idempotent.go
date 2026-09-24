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

type Claim int

const (
	Busy Claim = iota
	Acquired
	Completed
)

var ErrBusy = errors.New("event is processing; retry later")
var ErrLeaseLost = errors.New("processing lease lost; reconcile business outcome")

type Store interface {
	Claim(context.Context, string, string, time.Duration) (Claim, error)
	Complete(context.Context, string, string, time.Duration) (bool, error)
	Release(context.Context, string, string) (bool, error)
}
type Handler func(context.Context, []byte) error
type KeyExtractor func([]byte) (string, error)
type Config struct {
	Namespace        string
	Lease, Retention time.Duration
}

// Wrap returns a synchronous handler. Commit Kafka offsets only after nil.
func Wrap(store Store, cfg Config, extract KeyExtractor, next Handler) (Handler, error) {
	if store == nil || extract == nil || next == nil || strings.TrimSpace(cfg.Namespace) == "" || cfg.Lease < time.Millisecond || cfg.Retention < time.Millisecond {
		return nil, errors.New("store, handlers, namespace and positive millisecond durations are required")
	}
	return func(ctx context.Context, payload []byte) error {
		id, err := extract(payload)
		if err != nil {
			return err
		}
		if strings.TrimSpace(id) == "" {
			return errors.New("empty event key")
		}
		sum := sha256.Sum256([]byte(fmt.Sprintf("%d:%s:%s", len(cfg.Namespace), cfg.Namespace, id)))
		key := "rf:" + hex.EncodeToString(sum[:])
		token := make([]byte, 16)
		if _, err = rand.Read(token); err != nil {
			return err
		}
		owner := hex.EncodeToString(token)
		claim, err := store.Claim(ctx, key, owner, cfg.Lease)
		if err != nil {
			return err
		}
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
			cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 3*time.Second)
			defer cancel()
			_, releaseErr := store.Release(cleanup, key, owner)
			return errors.Join(err, releaseErr)
		}
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
