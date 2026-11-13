package com.elibrary.domain.lending;

import com.elibrary.domain.shared.Ids.LoanId;
import com.elibrary.domain.shared.Ids.ReaderId;
import com.elibrary.domain.shared.PageResult;

import java.time.LocalDate;
import java.util.Optional;

public interface LoanRepository {
    Optional<Loan> findById(LoanId id);
    Optional<Loan> lockById(LoanId id);
    long countActive(ReaderId readerId);
    PageResult<Loan> findByReader(ReaderId readerId, LoanStatus status,
                                 LocalDate overdueBefore, int page, int size);
    void save(Loan loan);
}
