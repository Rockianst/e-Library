package com.elibrary.application;

import com.elibrary.domain.catalog.Book;
import com.elibrary.domain.catalog.BookCopy;
import com.elibrary.domain.catalog.CopyRepository.Inventory;
import com.elibrary.domain.identity.Reader;
import com.elibrary.domain.lending.Loan;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class Views {
    private Views() {}

    public record BookView(UUID id, String title, String author, String category,
                           long totalCopies, long availableCopies) {
        static BookView from(Book book, Inventory inventory) {
            return new BookView(book.id().value(), book.title().value(), book.author().value(),
                    book.category().value(), inventory.totalCopies(), inventory.availableCopies());
        }
    }

    public record BookDetailView(UUID id, String title, String author, String category,
                                 long totalCopies, long availableCopies, List<CopyView> copies) {
        static BookDetailView from(Book book, Inventory inventory, List<BookCopy> copies) {
            return new BookDetailView(book.id().value(), book.title().value(), book.author().value(),
                    book.category().value(), inventory.totalCopies(), inventory.availableCopies(),
                    copies.stream().map(CopyView::from).toList());
        }
    }

    public record CopyView(UUID id, UUID bookId, String barcode, String status, Instant withdrawnAt) {
        static CopyView from(BookCopy copy) {
            return new CopyView(copy.id().value(), copy.bookId().value(), copy.barcode().value(),
                    copy.status().name(), copy.withdrawnAt());
        }
    }

    public record ReaderView(UUID id, String name, int maxActiveLoans) {
        static ReaderView from(Reader reader) {
            return new ReaderView(reader.id().value(), reader.name().value(), reader.maxActiveLoans());
        }
    }

    public record LoanView(UUID id, UUID readerId, UUID copyId, Instant borrowedAt,
                           LocalDate dueDate, Instant returnedAt, String status, int renewCount,
                           boolean overdue, boolean returnedLate) {
        static LoanView from(Loan loan, Clock clock) {
            boolean overdue = loan.isOverdue(clock);
            return new LoanView(loan.id().value(), loan.readerId().value(), loan.copyId().value(),
                    loan.borrowedAt(), loan.dueDate(), loan.returnedAt(),
                    overdue ? "OVERDUE" : loan.status().name(), loan.renewCount(), overdue,
                    loan.wasReturnedLate(clock));
        }
    }
}
