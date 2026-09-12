/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.reports.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

// Converts a report's XLSX bytes to ODS or PDF by shelling out to a
// headless LibreOffice install (`soffice --headless --convert-to`), rather
// than building native support for either format - every report already
// produces XLSX via its Jxls template, so this reuses that output as-is
// instead of needing a second template/format per report.
//
// LibreOffice's own profile directory isn't safe for concurrent headless
// invocations (a second `soffice` process started while one is still
// running for the same user profile fails to start), so calls are
// serialized process-wide. Fine for this instance's actual report volume;
// revisit with `-env:UserInstallation=file://<unique dir>` per call if
// that ever becomes a bottleneck.
public final class DocumentConverter {

    private DocumentConverter() {
    }

    private static final Object CONVERT_LOCK = new Object();
    private static final long TIMEOUT_SECONDS = 60;

    public static byte[] convert(byte[] source, String sourceExtension, String format)
            throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("traccar-convert-");
        try {
            Path input = tempDir.resolve("report." + sourceExtension);
            Files.write(input, source);

            synchronized (CONVERT_LOCK) {
                Process process = new ProcessBuilder(
                        "soffice", "--headless", "--norestore",
                        "--convert-to", format, "--outdir", tempDir.toString(), input.toString())
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("LibreOffice conversion timed out");
                }
                if (process.exitValue() != 0) {
                    String output = new String(process.getInputStream().readAllBytes());
                    throw new IOException("LibreOffice conversion failed: " + output);
                }
            }

            Path output = tempDir.resolve("report." + format);
            if (!Files.exists(output)) {
                throw new IOException("LibreOffice did not produce an output file");
            }
            return Files.readAllBytes(output);
        } finally {
            try (var files = Files.list(tempDir)) {
                files.forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // best effort cleanup
                    }
                });
            }
            Files.deleteIfExists(tempDir);
        }
    }

    public static String contentType(String format) {
        return switch (format) {
            case "ods" -> "application/vnd.oasis.opendocument.spreadsheet";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

}
