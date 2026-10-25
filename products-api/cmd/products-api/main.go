// Command products-api expone el catalogo de productos por mercado.
//
// Variables de entorno:
//
//	PORT                     puerto HTTP (default 8080)
//	FAULT_INJECTION_ENABLED  "true" monta internal/faults (default desactivado)
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ccardenas/b2b-order-platform/products-api/internal/faults"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/httpapi"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/product"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/product/memory"
)

const shutdownTimeout = 10 * time.Second

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := run(ctx, logger); err != nil {
		logger.Error("products-api stopped with error", "error", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, logger *slog.Logger) error {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	svc := product.NewService(memory.NewRepository(memory.Seed()))

	var productMiddleware []func(http.Handler) http.Handler
	if os.Getenv("FAULT_INJECTION_ENABLED") == "true" {
		productMiddleware = append(productMiddleware, faults.Middleware(faults.DefaultSlowDelay))
		logger.Warn("fault injection enabled")
	}

	server := &http.Server{
		Addr:              ":" + port,
		Handler:           httpapi.NewRouter(svc, logger, productMiddleware...),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		// Mayor que faults.DefaultSlowDelay para que PRD-SLOW llegue a
		// responder si el cliente espera lo suficiente.
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	serveErr := make(chan error, 1)
	go func() {
		logger.Info("products-api listening", "addr", server.Addr)
		serveErr <- server.ListenAndServe()
	}()

	select {
	case err := <-serveErr:
		// Fallo al arrancar (por ejemplo, puerto ocupado).
		return err
	case <-ctx.Done():
	}

	// Shutdown deja de aceptar conexiones y espera a los requests en curso,
	// con un tope para no colgar el apagado del contenedor.
	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		return err
	}
	if err := <-serveErr; !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	logger.Info("products-api stopped")
	return nil
}
