package com.comcast.flex;

import io.aeron.*;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.samples.RateReporter;
import io.aeron.samples.SampleConfiguration;
import io.aeron.samples.SamplesUtil;
import io.aeron.samples.StreamingPublisher;
import org.agrona.BitUtil;
import org.agrona.BufferUtil;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SigInt;
import org.agrona.concurrent.UnsafeBuffer;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.comcast.flex.AudioConstants.*;
import static io.aeron.samples.SampleConfiguration.FRAGMENT_COUNT_LIMIT;
import static java.lang.Boolean.TRUE;
import static javax.sound.sampled.AudioSystem.getMixer;

/**
 * This class takes the input audio bytes from mic and forwards them via Aeron publisher via UDP.
 */
public class AudioPublisher
{
    private static final int STREAM_ID = SampleConfiguration.STREAM_ID;
    private static final String CHANNEL = SampleConfiguration.CHANNEL;
    //private static final String RETURN_CHANNEL = SampleConfiguration.PONG_CHANNEL;
    private static final String RETURN_CHANNEL = "aeron:udp?endpoint=0.0.0.0:40124";
    private static final boolean EMBEDDED_MEDIA_DRIVER = SampleConfiguration.EMBEDDED_MEDIA_DRIVER;

    private static AudioFormat.Encoding encoding = AudioFormat.Encoding.PCM_SIGNED;
    private static float rate = 44100.0f;
    private static int channels = 2;
    private static int sampleSize = 16;
    private static boolean bigEndian = true;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final IdleStrategy idleStrategy = SampleConfiguration.newIdleStrategy();
    private static final RateReporter reporter = new RateReporter(TimeUnit.SECONDS.toNanos(1),
            StreamingPublisher::printRate);
    private static final FragmentHandler fragmentHandler = new FragmentAssembler(AudioPublisher::pongHandler);

    public static void main( String[] args )
    {
        System.out.println("Publishing to " + CHANNEL + " on stream id " + STREAM_ID);
        //aws endpoint for aeron -Daeron.sample.channel=aeron:udp?endpoint=ec2-34-212-31-216.us-west-2.compute.amazonaws.com:40123
        //localhost endpoint -Daeron.sample.channel=aeron:udp?endpoint=localhost:40123

        final ExecutorService executor = Executors.newFixedThreadPool(2);

        //executor.execute(reporter);

        final MediaDriver driver = EMBEDDED_MEDIA_DRIVER ? MediaDriver.launchEmbedded() : null;

        final Aeron.Context ctx = new Aeron.Context()
                .availableImageHandler(SamplesUtil::printAvailableImage)
                .unavailableImageHandler(SamplesUtil::printUnavailableImage);

        if (EMBEDDED_MEDIA_DRIVER)
        {
            System.out.println("embedded media driver launched");
            ctx.aeronDirectoryName(driver.aeronDirectoryName());
        } else {
            System.out.println("standalone media driver");
        }

        // Register a SIGINT handler for graceful shutdown.
        SigInt.register(() -> running.set(false));

        Aeron aeron = Aeron.connect(ctx);

        final ConcurrentPublication publication =
                aeron.addPublication(
                        new ChannelUriStringBuilder()
                                .media("udp")
                                .reliable(TRUE)
                                .endpoint("ec2-34-212-31-216.us-west-2.compute.amazonaws.com:40123")
                                .build(),
                        STREAM_ID);

        final Subscription subscription =
                aeron.addSubscription(
                        new ChannelUriStringBuilder()
                                .media("udp")
                                .reliable(TRUE)
                                .controlEndpoint("ec2-34-212-31-216.us-west-2.compute.amazonaws.com:40124")
                                .controlMode("dynamic")
                                .endpoint("192.168.0.110:8000")
                                .build(),
                        STREAM_ID,
                        image -> System.out.println("client: connected to server"),
                        image -> System.out.println("client: disconnected from server"));

        // Create an Aeron instance using the configured Context and create a
        //try
             //(Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             //Subscription subscription = aeron.addSubscription(RETURN_CHANNEL, STREAM_ID);)
        {
            executor.execute(
                    () -> {
                        subscriberLoop(fragmentHandler, FRAGMENT_COUNT_LIMIT, running).accept(subscription);
                    });

            handleAudioInput(publication);
            System.out.println("Shutting down...");
        }

        reporter.halt();
        executor.shutdown();
        CloseHelper.quietClose(driver);

    }

