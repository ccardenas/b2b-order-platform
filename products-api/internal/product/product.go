// Package product contiene el catalogo de productos por mercado: modelo,
// servicio, interfaz de repositorio y errores de dominio. No conoce HTTP
// (propuesta §4.2): el handler traduce estos errores al contrato de error.
package product

import (
	"context"
	"errors"
)

// Errores de dominio. El handler los distingue con errors.Is.
var (
	ErrNotFound      = errors.New("product not found")
	ErrInvalidMarket = errors.New("invalid market")
)

type Market string

const (
	MarketMX Market = "MX"
	MarketCO Market = "CO"
	MarketPE Market = "PE"
)

// ParseMarket acepta solo los valores exactos del contrato (enum en
// mayusculas). "mx" es invalido: normalizar ocultaria un error del cliente.
func ParseMarket(raw string) (Market, error) {
	switch m := Market(raw); m {
	case MarketMX, MarketCO, MarketPE:
		return m, nil
	default:
		return "", ErrInvalidMarket
	}
}

type Status string

const (
	StatusActive       Status = "ACTIVE"
	StatusDiscontinued Status = "DISCONTINUED"
)

// TaxCategory es un dato de catalogo. La tasa por mercado+categoria es una
// regla de order-processor, no de esta API (propuesta §3).
type TaxCategory string

const (
	TaxStandard TaxCategory = "STANDARD"
	TaxReduced  TaxCategory = "REDUCED"
	TaxExempt   TaxCategory = "EXEMPT"
)

type Product struct {
	ID          string
	Name        string
	SKU         string
	Status      Status
	TaxCategory TaxCategory
}

// Repository busca un producto dentro de un mercado. Devuelve ErrNotFound si
// no existe en ese mercado y debe respetar la cancelacion de ctx.
type Repository interface {
	FindByID(ctx context.Context, market Market, id string) (Product, error)
}

type Service struct {
	repo Repository
}

func NewService(repo Repository) *Service {
	return &Service{repo: repo}
}

// Get valida el mercado antes de tocar el repositorio y propaga ctx hasta el.
func (s *Service) Get(ctx context.Context, rawMarket, id string) (Product, error) {
	market, err := ParseMarket(rawMarket)
	if err != nil {
		return Product{}, err
	}
	return s.repo.FindByID(ctx, market, id)
}
