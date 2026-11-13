package com.elibrary.domain.catalog;

import com.elibrary.domain.shared.Ids.BookId;
import com.elibrary.domain.shared.Ids.CopyId;
import com.elibrary.domain.shared.Texts.Barcode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CopyRepository {
    record Inventory(long totalCopies, long availableCopies) {}

    Optional<BookCopy> findById(CopyId id);
    Optional<BookCopy> lockById(CopyId id);
    Optional<BookCopy> lockAvailableCopy(BookId bookId);
    List<BookCopy> findByBook(BookId bookId);
    Map<BookId, Inventory> inventory(List<BookId> bookIds);
    boolean existsByBarcode(Barcode barcode);
    void save(BookCopy copy);
}
