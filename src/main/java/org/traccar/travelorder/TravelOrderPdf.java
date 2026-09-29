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
package org.traccar.travelorder;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import jakarta.inject.Singleton;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.tools.generic.EscapeTool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

// Renders a travel order (the map from TravelOrderService, possibly read
// back from the stored JSON) to PDF: Velocity template -> XHTML ->
// openhtmltopdf, with Noto Sans embedded for the Slovak diacritics.
@Singleton
public class TravelOrderPdf {

    private static final String BASE = "/travelorder/";

    private final VelocityEngine velocity = new VelocityEngine();
    private final String template;

    public TravelOrderPdf() throws IOException {
        velocity.init();
        try (InputStream in = TravelOrderPdf.class.getResourceAsStream(BASE + "travelorder.vm")) {
            if (in == null) {
                throw new IOException("travelorder/travelorder.vm missing");
            }
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // number formatting for the template: Slovak decimal comma
    public static class Format {
        public String eur(Object value) {
            return money(value) + " €";
        }

        public String money(Object value) {
            double number = value instanceof Number n ? n.doubleValue() : 0;
            String text = String.format(Locale.ROOT, "%,.2f", number);
            return text.replace(',', ' ').replace('.', ',');
        }

        public String num(Object value, int digits) {
            double number = value instanceof Number n ? n.doubleValue() : 0;
            return String.format(Locale.ROOT, "%." + digits + "f", number).replace('.', ',');
        }

        public String yn(Object value) {
            return Boolean.TRUE.equals(value) ? "áno" : "nie";
        }
    }

    public String html(String number, Map<String, Object> result) {
        VelocityContext context = new VelocityContext();
        context.put("number", number);
        context.put("r", result);
        context.put("esc", new EscapeTool());
        context.put("fmt", new Format());
        StringWriter writer = new StringWriter();
        velocity.evaluate(context, writer, "travelorder", template);
        return writer.toString();
    }

    public byte[] pdf(String number, Map<String, Object> result) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(number, result, out);
        return out.toByteArray();
    }

    public void write(String number, Map<String, Object> result, OutputStream out) throws IOException {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        for (String[] font : new String[][] {
                {"NotoSans-Regular.ttf", "400"}, {"NotoSans-SemiBold.ttf", "600"}, {"NotoSans-Bold.ttf", "700"}}) {
            builder.useFont(() -> TravelOrderPdf.class.getResourceAsStream(BASE + font[0]),
                    "Noto Sans", Integer.parseInt(font[1]), PdfRendererBuilder.FontStyle.NORMAL, true);
        }
        builder.withHtmlContent(html(number, result), null);
        builder.toStream(out);
        builder.run();
    }

}
