package product_test

import (
	"context"
	"errors"
	"testing"

	"github.com/ccardenas/b2b-order-platform/products-api/internal/product"
)

// fakeRepo registra si fue llamado y con que mercado, para verificar que la
// validacion ocurre antes de tocar el repositorio.
type fakeRepo struct {
	products   map[product.Market]map[string]product.Product
	err        error
	called     bool
	lastMarket product.Market
}

func (f *fakeRepo) FindByID(ctx context.Context, market product.Market, id string) (product.Product, error) {
	f.called = true
	f.lastMarket = market
	if err := ctx.Err(); err != nil {
		return product.Product{}, err
	}
	if f.err != nil {
		return product.Product{}, f.err
	}
	p, ok := f.products[market][id]
	if !ok {
		return product.Product{}, product.ErrNotFound
	}
	return p, nil
}

var bebida = product.Product{
	ID: "PRD-001", Name: "Bebida 600 ml", SKU: "BEB-600-PET",
	Status: product.StatusActive, TaxCategory: product.TaxStandard,
}

func TestServiceGet(t *testing.T) {
	errBoom := errors.New("boom")

	tests := []struct {
		name         string
		market       string
		id           string
		repoErr      error
		cancelCtx    bool
		want         product.Product
		wantErr      error
		wantRepoCall bool
	}{
		{name: "found", market: "MX", id: "PRD-001", want: bebida, wantRepoCall: true},
		{name: "not found in market", market: "PE", id: "PRD-001", wantErr: product.ErrNotFound, wantRepoCall: true},
		{name: "unknown id", market: "MX", id: "PRD-999", wantErr: product.ErrNotFound, wantRepoCall: true},
		{name: "missing market", market: "", id: "PRD-001", wantErr: product.ErrInvalidMarket},
		{name: "unsupported market", market: "AR", id: "PRD-001", wantErr: product.ErrInvalidMarket},
		{name: "lowercase market is invalid", market: "mx", id: "PRD-001", wantErr: product.ErrInvalidMarket},
		{name: "repository error is propagated", market: "MX", id: "PRD-001", repoErr: errBoom, wantErr: errBoom, wantRepoCall: true},
		{name: "cancelled context reaches repository", market: "MX", id: "PRD-001", cancelCtx: true, wantErr: context.Canceled, wantRepoCall: true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repo := &fakeRepo{
				products: map[product.Market]map[string]product.Product{
					product.MarketMX: {"PRD-001": bebida},
				},
				err: tt.repoErr,
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if tt.cancelCtx {
				cancel()
			}

			got, err := product.NewService(repo).Get(ctx, tt.market, tt.id)

			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("err = %v, want %v", err, tt.wantErr)
			}
			if got != tt.want {
				t.Errorf("product = %+v, want %+v", got, tt.want)
			}
			if repo.called != tt.wantRepoCall {
				t.Errorf("repository called = %v, want %v", repo.called, tt.wantRepoCall)
			}
			if tt.wantRepoCall && string(repo.lastMarket) != tt.market {
				t.Errorf("repository market = %q, want %q", repo.lastMarket, tt.market)
			}
		})
	}
}
