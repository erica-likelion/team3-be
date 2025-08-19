package likelion.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class GlobalExceptionHandler {

    //400 @RequestBody 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "rejected", fe.getRejectedValue(), "reason", fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = base(status, "요청 값이 유효하지 않습니다.", req);
        pd.setTitle("Validation failed");
        pd.setProperty("errors", errors);
        log.warn("400 Validation {} -> {}", req.getRequestURI(), errors);
        return ResponseEntity.status(status).body(pd);
    }

    // 400 - 쿼리/경로 변수 바인딩 실패
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleBind(BindException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "rejected", fe.getRejectedValue(), "reason", fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = base(status, "요청 파라미터가 유효하지 않습니다.", req);
        pd.setTitle("Binding failed");
        pd.setProperty("errors", errors);
        log.warn("400 Bind {} -> {}", req.getRequestURI(), errors);
        return ResponseEntity.status(status).body(pd);
    }

    // 400 - 잘못된 값(예: Double.parseDouble 실패 등)
    @ExceptionHandler({IllegalArgumentException.class, NumberFormatException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ProblemDetail pd = base(status, "요청 값이 잘못되었습니다.", req);
        pd.setTitle("Bad Request");
        log.warn("400 {} -> {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(status).body(pd);
    }

    // 405 / 415 - 표준 웹 예외
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.METHOD_NOT_ALLOWED;
        ProblemDetail pd = base(status, "허용되지 않은 HTTP 메서드입니다.", req);
        pd.setTitle("Method Not Allowed");
        pd.setProperty("allowed", ex.getSupportedHttpMethods());
        log.warn("405 {} -> {}", req.getMethod(), ex.getSupportedHttpMethods());
        return ResponseEntity.status(status).body(pd);
    }

    // 409 - 제약조건/중복키
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.CONFLICT;
        ProblemDetail pd = base(status, "데이터 제약조건을 위반했습니다.", req);
        pd.setTitle("Data integrity violation");
        log.warn("409 {} -> {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(status).body(pd);
    }

    // 500 ....
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail pd = base(status, "서버 내부 오류가 발생했습니다.", req);
        pd.setTitle("Internal Server Error");
        log.error("500 at {}", req.getRequestURI(), ex); // 스택트레이스는 로그에만
        return ResponseEntity.status(status).body(pd);
    }

    private ProblemDetail base(HttpStatus status, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("path", req.getRequestURI());
        pd.setProperty("timestamp", OffsetDateTime.now().toString());
        return pd;
    }

    /**
     * 필수 파라미터 400
     * dto 쪽에 추가해야함
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "필수 파라미터가 누락되었습니다.");
        pd.setTitle("Missing parameter");
        pd.setProperty("path", req.getRequestURI());
        pd.setProperty("parameter", ex.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

}
