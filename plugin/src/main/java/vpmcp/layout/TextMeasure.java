package vpmcp.layout;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Text width in pixels for the diagram font. Inside VP, AWT metrics match
 * VP's own rendering (ADR-0036: actor captions measured + 21 px, one-line
 * fit for use cases). Tests inject a fixed-width measure instead.
 */
public interface TextMeasure {

    int width(String text);

    /** AWT metrics for a font (works headless). */
    static TextMeasure awt(String family, int size) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        FontMetrics metrics = graphics.getFontMetrics(new Font(family, Font.PLAIN, size));
        graphics.dispose();
        return text -> text == null ? 0 : metrics.stringWidth(text);
    }

    /** Every character the same width: deterministic, for tests. */
    static TextMeasure fixed(int perCharacter) {
        return text -> text == null ? 0 : text.length() * perCharacter;
    }

    /**
     * Lines a name needs when wrapped at word boundaries into {@code available}
     * pixels, the way VP wraps captions. A single word wider than the space
     * still takes one line (VP truncates it).
     */
    static List<String> wrap(TextMeasure measure, String text, int available) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && measure.width(candidate) > available) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        lines.add(line.toString());
        return lines;
    }
}
