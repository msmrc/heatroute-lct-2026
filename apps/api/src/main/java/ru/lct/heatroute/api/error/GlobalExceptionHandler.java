package ru.lct.heatroute.api.error;

import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus()).body(error(
                exception.getCode(), exception.getMessage(), exception.getDetails(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(
                "validation_error",
                "Request validation failed",
                Collections.singletonMap("field_error_count", exception.getBindingResult().getErrorCount()),
                request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        LOGGER.error("Unhandled request failure request_id={}", requestId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(new ApiError(
                "internal_error",
                "Unexpected server error",
                requestId,
                true,
                Collections.emptyMap())));
    }

    private ApiErrorResponse error(
            String code,
            String message,
            java.util.Map<String, Object> details,
            HttpServletRequest request) {
        return new ApiErrorResponse(new ApiError(code, message, requestId(request), false, details));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
