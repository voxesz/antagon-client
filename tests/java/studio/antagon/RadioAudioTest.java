package studio.antagon;

import java.io.*;

import javax.sound.sampled.*;

public final class RadioAudioTest {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        directory.mkdirs();
        File wav = new File(directory, "tone.wav");
        byte[] data = new byte[44100];
        for (int i = 0; i < data.length / 2; i++) {
            short sample = (short) (Math.sin(i * 2 * Math.PI * 440 / 44100) * 1000);
            data[i * 2] = (byte) sample;
            data[i * 2 + 1] = (byte) (sample >> 8);
        }
        AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
        try (AudioInputStream in =
                new AudioInputStream(new ByteArrayInputStream(data), format, data.length / 2)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, wav);
        }
        for (File file : new File[] {wav, new File(args[1])}) {
            final int[] progress = {0};
            RadioAudio player = new RadioAudio();
            player.play(
                    file,
                    new RadioAudio.Listener() {
                        public boolean current() {
                            return true;
                        }

                        public boolean paused() {
                            return false;
                        }

                        public float volume() {
                            return 0;
                        }

                        public double target() {
                            return 0;
                        }

                        public void progress(double position) {
                            if (!Double.isFinite(position)) throw new AssertionError();
                            progress[0]++;
                        }
                    });
            if (progress[0] == 0) throw new AssertionError("No decoded audio: " + file);
            System.out.println("Native audio OK: " + file.getName());
        }
    }
}
