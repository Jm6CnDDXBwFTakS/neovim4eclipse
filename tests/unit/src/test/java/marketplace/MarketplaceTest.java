// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import common.ToolFailure;

/** The Marketplace tool's pure parts: the listing, its Markdown and page, the demo GIF, the logo commands. */
class MarketplaceTest {

    private static final Path ROOT = Path.of("../..");
    private static final Map<String, String> FACTS = Map.of("neovim", "0.12.6", "java", "21");

    // ---------------------------------------------------------------- the listing

    @Test
    void fieldsKeepTheFormsOrderAndSkipComments() {
        Map<String, String> fields = Listing.parseFields("# a comment\n\nSolution Name: Neovim for Eclipse\nOrganization Name:\nStatus: Beta\n");
        assertEquals(List.of("Solution Name", "Organization Name", "Status"), List.copyOf(fields.keySet()));
        assertEquals("", fields.get("Organization Name"));
    }

    @Test
    void aFieldTwiceOrALineWithoutAColonStopsTheTool() {
        assertThrows(ToolFailure.class, () -> Listing.parseFields("Status: Beta\nStatus: Alpha\n"));
        assertThrows(ToolFailure.class, () -> Listing.parseFields("just text\n"));
    }

    @Test
    void factsAreFilledInAndAnUnknownOneStopsTheTool() {
        assertEquals("Neovim 0.12.6 on Java 21", Listing.fill("Neovim {neovim} on Java {java}", FACTS, "x"));
        ToolFailure f = assertThrows(ToolFailure.class, () -> Listing.fill("Neovim {neovm}", FACTS, "listing.txt"));
        assertTrue(f.getMessage().contains("{neovm}"), f.getMessage());
        assertEquals("a {Brace} and {}", Listing.fill("a {Brace} and {}", FACTS, "x"), "only {lower.case} names are facts");
    }

    @Test
    void changesSinceTheSubmittedListingNameEachField() {
        Listing then = new Listing(Map.of("Status", "Beta", "Gone", "x"), "old text");
        Listing now = Listing.of("Status: Stable\nTags: vim\n", "new text", FACTS);
        assertEquals(List.of("Status: \"Beta\" -> \"Stable\"", "Tags: new field, \"vim\"", "Gone: no longer in the listing",
                "Solution Description: changed (" + Listing.DESCRIPTION + ")"), now.changesSince(then));
        assertTrue(now.changesSince(Listing.of(now.fieldsText(), "new text\n", Map.of())).isEmpty(), "a listing is the same as its own copy");
    }

    @Test
    void theRepositorysListingFillsInFromItsSources() {
        Map<String, String> facts = Facts.read(ROOT);
        assertTrue(facts.get("neovim").matches("\\d+\\.\\d+\\.\\d+"), facts.toString());
        assertTrue(facts.get("eclipse.oldest").matches("20\\d\\d-\\d\\d"), facts.toString());
        assertTrue(facts.get("eclipse.newest.number").matches("4\\.\\d+"), facts.toString());
        assertTrue(facts.get("fingerprint").matches("[0-9A-F ]{40,}"), facts.toString());
        Listing listing = MarketplaceTool.current(ROOT);
        assertEquals("Neovim for Eclipse", listing.fields().get("Solution Name"));
        assertEquals("Java " + facts.get("java"), listing.fields().get("Minimum Java Version"));
        assertTrue(listing.description().contains("Neovim " + facts.get("neovim")), listing.description());
        assertFalse(Markdown.toHtml(listing.description()).isEmpty());
    }

    // ---------------------------------------------------------------- Markdown and the page

    @Test
    void markdownMakesParagraphsListsAndInlineMarks() {
        assertEquals("<p>a <strong>b</strong> <em>c</em> <code>d</code> e</p>\n<ul><li>one two</li><li>three</li></ul>",
                Markdown.toHtml("a **b** *c* `d`\ne\n\n- one\n  two\n- three\n"));
        assertEquals("<p><a href=\"https://example.org/a?b=1&amp;c\">x</a></p>", Markdown.toHtml("[x](https://example.org/a?b=1&c)"));
    }

    @Test
    void markdownEscapesEverythingElseAndRefusesOtherLinks() {
        assertEquals("<p>&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; <code>&lt;b&gt;</code></p>",
                Markdown.toHtml("<script>alert(\"x\")</script> & `<b>`"));
        assertThrows(ToolFailure.class, () -> Markdown.toHtml("[x](javascript:alert(1))"));
        assertThrows(ToolFailure.class, () -> Markdown.toHtml("[x](http://example.org)"));
    }

    @Test
    void thePageEscapesFieldsAndNamesThePictures() {
        Listing listing = new Listing(Map.of("Tags", "<b>vim</b>", "Organization Name", ""), "text");
        String html = ListingPage.html(listing, List.of("demo.gif"), List.of("Tags: \"a\" -> \"<i>\""));
        assertTrue(html.contains("&lt;b&gt;vim&lt;/b&gt;"), html);
        assertFalse(html.contains("<b>vim</b>"), html);
        assertTrue(html.contains("&lt;i&gt;"), html);
        assertTrue(html.contains("<img src=\"demo.gif\""), html);
    }

