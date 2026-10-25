// Package httpapi es el transporte HTTP: ruteo, DTOs, contrato de error y
// middlewares de traza y logging. Traduce los errores de dominio a HTTP.
package httpapi

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/ccardenas/b2b-order-platform/products-api/internal/product"
)

// productResponse es el DTO del contrato (components.schemas.Product). Se
// mapea explicitamente desde el dominio para que un cambio interno no
// altere el JSON publicado.
type productResponse struct {
	ProductID   string `json:"productId"`
	Name        string `json:"name"`
	SKU         string `json:"sku"`
	Status      string `json:"status"`
	TaxCategory string `json:"taxCategory"`
}

func toResponse(p product.Product) productResponse {
	return productResponse{
		ProductID:   p.ID,
		Name:        p.Name,
		SKU:         p.SKU,
		Status:      string(p.Status),
		TaxCategory: string(p.TaxCategory),
	}
}

type handler struct {
	svc    *product.Service
	logger *slog.Logger
}

// NewRouter arma las rutas. productMiddleware envuelve solo GET
// /products/{productId}, despues del ruteo, para que r.PathValue funcione
// dentro de el (lo usa internal/faults).
func NewRouter(svc *product.Service, logger *slog.Logger, productMiddleware ...func(http.Handler) http.Handler) http.Handler {
	h := &handler{svc: svc, logger: logger}

	var getProduct http.Handler = http.HandlerFunc(h.getProduct)
	for i := len(productMiddleware) - 1; i >= 0; i-- {
		getProduct = productMiddleware[i](getProduct)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", health)
	mux.Handle("GET /products/{productId}", getProduct)
	mux.HandleFunc("/", routeNotFound)

	return withTrace(withLogging(logger, mux))
}

func health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func routeNotFound(w http.ResponseWriter, r *http.Request) {
	WriteError(w, r, http.StatusNotFound, CodeRouteNotFound, fmt.Sprintf("No route for %s %s", r.Method, r.URL.Path))
}

func (h *handler) getProduct(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("productId")
	market := r.URL.Query().Get("market")

	p, err := h.svc.Get(r.Context(), market, id)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, toResponse(p))
	case errors.Is(err, product.ErrInvalidMarket):
		WriteError(w, r, http.StatusBadRequest, CodeInvalidMarket, "Query parameter market is required and must be one of MX, CO, PE")
	case errors.Is(err, product.ErrNotFound):
		WriteError(w, r, http.StatusNotFound, CodeProductNotFound, fmt.Sprintf("Product %s not found in market %s", id, market))
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		// Si el cliente ya se fue la respuesta se descarta; si fue un
		// deadline del servidor, 503 es transitorio para order-processor.
		WriteError(w, r, http.StatusServiceUnavailable, CodeServiceUnavailable, "Request cancelled or timed out")
	default:
		h.logger.ErrorContext(r.Context(), "unexpected error", "traceId", TraceID(r.Context()), "error", err)
		WriteError(w, r, http.StatusInternalServerError, CodeInternalError, "Internal error")
	}
}

// statusClientClosed es la convencion de nginx para "el cliente cerro la
// conexion antes de recibir respuesta". Solo se usa en el log.
const statusClientClosed = 499

// statusRecorder captura el status escrito. 0 significa "no se escribio
// nada", que no es lo mismo que un 200.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	if s.status == 0 {
		s.status = code
	}
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = http.StatusOK
	}
	return s.ResponseWriter.Write(b)
}

// withLogging escribe una linea JSON por request. Va dentro de withTrace
// para poder incluir el traceId.
func withLogging(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		status := rec.status
		if status == 0 {
			status = http.StatusOK
			if r.Context().Err() != nil {
				status = statusClientClosed
			}
		}
		logger.InfoContext(r.Context(), "request",
			"method", r.Method,
			"path", r.URL.Path,
			"query", r.URL.RawQuery,
			"status", status,
			"durationMs", time.Since(start).Milliseconds(),
			"traceId", TraceID(r.Context()),
		)
	})
}
