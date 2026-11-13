package com.elibrary.interfaces.rest;

import com.elibrary.application.LibraryService;
import com.elibrary.application.Views.LoanView;
import com.elibrary.application.Views.ReaderView;
import com.elibrary.domain.shared.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/readers")
@Validated
public class ReaderController {
    private final LibraryService library;

    public ReaderController(LibraryService library) { this.library = library; }

    @PostMapping
    public ResponseEntity<ReaderView> register(@Valid @RequestBody Requests.RegisterReader request) {
        return ResponseEntity.status(201).body(library.registerReader(request.name()));
    }

    @GetMapping("/me/loans")
    public PageResult<LoanView> loans(@RequestHeader("X-Reader-Id") UUID readerId,
                                      @RequestParam(defaultValue = "active") String status,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return library.currentLoans(readerId, status, page, size);
    }
}