    // ---------------------------------------------------------------- the demo GIF

    private static BufferedImage picture(Color color, Rectangle box) {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 40, 30);
        g.setColor(color);
        g.fillRect(box.x, box.y, box.width, box.height);
        g.dispose();
        return image;
    }

    @Test
    void theChangedRectangleHoldsExactlyTheDifferingPixels() {
        BufferedImage a = picture(Color.RED, new Rectangle(5, 5, 4, 4));
        BufferedImage b = picture(Color.RED, new Rectangle(6, 5, 4, 4));
        assertEquals(new Rectangle(5, 5, 5, 4), Gif.changed(a, b));
        assertNull(Gif.changed(a, picture(Color.RED, new Rectangle(5, 5, 4, 4))));
    }

    @Test
    void equalPicturesInARowBecomeOneLongerFrame(@TempDir Path dir) throws Exception {
        BufferedImage a = picture(Color.RED, new Rectangle(1, 1, 2, 2));
        BufferedImage b = picture(Color.BLUE, new Rectangle(1, 1, 2, 2));
        ImageIO.write(a, "png", dir.resolve("frame-0001.png").toFile());
        ImageIO.write(a, "png", dir.resolve("frame-0002.png").toFile());
        ImageIO.write(b, "png", dir.resolve("frame-0003.png").toFile());
        Files.writeString(dir.resolve("frames.tsv"), "frame-0001.png\t1000\nframe-0002.png\t2000\nframe-0003.png\t500\n");
        Path gif = dir.resolve("demo.gif");
        Gif.write(Gif.readFrames(dir), gif);
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
            reader.setInput(in);
            assertEquals(2, reader.getNumImages(true));
            assertEquals("300", delay(reader, 0), "the two equal pictures' times added up");
            assertEquals("50", delay(reader, 1));
        } finally {
            reader.dispose();
        }
        assertEquals(2, Gif.centiseconds(0), "never faster than browsers show");
        assertEquals(13, Gif.centiseconds(130));
    }

    private static String delay(ImageReader reader, int frame) throws Exception {
        IIOMetadataNode tree = (IIOMetadataNode) reader.getImageMetadata(frame).getAsTree("javax_imageio_gif_image_1.0");
        return ((IIOMetadataNode) tree.getElementsByTagName("GraphicControlExtension").item(0)).getAttribute("delayTime");
    }

    @Test
    void picturesOfAnotherSizeStopTheTool(@TempDir Path dir) throws Exception {
        ImageIO.write(picture(Color.RED, new Rectangle(1, 1, 2, 2)), "png", dir.resolve("frame-0001.png").toFile());
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", dir.resolve("frame-0002.png").toFile());
        Files.writeString(dir.resolve("frames.tsv"), "frame-0001.png\t100\nframe-0002.png\t100\n");
        assertThrows(ToolFailure.class, () -> Gif.write(Gif.readFrames(dir), dir.resolve("demo.gif")));
    }

    @Test
    void theGifLoopsAndItsLaterFramesHoldOnlyWhatChanged(@TempDir Path dir) throws Exception {
        BufferedImage a = picture(Color.RED, new Rectangle(5, 5, 4, 4));
        BufferedImage b = picture(Color.RED, new Rectangle(20, 10, 4, 4));
        ImageIO.write(a, "png", dir.resolve("frame-0001.png").toFile());
        ImageIO.write(b, "png", dir.resolve("frame-0002.png").toFile());
        Files.writeString(dir.resolve("frames.tsv"), "frame-0001.png\t1400\nframe-0002.png\t130\n");
        Path gif = dir.resolve("demo.gif");
        Gif.write(Gif.readFrames(dir), gif);

        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
            reader.setInput(in);
            assertEquals(2, reader.getNumImages(true));
            assertEquals(40, reader.read(0).getWidth(), "the first frame is whole");
            assertEquals(new Rectangle(5, 5, 19, 9).width, reader.read(1).getWidth(), "the second holds the change only");
            assertEquals("140", delay(reader, 0));
            IIOMetadataNode first = (IIOMetadataNode) reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
            IIOMetadataNode loop = (IIOMetadataNode) first.getElementsByTagName("ApplicationExtension").item(0);
            assertEquals("NETSCAPE", loop.getAttribute("applicationID"));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void aBadFramesListStopsTheTool(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("frames.tsv"), "../secret.png\t100\n");
        assertThrows(ToolFailure.class, () -> Gif.readFrames(dir));
    }

    // ---------------------------------------------------------------- the logo

    @Test
    void theIconsKeepTransparencyAndTheGreyOnesAreGrey() {
        MarketplaceTool.Icon grey = new MarketplaceTool.Icon("x/off.png", 16, true);
        assertEquals(List.of("magick", "big.png", "-filter", "Lanczos", "-resize", "16x16", "-colorspace", "Gray", "-strip", "PNG32:out.png"),
                MarketplaceTool.iconCommand("big.png", grey, "out.png"));
        assertEquals(4, MarketplaceTool.ICON_FILES.size());
        assertTrue(MarketplaceTool.marketplaceLogoCommand("s.png", "o.png").contains("110x80"));
    }
}
