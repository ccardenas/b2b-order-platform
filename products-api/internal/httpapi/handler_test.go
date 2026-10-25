package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/ccardenas/b2b-order-platform/products-api/internal/httpapi"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/product"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/product/memory"
)

var discardLogger = slog.New(slog.NewTextHandler(io.Discard, nil))

// Estructuras estrictas del contrato: con DisallowUnknownFields, un campo de
// mas en la respuesta hace fallar el test (error.schema.json tiene
// additionalProperties: false).
type errorBody struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
		TraceID string `json:"traceId"`
	} `json:"error"`
}

type productBody struct {
	ProductID   string `json:"productId"`
	Name        string `json:"name"`
	SKU         string `json:"sku"`
	Status      string `json:"status"`
	TaxCategory string `json:"taxCategory"`
}

func decodeStrict(t *testing.T, body []byte, v any) {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		t.Fatalf("decode %s: %v", body, err)
	}
}

func seededRouter() http.Handler {
	svc := product.NewService(memory.NewRepository(memory.Seed()))
	return httpapi.NewRouter(svc, discardLogger)
}

func TestGetProduct(t *testing.T) {
	tests := []struct {
		name       string
		method     string
		target     string
		wantStatus int
		wantCode   string // vacio cuando la respuesta es un producto
		wantSKU    string
	}{
		{name: "found", method: http.MethodGet, target: "/products/PRD-001?market=MX", wantStatus: http.StatusOK, wantSKU: "BEB-600-PET"},
		{name: "discontinued is still 200", method: http.MethodGet, target: "/products/PRD-014?market=CO", wantStatus: http.StatusOK, wantSKU: "INS-000-EXE"},
		{name: "missing market", method: http.MethodGet, target: "/products/PRD-001", wantStatus: http.StatusBadRequest, wantCode: httpapi.CodeInvalidMarket},
		{name: "unsupported market", method: http.MethodGet, target: "/products/PRD-001?market=AR", wantStatus: http.StatusBadRequest, wantCode: httpapi.CodeInvalidMarket},
		{name: "not found in market", method: http.MethodGet, target: "/products/PRD-001?market=PE", wantStatus: http.StatusNotFound, wantCode: httpapi.CodeProductNotFound},
		{name: "unknown product", method: http.MethodGet, target: "/products/PRD-999?market=MX", wantStatus: http.StatusNotFound, wantCode: httpapi.CodeProductNotFound},
		{name: "unknown route", method: http.MethodGet, target: "/nope", wantStatus: http.StatusNotFound, wantCode: httpapi.CodeRouteNotFound},
		{name: "wrong method", method: http.MethodPost, target: "/products/PRD-001?market=MX", wantStatus: http.StatusNotFound, wantCode: httpapi.CodeRouteNotFound},
	}

	router := seededRouter()
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			router.ServeHTTP(rec, httptest.NewRequest(tt.method, tt.target, nil))

			if rec.Code != tt.wantStatus {
				t.Fatalf("status = %d, want %d (body %s)", rec.Code, tt.wantStatus, rec.Body)
			}
			if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
				t.Errorf("Content-Type = %q, want application/json", ct)
			}

			if tt.wantCode == "" {
				var p productBody
				decodeStrict(t, rec.Body.Bytes(), &p)
				if p.SKU != tt.wantSKU {
					t.Errorf("sku = %q, want %q", p.SKU, tt.wantSKU)
				}
				return
			}

			var e errorBody
			decodeStrict(t, rec.Body.Bytes(), &e)
			if e.Error.Code != tt.wantCode {
				t.Errorf("code = %q, want %q", e.Error.Code, tt.wantCode)
			}
			if e.Error.Message == "" || e.Error.TraceID == "" {
				t.Errorf("message and traceId must be non-empty: %+v", e.Error)
			}
			if e.Error.TraceID != rec.Header().Get(httpapi.TraceHeader) {
				t.Errorf("traceId %q differs from header %q", e.Error.TraceID, rec.Header().Get(httpapi.TraceHeader))
			}
		})
	}
}

func TestNotFoundMessageMatchesContractExample(t *testing.T) {
	rec := httptest.NewRecorder()
	seededRouter().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/products/PRD-999?market=MX", nil))

	var e errorBody
	decodeStrict(t, rec.Body.Bytes(), &e)
	if want := "Product PRD-999 not found in market MX"; e.Error.Message != want {
		t.Errorf("message = %q, want %q", e.Error.Message, want)
	}
}

func TestTraceIDIsPropagatedFromHeader(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/products/PRD-999?market=MX", nil)
	req.Header.Set(httpapi.TraceHeader, "trace-from-caller")
	rec := httptest.NewRecorder()

	seededRouter().ServeHTTP(rec, req)

	var e errorBody
	decodeStrict(t, rec.Body.Bytes(), &e)
	if e.Error.TraceID != "trace-from-caller" {
		t.Errorf("traceId = %q, want trace-from-caller", e.Error.TraceID)
	}
}

func TestHealth(t *testing.T) {
	rec := httptest.NewRecorder()
	seededRouter().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	var body struct {
		Status string `json:"status"`
	}
	decodeStrict(t, rec.Body.Bytes(), &body)
	if body.Status != "UP" {
		t.Errorf("status = %q, want UP", body.Status)
	}
}

type errRepo struct{ err error }

func (r errRepo) FindByID(context.Context, product.Market, string) (product.Product, error) {
	return product.Product{}, r.err
}

func TestUnexpectedErrorIs500WithoutLeakingDetails(t *testing.T) {
	router := httpapi.NewRouter(product.NewService(errRepo{err: errors.New("mongo: secret dsn")}), discardLogger)
	rec := httptest.NewRecorder()

	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/products/PRD-001?market=MX", nil))

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", rec.Code)
	}
	var e errorBody
	decodeStrict(t, rec.Body.Bytes(), &e)
	if e.Error.Code != httpapi.CodeInternalError || bytes.Contains(rec.Body.Bytes(), []byte("secret")) {
		t.Errorf("unexpected body: %s", rec.Body)
	}
}

// blockingRepo simula un repositorio lento: avisa que empezo y espera hasta
// que el contexto del request se cancele.
type blockingRepo struct {
	started chan struct{}
	gotErr  chan error
}

func (r *blockingRepo) FindByID(ctx context.Context, _ product.Market, _ string) (product.Product, error) {
	close(r.started)
	<-ctx.Done()
	r.gotErr <- ctx.Err()
	return product.Product{}, ctx.Err()
}

func TestRequestCancellationReachesRepository(t *testing.T) {
	repo := &blockingRepo{started: make(chan struct{}), gotErr: make(chan error, 1)}
	router := httpapi.NewRouter(product.NewService(repo), discardLogger)

	ctx, cancel := context.WithCancel(context.Background())
	req := httptest.NewRequest(http.MethodGet, "/products/PRD-001?market=MX", nil).WithContext(ctx)
	rec := httptest.NewRecorder()

	done := make(chan struct{})
	go func() {
		router.ServeHTTP(rec, req)
		close(done)
	}()

	<-repo.started
	cancel()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("handler did not return after the request context was cancelled")
	}
	if err := <-repo.gotErr; !errors.Is(err, context.Canceled) {
		t.Errorf("repository saw %v, want context.Canceled", err)
	}
	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503", rec.Code)
	}
	var e errorBody
	decodeStrict(t, rec.Body.Bytes(), &e)
	if e.Error.Code != httpapi.CodeServiceUnavailable {
		t.Errorf("code = %q, want %q", e.Error.Code, httpapi.CodeServiceUnavailable)
	}
}
