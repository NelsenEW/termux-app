package com.termux.terminal;

import java.util.Arrays;
import java.util.Base64;
import java.util.zip.Deflater;

/** Pixel's real probe and delta wire format, including transfers split across PTY reads. */
public class PixelGuiTest extends TerminalTestCase {
    private static String data(byte... bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private void image(int w, int h, byte[] pixels) {
        enterString("\033_Ga=t,f=32,s=" + w + ",v=" + h + ",i=302,q=2;" + data(pixels) + "\033\\");
    }
    private void edit(String keys, String payload) {
        enterString("\033_Ga=f,f=32,i=302,r=1,X=1," + keys + ";" + payload + "\033\\");
    }
    public void testPixelProbeAndRootReplacement() {
        withTerminalSized(10, 5);
        image(1, 1, new byte[]{0,0,0,(byte)255});
        edit("s=1,v=1", data((byte)255,(byte)0,(byte)0,(byte)255));
        assertEquals("\033_Gi=302;OK\033\\", mOutput.getOutputAndClear());
        assertTrue(Arrays.equals(new byte[]{(byte)255,0,0,(byte)255}, mTerminal.copyKittyImageData(302)));
    }
    public void testRectanglePreservesPixelsOutsideDamageAndCursor() {
        withTerminalSized(10, 5);
        image(3, 2, new byte[24]);
        enterString("ab");
        edit("x=1,y=1,s=1,v=1", data((byte)10,(byte)20,(byte)30,(byte)255));
        byte[] expected = new byte[24]; expected[16]=10;expected[17]=20;expected[18]=30;expected[19]=(byte)255;
        assertTrue(Arrays.equals(expected,mTerminal.copyKittyImageData(302)));
        assertCursorAt(0,2);
    }
    public void testCompressedChunkedPassthroughDelta() {
        withTerminalSized(10,5); image(2,2,new byte[16]);
        byte[] pixels={1,2,3,(byte)255,4,5,6,(byte)255};
        Deflater d=new Deflater();d.setInput(pixels);d.finish();byte[] compressed=new byte[100];int n=d.deflate(compressed);d.end();
        String payload=data(Arrays.copyOf(compressed,n)); int split=4;
        String wire="\033Ptmux;\033\033_Ga=f,f=32,i=302,r=1,X=1,x=0,y=1,s=2,v=1,o=z,q=2,m=1;"+payload.substring(0,split)+"\033\033\\\033\\"+
            "\033Ptmux;\033\033_Gm=0;"+payload.substring(split)+"\033\033\\\033\\";
        for(int i=0;i<wire.length();i++) enterString(wire.substring(i,i+1));
        assertEquals("",mOutput.getOutputAndClear());
        byte[] expected=new byte[16];System.arraycopy(pixels,0,expected,8,8);
        assertTrue(Arrays.equals(expected,mTerminal.copyKittyImageData(302)));
    }
    public void testBlendUsesStraightAlpha() {
        byte[] dst={0,0,(byte)255,(byte)255};
        int[] colors=KittyFrameEdit.apply(dst,32,1,new byte[]{(byte)255,0,0,(byte)128},32,0,0,1,1,false);
        assertTrue(Arrays.equals(new byte[]{(byte)128,0,127,(byte)255},dst));
        assertEquals(0xff80007f,colors[0]);
    }
    public void testTransparentReplacementClearsPixel() {
        byte[] dst={(byte)255,0,0,(byte)255};
        KittyFrameEdit.apply(dst,32,1,new byte[4],32,0,0,1,1,true);
        assertTrue(Arrays.equals(new byte[4],dst));
    }
    public void testRgbDestinationRemainsOpaque() {
        byte[] dst={0,0,(byte)255};
        int[] colors=KittyFrameEdit.apply(dst,24,1,new byte[]{(byte)255,0,0,(byte)128},32,0,0,1,1,false);
        assertEquals(0xff80007f,colors[0]);
    }
    public void testOutOfBoundsEditLeavesOriginalUntouched() {
        withTerminalSized(10,5);image(1,1,new byte[4]);
        edit("x=1,s=1,v=1",data((byte)1,(byte)2,(byte)3,(byte)4));
        assertEquals("\033_Gi=302;EINVAL:frame edit rectangle outside image\033\\",mOutput.getOutputAndClear());
        assertTrue(Arrays.equals(new byte[4],mTerminal.copyKittyImageData(302)));
    }
    public void testTruncatedEditLeavesOriginalUntouched() {
        withTerminalSized(10,5);image(1,1,new byte[4]);
        edit("s=1,v=1", "AQID");
        assertTrue(mOutput.getOutputAndClear().contains("EINVAL:"));
        assertTrue(Arrays.equals(new byte[4],mTerminal.copyKittyImageData(302)));
    }
    public void testUnsupportedNewFramesAreNotAdvertised() {
        withTerminalSized(10,5);image(1,1,new byte[4]);
        assertEnteringStringGivesResponse("\033_Ga=f,f=32,i=302,r=2,s=1,v=1;AAAAAA==\033\\",
            "\033_Gi=302;ENOTSUP:only root frame edits (r=1) are supported\033\\");
    }
    public void testDeletedImageCannotBeEdited() {
        withTerminalSized(10,5);image(1,1,new byte[4]);
        enterString("\033_Ga=d,d=I,i=302,q=2\033\\");
        edit("s=1,v=1","AAAAAA==");
        assertTrue(mOutput.getOutputAndClear().contains("ENOENT:"));
    }
    public void testPixelMouseNegotiationAndCoordinates() {
        withTerminalSized(10,5);
        assertEnteringStringGivesResponse("\033[?1016$p","\033[?1016;2$y");
        enterString("\033[?1003h\033[?1016h");
        assertEnteringStringGivesResponse("\033[?1016$p","\033[?1016;1$y");
        mTerminal.sendMouseEventPixels(0,77,39,true);
        mTerminal.sendMouseEventPixels(0,77,39,false);
        assertEquals("\033[<0;77;39M\033[<0;77;39m",mOutput.getOutputAndClear());
        mTerminal.sendMouseEventPixels(TerminalEmulator.MOUSE_MOVED,999,-1,true);
        assertEquals("\033[<35;130;1M",mOutput.getOutputAndClear());
    }
    public void testSwitchingBackToCellMouseClearsPixelEncoding() {
        withTerminalSized(10,5);enterString("\033[?1016h\033[?1006h");
        assertFalse(mTerminal.isPixelMouseEnabled());
        assertEnteringStringGivesResponse("\033[?1016$p","\033[?1016;2$y");
        enterString("\033[?1016h");
        assertEnteringStringGivesResponse("\033[?1006$p","\033[?1006;2$y");
    }

    public void testTrackingModesRemainMutuallyExclusive() {
        withTerminalSized(10,5);enterString("\033[?1003h\033[?1006h");
        assertTrue(mTerminal.isMouseTrackingActive());
        enterString("\033[?1000h");
        assertFalse(mTerminal.isAnyMouseTrackingActive());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MOVED,1,1,true);
        assertEquals("",mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(0,2,3,true);
        assertEquals("\033[<0;2;3M",mOutput.getOutputAndClear());
    }
    public void testSynchronizedOutputNegotiatesAcrossReadsAndResets() {
        withTerminalSized(10,5);
        assertEnteringStringGivesResponse("\033[?2026$p","\033[?2026;2$y");
        enterString("\033[?2026h"); assertTrue(mTerminal.isSynchronizedOutputActive());
        enterString("abc"); assertTrue(mTerminal.isSynchronizedOutputActive());
        assertEnteringStringGivesResponse("\033[?2026$p","\033[?2026;1$y");
        enterString("\033[?2026l"); assertFalse(mTerminal.isSynchronizedOutputActive());
        enterString("\033[?2026h\033c"); assertFalse(mTerminal.isSynchronizedOutputActive());
    }
}
