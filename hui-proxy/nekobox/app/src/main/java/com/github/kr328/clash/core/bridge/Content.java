package com.github.kr328.clash.core.bridge;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import io.nekohasekai.sagernet.SagerNet;
public final class Content {
    private Content() {}
    public static int open(String url) throws Exception {
        Uri uri = Uri.parse(url);
        if (!"content".equals(uri.getScheme())) throw new UnsupportedOperationException("Unsupported scheme " + uri.getScheme());
        ParcelFileDescriptor fd = SagerNet.application.getContentResolver().openFileDescriptor(uri, "r");
        if (fd == null) throw new FileNotFoundException(uri + " not found");
        return fd.detachFd();
    }
}
