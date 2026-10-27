package com.termux.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.Message;
import com.termux.view.TerminalRenderer;
import com.termux.view.TerminalView;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidFrameCommitTest {
    private TerminalSession session;
    private int updates;
    private boolean screenVisible = true;

    @Before public void setup() {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(),
            new Class<?>[]{TerminalSessionClient.class}, (proxy, method, args) -> {
                if (method.getName().equals("isTerminalScreenVisible")) return screenVisible;
                if (method.getName().equals("onTextChanged")) updates++;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
        session = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 100, client);
        session.mEmulator = new TerminalEmulator(session, 10, 5, 2, 2, 100, client);
    }

    private String frame(String text) {
        return "\033[?2026h\033[H\033[2J" + text + "\033[?2026l";
    }

    private void receive(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(session.mProcessToTerminalIOQueue.write(bytes, 0, bytes.length));
        session.mMainThreadHandler.handleMessage(
            Message.obtain(session.mMainThreadHandler, 1));
    }

    private String screen() {
        return session.mEmulator.getScreen().getSelectedText(0, 0, 9, 4).trim();
    }

    private void drawn() {
        session.onScreenDrawn();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void coalescedFramesRemainSeparateUntilTheViewDraws() {
        receive(frame("FIRST") + frame("SECOND"));
        assertEquals("FIRST", screen());
        assertEquals(1, updates);
        assertFalse(session.mEmulator.isSynchronizedOutputActive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("FIRST", screen());
        drawn();
        assertEquals("SECOND", screen());
        assertEquals(2, updates);
        drawn();
        assertEquals("SECOND", screen());
    }

    @Test public void splitEndMarkerStopsBeforeTheFollowingFrame() {
        receive("\033[?2026h\033[H\033[2JFIRST\033[?202");
        assertTrue(session.mEmulator.isSynchronizedOutputActive());
        assertEquals(0, updates);
        receive("6l" + frame("SECOND"));
        assertEquals("FIRST", screen());
        assertFalse(session.mEmulator.isSynchronizedOutputActive());
        drawn();
        assertEquals("SECOND", screen());
    }

    @Test public void arrivalsDuringDrawingDoNotOverwriteThePendingTail() {
        receive(frame("FIRST") + frame("SECOND"));
        receive(frame("THIRD"));
        assertEquals("FIRST", screen());
        drawn();
        assertEquals("SECOND", screen());
        drawn();
        assertEquals("THIRD", screen());
        drawn();
        assertEquals(3, updates);
    }

    @Test public void aVisibleSessionContinuesAfterTheBoundedDrawTimeout() {
        receive(frame("FIRST") + frame("SECOND"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(249, TimeUnit.MILLISECONDS);
        assertEquals("FIRST", screen());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals("SECOND", screen());
        assertEquals(2, updates);
    }

    @Test public void aHiddenSessionDrainsBurstsWithoutWaitingForDrawTimeouts() {
        screenVisible = false;
        StringBuilder burst = new StringBuilder();
        for (int i = 0; i < 512; i++) burst.append(frame("FRAME" + i));
        receive(burst.toString());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("FRAME511", screen());
        assertEquals(512, updates);
        assertEquals(0, session.mProcessToTerminalIOQueue.read(new byte[1], false));
    }

    @Test public void detachingAVisibleClientReleasesItsPendingFrameImmediately() {
        receive(frame("FIRST") + frame("SECOND"));
        assertEquals("FIRST", screen());
        screenVisible = false;
        session.updateTerminalSessionClient(session.mClient);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("SECOND", screen());
        assertEquals(2, updates);
    }

    @Test public void ordinaryTerminalInputIsNotDelayedWaitingForADraw() {
        receive("ordinary");
        assertEquals("ordinary", screen());
        receive(" text");
        assertTrue(screen().contains("ordinary"));
        assertEquals(2, updates);
    }

    @Test public void anActualCanvasDrawReleasesTheNextCompletedFrame() throws Exception {
        receive(frame("FIRST") + frame("SECOND"));
        TerminalView view = new TerminalView(RuntimeEnvironment.getApplication(), null);
        view.mEmulator = session.mEmulator;
        view.mRenderer = new TerminalRenderer(14, Typeface.MONOSPACE);
        Field field = TerminalView.class.getDeclaredField("mTermSession");
        field.setAccessible(true);
        field.set(view, session);
        Bitmap output = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888);
        java.lang.reflect.Method draw = TerminalView.class.getDeclaredMethod("onDraw", Canvas.class);
        draw.setAccessible(true);
        draw.invoke(view, new Canvas(output));
        assertEquals("FIRST", screen());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("SECOND", screen());
        output.recycle();
    }

}
