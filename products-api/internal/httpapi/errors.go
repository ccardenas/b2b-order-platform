package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
)

// Codigos estables del contrato de error (propuesta §4.4). El consumidor
// decide por status HTTP + code, nunca por message.
const (
	CodeProductNotFound    = "PRODUCT_NOT_FOUND"
	CodeInvalidMarket      = "INVALID_MARKET"
	CodeRouteNotFound      = "ROUTE_NOT_FOUND"
	CodeRateLimited        = "RATE_LIMITED"
	CodeServiceUnavailable = "SERVICE_UNAVAILABLE"
	CodeInternalError      = "INTERNAL_ERROR"
)

// TraceHeader permite que el llamador propague su traceId. Si no viene, se
// genera uno por request.
const TraceHeader = "X-Trace-Id"

type errorBody struct {
	Error errorDetail `json:"error"`
}

type errorDetail struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	TraceID string `json:"traceId"`
}

// WriteError escribe el contrato {"error":{"code","message","traceId"}}.
// Es exportada para que internal/faults responda con el mismo contrato.
func WriteError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	writeJSON(w, status, errorBody{Error: errorDetail{
		Code:    code,
		Message: message,
		TraceID: TraceID(r.Context()),
	}})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

type traceKey struct{}

// TraceID devuelve el traceId del request, o "unknown" si el handler se usa
// sin el middleware withTrace (por ejemplo en un test aislado).
func TraceID(ctx context.Context) string {
	if id, ok := ctx.Value(traceKey{}).(string); ok {
		return id
	}
	return "unknown"
}

func withTrace(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get(TraceHeader)
		if id == "" {
			id = newTraceID()
		}
		w.Header().Set(TraceHeader, id)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), traceKey{}, id)))
	})
}

func newTraceID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
