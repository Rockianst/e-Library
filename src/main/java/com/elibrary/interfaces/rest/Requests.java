package com.elibrary.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class Requests {
    private Requests() {}

    public record RegisterBook(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 100) String author,
            @NotBlank @Size(max = 100) String category,
            @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 100) String> barcodes) {}

    public record RegisterCopy(@NotBlank @Size(max = 100) String barcode) {}

    public record RegisterReader(@NotBlank @Size(max = 100) String name) {}

    public record BorrowBook(@NotNull UUID bookId, UUID copyId) {}
}
