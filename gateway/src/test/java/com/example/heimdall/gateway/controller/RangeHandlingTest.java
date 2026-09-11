package com.example.heimdall.gateway.controller;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.gateway.service.ObjectGatewayService;
import com.example.heimdall.gateway.service.RangeNotSatisfiableException;
import com.example.heimdall.gateway.service.StreamingOrchestratorService;
import com.example.heimdall.gateway.service.StreamingOrchestratorService.PreparedStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Status-code and header handling for the {@code Range} header.
 *
 * <p>These exist because of a bug this project shipped: Spring's
 * {@link org.springframework.http.HttpRange} clamps a range's <em>end</em> to
 * the object's last byte but does not reject a <em>start</em> beyond it, so
 * {@code bytes=999999999-1000000000} over a 512 KiB object produced
 * {@code start > end} instead of throwing. The controller then advertised
 * {@code Content-Length: -999475711} on a {@code 206} and wrote no body, which
 * a client can only interpret as a broken connection.
 *
 * <p>It survived every unit test in the suite and was caught by an integration
 * test against the real gateway. These cases pull the check down to where it
 * runs in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class RangeHandlingTest {

    private static final int TOTAL_SIZE = 512 * 1024;

    @Mock
    private ObjectGatewayService gatewayService;
    @Mock
    private StreamingOrchestratorService streamingService;

    private ObjectController controller;

    @BeforeEach
    void setUp() {
        controller = new ObjectController(gatewayService, streamingService);
        ObjectMetadataDto meta = new ObjectMetadataDto("clip.mp4", "video/mp4", "clip.mp4",
                TOTAL_SIZE, 1024, TOTAL_SIZE / 1024, Instant.EPOCH);
        when(streamingService.prepare(any(), any()))
                .thenReturn(new PreparedStream("clip.mp4", meta, List.of("http://replica-0"), 1));
    }

    private ResponseEntity<StreamingResponseBody> download(String range) {
        return controller.download("clip.mp4", null, range);
    }

    private static long contentLength(ResponseEntity<?> response) {
        return Long.parseLong(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "bytes=999999999-1000000000",  // wholly past the end
            "bytes=524288-",               // starts exactly at length, so the first byte does not exist
            "bytes=524288-600000",
            "bytes=1000000-"
    })
    void rejectsARangeStartingAtOrBeyondTheEndOfTheObject(String range) {
        assertThatThrownBy(() -> download(range))
                .isInstanceOf(RangeNotSatisfiableException.class);
    }

    @Test
    void neverAdvertisesANegativeOrOversizedContentLength() {
        // The invariant the bug violated, stated directly.
        for (String range : List.of("bytes=0-0", "bytes=0-", "bytes=100-200",
                "bytes=524287-", "bytes=-100", "bytes=0-999999999")) {
            ResponseEntity<StreamingResponseBody> response = download(range);
            assertThat(contentLength(response))
                    .as("Content-Length for '%s'", range)
                    .isPositive()
                    .isLessThanOrEqualTo(TOTAL_SIZE);
        }
    }

    @Test
    void servesASatisfiableRangeAsA206WithTheRightContentRange() {
        ResponseEntity<StreamingResponseBody> response = download("bytes=100-199");

        assertThat(response.getStatusCode().value()).isEqualTo(206);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 100-199/" + TOTAL_SIZE);
        assertThat(contentLength(response)).isEqualTo(100);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
    }

    @Test
    void clampsARangeThatOverrunsTheEndRatherThanRejectingIt() {
        // Only the *start* being past the end is unsatisfiable; an end past it
        // is clamped, which is what a player asking for "the next 10 MiB" does
        // as it approaches the end of a file.
        ResponseEntity<StreamingResponseBody> response = download("bytes=524000-999999999");

        assertThat(response.getStatusCode().value()).isEqualTo(206);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 524000-" + (TOTAL_SIZE - 1) + "/" + TOTAL_SIZE);
        assertThat(contentLength(response)).isEqualTo(TOTAL_SIZE - 524000);
    }

    @Test
    void servesASuffixRange() {
        ResponseEntity<StreamingResponseBody> response = download("bytes=-1024");

        assertThat(response.getStatusCode().value()).isEqualTo(206);
        assertThat(contentLength(response)).isEqualTo(1024);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes " + (TOTAL_SIZE - 1024) + "-" + (TOTAL_SIZE - 1) + "/" + TOTAL_SIZE);
    }

    @Test
    void ignoresAMalformedRangeAndServesTheWholeObject() {
        // RFC 7233: an unparseable Range header is ignored, not an error.
        ResponseEntity<StreamingResponseBody> response = download("bytes=not-a-range");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(contentLength(response)).isEqualTo(TOTAL_SIZE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isNull();
    }

    @Test
    void servesTheWholeObjectWhenNoRangeIsRequested() {
        ResponseEntity<StreamingResponseBody> response = download(null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(contentLength(response)).isEqualTo(TOTAL_SIZE);
    }
}
