package gr.cytech.sendium.core.smpp.util;

/*
 * #%L
 * ch-commons-charset
 * %%
 * Copyright (C) 2012 Cloudhopper by Twitter
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.charset.ISO88591Charset;
import com.cloudhopper.commons.util.HexUtil;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * The <b>VFGRCharset</b> class is mostly based on Latin-1, using GSM charset for greek letters
 * <br />
 * By using DCS 0 you will submit SMS via VF GR SMSC customised latin 1
 * 1. Greek chars to be sent using the GSM7 respective encoding
 * so for
 * ΔΦΓΛΩΠΨΣΘΞ you will sent HEX  10 12 13 14 15 16 17 18 19 1Α
 * 2. for special characters ~!@#$%^&()_+-=[]{};',.
 * try sending HEX 7E 21 40 23 24 25 5E 26 2A 28 29 5F 2B 2D 3D 5B 5D 7B 7D 3B 27 3A 22 2C 2E 3C 3E 2F 3F 5C 7C
 * Euro sign can be sent with DCS=0 and HEX 80 or with DCS 3 (this is SMSC GSM7) and HEX 1B 65
 * <br />
 * Unsupported GSM characters: ç,Ø,Ä,Ö
 */
public class VFGRCharset extends ISO88591Charset {
    @Override
    public byte[] encode(CharSequence str0) {
        // first, convert UNICODE to GSM
        byte[] latinBytes = super.encode(str0);

        //let's try to find greek CAPITALS and ? and replace them as necessary
        for (int i = 0; i < latinBytes.length; i++) {
            if (latinBytes[i] == (byte) 0x3F) {
                //we found a '?' get the original character and re-map
                char original = str0.charAt(i);
                switch (original) {
                    case '?':
                        break; //it actually was a ?
                    case '\u0394': //Δ
                        latinBytes[i] = (byte) 0x10;
                        break;
                    case '\u03a6': //Φ
                        latinBytes[i] = (byte) 0x12;
                        break;
                    case '\u0393': //Γ
                        latinBytes[i] = (byte) 0x13;
                        break;
                    case '\u039b': //Λ
                        latinBytes[i] = (byte) 0x14;
                        break;
                    case '\u03a9': //Ω
                        latinBytes[i] = (byte) 0x15;
                        break;
                    case '\u03a0': //Π
                        latinBytes[i] = (byte) 0x16;
                        break;
                    case '\u03a8': //Ψ
                        latinBytes[i] = (byte) 0x17;
                        break;
                    case '\u03a3': //Σ
                        latinBytes[i] = (byte) 0x18;
                        break;
                    case '\u0398': //Θ
                        latinBytes[i] = (byte) 0x19;
                        break;
                    case '\u039e': //Ξ
                        latinBytes[i] = (byte) 0x1A;
                        break;
                    case '\u20ac': //€
                        latinBytes[i] = (byte) 0x80;
                        break;
                    default:
                        break;
                }
            }
        }
        return latinBytes;
    }

    @Override
    public void decode(byte[] bytes, StringBuilder buffer) {
        if (bytes == null || bytes.length == 0) {
            return;
        }

        String blindlyConverted = new String(bytes, StandardCharsets.ISO_8859_1);

        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            switch (b) {
                case (byte) 0x10:
                    buffer.append('\u0394'); //Δ
                    break;
                case (byte) 0x12:
                    buffer.append('\u03a6'); //Φ
                    break;
                case (byte) 0x13:
                    buffer.append('\u0393'); //Γ
                    break;
                case (byte) 0x14:
                    buffer.append('\u039b'); //Λ
                    break;
                case (byte) 0x15:
                    buffer.append('\u03a9'); //Ω
                    break;
                case (byte) 0x16:
                    buffer.append('\u03a0'); //Π
                    break;
                case (byte) 0x17:
                    buffer.append('\u03a8'); //Ψ
                    break;
                case (byte) 0x18:
                    buffer.append('\u03a3'); //Σ
                    break;
                case (byte) 0x19:
                    buffer.append('\u0398'); //Θ
                    break;
                case (byte) 0x1A:
                    buffer.append('\u039e'); //Ξ
                    break;
                case (byte) 0x80:
                    buffer.append('\u20ac'); //€
                    break;
                default:
                    buffer.append(blindlyConverted.charAt(i));
                    break;
            }
        }
    }

    @Override
    public String decode(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bytes.length);
        decode(bytes, sb);
        return sb.toString();
    }

    public static void main(String[] args) throws UnsupportedEncodingException {
        String gsmFull = "@£$¥èéùìòØøÅå Δ_ΦΓΛΩΠΨΣΘΞÆæßÉ !#¤%&\\'()*+,-./ :;<=>? ¡ABCDEFGHIJKLMNO PQRSTUVWXYZÄÖÑÜ§ ¿abcdefghijklmno pqrstuvwxyzäöñüà ^{}[~]| €";
        /*String hex = "7E 21 40 23 24 25 5E 26 2A 28 29 5F 2B 2D 3D 5B 5D 7B 7D 3B 27 3A 22 2C 2E 3C 3E 2F 3F 5C 7C".replace(" ", "");
        byte[] hexb = HexUtil.toByteArray(hex);
        System.out.println(Arrays.toString(hexb));
        System.out.println(new String(hexb, CharsetUtil.NAME_ISO_8859_1));
        System.out.println(new String(new byte[]{(byte) 0x3A}));*/
        String unmapped = "ΔΦΓΛΩΠΨΣΘΞ~!@#$%^&()_+-=[]{};',.€";
        for (char c : unmapped.toCharArray()) {
            byte[] gsmb = CharsetUtil.encode("" + c, CharsetUtil.CHARSET_GSM);
            String gsmx = HexUtil.toHexString(gsmb);
            byte[] latb = CharsetUtil.encode("" + c, CharsetUtil.CHARSET_ISO_8859_1);
            String latx = HexUtil.toHexString(latb);
            System.out.println(
                    "char: " + c +
                            // " lat:  " + Arrays.toString(gsmb) +
                            " latx: " + latx +
                            // " gsm:  " + Arrays.toString(gsmb) +
                            " gsmx: " + gsmx
            );
        }
    }
}
