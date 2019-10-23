package com.comcast.flex;

import static org.junit.Assert.assertEquals;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.io.DirectBufferInputStream;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;

/**
 * Unit test for simple AudioPublisher.
 */
public class AppTest 
{
    /**
     * Rigorous Test :-)
     */

    @Test
    public void testDirectBufferToInputStream() {
        ExpandableDirectByteBuffer mdb = new ExpandableDirectByteBuffer();
        DirectBufferInputStream dbis = new DirectBufferInputStream(mdb);
        mdb.putStringUtf8(0, "hello world");
        mdb.putStringUtf8(0, "second string");
    }


    @Test
    public void testWritableChannel()
    {
        // Create file object
        File file = new File("test_mm.dat");

        //Delete the file; we will create a new file
        file.delete();


        String testString = "Now is the time for all good men to come to the aid of their country.";
        ByteBuffer testByteBuffer = ByteBuffer.wrap(testString.getBytes());
        assertEquals(new String(testByteBuffer.array()), testString);
        int file_length = 0x8FFFFFF;
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile("test_mm.dat", "rw");
            MappedByteBuffer out = randomAccessFile.getChannel()
                    .map(FileChannel.MapMode.READ_WRITE, 0, file_length);

            InputStream is = Channels.newInputStream(randomAccessFile.getChannel());
            out.put(testByteBuffer);

            int length = testString.getBytes().length;
            byte[] data = new byte[length];
            is.read(data, 0, length);

            assertEquals(new String(data), (testString));

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            //Delete the file; we will create a new file
            file.delete();
        }
    }

    @Test
    public void testExtractingHost() {
        String sourceId = "127.0.0.1:51076";
        URI url = URI.create("http://" + sourceId);
        assertEquals("127.0.0.1", url.getHost());
    }
}
