package com.elibrary.domain.lending;

public record LoanPeriod(int days) {
    public LoanPeriod {
        if (days <= 0 || days > 3650) throw new IllegalArgumentException("借期必须在 1 到 3650 天之间");
    }
}
