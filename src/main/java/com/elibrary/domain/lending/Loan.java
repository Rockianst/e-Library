package com.elibrary.domain.lending;

import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.ErrorCode;
import com.elibrary.domain.shared.Ids.CopyId;
import com.elibrary.domain.shared.Ids.LoanId;
import com.elibrary.domain.shared.Ids.ReaderId;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public final class Loan {
    private final LoanId id;
    private final ReaderId readerId;
    private final CopyId copyId;
    private final Instant borrowedAt;
    private LocalDate dueDate;
    private Instant returnedAt;
    private LoanStatus status;
    private int renewCount;

    private Loan(LoanId id, ReaderId readerId, CopyId copyId, Instant borrowedAt,
                 LocalDate dueDate, Instant returnedAt, LoanStatus status, int renewCount) {
        this.id = Objects.requireNonNull(id);
        this.readerId = Objects.requireNonNull(readerId);
        this.copyId = Objects.requireNonNull(copyId);
        this.borrowedAt = Objects.requireNonNull(borrowedAt);
        this.dueDate = Objects.requireNonNull(dueDate);
        this.status = Objects.requireNonNull(status);
        this.returnedAt = returnedAt;
        this.renewCount = renewCount;
        if (renewCount < 0 || renewCount > 1 ||
                (status == LoanStatus.RETURNED) != (returnedAt != null) ||
                (returnedAt != null && returnedAt.isBefore(borrowedAt))) {
            throw new IllegalArgumentException("无效借阅状态");
        }
    }

    public static Loan borrow(ReaderId readerId, CopyId copyId, Clock clock, LoanPeriod period) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(clock.getZone()).toLocalDate();
        return new Loan(LoanId.newId(), readerId, copyId, now,
                today.plusDays(period.days()), null, LoanStatus.ACTIVE, 0);
    }

    public static Loan reconstitute(LoanId id, ReaderId readerId, CopyId copyId,
                                    Instant borrowedAt, LocalDate dueDate, Instant returnedAt,
                                    LoanStatus status, int renewCount) {
        return new Loan(id, readerId, copyId, borrowedAt, dueDate, returnedAt, status, renewCount);
    }

    public void renew(LoanPeriod period, Clock clock) {
        verifyActive();
        if (renewCount >= 1) {
            throw new DomainException(ErrorCode.RENEWAL_NOT_ALLOWED, "已续借过一次");
        }
        if (isOverdue(clock)) {
            throw new DomainException(ErrorCode.RENEWAL_NOT_ALLOWED, "逾期借阅不可续借");
        }
        dueDate = dueDate.plusDays(period.days());
        renewCount++;
    }

    public void returnBack(Clock clock) {
        verifyActive();
        Instant now = clock.instant();
        if (now.isBefore(borrowedAt)) throw new IllegalArgumentException("归还时间早于借出时间");
        returnedAt = now;
        status = LoanStatus.RETURNED;
    }

    public boolean isOverdue(Clock clock) {
        return status == LoanStatus.ACTIVE && LocalDate.now(clock).isAfter(dueDate);
    }

    /** 归还后仍可追溯是否曾逾期，以及原到期日和实际归还时间。 */
    public boolean wasReturnedLate(Clock clock) {
        return returnedAt != null && returnedAt.atZone(clock.getZone()).toLocalDate().isAfter(dueDate);
    }

    private void verifyActive() {
        if (status != LoanStatus.ACTIVE) {
            throw new DomainException(ErrorCode.LOAN_NOT_ACTIVE, "借阅记录已归还");
        }
    }

    public LoanId id() { return id; }
    public ReaderId readerId() { return readerId; }
    public CopyId copyId() { return copyId; }
    public Instant borrowedAt() { return borrowedAt; }
    public LocalDate dueDate() { return dueDate; }
    public Instant returnedAt() { return returnedAt; }
    public LoanStatus status() { return status; }
    public int renewCount() { return renewCount; }
}
