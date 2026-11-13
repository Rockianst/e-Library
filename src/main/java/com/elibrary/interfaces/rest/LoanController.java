package com.elibrary.interfaces.rest;

import com.elibrary.application.LibraryService;
import com.elibrary.application.Views.LoanView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/loans")
public class LoanController {
    private final LibraryService library;

    public LoanController(LibraryService library) { this.library = library; }

    @PostMapping
    public ResponseEntity<LoanView> borrow(@RequestHeader("X-Reader-Id") UUID readerId,
                                          @Valid @RequestBody Requests.BorrowBook request) {
        return ResponseEntity.status(201).body(library.borrow(readerId, request.bookId(), request.copyId()));
    }

    @PostMapping("/{loanId}/return")
    public LoanView returnLoan(@RequestHeader("X-Reader-Id") UUID readerId, @PathVariable UUID loanId) {
        return library.returnLoan(readerId, loanId);
    }

    @PostMapping("/{loanId}/renewal")
    public LoanView renewLoan(@RequestHeader("X-Reader-Id") UUID readerId, @PathVariable UUID loanId) {
        return library.renewLoan(readerId, loanId);
    }
}
