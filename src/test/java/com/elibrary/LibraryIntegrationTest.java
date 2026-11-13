package com.elibrary;

import com.elibrary.application.LibraryService;
import com.elibrary.application.Views.BookDetailView;
import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LibraryIntegrationTest.TimeConfiguration.class)
class LibraryIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-13T02:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LibraryService library;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @BeforeEach
    void reset() {
        for (String table : List.of("active_copy_loans", "loans", "book_copies", "books", "readers")) {
            jdbc.update("delete from " + table);
        }
        clock.set(START);
    }

    @Test
    void fullBorrowRenewReturnFlowKeepsHistoryAndInventory() throws Exception {
        UUID reader = reader();
        BookDetailView book = book(2);
        JsonNode loan = body(perform(post("/loans").header("X-Reader-Id", reader), Map.of("bookId", book.id()))
                .andExpect(status().isCreated()).andExpect(jsonPath("dueDate").value("2026-12-13")));
        String loanId = loan.get("id").asText();
        mvc.perform(get("/books/{id}", book.id())).andExpect(status().isOk())
                .andExpect(jsonPath("totalCopies").value(2)).andExpect(jsonPath("availableCopies").value(1));
        mvc.perform(post("/loans/{id}/renewal", loanId).header("X-Reader-Id", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("dueDate").value("2027-01-12"))
                .andExpect(jsonPath("renewCount").value(1));
        expectError(mvc.perform(post("/loans/{id}/renewal", loanId).header("X-Reader-Id", reader)),
                409, "RENEWAL_NOT_ALLOWED");
        mvc.perform(post("/loans/{id}/return", loanId).header("X-Reader-Id", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("RETURNED"))
                .andExpect(jsonPath("returnedAt").isNotEmpty());
        expectError(mvc.perform(post("/loans/{id}/return", loanId).header("X-Reader-Id", reader)),
                409, "LOAN_NOT_ACTIVE");
        mvc.perform(get("/books/{id}", book.id())).andExpect(jsonPath("availableCopies").value(2));
        mvc.perform(get("/readers/me/loans").header("X-Reader-Id", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(0));
        mvc.perform(get("/readers/me/loans").param("status", "returned").header("X-Reader-Id", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("content[0].id").value(loanId))
                .andExpect(jsonPath("totalElements").value(1));
        assertThat(jdbc.queryForObject("select count(*) from active_copy_loans", Long.class)).isZero();
    }

    @Test
    void overdueFilterDerivesStatusAndLateReturnIsTraceable() throws Exception {
        UUID reader = reader();
        BookDetailView book = book(1);
        var loan = library.borrow(reader, book.id(), null);
        clock.set(Instant.parse("2026-12-13T16:00:00Z"));
        mvc.perform(get("/readers/me/loans").header("X-Reader-Id", reader).param("status", "overdue"))
                .andExpect(status().isOk()).andExpect(jsonPath("content[0].status").value("OVERDUE"));
        mvc.perform(get("/readers/me/loans").header("X-Reader-Id", reader).param("status", "active"))
                .andExpect(jsonPath("totalElements").value(1));
        assertThat(jdbc.queryForObject("select status from loans where id = ?", String.class, loan.id()))
                .isEqualTo("ACTIVE");
        expectError(mvc.perform(post("/loans/{id}/renewal", loan.id()).header("X-Reader-Id", reader)),
                409, "RENEWAL_NOT_ALLOWED");
        mvc.perform(post("/loans/{id}/return", loan.id()).header("X-Reader-Id", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("returnedLate").value(true))
                .andExpect(jsonPath("status").value("RETURNED"));
    }

    @Test
    void bookSearchSupportsCategoryPaginationAndLiteralWildcards() throws Exception {
        library.registerBook("Java 100%", "Alice", "技术", List.of("SEARCH-1"));
        library.registerBook("Java 入门", "Bob", "技术", List.of("SEARCH-2"));
        library.registerBook("文学", "Alice", "小说", List.of("SEARCH-3"));
        mvc.perform(get("/books").param("q", "java").param("category", "技术").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(2))
                .andExpect(jsonPath("totalPages").value(2)).andExpect(jsonPath("content.length()").value(1));
        mvc.perform(get("/books").param("q", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(1))
                .andExpect(jsonPath("content[0].availableCopies").value(1));
        mvc.perform(get("/books").param("q", "alice").param("category", "小说"))
                .andExpect(jsonPath("totalElements").value(1));
        mvc.perform(get("/books").param("page", "100")).andExpect(jsonPath("content").isEmpty());
    }

    @Test
    void registrationRequiresCopiesAndGlobalUniqueNormalizedBarcodes() throws Exception {
        JsonNode registered = body(perform(post("/books"), Map.of("title", "DDD", "author", "Eric",
                "category", "技术", "barcodes", List.of(" BAR-1 ")))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")));
        UUID bookId = UUID.fromString(registered.get("id").asText());
        expectError(perform(post("/books/{id}/copies", bookId), Map.of("barcode", "BAR-1")),
                409, "BARCODE_ALREADY_EXISTS");
        expectError(perform(post("/books"), Map.of("title", "DDD", "author", "Eric",
                "category", "技术", "barcodes", List.of())), 400, "INVALID_REQUEST");
        expectError(perform(post("/books"), Map.of("title", "DDD", "author", "Eric",
                "category", "技术", "barcodes", List.of(" A ", "A"))), 409, "BARCODE_ALREADY_EXISTS");
        perform(post("/books/{id}/copies", bookId), Map.of("barcode", "BAR-2"))
                .andExpect(status().isCreated());
        assertThat(library.getBook(bookId).totalCopies()).isEqualTo(2);
    }

    @Test
    void withdrawalRejectsBorrowedAndLastCopyAndRetainsHistory() throws Exception {
        UUID reader = reader();
        BookDetailView book = book(2);
        var copyId = book.copies().get(0).id();
        var loan = library.borrow(reader, book.id(), copyId);
        expectError(mvc.perform(delete("/books/{book}/copies/{copy}", book.id(), copyId)),
                409, "COPY_NOT_AVAILABLE");
        library.returnLoan(reader, loan.id());
        mvc.perform(delete("/books/{book}/copies/{copy}", book.id(), copyId)).andExpect(status().isNoContent());
        assertThat(library.getBook(book.id()).totalCopies()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from loans where id = ?", Long.class, loan.id()))
                .isEqualTo(1);
        expectError(mvc.perform(delete("/books/{book}/copies/{copy}", book.id(), book.copies().get(1).id())),
                409, "LAST_COPY_CANNOT_BE_WITHDRAWN");
        expectError(perform(post("/loans").header("X-Reader-Id", reader),
                Map.of("bookId", book.id(), "copyId", copyId)), 409, "COPY_NOT_AVAILABLE");
    }

    @Test
    void loanOwnershipIsCheckedBeforeMutation() throws Exception {
        UUID owner = reader();
        UUID anotherReader = reader();
        var loan = library.borrow(owner, book(1).id(), null);
        for (String action : List.of("return", "renewal")) {
            expectError(mvc.perform(post("/loans/{id}/{action}", loan.id(), action)
                    .header("X-Reader-Id", anotherReader)), 403, "FORBIDDEN");
        }
        assertThat(library.currentLoans(owner, "active", 0, 20).totalElements()).isEqualTo(1);
        assertThat(library.currentLoans(anotherReader, "all", 0, 20).totalElements()).isZero();
    }

    @Test
    void invalidInputAndMissingResourcesUseStableErrors() throws Exception {
        UUID reader = reader();
        expectError(mvc.perform(get("/books/{id}", UUID.randomUUID())), 404, "BOOK_NOT_FOUND");
        expectError(mvc.perform(get("/books/not-a-uuid")), 400, "INVALID_REQUEST");
        expectError(mvc.perform(get("/books").param("size", "101")), 400, "INVALID_REQUEST");
        expectError(mvc.perform(get("/books").param("page", "2147483647")), 400, "INVALID_REQUEST");
        expectError(mvc.perform(get("/readers/me/loans")), 400, "INVALID_REQUEST");
        expectError(mvc.perform(get("/readers/me/loans").header("X-Reader-Id", reader).param("status", "bad")),
                400, "INVALID_REQUEST");
        expectError(mvc.perform(get("/readers/me/loans").header("X-Reader-Id", UUID.randomUUID())),
                404, "READER_NOT_FOUND");
        expectError(perform(post("/readers"), Map.of("name", " ")), 400, "INVALID_REQUEST");
        expectError(perform(post("/readers"), Map.of("name", "name", "unknown", true)), 400, "INVALID_REQUEST");
        expectError(perform(post("/loans").header("X-Reader-Id", reader), Map.of()), 400, "INVALID_REQUEST");
        expectError(mvc.perform(post("/loans/{id}/return", UUID.randomUUID()).header("X-Reader-Id", reader)),
                404, "LOAN_NOT_FOUND");
        expectError(mvc.perform(get("/not-found")), 404, "INVALID_REQUEST");
        expectError(mvc.perform(put("/books")), 405, "INVALID_REQUEST");
    }

    @Test
    void failedBorrowForWrongBookOrUnavailableCopyDoesNotCreateLoans() throws Exception {
        UUID reader = reader();
        var first = book(1);
        var second = book(1);
        expectError(perform(post("/loans").header("X-Reader-Id", reader),
                Map.of("bookId", first.id(), "copyId", second.copies().get(0).id())), 409, "COPY_BOOK_MISMATCH");
        assertThat(library.getBook(first.id()).availableCopies()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from loans", Long.class)).isZero();
        library.borrow(reader, first.id(), null);
        expectError(perform(post("/loans").header("X-Reader-Id", reader), Map.of("bookId", first.id())),
                409, "COPY_NOT_AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from loans", Long.class)).isEqualTo(1);
    }

    @Test
    void databaseFailureRollsBackCopyChangeAndNewLoanTogether() throws Exception {
        UUID reader = reader();
        var book = book(1);
        var old = library.borrow(reader, book.id(), null);
        library.returnLoan(reader, old.id());
        // 模拟防重表已有冲突，令新借阅在 flush/commit 时真正触发数据库错误。
        jdbc.update("insert into active_copy_loans(copy_id, loan_id) values (?, ?)", old.copyId(), old.id());
        expectError(perform(post("/loans").header("X-Reader-Id", reader), Map.of("bookId", book.id())),
                409, "COPY_NOT_AVAILABLE");
        assertThat(library.getBook(book.id()).availableCopies()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from loans", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from loans where status = 'ACTIVE'", Long.class)).isZero();
    }

    @Test
    void concurrentReadersCannotBorrowSameExplicitCopy() throws Exception {
        BookDetailView book = book(1);
        UUID first = reader();
        UUID second = reader();
        List<ErrorCode> results = parallel(List.of(
                () -> borrowResult(first, book.id(), book.copies().get(0).id()),
                () -> borrowResult(second, book.id(), book.copies().get(0).id())));
        assertThat(results).containsExactlyInAnyOrder(null, ErrorCode.COPY_NOT_AVAILABLE);
        assertThat(jdbc.queryForObject("select count(*) from loans", Long.class)).isEqualTo(1);
        assertThat(library.getBook(book.id()).availableCopies()).isZero();
    }

    @Test
    void concurrentAutomaticAllocationCannotBorrowSameCopy() throws Exception {
        BookDetailView book = book(1);
        UUID first = reader();
        UUID second = reader();
        List<ErrorCode> results = parallel(List.of(
                () -> borrowResult(first, book.id(), null), () -> borrowResult(second, book.id(), null)));
        assertThat(results).containsExactlyInAnyOrder(null, ErrorCode.COPY_NOT_AVAILABLE);
        assertThat(jdbc.queryForObject("select count(*) from active_copy_loans", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentAutomaticAllocationUsesDistinctAvailableCopies() throws Exception {
        BookDetailView book = book(2);
        UUID first = reader();
        UUID second = reader();
        assertThat(parallel(List.of(() -> borrowResult(first, book.id(), null),
                () -> borrowResult(second, book.id(), null)))).containsOnlyNulls().hasSize(2);
        assertThat(jdbc.queryForObject("select count(distinct copy_id) from loans", Long.class)).isEqualTo(2);
        assertThat(library.getBook(book.id()).availableCopies()).isZero();
    }

    @Test
    void openApiParsesAndMatchesActualRoutesWithResolvableReferences() throws Exception {
        try (var input = getClass().getResourceAsStream("/static/openapi.yaml")) {
            assertThat(input).isNotNull();
            Map<String, Object> spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            assertThat(spec.get("openapi")).isEqualTo("3.0.3");
            Map<?, ?> paths = (Map<?, ?>) spec.get("paths");
            Set<String> documented = new HashSet<>();
            Set<String> verbs = Set.of("get", "post", "put", "patch", "delete");
            paths.forEach((path, operations) -> ((Map<?, ?>) operations).keySet().stream()
                    .map(Object::toString).filter(verbs::contains)
                    .forEach(verb -> documented.add(verb.toUpperCase(java.util.Locale.ROOT) + " " + path)));
            Set<String> implemented = new HashSet<>();
            mappings.getHandlerMethods().forEach((mapping, method) -> {
                if (method.getBeanType().getPackageName().equals("com.elibrary.interfaces.rest")) {
                    mapping.getMethodsCondition().getMethods().forEach(verb ->
                            mapping.getPatternValues().forEach(path -> implemented.add(verb.name() + " " + path)));
                }
            });
            assertThat(documented).hasSize(10).isEqualTo(implemented);
            verifyReferences(spec, spec);
        }
        mvc.perform(get("/openapi.yaml")).andExpect(status().isOk());
    }

    @Test
    void concurrentBorrowingByOneReaderNeverExceedsFive() throws Exception {
        UUID reader = reader();
        BookDetailView book = book(6);
        List<Callable<ErrorCode>> tasks = book.copies().stream()
                .<Callable<ErrorCode>>map(copy -> () -> borrowResult(reader, book.id(), copy.id())).toList();
        List<ErrorCode> results = parallel(tasks);
        assertThat(results.stream().filter(code -> code == null).count()).isEqualTo(5);
        assertThat(results.stream().filter(code -> code == ErrorCode.LOAN_LIMIT_EXCEEDED).count()).isEqualTo(1);
        assertThat(library.getBook(book.id()).availableCopies()).isEqualTo(1);
        assertThat(library.currentLoans(reader, "active", 0, 20).totalElements()).isEqualTo(5);
    }

    @Test
    void concurrentWithdrawalKeepsAtLeastOneCopy() throws Exception {
        BookDetailView book = book(2);
        List<Callable<ErrorCode>> tasks = book.copies().stream().<Callable<ErrorCode>>map(copy -> () -> {
            try {
                library.withdrawCopy(book.id(), copy.id());
                return null;
            } catch (DomainException exception) {
                return exception.code();
            }
        }).toList();
        assertThat(parallel(tasks)).containsExactlyInAnyOrder(null, ErrorCode.LAST_COPY_CANNOT_BE_WITHDRAWN);
        assertThat(library.getBook(book.id()).totalCopies()).isEqualTo(1);
    }

    private UUID reader() { return library.registerReader("读者 " + UUID.randomUUID()).id(); }

    private BookDetailView book(int copies) {
        return library.registerBook("领域驱动设计", "Eric Evans", "技术", IntStream.range(0, copies)
                .mapToObj(index -> "COPY-" + UUID.randomUUID()).toList());
    }

    private ErrorCode borrowResult(UUID reader, UUID book, UUID copy) {
        try {
            library.borrow(reader, book, copy);
            return null;
        } catch (DomainException exception) {
            return exception.code();
        }
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Object payload) throws Exception {
        return mvc.perform(request.contentType("application/json").content(json.writeValueAsBytes(payload)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private void expectError(ResultActions result, int statusCode, String code) throws Exception {
        result.andExpect(status().is(statusCode)).andExpect(jsonPath("code").value(code))
                .andExpect(jsonPath("status").value(statusCode)).andExpect(jsonPath("path").isNotEmpty());
    }

    private static <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("等待并发起点超时");
                return task.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> result = new ArrayList<>();
            for (Future<T> future : futures) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void verifyReferences(Object node, Map<String, Object> root) {
        if (node instanceof Map<?, ?> fields) {
            Object reference = fields.get("$ref");
            if (reference != null) {
                String ref = reference.toString();
                assertThat(ref).startsWith("#/");
                Object target = root;
                for (String segment : ref.substring(2).split("/")) {
                    assertThat(target).isInstanceOf(Map.class);
                    target = ((Map<?, ?>) target).get(segment.replace("~1", "/").replace("~0", "~"));
                    assertThat(target).as("Unresolved reference: %s", ref).isNotNull();
                }
            }
            fields.values().forEach(child -> verifyReferences(child, root));
        } else if (node instanceof List<?> items) {
            items.forEach(child -> verifyReferences(child, root));
        }
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary
        MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
