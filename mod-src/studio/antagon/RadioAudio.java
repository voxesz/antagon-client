package studio.antagon;

import javazoom.jl.decoder.*;

import java.io.*;

import javax.sound.sampled.*;

/** MP3/PCM decoding off the game thread, with a bounded JavaSound output buffer. */
final class RadioAudio implements Closeable {
    interface Listener {
        boolean current();

        boolean paused();

        float volume();

        double target();

        void progress(double position);
    }

    private volatile boolean closed;
    private volatile SourceDataLine output;

    void play(File file, Listener listener) throws Exception {
        if (file.getName().endsWith(".mp3")) mp3(file, listener);
        else if (file.getName().endsWith(".wav")) wav(file, listener);
        else if (file.getName().endsWith(".ogg")) ogg(file, listener);
        else throw new IOException("Esta faixa deve ser reproduzida no launcher (M4A).");
    }

    private void mp3(File file, Listener l) throws Exception {
        Bitstream bits = new Bitstream(new BufferedInputStream(new FileInputStream(file)));
        Decoder decoder = new Decoder();
        double at = 0;
        boolean started = false;
        try {
            Header header;
            while (l.current() && !closed && (header = bits.readFrame()) != null) {
                SampleBuffer pcm = (SampleBuffer) decoder.decodeFrame(header, bits);
                int count = pcm.getBufferLength(),
                        rate = pcm.getSampleFrequency(),
                        channels = pcm.getChannelCount();
                double end = at + count / (double) (rate * channels);
                if (started || end >= l.target()) {
                    if (!started) open(new AudioFormat(rate, 16, channels, true, false));
                    int skip =
                            started
                                    ? 0
                                    : Math.min(
                                            count,
                                            Math.max(0, (int) ((l.target() - at) * rate))
                                                    * channels);
                    short[] samples = pcm.getBuffer();
                    byte[] bytes = new byte[(count - skip) * 2];
                    for (int i = skip, j = 0; i < count; i++) {
                        short s = samples[i];
                        bytes[j++] = (byte) s;
                        bytes[j++] = (byte) (s >> 8);
                    }
                    write(
                            bytes,
                            new AudioFormat(rate, 16, channels, true, false),
                            l,
                            at + skip / (double) (rate * channels));
                    started = true;
                }
                at = end;
                bits.closeFrame();
            }
            if (l.current() && !closed && output != null) output.drain();
        } finally {
            bits.close();
            close();
        }
    }

    private void wav(File file, Listener l) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(file)) {
            AudioFormat original = source.getFormat();
            AudioFormat format =
                    new AudioFormat(
                            original.getSampleRate(), 16, original.getChannels(), true, false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(format, source)) {
                open(format);
                byte[] buffer = new byte[8192 - original.getChannels() * 2];
                double at = 0;
                int n;
                boolean started = false;
                while (l.current()
                        && !closed
                        && (n =
                                        pcm.read(
                                                buffer,
                                                0,
                                                buffer.length
                                                        - buffer.length % format.getFrameSize()))
                                > 0) {
                    double end = at + n / (double) (format.getFrameSize() * format.getFrameRate());
                    if (started || end >= l.target()) {
                        int skip =
                                started
                                        ? 0
                                        : Math.min(
                                                n,
                                                Math.max(
                                                                0,
                                                                (int)
                                                                        ((l.target() - at)
                                                                                * format
                                                                                        .getFrameRate()))
                                                        * format.getFrameSize());
                        write(
                                java.util.Arrays.copyOfRange(buffer, skip, n),
                                format,
                                l,
                                at
                                        + skip
                                                / (double)
                                                        (format.getFrameSize()
                                                                * format.getFrameRate()));
                        started = true;
                    }
                    at = end;
                }
                if (l.current() && !closed && output != null) output.drain();
            }
        } finally {
            close();
        }
    }

    private void ogg(File file, Listener l) throws Exception {
        Object codec = Class.forName("paulscode.sound.codecs.CodecJOrbis").newInstance();
        try {
            Reflect.call(codec, new String[] {"reverseByteOrder"}, false);
            if (!Boolean.TRUE.equals(
                    Reflect.call(codec, new String[] {"initialize"}, file.toURI().toURL())))
                throw new IOException("Não foi possível abrir o áudio OGG.");
            AudioFormat format = (AudioFormat) Reflect.call(codec, new String[] {"getAudioFormat"});
            open(format);
            double at = 0;
            boolean started = false;
            while (l.current() && !closed) {
                Object block = Reflect.call(codec, new String[] {"read"});
                if (block == null) break;
                byte[] bytes = (byte[]) Reflect.field(block, "audioData");
                double end =
                        at
                                + bytes.length
                                        / (double) (format.getFrameSize() * format.getFrameRate());
                if (started || end >= l.target()) {
                    int skip =
                            started
                                    ? 0
                                    : Math.min(
                                            bytes.length,
                                            Math.max(
                                                            0,
                                                            (int)
                                                                    ((l.target() - at)
                                                                            * format
                                                                                    .getFrameRate()))
                                                    * format.getFrameSize());
                    write(
                            java.util.Arrays.copyOfRange(bytes, skip, bytes.length),
                            format,
                            l,
                            at + skip / (double) (format.getFrameSize() * format.getFrameRate()));
                    started = true;
                }
                at = end;
            }
            if (l.current() && !closed && output != null) output.drain();
        } finally {
            Reflect.call(codec, new String[] {"cleanup"});
            close();
        }
    }

    private synchronized void open(AudioFormat format) throws Exception {
        if (closed) return;
        output = AudioSystem.getSourceDataLine(format);
        output.open(format, 8192);
        output.start();
    }

    private void write(byte[] bytes, AudioFormat format, Listener l, double position)
            throws Exception {
        SourceDataLine line = output;
        if (line == null || closed) return;
        while (l.current() && !closed && l.paused()) {
            line.stop();
            Thread.sleep(30);
        }
        if (!l.current() || closed) return;
        line.start();
        float gain = l.volume();
        for (int i = 0; i + 1 < bytes.length; i += 2) {
            int sample =
                    format.isBigEndian()
                            ? (short) ((bytes[i] << 8) | (bytes[i + 1] & 255))
                            : (short) ((bytes[i + 1] << 8) | (bytes[i] & 255));
            short scaled = (short) (sample * gain);
            bytes[i] = (byte) (format.isBigEndian() ? scaled >> 8 : scaled);
            bytes[i + 1] = (byte) (format.isBigEndian() ? scaled : scaled >> 8);
        }
        int sent = 0;
        while (sent < bytes.length && l.current() && !closed) {
            int n = line.write(bytes, sent, bytes.length - sent);
            if (n <= 0) break;
            sent += n;
        }
        double queued =
                (line.getBufferSize() - line.available())
                        / (double) (format.getFrameSize() * format.getFrameRate());
        l.progress(
                Math.max(
                        0,
                        position
                                + sent / (double) (format.getFrameSize() * format.getFrameRate())
                                - queued));
    }

    public synchronized void close() {
        closed = true;
        SourceDataLine line = output;
        output = null;
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
        }
    }
}
