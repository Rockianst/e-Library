package com.elibrary.domain.identity;

import com.elibrary.domain.shared.Ids.ReaderId;

import java.util.Optional;

public interface ReaderRepository {
    Optional<Reader> findById(ReaderId id);
    Optional<Reader> lockById(ReaderId id);
    void save(Reader reader);
}
