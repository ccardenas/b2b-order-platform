package memory

import "github.com/ccardenas/b2b-order-platform/products-api/internal/product"

// Seed es el catalogo inicial. Cada mercado tiene las tres categorias
// fiscales y al menos un producto DISCONTINUED. PRD-001 existe en MX y CO
// pero no en PE, para ejercitar "producto inexistente en el mercado" (S6).
// Los IDs coinciden con los fixtures de contracts/ (PRD-001 y PRD-008 en MX,
// PRD-014 en CO).
func Seed() map[product.Market][]product.Product {
	return map[product.Market][]product.Product{
		product.MarketMX: {
			{ID: "PRD-001", Name: "Bebida 600 ml", SKU: "BEB-600-PET", Status: product.StatusActive, TaxCategory: product.TaxStandard},
			{ID: "PRD-002", Name: "Galletas surtidas 1 kg", SKU: "GAL-1KG-SUR", Status: product.StatusActive, TaxCategory: product.TaxReduced},
			{ID: "PRD-003", Name: "Tortillas de maiz 1 kg", SKU: "TOR-1KG-MAI", Status: product.StatusActive, TaxCategory: product.TaxExempt},
			{ID: "PRD-004", Name: "Jugo de naranja 1 L", SKU: "JUG-1L-NAR", Status: product.StatusDiscontinued, TaxCategory: product.TaxStandard},
			{ID: "PRD-008", Name: "Detergente liquido 3 L", SKU: "DET-3L-LIQ", Status: product.StatusActive, TaxCategory: product.TaxStandard},
		},
		product.MarketCO: {
			{ID: "PRD-001", Name: "Bebida 600 ml", SKU: "BEB-600-PET", Status: product.StatusActive, TaxCategory: product.TaxStandard},
			{ID: "PRD-005", Name: "Cafe molido 500 g", SKU: "CAF-500-MOL", Status: product.StatusActive, TaxCategory: product.TaxReduced},
			{ID: "PRD-006", Name: "Arroz blanco 5 kg", SKU: "ARR-5KG-BLA", Status: product.StatusActive, TaxCategory: product.TaxExempt},
			{ID: "PRD-014", Name: "Insumo exento", SKU: "INS-000-EXE", Status: product.StatusDiscontinued, TaxCategory: product.TaxExempt},
		},
		product.MarketPE: {
			{ID: "PRD-009", Name: "Aceite vegetal 1 L", SKU: "ACE-1L-VEG", Status: product.StatusActive, TaxCategory: product.TaxStandard},
			{ID: "PRD-010", Name: "Leche evaporada 400 g", SKU: "LEC-400-EVA", Status: product.StatusActive, TaxCategory: product.TaxReduced},
			{ID: "PRD-011", Name: "Quinua 1 kg", SKU: "QUI-1KG-BLA", Status: product.StatusActive, TaxCategory: product.TaxExempt},
			{ID: "PRD-012", Name: "Galleta de soda 6 un", SKU: "GAL-SOD-6UN", Status: product.StatusDiscontinued, TaxCategory: product.TaxReduced},
		},
	}
}
