package com.example.orders.adapter.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.servlet.ReadListener;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.ServletInputStream;

class CalculationBodyLimitFilterTest {
    private final CalculationBodyLimitFilter filter = new CalculationBodyLimitFilter(JsonMapper.builder().build());

    @Test
    void getInputStreamAfterGetReaderThrowsIllegalStateException() throws Exception {
        filter.doFilter(smallPost(), new MockHttpServletResponse(), (request, response) -> {
            assertTrue(request.getReader().read() >= 0);

            assertThrows(IllegalStateException.class, () -> request.getInputStream());
        });
    }

    @Test
    void getReaderAfterGetInputStreamThrowsIllegalStateException() throws Exception {
        filter.doFilter(smallPost(), new MockHttpServletResponse(), (request, response) -> {
            assertTrue(request.getInputStream().read() >= 0);

            assertThrows(IllegalStateException.class, () -> request.getReader());
        });
    }

    @Test
    void repeatedStreamAccessPreservesIdentityAndReadPosition() throws Exception {
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            ServletInputStream first = request.getInputStream();
            assertEquals('a', first.read());
            ServletInputStream second = request.getInputStream();
            assertSame(first, second);
            assertEquals('b', second.read());
            assertEquals('c', request.getInputStream().read());
            assertEquals(-1, first.read());
        });
    }

    @Test
    void repeatedReaderAccessPreservesIdentityAndBufferedReadPosition() throws Exception {
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            BufferedReader first = request.getReader();
            assertEquals('a', first.read());
            BufferedReader second = request.getReader();
            assertSame(first, second);
            assertEquals('b', second.read());
            assertEquals('c', request.getReader().read());
            assertEquals(-1, first.read());
        });
    }

    @Test
    void accessorSelectionPreventsConflictBeforeReading() throws Exception {
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            request.getReader();
            assertThrows(IllegalStateException.class, () -> request.getInputStream());
        });
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            request.getInputStream();
            assertThrows(IllegalStateException.class, () -> request.getReader());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc"})
    void streamRemainsSelectedAtEofAndAfterClose(String body) throws Exception {
        filter.doFilter(post(body), new MockHttpServletResponse(), (request, response) -> {
            ServletInputStream input = request.getInputStream();
            assertEquals(body, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(input.isFinished());
            assertEquals(-1, input.read());
            assertSame(input, request.getInputStream());
            assertThrows(IllegalStateException.class, () -> request.getReader());
            input.close();
            assertSame(input, request.getInputStream());
            assertThrows(IllegalStateException.class, () -> request.getReader());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc"})
    void readerRemainsSelectedAtEofAndAfterClose(String body) throws Exception {
        filter.doFilter(post(body), new MockHttpServletResponse(), (request, response) -> {
            BufferedReader reader = request.getReader();
            assertEquals(body.isEmpty() ? null : body, reader.readLine());
            assertEquals(-1, reader.read());
            assertSame(reader, request.getReader());
            assertThrows(IllegalStateException.class, () -> request.getInputStream());
            reader.close();
            assertSame(reader, request.getReader());
            assertThrows(IOException.class, () -> reader.read());
            assertThrows(IllegalStateException.class, () -> request.getInputStream());
        });
    }

    @Test
    void closingBeforeEofDoesNotPermitSwitchingAccessors() throws Exception {
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            ServletInputStream input = request.getInputStream();
            input.close();
            assertSame(input, request.getInputStream());
            assertEquals('a', input.read());
            assertThrows(IllegalStateException.class, () -> request.getReader());
        });
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            BufferedReader reader = request.getReader();
            reader.close();
            assertSame(reader, request.getReader());
            assertThrows(IllegalStateException.class, () -> request.getInputStream());
        });
    }

    @Test
    void separateRequestsHaveIndependentAccessorStateAndPositions() throws Exception {
        AtomicReference<ServletInputStream> firstStream = new AtomicReference<>();
        AtomicReference<BufferedReader> firstReader = new AtomicReference<>();
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            firstStream.set(request.getInputStream());
            assertEquals('a', firstStream.get().read());
        });
        filter.doFilter(post("xyz"), new MockHttpServletResponse(), (request, response) -> {
            firstReader.set(request.getReader());
            assertEquals('x', firstReader.get().read());
        });
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            ServletInputStream input = request.getInputStream();
            assertNotSame(firstStream.get(), input);
            assertEquals('a', input.read());
        });
        filter.doFilter(post("xyz"), new MockHttpServletResponse(), (request, response) -> {
            BufferedReader reader = request.getReader();
            assertNotSame(firstReader.get(), reader);
            assertEquals('x', reader.read());
        });
    }

    @Test
    void preservesUtf8ReaderAndSynchronousStreamBehavior() throws Exception {
        filter.doFilter(post("\u00e9"), new MockHttpServletResponse(), (request, response) -> {
            assertEquals('\u00e9', request.getReader().read());
            assertEquals(-1, request.getReader().read());
        });
        filter.doFilter(post("abc"), new MockHttpServletResponse(), (request, response) -> {
            ServletInputStream input = request.getInputStream();
            assertTrue(input.isReady());
            assertFalse(input.isFinished());
            ReadListener listener = new ReadListener() {
                public void onDataAvailable() { }
                public void onAllDataRead() { }
                public void onError(Throwable failure) { }
            };
            assertThrows(UnsupportedOperationException.class, () -> input.setReadListener(listener));
            byte[] bytes = new byte[3];
            assertEquals(3, input.read(bytes, 0, bytes.length));
            assertEquals("abc", new String(bytes, StandardCharsets.UTF_8));
            assertTrue(input.isFinished());
            assertTrue(input.isReady());
        });
    }

    private MockHttpServletRequest smallPost() {
        return post("{\"currency\":\"USD\"}");
    }

    private MockHttpServletRequest post(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/calculate");
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
