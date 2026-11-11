// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package marketplace;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import common.ToolFailure;

/**
 * Joins the README demo's pictures into an animated GIF that loops, with Java's own ImageIO (no
 * ffmpeg or ImageMagick to install).
 *
 * <p>Small on purpose: a frame after the first holds only the rectangle that changed since the
 * frame before, placed at its offset, and the frame under it stays (GIF's "do not dispose"). A key
 * that moves the cursor changes a few hundred pixels, not the whole window, so most frames are a
 * few kilobytes. A picture the same as the one before only lengthens that one's time on screen.
 *
 * <p>The demo test writes the pictures and {@code frames.tsv}: per line the PNG file and its time
 * on screen in milliseconds, such as {@code frame-0001.png	1400}. GIF counts time in hundredths
 * of a second.
 */
public final class Gif {

    /** A picture's file, in the folder {@code frames.tsv} is in, and how long it stays, in milliseconds. */
    public record Frame(Path file, int millis) {
    }

    private Gif() {
    }

    /**
     * The frames {@code frames.tsv} in {@code folder} lists, in order. Each name must be
     * {@code frame-NNNN.png}, so the list cannot point outside the folder.
     */
    public static List<Frame> readFrames(Path folder) {
        Path list = folder.resolve("frames.tsv");
        List<Frame> frames = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(list)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split("\t");
                if (f.length != 2 || !f[0].matches("frame-\\d{4}\\.png") || !f[1].matches("\\d{1,6}")) {
                    throw new ToolFailure(list + " has a line that is not \"frame-NNNN.png<tab>milliseconds\": " + line);
                }
                frames.add(new Frame(folder.resolve(f[0]), Integer.parseInt(f[1])));
            }
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + list + ": " + e.getMessage());
        }
        if (frames.isEmpty()) {
            throw new ToolFailure(list + " lists no pictures");
        }
        return frames;
    }

    /**
     * Writes {@code frames} to {@code out} as a looping GIF. All frames must have the first one's
     * size. The pictures are read one at a time, as they are written: a demo of a few hundred
     * window-sized pictures would not fit in a small heap all at once.
     */
    public static void write(List<Frame> frames, Path out) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.deleteIfExists(out);
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(out.toFile())) {
                writer.setOutput(stream);
                writer.prepareWriteSequence(null);
                // The picture waiting to be written, with what changed since the one before it
                // (null: the whole picture, for the first) and its time so far: a picture the same
                // as the waiting one only adds its time to it.
                BufferedImage waiting = null;
                Rectangle waitingChange = null;
                int waitingMillis = 0;
                boolean first = true;
                for (Frame f : frames) {
                    BufferedImage now = read(f.file());
                    if (waiting == null) {
                        waiting = now;
                        waitingMillis = f.millis();
                        continue;
                    }
                    requireSameSize(waiting, now);
                    Rectangle change = changed(waiting, now);
                    if (change == null) {
                        waitingMillis += f.millis();
                        continue;
                    }
                    writeFrame(writer, waiting, waitingChange, waitingMillis, first);
                    first = false;
                    waiting = now;
                    waitingChange = change;
                    waitingMillis = f.millis();
                }
                writeFrame(writer, waiting, waitingChange, waitingMillis, first);
                writer.endWriteSequence();
            }
        } catch (IOException e) {
            throw new ToolFailure("cannot write " + out + ": " + e.getMessage());
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage read(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new ToolFailure("cannot read " + file + " as a picture");
        }
        return image;
    }

    private static void requireSameSize(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new ToolFailure("the demo's pictures differ in size (" + a.getWidth() + "x" + a.getHeight() + " and "
                    + b.getWidth() + "x" + b.getHeight() + "); record them again");
        }
    }

    /** Writes the part {@code change} of {@code image} (all of it when null) for {@code millis}. */
    private static void writeFrame(ImageWriter writer, BufferedImage image, Rectangle change, int millis, boolean first) throws IOException {
        Rectangle r = change == null ? new Rectangle(0, 0, image.getWidth(), image.getHeight()) : change;
        BufferedImage part = rgb(image.getSubimage(r.x, r.y, r.width, r.height));
        writer.writeToSequence(new IIOImage(part, null, metadata(writer, part, r, centiseconds(millis), first)), null);
    }

    /** The smallest rectangle holding every pixel that differs between {@code a} and {@code b}; {@code null} when none does. */
    static Rectangle changed(BufferedImage a, BufferedImage b) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** At least 2: browsers show a 0 or 1 as a tenth of a second, which would race through the typing. */
    static int centiseconds(int millis) {
        return Math.max(2, Math.round(millis / 10f));
    }

    /** A copy in plain RGB, which ImageIO's GIF writer reduces to a palette of 256 colors itself. */
    private static BufferedImage rgb(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(image, 0, 0, null);
        return copy;
    }

    /**
     * The frame's GIF metadata: where it goes ({@code imageLeftPosition}, {@code imageTopPosition}),
     * its time, "do not dispose" so the picture under it stays, and on the first frame the
     * NETSCAPE2.0 block whose loop count 0 means "loop for ever".
     */
    private static IIOMetadata metadata(ImageWriter writer, BufferedImage part, Rectangle at, int delay, boolean first) throws IOException {
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(part), param);
        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        IIOMetadataNode descriptor = child(root, "ImageDescriptor");
        descriptor.setAttribute("imageLeftPosition", Integer.toString(at.x));
        descriptor.setAttribute("imageTopPosition", Integer.toString(at.y));
        descriptor.setAttribute("imageWidth", Integer.toString(at.width));
        descriptor.setAttribute("imageHeight", Integer.toString(at.height));

        IIOMetadataNode control = child(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "doNotDispose");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", Integer.toString(delay));
        control.setAttribute("transparentColorIndex", "0");

        if (first) {
            IIOMetadataNode extensions = child(root, "ApplicationExtensions");
            IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
            loop.setAttribute("applicationID", "NETSCAPE");
            loop.setAttribute("authenticationCode", "2.0");
            loop.setUserObject(new byte[] { 1, 0, 0 }); // sub-block 1, loop count 0 (little-endian): for ever
            extensions.appendChild(loop);
        }
        meta.setFromTree(format, root);
        return meta;
    }

    /** The child of {@code root} named {@code name}, made if missing. */
    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
