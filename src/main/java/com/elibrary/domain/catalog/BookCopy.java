package com.elibrary.domain.catalog;

import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.ErrorCode;
import com.elibrary.domain.shared.Ids.BookId;
import com.elibrary.domain.shared.Ids.CopyId;
import com.elibrary.domain.shared.Texts.Barcode;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class BookCopy {
    private final CopyId id;
    private final Barcode barcode;
    private final BookId bookId;
    private CopyStatus status;
    private Instant withdrawnAt;

    private BookCopy(CopyId id, Barcode barcode, BookId bookId,
                     CopyStatus status, Instant withdrawnAt) {
        this.id = Objects.requireNonNull(id);
        this.barcode = Objects.requireNonNull(barcode);
        this.bookId = Objects.requireNonNull(bookId);
        this.status = Objects.requireNonNull(status);
        this.withdrawnAt = withdrawnAt;
        if (withdrawnAt != null && status != CopyStatus.AVAILABLE) {
            throw new IllegalArgumentException("已下架副本不能处于借出状态");
        }
    }

    public static BookCopy register(BookId bookId, Barcode barcode) {
        return new BookCopy(CopyId.newId(), barcode, bookId, CopyStatus.AVAILABLE, null);
    }

    public static BookCopy reconstitute(CopyId id, Barcode barcode, BookId bookId,
                                        CopyStatus status, Instant withdrawnAt) {
        return new BookCopy(id, barcode, bookId, status, withdrawnAt);
    }

    public void lendOut() {
        if (!isAvailable()) {
            throw new DomainException(ErrorCode.COPY_NOT_AVAILABLE, "副本不可借阅");
        }
        status = CopyStatus.BORROWED;
    }

    public void putBack() {
        if (isWithdrawn() || status != CopyStatus.BORROWED) {
            throw new DomainException(ErrorCode.COPY_NOT_AVAILABLE, "副本不处于借出状态");
        }
        status = CopyStatus.AVAILABLE;
    }

    void verifyCanWithdraw() {
        if (isWithdrawn()) {
            throw new DomainException(ErrorCode.COPY_ALREADY_WITHDRAWN, "副本已下架");
        }
        if (status != CopyStatus.AVAILABLE) {
            throw new DomainException(ErrorCode.COPY_NOT_AVAILABLE, "借出副本不可下架");
        }
    }

    void withdraw(Clock clock) {
        verifyCanWithdraw();
        withdrawnAt = clock.instant();
    }

    public boolean isAvailable() { return !isWithdrawn() && status == CopyStatus.AVAILABLE; }
    public boolean isWithdrawn() { return withdrawnAt != null; }
    public CopyId id() { return id; }
    public Barcode barcode() { return barcode; }
    public BookId bookId() { return bookId; }
    public CopyStatus status() { return status; }
    public Instant withdrawnAt() { return withdrawnAt; }
}
