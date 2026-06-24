package com.eventledger.gateway.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

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

    @Test
    void handleNotReadable_malformedBody_returnsBadRequestWithCauseMessage() {
        HttpInputMessage inputMessage = mock(HttpInputMessage.class);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new IllegalArgumentException("parse failure"), inputMessage);

        ResponseEntity<?> resp = handler.handleNotReadable(ex, mockRequest("/events"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ErrorResponse) resp.getBody()).message()).isEqualTo("parse failure");
    }

    @Test
    void handleResponseStatus_notFound_propagatesStatusAndReason() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Event not found: evt-x");

        ResponseEntity<?> resp = handler.handleResponseStatus(ex, mockRequest("/events/evt-x"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ErrorResponse) resp.getBody()).message()).isEqualTo("Event not found: evt-x");
    }

    @Test
    void handleResponseStatus_conflict_propagates409() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.CONFLICT, "Event ID already exists with a different payload");

        ResponseEntity<?> resp = handler.handleResponseStatus(ex, mockRequest("/events"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private HttpServletRequest mockRequest(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }
}
