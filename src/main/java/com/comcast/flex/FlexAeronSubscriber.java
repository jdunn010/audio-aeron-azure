package com.comcast.flex;

import io.aeron.*;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.samples.SampleConfiguration;
import io.aeron.samples.SamplesUtil;
import org.agrona.CloseHelper;
import org.agrona.concurrent.SigInt;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.comcast.flex.AudioConstants.subscriberLoop;
import static java.lang.Boolean.TRUE;

/**
 * copied this from BasicPublisher in aeron ssmples
 */
public class FlexAeronSubscriber {
    private static final int STREAM_ID;
    private static final String CHANNEL;
    private static final String RETURN_CHANNEL;
    private static final int FRAGMENT_COUNT_LIMIT;
    private static final boolean EMBEDDED_MEDIA_DRIVER;

    static {
        STREAM_ID = SampleConfiguration.STREAM_ID;
        CHANNEL = SampleConfiguration.CHANNEL;
        RETURN_CHANNEL = SampleConfiguration.PONG_CHANNEL;
        FRAGMENT_COUNT_LIMIT = SampleConfiguration.FRAGMENT_COUNT_LIMIT;
        EMBEDDED_MEDIA_DRIVER = SampleConfiguration.EMBEDDED_MEDIA_DRIVER;
    }

    public static void main(String[] args) {

        System.out.println("Subscribing to " + CHANNEL + " on stream id " + STREAM_ID);
        MediaDriver driver = EMBEDDED_MEDIA_DRIVER ? MediaDriver.launchEmbedded() : null;
        Aeron.Context ctx = (new Aeron.Context())
                .availableImageHandler(SamplesUtil::printAvailableImage)
                .unavailableImageHandler(SamplesUtil::printUnavailableImage);

        if (EMBEDDED_MEDIA_DRIVER) {
            System.out.println("using an embedded media driver");
            ctx.aeronDirectoryName(driver.aeronDirectoryName());
        } else {
            System.out.println("using a standalone media driver");
        }


        AtomicBoolean running = new AtomicBoolean(true);
        SigInt.register(() -> {
            running.set(false);
        });

        Aeron aeron = Aeron.connect(ctx);
        Throwable var6 = null;

        final ConcurrentPublication publication =
                aeron.addPublication(
                        new ChannelUriStringBuilder()
                                .media("udp")
                                .reliable(TRUE)
                                .controlEndpoint("96.115.215.119:40124")
                                .controlMode("dynamic")
                                .endpoint("96.115.215.119:40122")
                                .build(),
                        STREAM_ID);


        final Subscription subscription =
                aeron.addSubscription(
                        new ChannelUriStringBuilder()
                                .media("udp")
                                .reliable(TRUE)
                                .endpoint("0.0.0.0:40123")
                                .build(),
                        STREAM_ID,
                        image -> System.out.println("server: a client has connected"),
                        image -> System.out.println("server: a client has disconnected"));

        try {
            Throwable var8 = null;
            //FragmentHandler fragmentHandler = new AudioFragmentHandler();
            //use the default localhost aeron publication
            Thread.sleep(1000);
            System.out.println("default publication = " + publication + " is connected = " + publication.isConnected() );
            FragmentHandler fragmentHandler = new AzureAudioFragmentHandler(aeron, publication);
            FragmentAssembler fragmentAssembler = new FragmentAssembler(fragmentHandler);
            try {
                subscriberLoop(fragmentAssembler, FRAGMENT_COUNT_LIMIT, running).accept(subscription);
                System.out.println("Shutting down...");
            } catch (Throwable var31) {
                var8 = var31;
                throw var31;
            } finally {
                if (subscription != null) {
                    if (var8 != null) {
                        try {
                            subscription.close();
                        } catch (Throwable var30) {
                            var8.addSuppressed(var30);
                        }
                    } else {
                        subscription.close();
                    }
                }
            }
        } catch (Throwable var33) {
            var6 = var33;
            try {
                throw var33;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } finally {
            if (var6 != null) {
                try {
                    aeron.close();
                } catch (Throwable var29) {
                    var6.addSuppressed(var29);
                }
            } else {
                aeron.close();
            }
        }

        CloseHelper.quietClose(driver);
    }
}
