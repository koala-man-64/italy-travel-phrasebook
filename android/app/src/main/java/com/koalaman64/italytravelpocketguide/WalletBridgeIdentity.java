package com.koalaman64.italytravelpocketguide;

import java.util.Map;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Exact main-document admission shared by the Android listener and tests. */
final class WalletBridgeIdentity {
    static final String ORIGIN="https://appassets.androidplatform.net";
    static final String DOCUMENT=ORIGIN+"/assets/index.html";
    static boolean trusted(boolean mainFrame,String origin,String currentUrl) {
        return mainFrame&&ORIGIN.equals(origin)&&DOCUMENT.equals(currentUrl);
    }
    static boolean bound(String raw,Context expected)throws Exception {
        Map<String,Object> m=WalletCodec.parse(WalletCodec.utf8(raw,2048),2048);
        WalletCodec.keys(m,"v","kind","context","nonce");
        nonce(m.get("nonce"));
        return WalletCodec.number(m.get("v"))==1&&"bound".equals(m.get("kind"))&&WalletWebChannel.context(expected).equals(WalletCodec.map(m.get("context")));
    }
    static String nonce(Object raw)throws Exception { String value=WalletCodec.text(raw);if(!value.matches("[0-9a-f]{32}"))throw new WalletFailure(Code.INVALID_DATA);return value; }
    static boolean currentFrame(String raw,Context expected)throws Exception {
        Map<String,Object> m=WalletCodec.parse(WalletCodec.utf8(raw,WalletWebChannel.ENVELOPE_BYTES),WalletWebChannel.ENVELOPE_BYTES);
        return WalletWebChannel.context(expected).equals(m.get("context"));
    }
}
