package com.termux.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidJpegFrameTest {
    private TerminalEmulator terminal;
    private ByteArrayOutputStream replies;

    @Before public void setup() {
        replies = new ByteArrayOutputStream();
        TerminalOutput output = new TerminalOutput() {
            public void write(byte[] bytes, int offset, int length) { replies.write(bytes, offset, length); }
            public void titleChanged(String oldTitle, String newTitle) {}
            public void onCopyTextToClipboard(String text) {}
            public void onPasteTextFromClipboard() {}
            public void onBell() {}
            public void onColorsChanged() {}
        };
        terminal = new TerminalEmulator(output, 20, 10, 2, 2, 100, null);
    }

    private void send(String command) {
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        terminal.append(bytes, bytes.length);
    }

    private String response() {
        String result = new String(replies.toByteArray(), StandardCharsets.UTF_8);
        replies.reset();
        return result;
    }

    private String base64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }

    private byte[] jpeg(int width, int height, int[] pixels) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output));
        bitmap.recycle();
        return output.toByteArray();
    }

    private byte[] rgba(int[] pixels) {
        byte[] result = new byte[pixels.length * 4];
        for (int i = 0; i < pixels.length; i++) {
            result[i * 4] = (byte) (pixels[i] >>> 16);
            result[i * 4 + 1] = (byte) (pixels[i] >>> 8);
            result[i * 4 + 2] = (byte) pixels[i];
            result[i * 4 + 3] = (byte) (pixels[i] >>> 24);
        }
        return result;
    }

    private byte[] decoded(byte[] jpeg) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        bitmap.recycle();
        return rgba(pixels);
    }

    private byte[] compressed(byte[] bytes) {
        Deflater deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return output.toByteArray();
    }

    @Test public void jpegQueryDecodesRealBitmapAndPreservesExistingImage() {
        send("\033_Ga=t,f=32,i=77,s=1,v=1,q=2;AQIDBA==\033\\");
        byte[] jpeg = jpeg(1, 1, new int[] {0xff804020});
        send("\033_Ga=q,f=101,i=77,s=1,v=1;" + base64(jpeg) + "\033\\");
        assertEquals("\033_Gi=77;OK\033\\", response());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, terminal.copyKittyImageData(77));
    }

    @Test public void compressedMultipartJpegAndLosslessBorderPreserveFirstRectangleAndAlpha() {
        byte[] base = new byte[160 * 150 * 4];
        for (int i = 0; i < base.length; i += 4) {
            base[i] = 20; base[i + 1] = 30; base[i + 2] = 40; base[i + 3] = 77;
        }
        send("\033_Ga=t,f=32,i=77,s=160,v=150,q=2;" + base64(base) + "\033\\");
        int[] pixels = new int[128 * 128];
        Random random = new Random(77);
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | random.nextInt(1 << 24);
        byte[] jpeg = jpeg(128, 128, pixels);
        byte[] expectedPixels = decoded(jpeg);
        String payload = base64(compressed(jpeg));
        assertTrue(payload.length() > 8192);
        send("\033[?2026h");
        for (int offset = 0; offset < payload.length(); offset += 4096) {
            int end = Math.min(offset + 4096, payload.length());
            int more = end < payload.length() ? 1 : 0;
            String header = offset == 0 ? "a=f,f=101,o=z,i=77,r=1,X=1,x=7,y=5,s=128,v=128,q=1" : "a=f,i=77,q=1";
            send("\033_G" + header + ",m=" + more + ";" + payload.substring(offset, end) + "\033\\");
            if (more != 0) assertArrayEquals(base, terminal.copyKittyImageData(77));
        }
        byte[] border = new byte[2 * 128 * 4];
        for (int i = 0; i < border.length; i += 4) {
            border[i] = 5; border[i + 1] = 6; border[i + 2] = 7; border[i + 3] = 80;
        }
        send("\033_Ga=f,f=32,o=z,i=77,r=1,X=1,x=5,y=5,s=2,v=128,q=1;" + base64(compressed(border)) + "\033\\");
        send("\033[?2026l");
        byte[] expected = base.clone();
        for (int y = 0; y < 128; y++) {
            System.arraycopy(expectedPixels, y * 128 * 4, expected, ((y + 5) * 160 + 7) * 4, 128 * 4);
            System.arraycopy(border, y * 2 * 4, expected, ((y + 5) * 160 + 5) * 4, 2 * 4);
        }
        assertEquals("", response());
        assertArrayEquals(expected, terminal.copyKittyImageData(77));
        assertFalse(terminal.isSynchronizedOutputActive());
    }

    @Test public void jpegEditUpdatesExistingVirtualBitmapAndTransparentEditClearsIt() {
        send("\033_Ga=T,f=32,i=77,s=2,v=2,U=1,c=1,r=1,q=2;" +
            base64(rgba(new int[] {0xff0000ff, 0xff0000ff, 0xff0000ff, 0xff0000ff})) + "\033\\");
        KittyPlaceholder original = terminal.getKittyPlaceholder(77);
        assertNotNull(original);
        byte[] jpeg = jpeg(1, 1, new int[] {0xffff0000});
        send("\033_Ga=f,f=101,i=77,r=1,X=1,x=1,y=0,s=1,v=1,q=1;" + base64(jpeg) + "\033\\");
        assertSame(original, terminal.getKittyPlaceholder(77));
        Bitmap target = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        original.drawCell(new Canvas(target), 0, 0, 2, 2, 0, 0);
        Bitmap reference = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        assertEquals(reference.getPixel(0, 0), target.getPixel(1, 0));
        assertEquals(0xff0000ff, target.getPixel(0, 1));
        send("\033_Ga=f,f=32,i=77,r=1,X=1,x=1,y=0,s=1,v=1,q=1;AAAAAA==\033\\");
        Bitmap cleared = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        original.drawCell(new Canvas(cleared), 0, 0, 2, 2, 0, 0);
        assertEquals(0, cleared.getPixel(1, 0));
        assertEquals("", response());
        target.recycle(); reference.recycle(); cleared.recycle();
    }

    @Test public void malformedJpegAndOutOfBoundsEditsLeaveStoredImageUntouched() {
        byte[] base = rgba(new int[] {0xff112233, 0xff445566, 0xff778899, 0xffaabbcc});
        send("\033_Ga=t,f=32,i=77,s=2,v=2,q=2;" + base64(base) + "\033\\");
        byte[] jpeg = jpeg(3, 3, new int[] {0xff123456, 0xff123456, 0xff123456, 0xff123456,
            0xff123456, 0xff123456, 0xff123456, 0xff123456, 0xff123456});
        for (byte[] invalid : new byte[][] {new byte[] {(byte) 255, (byte) 216, 0, (byte) 255, (byte) 217},
                Arrays.copyOf(jpeg, jpeg.length - 2)}) {
            send("\033_Ga=f,f=101,i=77,r=1,X=1,s=1,v=1,q=1;" + base64(invalid) + "\033\\");
            assertTrue(response().contains("EBADDATA:"));
            assertArrayEquals(base, terminal.copyKittyImageData(77));
        }
        send("\033_Ga=f,f=101,i=77,r=1,X=1,x=1,y=0,s=3,v=3,q=1;" + base64(jpeg) + "\033\\");
        assertTrue(response().contains("EINVAL:frame edit rectangle outside image"));
        assertArrayEquals(base, terminal.copyKittyImageData(77));
    }

    @Test public void oversizedJpegIsRejectedBeforeAllocatingDecodedPixels() {
        byte[] jpeg = jpeg(1, 1, new int[] {0xff123456});
        boolean changed = false;
        for (int i = 0; i + 8 < jpeg.length; i++) {
            if ((jpeg[i] & 255) == 255 && (jpeg[i + 1] & 255) == 192) {
                jpeg[i + 5] = 39; jpeg[i + 6] = 16;
                jpeg[i + 7] = 39; jpeg[i + 8] = 16;
                changed = true; break;
            }
        }
        assertTrue(changed);
        send("\033_Ga=q,f=101,i=99;" + base64(jpeg) + "\033\\");
        assertTrue(response().contains("ENOSPC:decoded JPEG exceeds image memory limit"));
        assertEquals(-1, terminal.getKittyImageDataLength(99));
    }
}
