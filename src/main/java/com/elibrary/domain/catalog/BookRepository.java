package com.elibrary.domain.catalog;

import com.elibrary.domain.shared.Ids.BookId;
import com.elibrary.domain.shared.PageResult;

import java.util.Optional;

public interface BookRepository {
    Optional<Book> findById(BookId id);
    Optional<Book> lockById(BookId id);
    PageResult<Book> search(String query, String category, int page, int size);
    void save(Book book);
}
