import io.github.trevarj.motd.ai.tts.KokoroEngine;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Calls the shipped Kotlin wrapper, including its compiled native-chunk lambda. */
public final class KokoroJniSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void playableWav(File file) throws Exception {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(file)) {
            AudioFormat format = audio.getFormat();
            check(format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                    && format.getChannels() == 1 && format.getSampleSizeInBits() == 16
                    && format.getSampleRate() == 24000f && !format.isBigEndian(),
                    "Not playable mono PCM16 24 kHz WAV: " + file);
            long frames = audio.getFrameLength();
            check(frames >= 2400 && frames <= 24000 * 60 && file.length() == 44 + frames * 2,
                    "Incomplete or implausible speech WAV: " + file);
            byte[] buffer = new byte[4096];
            long samples = 0;
            double squared = 0;
            for (int count; (count = audio.read(buffer)) != -1; ) {
                check(count % 2 == 0, "Incomplete PCM sample");
                for (int i = 0; i < count; i += 2) {
                    short sample = (short) ((buffer[i] & 255) | (buffer[i + 1] << 8));
                    squared += (double) sample * sample;
                    samples++;
                }
            }
            double rms = Math.sqrt(squared / Math.max(1, samples)) / 32768;
            check(samples == frames && rms > 0.0001, "Truncated or silent speech WAV: " + file);
            System.out.printf("%s: mono PCM16 24000 Hz, %.3fs, RMS=%.6f%n",
                    file, samples / 24000.0, rms);
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "Usage: KokoroJniSmoke <checked-assets> <absent-or-empty-output.wav>");
        Path output = Path.of(args[1]).toAbsolutePath();
        check(!Files.isSymbolicLink(output)
                && (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                    || (Files.isRegularFile(output) && Files.size(output) == 0)),
                "Use an absent or precreated zero-byte regular output file");
        Path cancelled = output.resolveSibling(output.getFileName() + ".cancelled.wav");
        Path reused = output.resolveSibling(output.getFileName() + ".reused.wav");
        check(!Files.exists(cancelled, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(reused, LinkOption.NOFOLLOW_LINKS), "Use fresh smoke output paths");
        boolean precreated = Files.exists(output);
        KokoroEngine engine = KokoroEngine.Companion.load(new File(args[0]));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch continueChunk = new CountDownLatch(1);
        try {
            check(engine.getSampleRate() == 24000 && engine.getNumSpeakers() == 54,
                    "Wrong checked Kokoro model");
            File generated = engine.generate("Alice says: Hello from the real Kotlin JNI speech wrapper.",
                    output.toFile(), 3, "en-us", 1f, () -> false);
            check(generated.equals(output.toFile()), "Wrapper did not return the completed output");
            playableWav(generated);
            System.out.println("Completed real compiled Kotlin callback ABI; precreated output=" + precreated);

            Files.createFile(cancelled);
            CountDownLatch nativeChunk = new CountDownLatch(1);
            AtomicInteger cancellationChecks = new AtomicInteger();
            AtomicBoolean stop = new AtomicBoolean();
            Future<File> generating = worker.submit(() -> engine.generate(
                    "This speech must stop at a native audio chunk, before any WAV is saved. ".repeat(30),
                    cancelled.toFile(), 3, "en-us", 1f, () -> {
                        if (cancellationChecks.incrementAndGet() == 1) return false;
                        nativeChunk.countDown();
                        try {
                            check(continueChunk.await(60, TimeUnit.SECONDS), "Chunk cancellation gate timed out");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(failure);
                        }
                        return stop.get();
                    }));
            check(nativeChunk.await(60, TimeUnit.SECONDS), "No real JNI audio chunk arrived");
            stop.set(true);
            engine.cancel();
            continueChunk.countDown();
            try {
                generating.get(60, TimeUnit.SECONDS);
                throw new AssertionError("Cancelled synthesis returned a WAV");
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof CancellationException)) throw failure;
            }
            check(!Files.exists(cancelled, LinkOption.NOFOLLOW_LINKS), "Cancellation retained partial output");

            Files.createFile(reused);
            playableWav(engine.generate("Bob says: Native synthesis drained and this engine works again.",
                    reused.toFile(), 16, "en-us", 1f, () -> false));
            System.out.println("Verified native-chunk cancellation, JNI drain, partial deletion and engine reuse");

            for (int voiceId : new int[] {21, 26}) {
                Path british = output.resolveSibling(output.getFileName() + ".british-" + voiceId + ".wav");
                check(!Files.exists(british, LinkOption.NOFOLLOW_LINKS), "Use fresh British smoke output paths");
                playableWav(engine.generate("Trev says: Hello, how are you? Shall we walk to the theatre?",
                        british.toFile(), voiceId, "en", 1f, () -> false));
            }
            System.out.println("Verified British female and male voices with Great Britain pronunciation");
        } finally {
            continueChunk.countDown();
            engine.release();
            worker.shutdownNow();
            check(worker.awaitTermination(60, TimeUnit.SECONDS), "Synthesis worker did not drain");
        }
        System.out.println("Released real Kotlin KokoroEngine");
    }
}
