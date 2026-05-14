package gr.cytech.sendium.core.smpp.util;

import com.cloudhopper.commons.charset.JavaCharset;

public class CustomCharset extends JavaCharset {
    public CustomCharset(String charsetName) {
        super(charsetName);
    }

    @Override
    public int estimateEncodeByteLength(CharSequence str0) {
        return str0 == null ? 0 : str0.length();
    }

    @Override
    public int estimateDecodeCharLength(byte[] bytes) {
        return bytes.length;
    }
}
