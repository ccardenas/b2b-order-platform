// Package memory implementa product.Repository con una semilla en memoria.
package memory

import (
	"context"

	"github.com/ccardenas/b2b-order-platform/products-api/internal/product"
)

type key struct {
	market product.Market
	id     string
}

// Repository es de solo lectura despues de construirse, por eso no necesita
// mutex para lecturas concurrentes.
type Repository struct {
	products map[key]product.Product
}

func NewRepository(seed map[product.Market][]product.Product) *Repository {
	products := make(map[key]product.Product)
	for market, list := range seed {
		for _, p := range list {
			products[key{market: market, id: p.ID}] = p
		}
	}
	return &Repository{products: products}
}

func (r *Repository) FindByID(ctx context.Context, market product.Market, id string) (product.Product, error) {
	if err := ctx.Err(); err != nil {
		return product.Product{}, err
	}
	p, ok := r.products[key{market: market, id: id}]
	if !ok {
		return product.Product{}, product.ErrNotFound
	}
	return p, nil
}
