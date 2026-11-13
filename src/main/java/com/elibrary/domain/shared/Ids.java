package com.elibrary.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** 不同聚合使用不同 ID 类型，避免错误地交换跨聚合引用。 */
public final class Ids {
    private Ids() {}

    public record BookId(UUID value) {
        public BookId { Objects.requireNonNull(value, "bookId"); }
        public static BookId newId() { return new BookId(UUID.randomUUID()); }
    }

    public record CopyId(UUID value) {
        public CopyId { Objects.requireNonNull(value, "copyId"); }
        public static CopyId newId() { return new CopyId(UUID.randomUUID()); }
    }

    public record ReaderId(UUID value) {
        public ReaderId { Objects.requireNonNull(value, "readerId"); }
        public static ReaderId newId() { return new ReaderId(UUID.randomUUID()); }
    }

    public record LoanId(UUID value) {
        public LoanId { Objects.requireNonNull(value, "loanId"); }
        public static LoanId newId() { return new LoanId(UUID.randomUUID()); }
    }
}
