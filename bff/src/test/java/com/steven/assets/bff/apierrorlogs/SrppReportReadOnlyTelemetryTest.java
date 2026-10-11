package com.steven.assets.bff.apierrorlogs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
/** The literal read-only report contract excludes error persistence, including background gateway replay. */
class SrppReportReadOnlyTelemetryTest {
 @TempDir Path temp;
 @Test void gatewayConnectionFailuresOnReportRouteNeverIngest() throws Exception {
  var ingest=mock(ApiErrorLogIngestClient.class);Path log=temp.resolve("error.log");
  Files.writeString(log,"2026/10/08 01:05:00 [error] 1#1: upstream timed out, request: \"GET /api/public/srpp/report-calculations?unexpected=1 HTTP/1.1\"\n");
  new NginxGatewayFailureLogTailer(log.toString(),ingest,new ApiErrorLogDiagnosticRenderer(""),
          Clock.fixed(Instant.parse("2026-10-09T01:05:00Z"), ZoneOffset.UTC)).tail();
  verifyNoInteractions(ingest);
  assertThat(OpenApiRouteCatalog.ROUTES).containsKey("GET /api/public/srpp/report-calculations");
  assertThat(OpenApiRouteCatalog.isReadOnlyReport("GET","/api/public/srpp/report-calculations")).isTrue();
  assertThat(OpenApiRouteCatalog.isReadOnlyReport("GET","/api/public/srpp/calculations")).isFalse();
 }
}
