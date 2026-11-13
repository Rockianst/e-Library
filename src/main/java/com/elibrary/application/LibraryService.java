package com.elibrary.application;

import com.elibrary.application.Views.*;
import com.elibrary.domain.catalog.*;
import com.elibrary.domain.catalog.CopyRepository.Inventory;
import com.elibrary.domain.identity.Reader;
import com.elibrary.domain.identity.ReaderRepository;
import com.elibrary.domain.lending.*;
import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.Ids.*;
import com.elibrary.domain.shared.PageResult;
import com.elibrary.domain.shared.Texts.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.elibrary.domain.shared.ErrorCode.*;

/** 应用层负责事务、仓储协调和锁顺序，业务状态流转委托领域对象。 */
@Service
@Transactional(readOnly = true)
public class LibraryService {
    private final BookRepository books;
    private final CopyRepository copies;
    private final ReaderRepository readers;
    private final LoanRepository loans;
    private final Clock clock;
    private final LoanPeriod loanPeriod;

    public LibraryService(BookRepository books, CopyRepository copies, ReaderRepository readers,
                          LoanRepository loans, Clock clock, LoanPeriod loanPeriod) {
        this.books = books;
        this.copies = copies;
        this.readers = readers;
        this.loans = loans;
        this.clock = clock;
        this.loanPeriod = loanPeriod;
    }

    @Transactional
    public ReaderView registerReader(String name) {
        Reader reader = Reader.register(new Name(name));
        readers.save(reader);
        return ReaderView.from(reader);
    }

    @Transactional
    public BookDetailView registerBook(String title, String author, String category, List<String> barcodes) {
        if (barcodes == null || barcodes.isEmpty()) {
            throw new DomainException(INVALID_REQUEST, "入库时至少登记一个副本");
        }
        List<Barcode> values = barcodes.stream().map(Barcode::new).toList();
        if (new HashSet<>(values).size() != values.size()) {
            throw new DomainException(BARCODE_ALREADY_EXISTS, "条码不能重复");
        }
        values.forEach(this::verifyBarcodeUnused);
        Book book = new Book(BookId.newId(), new Title(title), new Author(author), new Category(category));
        books.save(book);
        List<BookCopy> registered = values.stream().map(book::registerCopy).toList();
        registered.forEach(copies::save);
        return BookDetailView.from(book, new Inventory(registered.size(), registered.size()), registered);
    }

    public PageResult<BookView> browseBooks(String query, String category, int page, int size) {
        verifyPage(page, size);
        PageResult<Book> result = books.search(normalize(query), normalize(category), page, size);
        var inventory = copies.inventory(result.content().stream().map(Book::id).toList());
        return result.map(book -> BookView.from(book, inventory.getOrDefault(book.id(), new Inventory(0, 0))));
    }

    public BookDetailView getBook(UUID bookId) {
        Book book = requireBook(new BookId(bookId));
        List<BookCopy> currentCopies = copies.findByBook(book.id());
        return BookDetailView.from(book, new Inventory(currentCopies.size(),
                currentCopies.stream().filter(BookCopy::isAvailable).count()), currentCopies);
    }

    @Transactional
    public CopyView registerCopy(UUID bookId, String barcode) {
        Book book = books.lockById(new BookId(bookId))
                .orElseThrow(() -> new DomainException(BOOK_NOT_FOUND, "书目不存在"));
        Barcode value = new Barcode(barcode);
        verifyBarcodeUnused(value);
        BookCopy copy = book.registerCopy(value);
        copies.save(copy);
        return CopyView.from(copy);
    }

    @Transactional
    public void withdrawCopy(UUID bookId, UUID copyId) {
        // 同一书目的入库、下架串行，避免并发下架最后两个副本。
        Book book = books.lockById(new BookId(bookId))
                .orElseThrow(() -> new DomainException(BOOK_NOT_FOUND, "书目不存在"));
        BookCopy copy = copies.lockById(new CopyId(copyId))
                .orElseThrow(() -> new DomainException(COPY_NOT_FOUND, "副本不存在"));
        book.withdrawCopy(copy, copies.findByBook(book.id()).size(), clock);
        copies.save(copy);
    }

