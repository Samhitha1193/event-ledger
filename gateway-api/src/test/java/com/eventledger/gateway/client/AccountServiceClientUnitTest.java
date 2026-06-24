package com.eventledger.gateway.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountServiceClientUnitTest {

    @Test
    void block_checkedExceptionCause_wrapsInRuntimeException() {
        // A future that fails with a checked exception (not a RuntimeException)
        // exercises the false branch of `cause instanceof RuntimeException`
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(new IOException("checked exception cause"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> AccountServiceClient.block(future));

        assertThat(thrown.getCause()).isInstanceOf(IOException.class);
        assertThat(thrown.getCause().getMessage()).isEqualTo("checked exception cause");
    }
}
