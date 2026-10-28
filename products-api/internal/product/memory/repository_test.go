package memory_test

import (
	"context"
	"errors"
	"testing"

	"github.com/ccardenas/b2b-order-platform/products-api/internal/product"
	"github.com/ccardenas/b2b-order-platform/products-api/internal/product/memory"
)

// La semilla es un requisito del hito 2 (propuesta §12): >= 10 productos, los
// tres mercados, todos los status y todas las taxCategory en cada mercado.
func TestSeedCoverage(t *testing.T) {
	seed := memory.Seed()

	total := 0
	for _, market := range []product.Market{product.MarketMX, product.MarketCO, product.MarketPE} {
		list := seed[market]
		total += len(list)

		statuses := map[product.Status]bool{}
		categories := map[product.TaxCategory]bool{}
		ids := map[string]bool{}
		for _, p := range list {
			if ids[p.ID] {
				t.Errorf("%s: duplicated id %s", market, p.ID)
			}
			ids[p.ID] = true
			statuses[p.Status] = true
			categories[p.TaxCategory] = true
		}
		for _, s := range []product.Status{product.StatusActive, product.StatusDiscontinued} {
			if !statuses[s] {
				t.Errorf("%s: no product with status %s", market, s)
			}
		}
		for _, c := range []product.TaxCategory{product.TaxStandard, product.TaxReduced, product.TaxExempt} {
			if !categories[c] {
				t.Errorf("%s: no product with taxCategory %s", market, c)
			}
		}
	}
	if total < 10 {
		t.Errorf("seed has %d products, want >= 10", total)
	}
}

func TestRepositoryFindByID(t *testing.T) {
	repo := memory.NewRepository(memory.Seed())

	tests := []struct {
		name    string
		market  product.Market
		id      string
		wantSKU string
		wantErr error
	}{
		{name: "exists in MX", market: product.MarketMX, id: "PRD-001", wantSKU: "BEB-600-PET"},
		{name: "same id exists in CO", market: product.MarketCO, id: "PRD-001", wantSKU: "BEB-600-PET"},
		{name: "same id missing in PE", market: product.MarketPE, id: "PRD-001", wantErr: product.ErrNotFound},
		{name: "unknown id", market: product.MarketMX, id: "PRD-999", wantErr: product.ErrNotFound},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := repo.FindByID(context.Background(), tt.market, tt.id)
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("err = %v, want %v", err, tt.wantErr)
			}
			if got.SKU != tt.wantSKU {
				t.Errorf("sku = %q, want %q", got.SKU, tt.wantSKU)
			}
		})
	}
}

func TestRepositoryRespectsCancelledContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := memory.NewRepository(memory.Seed()).FindByID(ctx, product.MarketMX, "PRD-001")

	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
}
