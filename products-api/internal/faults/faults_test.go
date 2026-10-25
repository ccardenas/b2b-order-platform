package faults_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ccardenas/b2b-order-platform/products-api/internal/faults"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/httpapi"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/product"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/product/memory"
)

func routerWithFaults(slowDelay time.Duration) http.Handler {
	svc := product.NewService(memory.NewRepository(memory.Seed()))
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return httpapi.NewRouter(svc, logger, faults.Middleware(slowDelay))
}

func TestFaultInjection(t *testing.T) {
	tests := []struct {
		id             string
		wantStatus     int
		wantCode       string
		wantStatusEnum string
		wantRetryAfter string
	}{
		{id: faults.IDFail503, wantStatus: http.StatusServiceUnavailable, wantCode: httpapi.CodeServiceUnavailable},
		{id: faults.IDFail500, wantStatus: http.StatusInternalServerError, wantCode: httpapi.CodeInternalError},
		{id: faults.IDFail429, wantStatus: http.StatusTooManyRequests, wantCode: httpapi.CodeRateLimited, wantRetryAfter: "1"},
		{id: faults.IDBadEnum, wantStatus: http.StatusOK, wantStatusEnum: "ARCHIVED"},
		{id: faults.IDSlow, wantStatus: http.StatusNotFound, wantCode: httpapi.CodeProductNotFound},
		{id: "PRD-001", wantStatus: http.StatusOK, wantStatusEnum: "ACTIVE"},
	}

	router := routerWithFaults(10 * time.Millisecond)
	for _, tt := range tests {
		t.Run(tt.id, func(t *testing.T) {
			rec := httptest.NewRecorder()
			router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/products/"+tt.id+"?market=MX", nil))

			if rec.Code != tt.wantStatus {
				t.Fatalf("status = %d, want %d (body %s)", rec.Code, tt.wantStatus, rec.Body)
			}
			if got := rec.Header().Get("Retry-After"); got != tt.wantRetryAfter {
				t.Errorf("Retry-After = %q, want %q", got, tt.wantRetryAfter)
			}

			var body struct {
				Status string `json:"status"`
				Error  struct {
					Code    string `json:"code"`
					TraceID string `json:"traceId"`
				} `json:"error"`
			}
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatalf("decode: %v", err)
			}
			if body.Error.Code != tt.wantCode {
				t.Errorf("code = %q, want %q", body.Error.Code, tt.wantCode)
			}
			if tt.wantCode != "" && body.Error.TraceID == "" {
				t.Error("injected errors must keep the error contract (traceId missing)")
			}
			if body.Status != tt.wantStatusEnum {
				t.Errorf("product status = %q, want %q", body.Status, tt.wantStatusEnum)
			}
		})
	}
}

func TestSlowFaultStopsWhenRequestIsCancelled(t *testing.T) {
	var logs bytes.Buffer
	svc := product.NewService(memory.NewRepository(memory.Seed()))
	router := httpapi.NewRouter(svc, slog.New(slog.NewJSONHandler(&logs, nil)), faults.Middleware(time.Hour))
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	req := httptest.NewRequest(http.MethodGet, "/products/"+faults.IDSlow+"?market=MX", nil).WithContext(ctx)

	done := make(chan struct{})
	go func() {
		router.ServeHTTP(httptest.NewRecorder(), req)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("PRD-SLOW kept running after the request context was done")
	}
	// Sin respuesta escrita, el log no debe decir 200.
	if !strings.Contains(logs.String(), `"status":499`) {
		t.Errorf("access log should record 499 for a request abandoned by the client, got %s", logs.String())
	}
}

func TestWithoutMiddlewareFaultIDsAreRegularProducts(t *testing.T) {
	svc := product.NewService(memory.NewRepository(memory.Seed()))
	router := httpapi.NewRouter(svc, slog.New(slog.NewTextHandler(io.Discard, nil)))
	rec := httptest.NewRecorder()

	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/products/"+faults.IDFail503+"?market=MX", nil))

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404 when fault injection is disabled", rec.Code)
	}
}
