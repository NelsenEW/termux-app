package com.termux.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.Shadows;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PixelGuiRenderingTest {
    private TerminalEmulator terminal;
    private ByteArrayOutputStream replies;
    @Before public void setup() {
        replies = new ByteArrayOutputStream();
        TerminalOutput output = new TerminalOutput() {
            public void write(byte[] b,int o,int n){replies.write(b,o,n);}
            public void titleChanged(String a,String b){}
            public void onCopyTextToClipboard(String s){}
            public void onPasteTextFromClipboard(){}
            public void onBell(){}
            public void onColorsChanged(){}
        };
        terminal = new TerminalEmulator(output,10,5,2,2,100,null);
    }
    private void send(String s) {
        byte[] b=s.getBytes(StandardCharsets.UTF_8);terminal.append(b,b.length);
    }
    private String rgba(int... colors) {
        byte[] bytes=new byte[colors.length*4];
        for(int i=0;i<colors.length;i++) {
            bytes[i*4]=(byte)(colors[i]>>16);bytes[i*4+1]=(byte)(colors[i]>>8);
            bytes[i*4+2]=(byte)colors[i];bytes[i*4+3]=(byte)(colors[i]>>>24);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
    private void image(String placement) {
        send("\033_Ga=T,f=32,s=2,v=2,i=302,q=0,C=1,"+placement+";"+
            rgba(0xff0000ff,0xff0000ff,0xff0000ff,0xff0000ff)+"\033\\");
        assertTrue(replies.toString().contains("OK"));replies.reset();
    }
    private void edit(int color) {
        send("\033_Ga=f,f=32,i=302,r=1,X=1,x=1,y=0,s=1,v=1;"+rgba(color)+"\033\\");
        assertTrue(replies.toString().contains("OK"));replies.reset();
    }
    @Test public void virtualPlacementSeesEditsWithoutReplacingPrototype() {
        image("U=1,c=1,r=1");
        KittyPlaceholder placement=terminal.getKittyPlaceholder(302);assertNotNull(placement);
        edit(0xffff0000);
        assertSame(placement,terminal.getKittyPlaceholder(302));
        Bitmap target=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        placement.drawCell(new Canvas(target),0,0,2,2,0,0);
        assertEquals(0xffff0000,target.getPixel(1,0));assertEquals(0xff0000ff,target.getPixel(0,1));
    }
    @Test public void classicPlacementUpdatesPixelsAndKeepsCursor() {
        image("c=1,r=1");
        long bitmapStyle=terminal.getScreen().getStyleAt(0,0);
        TerminalBitmap placement=terminal.getScreen().getTerminalBitmap(bitmapStyle);assertNotNull(placement);
        edit(0xff00ff00);
        assertEquals(0xff00ff00,placement.getBitmap().getPixel(1,0));
        assertEquals(0xff0000ff,placement.getBitmap().getPixel(0,1));
        assertEquals(0,terminal.getCursorRow());assertEquals(0,terminal.getCursorCol());
    }
    @Test public void croppedScaledPlacementReceivesEditAtItsOwnCoordinates() {
        image("x=1,y=0,w=1,h=2,c=2,r=2");
        long bitmapStyle=terminal.getScreen().getStyleAt(0,0);
        TerminalBitmap placement=terminal.getScreen().getTerminalBitmap(bitmapStyle);assertNotNull(placement);
        edit(0xffff0000);
        assertEquals(0xffff0000,placement.getBitmap().getPixel(0,0));
        assertEquals(0xff0000ff,placement.getBitmap().getPixel(0,placement.getBitmap().getHeight()-1));
    }
    @Test public void transparentDeltaClearsVirtualImagePixel() {
        image("U=1,c=1,r=1");edit(0);
        Bitmap target=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        terminal.getKittyPlaceholder(302).drawCell(new Canvas(target),0,0,2,2,0,0);
        assertEquals(0,target.getPixel(1,0));assertEquals(0xff0000ff,target.getPixel(0,1));
    }
    @Test public void sessionDefersUpdatesAndTimeoutRecoversMissingEndMarker() {
        final int[] updates={0};
        TerminalSessionClient client=(TerminalSessionClient)Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(),new Class[]{TerminalSessionClient.class},(proxy,method,args)->{
                if(method.getName().equals("onTextChanged")) updates[0]++;
                if(method.getReturnType()==boolean.class)return false;
                if(method.getReturnType()==int.class)return 0;
                return null;
            });
        TerminalSession session=new TerminalSession("/bin/sh","/",new String[0],new String[0],100,client);
        session.mEmulator=new TerminalEmulator(session,10,5,2,2,100,client);
        session.mEmulator.doDecSetOrReset(true,2026);
        session.notifyScreenUpdate();assertEquals(0,updates[0]);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1001,TimeUnit.MILLISECONDS);
        assertFalse(session.mEmulator.isSynchronizedOutputActive());assertEquals(1,updates[0]);
        session.mEmulator.doDecSetOrReset(true,2026);
        session.mEmulator.doDecSetOrReset(false,2026);session.notifyScreenUpdate();
        assertEquals(2,updates[0]);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1001,TimeUnit.MILLISECONDS);
        assertEquals(2,updates[0]);
    }
}
