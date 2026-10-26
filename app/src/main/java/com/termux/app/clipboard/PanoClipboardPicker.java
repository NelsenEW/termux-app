package com.termux.app.clipboard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;
import java.security.MessageDigest;

/** Returns an explicitly selected Pano item to the terminal that opened the picker. */
public final class PanoClipboardPicker {
    public static final int REQUEST=6522;
    private static final String PACKAGE="dev.nelsen.dexpano";
    private static final String CERT="7703cdd2be0559957c03b2b6df991d9abd9e53efc4ebaa8363ec434d35e06834";
    private final TermuxActivity activity;
    private TerminalSession pending;
    private ClipData selected;
    public PanoClipboardPicker(TermuxActivity activity){this.activity=activity;}

    @SuppressWarnings("deprecation")
    private boolean trustedPano() throws Exception {
        byte[] digest=new byte[32];
        for(int i=0;i<digest.length;i++)digest[i]=(byte)Integer.parseInt(CERT.substring(i*2,i*2+2),16);
        PackageManager pm=activity.getPackageManager();
        PackageInfo info=pm.getPackageInfo(PACKAGE,PackageManager.GET_SIGNATURES);
        if(info.versionCode<21)return false;
        if(Build.VERSION.SDK_INT>=28)return pm.hasSigningCertificate(PACKAGE,digest,PackageManager.CERT_INPUT_SHA256);
        return info.signatures!=null && info.signatures.length==1 && MessageDigest.isEqual(digest,
            MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray()));
    }
    public void open(TerminalSession session){
        if(pending!=null || session==null || !session.isRunning())return;
        if(activity.getTerminalView().getDisplay()==null || activity.getTerminalView().getDisplay().getDisplayId()==0){
            activity.showToast("Connect DeX to open Pano",false);return;
        }
        try {
            if(!trustedPano()){activity.showToast("Update DeX Pano Keys to use the clipboard picker",true);return;}
            Intent intent=new Intent("dev.nelsen.dexpano.action.PICK_CLIP")
                .setComponent(new ComponentName(PACKAGE,PACKAGE+".KittyPickerActivity"));
            try {
                ClipData current=((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
                if(current!=null){intent.setClipData(current);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            }catch(SecurityException ignored){}
            pending=session;
            activity.startActivityForResult(intent,REQUEST);
        }catch(Exception error){pending=null;activity.showToast("Install the updated DeX Pano Keys to open the clipboard",true);}
    }
    public void result(int code,Intent data){
        if(pending==null)return;
        if(code!=Activity.RESULT_OK || data==null || data.getClipData()==null){clear();return;}
        selected=data.getClipData();
        if(selected.getItemCount()!=1){clear();return;}
        activity.getTerminalView().post(this::pasteWhenFocused);
    }
    public void pasteWhenFocused(){
        if(selected==null || !activity.isVisible() || !activity.hasWindowFocus())return;
        TerminalSession session=pending;ClipData clip=selected;clear();
        if(session==null || !session.isRunning() || activity.getCurrentSession()!=session){
            activity.showToast("Pano selection cancelled because the terminal session changed",false);return;
        }
        try{activity.getTermuxTerminalSessionClient().mKittyClipboard.paste(session,clip);}
        catch(Exception error){activity.showToast("Unable to paste the Pano item",true);}
    }
    private void clear(){pending=null;selected=null;}
}
