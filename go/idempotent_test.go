package idempotent

import (
	"context"
	"errors"
	"testing"
	"time"
)

type fakeStore struct {
	claim      Claim
	err        error
	complete   bool
	released   bool
	completed  bool
	releaseErr error
}

func (s *fakeStore) Claim(context.Context, string, string, time.Duration) (Claim, error) {
	return s.claim, s.err
}
func (s *fakeStore) Complete(context.Context, string, string, time.Duration) (bool, error) {
	s.completed = true
	return s.complete, s.err
}
func (s *fakeStore) Release(context.Context, string, string) (bool, error) {
	s.released = true
	return true, s.releaseErr
}
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
func TestStoreUnavailableDoesNotProcess(t *testing.T) {
	failure := errors.New("offline")
	s := &fakeStore{err: failure}
	h, _ := Wrap(s, Config{"orders", time.Minute, time.Hour}, func([]byte) (string, error) { return "1", nil }, func(context.Context, []byte) error { t.Fatal("handler ran"); return nil })
	if !errors.Is(h(context.Background(), nil), failure) {
		t.Fatal("missing store error")
	}
}
func TestInvalidConfig(t *testing.T) {
	if _, err := Wrap(nil, Config{}, nil, nil); err == nil {
		t.Fatal("accepted invalid config")
	}
}
