package com.dshmobile.app;

import java.net.HttpURLConnection;
import java.net.URL;

/** Network metadata lookup used only for size and mirror diagnostics. */
public final class ProvisionMetadata {
    public static long contentLength(String url) {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(7000); c.setReadTimeout(9000); c.setInstanceFollowRedirects(true);
            c.setRequestMethod("HEAD"); c.setRequestProperty("User-Agent","dsh-mobile/1.0");
            int code=c.getResponseCode(); if(code>=200&&code<400)return c.getContentLengthLong();
        } catch(Exception ignored) {} finally {if(c!=null)c.disconnect();}
        c=null;
        try {
            c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(7000); c.setReadTimeout(9000); c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Range","bytes=0-0"); c.setRequestProperty("User-Agent","dsh-mobile/1.0");
            int code=c.getResponseCode(); long n=c.getContentLengthLong();
            String cr=c.getHeaderField("Content-Range");
            if(cr!=null){int slash=cr.lastIndexOf('/'); if(slash>=0){try{n=Long.parseLong(cr.substring(slash+1));}catch(Exception ignored){}}}
            if((code==200||code==206)&&n>0)return n;
        } catch(Exception ignored) {} finally {if(c!=null)c.disconnect();}
        return -1;
    }
    private ProvisionMetadata(){}
}
