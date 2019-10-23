package com.comcast.flex;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import io.aeron.Aeron;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.samples.RateReporter;
import io.aeron.samples.SampleConfiguration;
import io.aeron.samples.StreamingPublisher;
import org.agrona.BitUtil;
import org.agrona.BufferUtil;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.aeron.samples.SampleConfiguration.PONG_CHANNEL;
import static io.aeron.samples.SampleConfiguration.STREAM_ID;


public class AzureAudioFragmentHandler  implements FragmentHandler {

    static Publication publisher;
    static Aeron aeron;
    static String speechSubscriptionKey = System.getProperty("AZURE_SUBSCRIPTION_KEY");
    static {
        if (speechSubscriptionKey == null) {
            speechSubscriptionKey = System.getenv("AZURE_SUBSCRIPTION_KEY");
        }
    }
    static String serviceRegion = "westus";
    private static final IdleStrategy idleStrategy = SampleConfiguration.newIdleStrategy();
    static SpeechConfig config = SpeechConfig.fromSubscription(speechSubscriptionKey, serviceRegion);
    static  PushAudioInputStream pushStream = AudioInputStream.createPushStream();

    // Creates a speech recognizer using Push Stream as audio input.
    static AudioConfig audioInput = AudioConfig.fromStreamInput(pushStream);
    static SpeechRecognizer speechRecognizer = new SpeechRecognizer(config, audioInput);
    private static final RateReporter reporter = new RateReporter(TimeUnit.SECONDS.toNanos(1),
            StreamingPublisher::printRate);

    private static final ConcurrentHashMap<String, Publication> publications = new ConcurrentHashMap<>();


    static
    {
        speechRecognizer.recognizing.addEventListener((o, speechRecognitionEventArgs) -> {
            final String s = speechRecognitionEventArgs.getResult().getText();
            System.out.println("s = " + s);
        });

        speechRecognizer.recognized.addEventListener((s, e) ->  {
            try {
                final String text = e.getResult().getText();
                String aeronSessionId = config.getProperty("aeron-session-id");
                if (aeronSessionId == null || aeronSessionId.isEmpty()) {
                    throw new IllegalArgumentException();
                }
                System.out.println("aeronSessionId = " + aeronSessionId);
                if (text.isEmpty() == false) {
                    //Publication publication = publications.get(aeronSessionId);
                    System.out.println("recognized final text = " + text + " within sessionId=" + e.getSessionId());
                    if (publisher == null) {
                        throw new IllegalArgumentException();
                    }
                    //send back to original source
                    sendResultsBack(publisher, text.getBytes().length, text.getBytes());
                }
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        });

        speechRecognizer.canceled.addEventListener((s, e) -> {
            System.out.println("CANCELED: Reason=" + e.getReason());

            if (e.getReason() == CancellationReason.Error) {
                System.out.println("CANCELED: ErrorCode=" + e.getErrorCode());
                System.out.println("CANCELED: ErrorDetails=" + e.getErrorDetails());
                System.out.println("CANCELED: Did you update the subscription info?");
            }
        });

        speechRecognizer.sessionStarted.addEventListener((s, sessionEventArgs) -> {
            System.out.println("\n    Session started event, session id = " + sessionEventArgs.getSessionId());
        });

        speechRecognizer.sessionStopped.addEventListener((s, sessionEventArgs) -> {
            System.out.println("\n    Session stopped event, session id = " + sessionEventArgs.getSessionId());
            publications.remove("" + sessionEventArgs.getSessionId());
        });

        speechRecognizer.startContinuousRecognitionAsync();
    }

    public AzureAudioFragmentHandler(Aeron aeron, Publication publisher) {
        AzureAudioFragmentHandler.aeron = aeron;
        AzureAudioFragmentHandler.publisher = publisher;
    }

    @Override
    public void onFragment(DirectBuffer directBuffer, int offset, int length, Header header) {
        Image image = (Image) header.context();

        //this is the session id for aeron
        /*
        System.out.println("session id = " + header.sessionId() +
                " stream id = " + header.streamId() +
                " source identity = " + image.sourceIdentity());
         */
        AzureAudioFragmentHandler.config.setProperty("aeron-session-id", "" + header.sessionId());
        if (AzureAudioFragmentHandler.config.getProperty("aeron-session-id") == null){
            throw new IllegalArgumentException();
        }
        /*
        if (publications.containsKey("" + header.sessionId()) == false ) {
            if (image.sourceIdentity().contains("127.0.0.1")) {
                publications.putIfAbsent("" + header.sessionId(), publisher);
            } else {
                //todo do something about this
                URI url = URI.create("http://" + image.sourceIdentity());
                String udpEndpoint = "aeron:udp?endpoint=" + url.getHost() + ":40124";
                Publication publication = aeron.addPublication( udpEndpoint, STREAM_ID);
                publications.putIfAbsent("" + header.sessionId(), publication);
                System.out.println("aeron publisher created = " + publication);
            }
        }*/
        byte[] data = new byte[length];
        directBuffer.getBytes(offset, data);

        pushStream.write(data);
    }

    private static void sendResultsBack(Publication publication, int numBytesRead, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        try {
            System.out.println("sending recognized speech results back "  + new String(data) + " aeron publisher = " + publication + " is connected = " + publication.isConnected());

            UnsafeBuffer OFFER_BUFFER = new UnsafeBuffer(
                    BufferUtil.allocateDirectAligned(numBytesRead, BitUtil.CACHE_LINE_LENGTH));
            //System.out.println("OFFER_BUFFER capacity = " + OFFER_BUFFER.capacity());

            OFFER_BUFFER.putBytes(0, data);
            idleStrategy.reset();
            long result = 0L;
            for (int index = 0; index < 5; ++index) {
                result = publication.offer(OFFER_BUFFER, 0, numBytesRead);
                if (result < 0L) {
                    try {
                        Thread.sleep(100L);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                System.out.println("results sent back");
                return;
            }
            throw new IOException(
                    "Could not send message: Error code: " + errorCodeName(result));
            //reporter.onMessage(1, numBytesRead);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String errorCodeName(final long result)
    {
        if (result == Publication.NOT_CONNECTED) {
            return "Not connected";
        }
        if (result == Publication.ADMIN_ACTION) {
            return "Administrative action";
        }
        if (result == Publication.BACK_PRESSURED) {
            return "Back pressured";
        }
        if (result == Publication.CLOSED) {
            return "Publication is closed";
        }
        if (result == Publication.MAX_POSITION_EXCEEDED) {
            return "Maximum term position exceeded";
        }
        throw new IllegalStateException();
    }
}
