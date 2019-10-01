package com.comcast.flex;

import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.io.DirectBufferInputStream;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient;
import software.amazon.awssdk.services.transcribestreaming.model.*;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.comcast.flex.AudioConstants.audioFormat;
import static org.agrona.concurrent.ringbuffer.RingBufferDescriptor.TRAILER_LENGTH;

public class AudioFragmentHandler implements FragmentHandler {
    //int file_length = 0x8FFFFFF;

    AtomicBoolean isAwsClientStarted = new AtomicBoolean(false);

    AtomicBuffer mdb = new UnsafeBuffer(new byte[1073741824 + TRAILER_LENGTH]);
    OneToOneRingBuffer ringBuffer = new OneToOneRingBuffer(mdb);
    DirectBufferInputStream dbis = new DirectBufferInputStream(mdb);

    TranscribeStreamingAsyncClient client = TranscribeStreamingAsyncClient
            .builder()
            //.credentialsProvider(DefaultCredentialsProvider.create())
            .build();

    StartStreamTranscriptionRequest request = StartStreamTranscriptionRequest.builder()
            .mediaEncoding(MediaEncoding.PCM)
            .languageCode(LanguageCode.EN_US)
            //.mediaSampleRateHertz(44_100).build();
            .mediaSampleRateHertz(44_100).build();

    StartStreamTranscriptionResponseHandler response = getResponseHandler();
    /*
            StartStreamTranscriptionResponseHandler.builder().subscriber(e -> {
                TranscriptEvent event = (TranscriptEvent) e;
                event.transcript().results().forEach(r -> r.alternatives().forEach(a -> System.out.println(a.transcript())));
            }).build();

*/

    OneToOneConcurrentArrayQueue queue;
    AudioStreamPublisher publisher;
    {
        try {

            /*
            // Create file object
            File file = new File("test_mm.dat");
            file.delete();
            RandomAccessFile randomAccessFile = new RandomAccessFile("test_mm.dat", "rw");
            audioBuffer = randomAccessFile.getChannel()
                    .map(FileChannel.MapMode.READ_WRITE, 0, file_length);

            InputStream ais = Channels.newInputStream(randomAccessFile.getChannel());
             */
            publisher = new AudioStreamPublisher(dbis);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onFragment(DirectBuffer directBuffer, int offset, int length, Header header) {
        try {
            //toSpeaker(data);
                        /*
            System.out.println("handling fragment of length = " + length + " header stream id = " + header.streamId());
            */
            //byte[] data = new byte[length];
            //directBuffer.getBytes(offset, data);
            //audioBuffer.put(ByteBuffer.wrap(data));

            ringBuffer.write(1,directBuffer, offset, length);
            if (!isAwsClientStarted.getAndSet(true)) {
                System.out.println("starting aws client streaming");
                client.startStreamTranscription(request, publisher, response);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendToAWSTranscribe(byte[] data) {

        //System.out.println("sending bytes to AWS transcribe " + data.length);
        //System.out.println("channel open = " + channel.isOpen());



    }

    private void toSpeaker(byte soundbytes[]) {
        if (soundbytes == null || soundbytes.length == 0) {
            System.out.println("nothing in audio stream");
            return;
        }
        try {
            SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat()));
            sourceDataLine.open(audioFormat());
            sourceDataLine.start();
            System.out.println(soundbytes.length + " bytes sent to the speaker");
            sourceDataLine.write(soundbytes, 0, soundbytes.length);
            sourceDataLine.drain();
            sourceDataLine.stop();
        } catch (Exception e) {
            System.out.println("Not working in speakers...");
            e.printStackTrace();
        }
    }


    private static AwsCredentialsProvider getCredentials() {
        return DefaultCredentialsProvider.create();
    }

    private static StartStreamTranscriptionRequest getRequest(Integer mediaSampleRateHertz) {
        return StartStreamTranscriptionRequest.builder()
                .languageCode(LanguageCode.EN_US.toString())
                .mediaEncoding(MediaEncoding.PCM)
                .mediaSampleRateHertz(mediaSampleRateHertz)
                .build();
    }

    private static StartStreamTranscriptionResponseHandler getResponseHandler() {
        return StartStreamTranscriptionResponseHandler.builder()
                .onResponse(r -> {
                    System.out.println("Received Initial response" + r);
                })
                .onError(e -> {
                    System.out.println(e.getMessage());
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    System.out.println("Error Occurred: " + sw.toString());
                })
                .onComplete(() -> {
                    System.out.println("=== All records stream successfully ===");
                })
                .subscriber(event -> {
                    //System.out.println("received event on audio stream " + event);
                    List<Result> results = ((TranscriptEvent) event).transcript().results();
                    if (results.size() > 0) {
                        if (!results.get(0).alternatives().get(0).transcript().isEmpty()) {
                            if(results.get(0).isPartial() == false) {
                                System.out.println(results.get(0).alternatives().get(0).transcript());
                            }
                        }
                    }
                })
                .build();
    }

}

