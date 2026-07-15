package com.xianda.freshdelivery.common;

import java.util.stream.Collectors;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException exception) {
        return ApiResponse.fail(exception.code(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ApiResponse<Void> handleValidation(Exception exception) {
        String message;
        if (exception instanceof MethodArgumentNotValidException validException) {
            message = validException.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + " " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        } else if (exception instanceof BindException bindException) {
            message = bindException.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + " " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        } else {
            message = "参数错误";
        }
        return ApiResponse.fail(400, message);
    }
}
