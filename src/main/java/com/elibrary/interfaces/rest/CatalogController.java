package com.elibrary.interfaces.rest;

import com.elibrary.application.LibraryService;
import com.elibrary.application.Views.*;
import com.elibrary.domain.shared.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/books")
@Validated
public class CatalogController {
    private final LibraryService library;

    public CatalogController(LibraryService library) { this.library = library; }

    @GetMapping
    public PageResult<BookView> browse(@RequestParam(defaultValue = "") @Size(max = 200) String q,
                                       @RequestParam(defaultValue = "") @Size(max = 100) String category,
                                       @RequestParam(defaultValue = "0") @Min(0) int page,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return library.browseBooks(q, category, page, size);
    }

    @GetMapping("/{bookId}")
    public BookDetailView detail(@PathVariable UUID bookId) { return library.getBook(bookId); }

    @PostMapping
    public ResponseEntity<BookDetailView> register(@Valid @RequestBody Requests.RegisterBook request) {
        var book = library.registerBook(request.title(), request.author(), request.category(), request.barcodes());
        return ResponseEntity.created(URI.create("/books/" + book.id())).body(book);
    }

    @PostMapping("/{bookId}/copies")
    public ResponseEntity<CopyView> registerCopy(@PathVariable UUID bookId,
                                                @Valid @RequestBody Requests.RegisterCopy request) {
        return ResponseEntity.status(201).body(library.registerCopy(bookId, request.barcode()));
    }

    @DeleteMapping("/{bookId}/copies/{copyId}")
    public ResponseEntity<Void> withdraw(@PathVariable UUID bookId, @PathVariable UUID copyId) {
        library.withdrawCopy(bookId, copyId);
        return ResponseEntity.noContent().build();
    }
}
