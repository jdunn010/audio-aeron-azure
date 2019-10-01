package com.comcast.flex;

import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.samples.SampleConfiguration;
import org.agrona.LangUtil;
import org.agrona.concurrent.IdleStrategy;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class AudioConstants {
    private static AudioFormat.Encoding encoding = AudioFormat.Encoding.PCM_SIGNED;
    //private static float rate = 44100.0f;
    private static float rate = 16000.0f;
    private static int channels = 1;
    private static int sampleSize = 16;
    private static boolean bigEndian = false;

    static AudioFormat audioFormat() {
        return new AudioFormat(encoding,
                rate,
                sampleSize,
                channels,
                (sampleSize / 8) * channels,
                rate,
                bigEndian);
    }

    public static Consumer<Subscription> subscriberLoop(FragmentHandler fragmentHandler, int limit, AtomicBoolean running) {
        IdleStrategy idleStrategy = SampleConfiguration.newIdleStrategy();
        return subscriberLoop(fragmentHandler, limit, running, idleStrategy);
    }

    public static Consumer<Subscription> subscriberLoop(FragmentHandler fragmentHandler, int limit, AtomicBoolean running, IdleStrategy idleStrategy) {
        return (subscription) -> {
            while(true) {
                try {
                    if (running.get()) {
                        int fragmentsRead = subscription.poll(fragmentHandler, limit);
                        //System.out.println("fragmentsRead = " + fragmentsRead);
                        idleStrategy.idle(fragmentsRead);
                        continue;
                    } else {
                        System.out.println("not running");
                    }
                } catch (Exception var6) {
                    LangUtil.rethrowUnchecked(var6);
                }

                return;
            }
        };
    }
    static void sendToSpeakers(byte[] data) throws Exception {
        sendToSpeakers(audioFormat(), data);
    }

    static void playClip(byte[] data) throws Exception {
        Clip clip = AudioSystem.getClip();
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        AudioInputStream ais = new AudioInputStream(bais,audioFormat(),data.length);
        clip.open(ais);
        clip.start();
        Thread.sleep(500);
        clip.flush();
        clip.drain();
        clip.close();
        //clip.loop(Clip.LOOP_CONTINUOUSLY);
    }

    static void sendToSpeakers(AudioFormat format, byte[] data) throws Exception {
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class,format);
        SourceDataLine speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        speaker.open(format);
        speaker.start();

        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        AudioInputStream ais = new AudioInputStream(bais,format,data.length);
        int bytesRead = 0;
        if((bytesRead = ais.read(data)) != -1){
            System.out.println("Writing to audio output data.length = " + data.length );
            speaker.write(data,0, bytesRead);

            //                 bais.reset();
        }
        ais.close();
        bais.close();
        speaker.drain();
        speaker.close();
    }
}
