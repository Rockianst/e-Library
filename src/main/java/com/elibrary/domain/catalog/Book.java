package com.elibrary.domain.catalog;

import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.ErrorCode;
import com.elibrary.domain.shared.Ids.BookId;
import com.elibrary.domain.shared.Texts.Author;
import com.elibrary.domain.shared.Texts.Barcode;
import com.elibrary.domain.shared.Texts.Category;
import com.elibrary.domain.shared.Texts.Title;

import java.time.Clock;
import java.util.Objects;

/** 书目聚合：不持有库存计数，也不持有其他聚合实体。 */
public final class Book {
    private final BookId id;
    private final Title title;
    private final Author author;
    private final Category category;

    public Book(BookId id, Title title, Author author, Category category) {
        this.id = Objects.requireNonNull(id);
        this.title = Objects.requireNonNull(title);
        this.author = Objects.requireNonNull(author);
        this.category = Objects.requireNonNull(category);
    }

    public BookCopy registerCopy(Barcode barcode) {
        return BookCopy.register(id, barcode);
    }

    /** 传入副本才能核验所属书目和当前状态；不通过 ID 隐式访问仓储。 */
    public void withdrawCopy(BookCopy copy, int currentCopyCount, Clock clock) {
        if (!id.equals(copy.bookId())) {
            throw new DomainException(ErrorCode.COPY_BOOK_MISMATCH, "副本不属于该书目");
        }
        copy.verifyCanWithdraw();
        if (currentCopyCount <= 1) {
            throw new DomainException(ErrorCode.LAST_COPY_CANNOT_BE_WITHDRAWN, "馆藏至少保留一个副本");
        }
        copy.withdraw(clock);
    }

    public BookId id() { return id; }
    public Title title() { return title; }
    public Author author() { return author; }
    public Category category() { return category; }
}
