package com.elibrary.interfaces.rest;

import com.elibrary.domain.shared.DomainException;
import com.elibrary.domain.shared.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static com.elibrary.domain.shared.ErrorCode.*;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Clock clock;

    public ApiExceptionHandler(Clock clock) { this.clock = clock; }

    public record ApiError(Instant timestamp, int status, String code, String message,
                            String path, List<String> details) {}

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> domain(DomainException exception, WebRequest request) {
        HttpStatus status = switch (exception.code()) {
            case BOOK_NOT_FOUND, COPY_NOT_FOUND, READER_NOT_FOUND, LOAN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return error(status, exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> validation(ConstraintViolationException exception, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "请求参数校验失败", request,
                exception.getConstraintViolations().stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList());
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<Object> concurrency(ConcurrencyFailureException exception, WebRequest request) {
        return error(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION, "资源正在被其他请求修改，请重试", request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> integrity(DataIntegrityViolationException exception, WebRequest request) {
        String cause = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        if (cause.contains("uk_copy_barcode")) {
            return error(HttpStatus.CONFLICT, BARCODE_ALREADY_EXISTS, "条码已存在", request, List.of());
        }
        if (cause.contains("active_copy_loans")) {
            return error(HttpStatus.CONFLICT, COPY_NOT_AVAILABLE, "该副本已有活动借阅", request, List.of());
        }
        log.error("数据库完整性约束失败", exception);
        return error(HttpStatus.CONFLICT, DATA_CONFLICT, "数据状态冲突", request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
                                                              HttpHeaders headers, HttpStatusCode status,
                                                              WebRequest request) {
        List<String> details = exception instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage()).sorted().toList()
                : List.of();
        String message = exception instanceof MethodArgumentNotValidException ? "请求参数校验失败"
                : "请求无法处理，请检查路径、参数、请求头和 JSON 格式";
        ApiError error = body(status, status.is5xxServerError() ? INTERNAL_ERROR : INVALID_REQUEST,
                message, request, details);
        return new ResponseEntity<>(error, headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        log.error("未处理的 API 异常", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "服务内部错误", request, List.of());
    }

    private ResponseEntity<Object> error(HttpStatus status, ErrorCode code, String message,
                                         WebRequest request, List<String> details) {
        return ResponseEntity.status(status).body(body(status, code, message, request, details));
    }

    private ApiError body(HttpStatusCode status, ErrorCode code, String message,
                           WebRequest request, List<String> details) {
        String path = request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : "";
        return new ApiError(clock.instant(), status.value(), code.name(), message, path, details);
    }
}
