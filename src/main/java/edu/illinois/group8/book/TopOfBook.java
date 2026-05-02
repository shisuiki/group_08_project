package edu.illinois.group8.book;

public record TopOfBook(
    long bidPriceMicros,
    long bidQuantityMicros,
    long askPriceMicros,
    long askQuantityMicros,
    boolean crossed
) {
}