    @Transactional
    public LoanView borrow(UUID readerId, UUID bookId, UUID copyId) {
        Book book = requireBook(new BookId(bookId));
        // 先锁读者再统计，保证多个并发借阅请求也遵守限额。
        Reader reader = lockReader(new ReaderId(readerId));
        reader.verifyCanBorrow(loans.countActive(reader.id()));
        BookCopy copy = copyId == null
                ? copies.lockAvailableCopy(book.id())
                    .orElseThrow(() -> new DomainException(COPY_NOT_AVAILABLE, "该书目没有可借副本"))
                : copies.lockById(new CopyId(copyId))
                    .orElseThrow(() -> new DomainException(COPY_NOT_FOUND, "副本不存在"));
        if (!book.id().equals(copy.bookId())) {
            throw new DomainException(COPY_BOOK_MISMATCH, "副本不属于指定书目");
        }
        copy.lendOut();
        Loan loan = Loan.borrow(reader.id(), copy.id(), clock, loanPeriod);
        copies.save(copy);
        loans.save(loan);
        return LoanView.from(loan, clock);
    }

    @Transactional
    public LoanView returnLoan(UUID readerId, UUID loanId) {
        Loan loan = lockOwnedLoan(readerId, loanId);
        BookCopy copy = copies.lockById(loan.copyId())
                .orElseThrow(() -> new DomainException(COPY_NOT_FOUND, "副本不存在"));
        loan.returnBack(clock);
        copy.putBack();
        loans.save(loan);
        copies.save(copy);
        return LoanView.from(loan, clock);
    }

    @Transactional
    public LoanView renewLoan(UUID readerId, UUID loanId) {
        Loan loan = lockOwnedLoan(readerId, loanId);
        loan.renew(loanPeriod, clock);
        loans.save(loan);
        return LoanView.from(loan, clock);
    }

    public PageResult<LoanView> currentLoans(UUID readerId, String status, int page, int size) {
        verifyPage(page, size);
        ReaderId id = new ReaderId(readerId);
        readers.findById(id).orElseThrow(() -> new DomainException(READER_NOT_FOUND, "读者不存在"));
        String filter = normalize(status).toLowerCase(Locale.ROOT);
        LoanStatus storedStatus = switch (filter) {
            case "all", "" -> null;
            case "active", "overdue" -> LoanStatus.ACTIVE;
            case "returned" -> LoanStatus.RETURNED;
            default -> throw new DomainException(INVALID_REQUEST, "status 只能为 all、active、overdue、returned");
        };
        LocalDate overdueBefore = filter.equals("overdue") ? LocalDate.now(clock) : null;
        return loans.findByReader(id, storedStatus, overdueBefore, page, size)
                .map(loan -> LoanView.from(loan, clock));
    }

    private Loan lockOwnedLoan(UUID readerId, UUID loanId) {
        Reader reader = lockReader(new ReaderId(readerId));
        Loan loan = loans.lockById(new LoanId(loanId))
                .orElseThrow(() -> new DomainException(LOAN_NOT_FOUND, "借阅记录不存在"));
        if (!reader.id().equals(loan.readerId())) {
            throw new DomainException(FORBIDDEN, "不能操作其他读者的借阅记录");
        }
        return loan;
    }

    private Reader lockReader(ReaderId id) {
        return readers.lockById(id).orElseThrow(() -> new DomainException(READER_NOT_FOUND, "读者不存在"));
    }

    private Book requireBook(BookId id) {
        return books.findById(id).orElseThrow(() -> new DomainException(BOOK_NOT_FOUND, "书目不存在"));
    }

    private void verifyBarcodeUnused(Barcode barcode) {
        if (copies.existsByBarcode(barcode)) {
            throw new DomainException(BARCODE_ALREADY_EXISTS, "条码已存在：" + barcode.value());
        }
    }

    private static void verifyPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size) {
            throw new DomainException(INVALID_REQUEST, "page 必须非负，size 必须在 1 到 100 之间");
        }
    }

    private static String normalize(String value) { return value == null ? "" : value.strip(); }
}
