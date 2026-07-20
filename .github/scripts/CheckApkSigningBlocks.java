import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Rejects APK signing-block entries that F-Droid's binary scanner forbids. */
final class CheckApkSigningBlocks {
    private static final byte[] APK_SIGNING_BLOCK_MAGIC =
        "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);
    private static final int DEPENDENCY_METADATA_ID = 0x504B4453;
    private static final byte[] EOCD_MAGIC = {'P', 'K', 5, 6};
    private static final int MAX_EOCD_SIZE = 22 + 65_535;

    private CheckApkSigningBlocks() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java CheckApkSigningBlocks.java APK");
            System.exit(2);
        }

        var apkPath = Path.of(args[0]);
        try {
            var entryIds = signingBlockIds(Files.readAllBytes(apkPath));
            if (entryIds.contains(DEPENDENCY_METADATA_ID)) {
                System.err.println(apkPath + ": found forbidden dependency metadata signing block");
                System.exit(1);
            }
            System.out.println(apkPath + ": no forbidden dependency metadata signing block");
        } catch (IllegalArgumentException exception) {
            System.err.println(apkPath + ": " + exception.getMessage());
            System.exit(2);
        }
    }

    private static java.util.Set<Integer> signingBlockIds(byte[] apk) {
        var eocdOffset = findEocd(apk);
        var buffer = ByteBuffer.wrap(apk).order(ByteOrder.LITTLE_ENDIAN);
        var centralDirectoryOffset = Integer.toUnsignedLong(buffer.getInt(eocdOffset + 16));
        if (centralDirectoryOffset < 24 || centralDirectoryOffset > apk.length) {
            return java.util.Set.of();
        }

        var centralDirectory = Math.toIntExact(centralDirectoryOffset);
        var magicStart = centralDirectory - APK_SIGNING_BLOCK_MAGIC.length;
        if (!matches(apk, magicStart, APK_SIGNING_BLOCK_MAGIC)) {
            return java.util.Set.of();
        }

        var footerSizeOffset = centralDirectory - 24;
        var blockSize = buffer.getLong(footerSizeOffset);
        var blockStart = centralDirectoryOffset - blockSize - 8;
        if (blockSize < 24 || blockStart < 0 || blockStart > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid APK signing block size");
        }

        var cursor = Math.toIntExact(blockStart);
        if (buffer.getLong(cursor) != blockSize) {
            throw new IllegalArgumentException("APK signing block size fields do not match");
        }
        cursor += 8;

        var entryIds = new java.util.HashSet<Integer>();
        while (cursor < footerSizeOffset) {
            if (cursor + 8 > footerSizeOffset) {
                throw new IllegalArgumentException("truncated APK signing block entry size");
            }
            var entrySize = buffer.getLong(cursor);
            cursor += 8;
            if (entrySize < 4 || entrySize > footerSizeOffset - cursor) {
                throw new IllegalArgumentException("invalid APK signing block entry size");
            }
            entryIds.add(buffer.getInt(cursor));
            cursor = Math.toIntExact(cursor + entrySize);
        }
        return entryIds;
    }

    private static int findEocd(byte[] apk) {
        var searchStart = Math.max(0, apk.length - MAX_EOCD_SIZE);
        for (var offset = apk.length - 22; offset >= searchStart; offset--) {
            if (!matches(apk, offset, EOCD_MAGIC)) {
                continue;
            }
            var commentLength = Short.toUnsignedInt(
                ByteBuffer.wrap(apk, offset + 20, 2).order(ByteOrder.LITTLE_ENDIAN).getShort()
            );
            if (offset + 22 + commentLength == apk.length) {
                return offset;
            }
        }
        throw new IllegalArgumentException("ZIP end-of-central-directory record not found");
    }

    private static boolean matches(byte[] input, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > input.length) {
            return false;
        }
        for (var index = 0; index < expected.length; index++) {
            if (input[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
