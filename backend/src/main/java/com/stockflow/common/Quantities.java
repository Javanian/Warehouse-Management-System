package com.stockflow.common;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;

public final class Quantities {

    public static final BigDecimal MAX = new BigDecimal("999999999999.999");

    private Quantities() {}

    public static void requireScale(BigDecimal qty, int scale, String uom, String field) {
        if (qty == null) {
            throw ApiException.field(field, "quantity is required");
        }
        if (qty.compareTo(MAX) > 0) {
            throw ApiException.field(field, "quantity too large");
        }
        if (qty.stripTrailingZeros().scale() > scale) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "QUANTITY_SCALE_INVALID",
                    "Quantity " + qty.toPlainString() + " has more decimals than " + uom + " allows (" + scale + ")",
                    java.util.List.of(new ErrorResponse.FieldError(field, "max " + scale + " decimals for " + uom)),
                    Map.of());
        }
    }

    public static void requirePositive(BigDecimal qty, String field) {
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.field(field, "quantity must be greater than 0");
        }
    }

    public static String fmt(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }
}
