package com.elibrary.domain.identity;

import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.ErrorCode;
import com.elibrary.domain.shared.Ids.ReaderId;
import com.elibrary.domain.shared.Texts.Name;

import java.util.Objects;

public final class Reader {
    public static final int DEFAULT_MAX_ACTIVE_LOANS = 5;
    private final ReaderId id;
    private final Name name;
    private final int maxActiveLoans;

    public Reader(ReaderId id, Name name, int maxActiveLoans) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        if (maxActiveLoans <= 0) throw new IllegalArgumentException("借阅限额必须为正数");
        this.maxActiveLoans = maxActiveLoans;
    }

    public static Reader register(Name name) {
        return new Reader(ReaderId.newId(), name, DEFAULT_MAX_ACTIVE_LOANS);
    }

    public void verifyCanBorrow(long currentActiveLoans) {
        if (currentActiveLoans < 0) throw new IllegalArgumentException("借阅数不能为负数");
        if (currentActiveLoans >= maxActiveLoans) {
            throw new DomainException(ErrorCode.LOAN_LIMIT_EXCEEDED,
                    "读者当前最多可借阅 " + maxActiveLoans + " 本书");
        }
    }

    public ReaderId id() { return id; }
    public Name name() { return name; }
    public int maxActiveLoans() { return maxActiveLoans; }
}
