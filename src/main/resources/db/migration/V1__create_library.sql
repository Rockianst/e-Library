CREATE TABLE books (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    author VARCHAR(100) NOT NULL,
    category VARCHAR(100) NOT NULL
);

CREATE TABLE readers (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    max_active_loans INTEGER NOT NULL CHECK (max_active_loans > 0)
);

CREATE TABLE book_copies (
    id UUID PRIMARY KEY,
    barcode VARCHAR(100) NOT NULL,
    book_id UUID NOT NULL REFERENCES books(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('AVAILABLE', 'BORROWED')),
    withdrawn_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL,
    CONSTRAINT uk_copy_barcode UNIQUE (barcode),
    CONSTRAINT ck_withdrawn_copy CHECK (withdrawn_at IS NULL OR status = 'AVAILABLE')
);
CREATE INDEX idx_copy_book_status ON book_copies(book_id, status, withdrawn_at);

CREATE TABLE loans (
    id UUID PRIMARY KEY,
    reader_id UUID NOT NULL REFERENCES readers(id),
    copy_id UUID NOT NULL REFERENCES book_copies(id),
    borrowed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    due_date DATE NOT NULL,
    returned_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'RETURNED')),
    renew_count INTEGER NOT NULL CHECK (renew_count BETWEEN 0 AND 1),
    version BIGINT NOT NULL,
    CONSTRAINT ck_loan_return CHECK (
        (status = 'ACTIVE' AND returned_at IS NULL) OR
        (status = 'RETURNED' AND returned_at IS NOT NULL AND returned_at >= borrowed_at)
    )
);
CREATE INDEX idx_loan_reader_status ON loans(reader_id, status, borrowed_at);
CREATE INDEX idx_loan_copy ON loans(copy_id);

-- One row per currently borrowed copy: the primary key is a database-level
-- backstop against two ACTIVE loans for the same copy. History remains in loans.
CREATE TABLE active_copy_loans (
    copy_id UUID PRIMARY KEY REFERENCES book_copies(id),
    loan_id UUID NOT NULL UNIQUE REFERENCES loans(id)
);
