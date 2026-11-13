package com.elibrary.domain;

import com.elibrary.domain.lending.Loan;
import com.elibrary.domain.lending.LoanPeriod;
import com.elibrary.domain.lending.LoanStatus;
import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.ErrorCode;
import com.elibrary.domain.shared.Ids.CopyId;
import com.elibrary.domain.shared.Ids.ReaderId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.*;

class LoanTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = at("2026-11-12T16:30:00Z");
    private static final LoanPeriod PERIOD = new LoanPeriod(30);

    @Test
    void borrowUsesBusinessDateAndStartsActive() {
        Loan loan = loan();
        assertThat(loan.borrowedAt()).isEqualTo(CLOCK.instant());
        assertThat(loan.dueDate()).isEqualTo(LocalDate.of(2026, 12, 13));
        assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(loan.renewCount()).isZero();
        assertThat(loan.returnedAt()).isNull();
    }

    @Test
    void renewalExtendsOriginalDueDateAndOnlyOnce() {
        Loan loan = loan();
        loan.renew(PERIOD, CLOCK);
        assertThat(loan.dueDate()).isEqualTo(LocalDate.of(2027, 1, 12));
        assertThat(loan.renewCount()).isEqualTo(1);
        expect(ErrorCode.RENEWAL_NOT_ALLOWED, () -> loan.renew(PERIOD, CLOCK));
    }

    @Test
    void renewalIsAllowedUntilEndOfDueDate() {
        Loan loan = loan();
        Clock dueDateEnd = at("2026-12-13T15:59:59Z");
        assertThat(loan.isOverdue(dueDateEnd)).isFalse();
        loan.renew(PERIOD, dueDateEnd);
        assertThat(loan.dueDate()).isEqualTo(LocalDate.of(2027, 1, 12));
    }

    @Test
    void overdueIsDerivedAtLocalMidnightAndBlocksRenewal() {
        Loan loan = loan();
        Clock nextDay = at("2026-12-13T16:00:00Z");
        assertThat(loan.isOverdue(nextDay)).isTrue();
        assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
        expect(ErrorCode.RENEWAL_NOT_ALLOWED, () -> loan.renew(PERIOD, nextDay));
    }

    @Test
    void returnedLoanRetainsFactsAndRejectsFurtherTransitions() {
        Loan loan = loan();
        Clock later = at("2026-11-14T02:00:00Z");
        loan.returnBack(later);
        assertThat(loan.status()).isEqualTo(LoanStatus.RETURNED);
        assertThat(loan.returnedAt()).isEqualTo(later.instant());
        assertThat(loan.borrowedAt()).isEqualTo(CLOCK.instant());
        assertThat(loan.wasReturnedLate(later)).isFalse();
        expect(ErrorCode.LOAN_NOT_ACTIVE, () -> loan.returnBack(later));
        expect(ErrorCode.LOAN_NOT_ACTIVE, () -> loan.renew(PERIOD, later));
    }

    @Test
    void lateReturnRetainsOverdueEvidenceWithoutPersistingOverdueStatus() {
        Loan loan = loan();
        Clock late = at("2026-12-14T03:00:00Z");
        loan.returnBack(late);
        assertThat(loan.wasReturnedLate(late)).isTrue();
        assertThat(loan.isOverdue(late)).isFalse();
        assertThat(loan.dueDate()).isEqualTo(LocalDate.of(2026, 12, 13));
        assertThat(loan.status()).isEqualTo(LoanStatus.RETURNED);
    }

    @Test
    void loanPeriodMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LoanPeriod(0));
    }

    private static Loan loan() { return Loan.borrow(ReaderId.newId(), CopyId.newId(), CLOCK, PERIOD); }
    private static Clock at(String instant) { return Clock.fixed(Instant.parse(instant), ZONE); }

    private static void expect(ErrorCode code, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
