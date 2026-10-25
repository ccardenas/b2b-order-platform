// Package faults simula fallos de products-api de forma determinista segun
// IDs semilla (propuesta §10). Solo se monta si FAULT_INJECTION_ENABLED=true
// y vive fuera del dominio: el servicio no sabe que existe.
//
// IDs documentados:
//
//	PRD-FAIL-503  -> 503 SERVICE_UNAVAILABLE (transitorio)
//	PRD-FAIL-500  -> 500 INTERNAL_ERROR      (transitorio)
//	PRD-FAIL-429  -> 429 RATE_LIMITED con Retry-After: 1
//	PRD-SLOW      -> espera slowDelay (mas que el read timeout de 2 s del
//	                 cliente) y luego sigue al handler real (404, no esta en
//	                 la semilla)
//	PRD-BAD-ENUM  -> 200 con status "ARCHIVED", fuera del enum (S10)
package faults

import (
	"net/http"
	"time"

	"github.com/ccardenas/b2b-order-platform/products-api/internal/httpapi"
)

const (
	IDFail503 = "PRD-FAIL-503"
	IDFail500 = "PRD-FAIL-500"
	IDFail429 = "PRD-FAIL-429"
	IDSlow    = "PRD-SLOW"
	IDBadEnum = "PRD-BAD-ENUM"
)

// DefaultSlowDelay supera el read timeout de 2 s de order-processor (§5.3).
const DefaultSlowDelay = 5 * time.Second

// Middleware debe montarse sobre la ruta GET /products/{productId}, porque
// lee r.PathValue("productId").
func Middleware(slowDelay time.Duration) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.PathValue("productId") {
			case IDFail503:
				httpapi.WriteError(w, r, http.StatusServiceUnavailable, httpapi.CodeServiceUnavailable, "Injected fault: service unavailable")
			case IDFail500:
				httpapi.WriteError(w, r, http.StatusInternalServerError, httpapi.CodeInternalError, "Injected fault: internal error")
			case IDFail429:
				w.Header().Set("Retry-After", "1")
				httpapi.WriteError(w, r, http.StatusTooManyRequests, httpapi.CodeRateLimited, "Injected fault: rate limited")
			case IDBadEnum:
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"productId":"PRD-BAD-ENUM","name":"Producto con enum desconocido","sku":"BAD-ENUM-000","status":"ARCHIVED","taxCategory":"STANDARD"}` + "\n"))
			case IDSlow:
				// Respeta la cancelacion: si el cliente corta, no se sigue
				// ocupando la goroutine.
				select {
				case <-time.After(slowDelay):
					next.ServeHTTP(w, r)
				case <-r.Context().Done():
				}
			default:
				next.ServeHTTP(w, r)
			}
		})
	}
}