    private static void handleAudioInput(Publication publication) {
        System.out.println("handling audio input, publication isConnected= " + publication.isConnected());
        TargetDataLine targetDataLine = getTargetDataLine();
        if (targetDataLine == null) {
            System.err.println("No MIC input available -- SHUT DOWN!");
            return;
        }


        try {
            int numBytesRead;
            targetDataLine.open(audioFormat());

            // Begin audio capture.
            targetDataLine.start();
            System.out.println("start reading from target data line buffer size = " + targetDataLine.getBufferSize() + " publication isConnected= " + publication.isConnected());
            // Here, running is a global boolean set by another thread.
            while (running.get()) {
                //System.out.println("reading from target data line");
                // Read the next chunk of data from the TargetDataLine.
                byte[] data = new byte[targetDataLine.getBufferSize()];
                numBytesRead =  targetDataLine.read(data, 0, data.length);
                if (numBytesRead == 0) {
                    System.out.println("sleeping no bytes read");
                    Thread.sleep(TimeUnit.SECONDS.toMillis(1));
                    continue;
                }
                //System.out.println("publishing " + numBytesRead);
                sendToCloud(publication, numBytesRead, data);
               //sendToSpeakers(format, data);
            }
        } catch (Exception e2) {
            e2.printStackTrace();
        }
    }

    private static void sendToCloud(Publication publication, int numBytesRead, byte[] data) {
        UnsafeBuffer OFFER_BUFFER = new UnsafeBuffer(
                BufferUtil.allocateDirectAligned(numBytesRead, BitUtil.CACHE_LINE_LENGTH));
        //System.out.println("OFFER_BUFFER capacity = " + OFFER_BUFFER.capacity());
        OFFER_BUFFER.putBytes(0, data);
        idleStrategy.reset();
        //System.out.println("PUBLICATION isConnected = " + publication.isConnected());
        while (publication.offer(OFFER_BUFFER, 0, numBytesRead) < 0L)
        {
            // The offer failed, which is usually due to the publication
            // being temporarily blocked.  Retry the offer after a short
            // spin/yield/sleep, depending on the chosen IdleStrategy.
            //backPressureCount++;
            System.out.println("publication offer failed");
            idleStrategy.idle();
        }
        //reporter.onMessage(1, numBytesRead);
    }

    private static TargetDataLine getTargetDataLine() {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        Mixer micMixer;
        TargetDataLine line = null;
        for (Mixer.Info info : mixers) {
            System.out.println(info.toString() + " mixer description: " + info.getDescription());
            if (info.getName().contains("Headset Microphone (Pixel USB-C")) {
                micMixer = getMixer(info);
                Line.Info[] lineInfos = micMixer.getTargetLineInfo();
                if(lineInfos.length >=1 && lineInfos[0].getLineClass().equals(TargetDataLine.class)){//Only prints out info if it is a Microphone
                    System.out.println("Line Name: " + info.getName());//The name of the AudioDevice
                    System.out.println("Line Description: " + info.getDescription());//The type of audio device
                    for (Line.Info lineInfo:lineInfos){
                        System.out.println ("\t"+"---"+lineInfo);
                        try {
                            line = (TargetDataLine) micMixer.getLine(lineInfo);
                        } catch (LineUnavailableException e) {
                            e.printStackTrace();
                            return null;
                        }
                        System.out.println("\t-----"+line);
                    }
                } else {
                    System.out.println("did not have any target line info");
                }
            }
        }
        return line;
    }

    private static void pongHandler(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        System.out.println("received text back: " + buffer.getStringWithoutLengthUtf8(offset, length));
    }
}
