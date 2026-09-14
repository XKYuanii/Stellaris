package com.stellaris.exception;

import com.stellaris.common.ApiResponse;
import com.stellaris.enums.BaseCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 异常处理器
 * @author: xz_y
 **/
@Slf4j
@RestControllerAdvice
public class DefaultExceptionHandler {

    /** Missing MVC routes are client errors, not application failures. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<String>> notFoundHandler(HttpServletRequest request, Exception exception) {
        String message = String.format(BaseCode.NOT_FOUND.getMsg(), request.getMethod(), request.getRequestURI());
        log.warn("接口不存在 method:{} url:{}", request.getMethod(), getRequestUrl(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(BaseCode.NOT_FOUND.getCode(), message));
    }

    /**
    * 业务异常
    * */
    @ExceptionHandler(value = StellarisFrameException.class)
    public ApiResponse<String> toolkitExceptionHandler(HttpServletRequest request, StellarisFrameException StellarisFrameException) {
        log.error("业务异常 错误信息 : {} method : {} url : {} query : {} ", StellarisFrameException.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), StellarisFrameException);
        return ApiResponse.error(StellarisFrameException.getCode(), StellarisFrameException.getMessage());
    }
    /**
     * 参数验证异常
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ApiResponse<List<ArgumentError>> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        log.error("参数验证异常 错误信息 : {} method : {} url : {} query : {} ", ex.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), ex);
        BindingResult bindingResult = ex.getBindingResult();
        List<ArgumentError> argumentErrorList = 
                bindingResult.getFieldErrors()
                        .stream()
                        .map(fieldError -> {
                            ArgumentError argumentError = new ArgumentError();
                            argumentError.setArgumentName(fieldError.getField());
                            argumentError.setMessage(fieldError.getDefaultMessage());
                            return argumentError;
                        }).collect(Collectors.toList());
        return ApiResponse.error(BaseCode.PARAMETER_ERROR.getCode(),argumentErrorList);
    }

    /**
     * 拦截未捕获异常
     */
    @ExceptionHandler(value = Throwable.class)
    public ApiResponse<String> defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("全局异常 错误信息 : {} method : {} url : {} query : {} ", throwable.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), throwable);
        return ApiResponse.error();
    }

    private String getRequestUrl(HttpServletRequest request) {
        return request.getRequestURL().toString();
    }

    private String getRequestQuery(HttpServletRequest request){
        return request.getQueryString();
    }
}
