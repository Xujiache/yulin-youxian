package com.xianda.freshdelivery.backup;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.dto.RiderLoginResponse;
import com.xianda.freshdelivery.dto.AdminLoginResponse;
import com.xianda.freshdelivery.dto.WxLoginResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class SessionIssuanceAdvice implements ResponseBodyAdvice<Object> {
    private final SessionRevocationGuard sessionGuard;

    public SessionIssuanceAdvice(SessionRevocationGuard sessionGuard) {
        this.sessionGuard = sessionGuard;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        Object data = body instanceof ApiResponse<?> apiResponse ? apiResponse.data() : body;
        if (data instanceof AdminLoginResponse login) {
            sessionGuard.admit(login.token());
        } else if (data instanceof RiderLoginResponse login) {
            sessionGuard.admit(login.accessToken());
            sessionGuard.admit(login.refreshToken());
        } else if (data instanceof WxLoginResponse login) {
            sessionGuard.admit(login.token());
        }
        return body;
    }
}
