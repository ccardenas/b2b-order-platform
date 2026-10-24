package com.mariposa.b2b.orderprocessor.domain.validation;

import com.mariposa.b2b.orderprocessor.domain.model.ValidatedOrder;

public sealed interface ValidationResult {

    record Valid(ValidatedOrder order) implements ValidationResult {
    }

    record Invalid(ValidationFailure failure) implements ValidationResult {
    }
}
