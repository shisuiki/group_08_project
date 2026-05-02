package edu.illinois.group8.canonical;

public record PriceLevel(
    long priceMicros,
    long quantityMicros,
    String sourcePrice,
    String sourceQuantity
) {
}
