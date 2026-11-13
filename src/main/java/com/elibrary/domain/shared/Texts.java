package com.elibrary.domain.shared;

public final class Texts {
    private Texts() {}

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new DomainException(ErrorCode.INVALID_REQUEST,
                    field + " 必须非空且长度不超过 " + maxLength);
        }
        return value.strip();
    }

    public record Title(String value) {
        public Title { value = required(value, "title", 200); }
    }

    public record Author(String value) {
        public Author { value = required(value, "author", 100); }
    }

    public record Category(String value) {
        public Category { value = required(value, "category", 100); }
    }

    public record Barcode(String value) {
        public Barcode { value = required(value, "barcode", 100); }
    }

    public record Name(String value) {
        public Name { value = required(value, "name", 100); }
    }
}
