package com.elibrary.domain;

import com.elibrary.domain.catalog.Book;
import com.elibrary.domain.catalog.BookCopy;
import com.elibrary.domain.catalog.CopyStatus;
import com.elibrary.domain.identity.Reader;
import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.ErrorCode;
import com.elibrary.domain.shared.Ids.BookId;
import com.elibrary.domain.shared.Texts.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.*;

class CatalogAndReaderTest {
    private final Clock clock = Clock.systemUTC();

    @Test
    void newCopyBelongsToBookAndCanOnlyBeLentOnceUntilReturned() {
        Book book = book();
        BookCopy copy = book.registerCopy(new Barcode("COPY-1"));
        assertThat(copy.bookId()).isEqualTo(book.id());
        assertThat(copy.isAvailable()).isTrue();
        copy.lendOut();
        assertThat(copy.status()).isEqualTo(CopyStatus.BORROWED);
        expect(ErrorCode.COPY_NOT_AVAILABLE, copy::lendOut);
        copy.putBack();
        assertThat(copy.isAvailable()).isTrue();
        expect(ErrorCode.COPY_NOT_AVAILABLE, copy::putBack);
    }

    @Test
    void borrowedCopyCannotBeWithdrawn() {
        Book book = book();
        BookCopy copy = book.registerCopy(new Barcode("COPY-1"));
        copy.lendOut();
        expect(ErrorCode.COPY_NOT_AVAILABLE, () -> book.withdrawCopy(copy, 2, clock));
    }

    @Test
    void withdrawalKeepsIdentityAndPreventsFutureBorrowing() {
        Book book = book();
        BookCopy copy = book.registerCopy(new Barcode("COPY-1"));
        var id = copy.id();
        book.withdrawCopy(copy, 2, clock);
        assertThat(copy.isWithdrawn()).isTrue();
        assertThat(copy.id()).isEqualTo(id);
        assertThat(copy.isAvailable()).isFalse();
        expect(ErrorCode.COPY_NOT_AVAILABLE, copy::lendOut);
        expect(ErrorCode.COPY_ALREADY_WITHDRAWN, () -> book.withdrawCopy(copy, 2, clock));
    }

    @Test
    void lastCopyAndOtherBooksCopyCannotBeWithdrawn() {
        Book book = book();
        BookCopy copy = book.registerCopy(new Barcode("COPY-1"));
        expect(ErrorCode.LAST_COPY_CANNOT_BE_WITHDRAWN, () -> book.withdrawCopy(copy, 1, clock));
        expect(ErrorCode.COPY_BOOK_MISMATCH, () -> book().withdrawCopy(copy, 2, clock));
    }

    @Test
    void readerOwnsDefaultFiveLoanConstraint() {
        Reader reader = Reader.register(new Name("张三"));
        assertThat(reader.maxActiveLoans()).isEqualTo(5);
        reader.verifyCanBorrow(4);
        expect(ErrorCode.LOAN_LIMIT_EXCEEDED, () -> reader.verifyCanBorrow(5));
        expect(ErrorCode.LOAN_LIMIT_EXCEEDED, () -> reader.verifyCanBorrow(6));
    }

    @Test
    void textValuesValidateAndNormalizeAtDomainBoundary() {
        assertThat(new Barcode("  COPY-1  ").value()).isEqualTo("COPY-1");
        expect(ErrorCode.INVALID_REQUEST, () -> new Title("  "));
        expect(ErrorCode.INVALID_REQUEST, () -> new Name(null));
        expect(ErrorCode.INVALID_REQUEST, () -> new Author("x".repeat(101)));
    }

    private static Book book() {
        return new Book(BookId.newId(), new Title("领域驱动设计"), new Author("Eric Evans"), new Category("技术"));
    }

    private static void expect(ErrorCode code, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
