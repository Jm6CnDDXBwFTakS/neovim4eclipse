// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes small {@code .tar.gz} and {@code .zip} archives for the tests, laid out as Neovim's release
 * archives or deliberately not: a ustar writer of a few lines, with PAX long names and links, so the
 * extractor is tested against archives whose every byte the test controls.
 */
final class TestArchives {

    /** A tar archive being written; {@link #gzipTo} finishes it. */
    static final class Tar {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Tar folder(String name) throws IOException {
            return entry(name, '5', 0755, new byte[0], null);
        }

        Tar file(String name, String text, boolean executable) throws IOException {
            return entry(name, '0', executable ? 0755 : 0644, text.getBytes(StandardCharsets.UTF_8), null);
        }

        Tar symlink(String name, String target) throws IOException {
            return entry(name, '2', 0777, new byte[0], target);
        }

        /** A file whose name is in a PAX header ({@code x}) and too long for the header itself. */
        Tar paxFile(String name, String text) throws IOException {
            String record = " path=" + name + "\n";
            int length = record.length() + 1;
            while (String.valueOf(length).length() + record.length() != length) {
                length = String.valueOf(length).length() + record.length();
            }
            byte[] pax = (length + record).getBytes(StandardCharsets.UTF_8);
            entry("PaxHeaders/x", 'x', 0644, pax, null);
            return entry("ignored-short-name", '0', 0644, text.getBytes(StandardCharsets.UTF_8), null);
        }

        private Tar entry(String name, char type, int mode, byte[] data, String link) throws IOException {
            byte[] h = new byte[512];
            put(h, 0, 100, name);
            put(h, 100, 8, String.format("%07o", mode));
            put(h, 108, 8, "0000000");
            put(h, 116, 8, "0000000");
            put(h, 124, 12, String.format("%011o", data.length));
            put(h, 136, 12, "00000000000");
            for (int i = 148; i < 156; i++) {
                h[i] = ' ';
            }
            h[156] = (byte) type;
            if (link != null) {
                put(h, 157, 100, link);
            }
            put(h, 257, 6, "ustar");
            put(h, 263, 2, "00");
            int sum = 0;
            for (byte b : h) {
                sum += b & 0xff;
            }
            put(h, 148, 8, String.format("%06o", sum) + "\0 ");
            bytes.write(h);
            bytes.write(data);
            int pad = (512 - data.length % 512) % 512;
            bytes.write(new byte[pad]);
            return this;
        }

        private static void put(byte[] h, int off, int len, String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(b, 0, h, off, Math.min(len, b.length));
        }

        Path gzipTo(Path file) throws IOException {
            bytes.write(new byte[1024]);
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
                bytes.writeTo(out);
            }
            return file;
        }
    }

    /** A zip file of {@code name, text} pairs. */
    static Path zip(Path file, String... nameAndText) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < nameAndText.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameAndText[i]));
                zip.write(nameAndText[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    /** A Neovim-like release: {@code top/bin/nvim} (executable) and {@code top/share/nvim/runtime/}. */
    static Path neovimTarGz(Path file, String top) throws IOException {
        return new Tar().folder(top + "/").folder(top + "/bin/").file(top + "/bin/nvim", "#!/bin/sh\n", true)
                .folder(top + "/share/nvim/runtime/").file(top + "/share/nvim/runtime/filetype.lua", "-- ft\n", false)
                .gzipTo(file);
    }

    private TestArchives() {
    }
}
