package com.eventledger.gateway.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidation_fieldWithNullDefaultMessage_usesInvalidFallback() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "obj");
        br.addError(new FieldError("obj", "myField", null, false, null, null, null));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                mock(MethodParameter.class), br);

        ResponseEntity<?> resp = handler.handleValidation(ex, mockRequest("/events"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(((ErrorResponse) resp.getBody()).errors()).containsEntry("myField", "invalid");
    }

    @Test
    void handleValidation_duplicateFieldNames_firstMessageWins() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "obj");
        br.addError(new FieldError("obj", "amount", "must be positive"));
        br.addError(new FieldError("obj", "amount", "must not be null"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                mock(MethodParameter.class), br);

        ResponseEntity<?> resp = handler.handleValidation(ex, mockRequest("/events"));

        assertThat(((ErrorResponse) resp.getBody()).errors())
                .containsEntry("amount", "must be positive");
    }

    @Test
    void handleAll_genericException_returnsInternalServerError() {
        ResponseEntity<?> resp = handler.handleAll(
                new RuntimeException("unexpected failure"), mockRequest("/events"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(((ErrorResponse) resp.getBody()).message()).isEqualTo("unexpected failure");
    }

    private HttpServletRequest mockRequest(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }
}
