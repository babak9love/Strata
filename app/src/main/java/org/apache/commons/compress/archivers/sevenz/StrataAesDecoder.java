package org.apache.commons.compress.archivers.sevenz;

import dev.cluvex.strata.core.archive.SevenZKeys;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.compress.PasswordRequiredException;

public final class StrataAesDecoder extends AbstractCoder {

    private final AbstractCoder original;

    StrataAesDecoder(final AbstractCoder original) {
        super(AES256Options.class);
        this.original = original;
    }

    public static synchronized boolean install() {
        try {
            final Field field = Coders.class.getDeclaredField("CODER_MAP");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            final Map<SevenZMethod, AbstractCoder> map = (Map<SevenZMethod, AbstractCoder>) field.get(null);
            final AbstractCoder current = map.get(SevenZMethod.AES256SHA256);
            if (current instanceof StrataAesDecoder) {
                return true;
            }
            if (current == null) {
                return false;
            }
            map.put(SevenZMethod.AES256SHA256, new StrataAesDecoder(current));
            return true;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static boolean isInstalled() {
        return Coders.findByMethod(SevenZMethod.AES256SHA256) instanceof StrataAesDecoder;
    }

    @Override
    InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder,
            final byte[] passwordBytes, final int maxMemoryLimitKiB) throws IOException {
        if (passwordBytes == null) {
            throw new PasswordRequiredException(archiveName);
        }
        final byte[] props = coder.properties;
        if (props == null || props.length < 2) {
            throw new IOException("Missing AES256 properties in " + archiveName);
        }
        final int b0 = props[0] & 0xff;
        final int power = b0 & 0x3f;
        final int b1 = props[1] & 0xff;
        final int ivSize = ((b0 >> 6) & 1) + (b1 & 0x0f);
        final int saltSize = ((b0 >> 7) & 1) + (b1 >> 4);
        if (2 + saltSize + ivSize > props.length) {
            throw new IOException("Salt size + IV size too long in " + archiveName);
        }
        if (power > SevenZKeys.MAX_POWER && power != SevenZKeys.NO_HASHING) {
            throw new IOException("Unsupported number of cycles (2^" + power + ") in " + archiveName);
        }
        final byte[] salt = Arrays.copyOfRange(props, 2, 2 + saltSize);
        final byte[] iv = new byte[16];
        System.arraycopy(props, 2 + saltSize, iv, 0, ivSize);
        final byte[] key = SevenZKeys.key(passwordBytes, salt, power);
        try {
            final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new CipherInputStream(in, cipher);
        } catch (final GeneralSecurityException e) {
            throw new IOException("AES decryption is not available: " + e.getMessage(), e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    OutputStream encode(final OutputStream out, final Object options) throws IOException {
        return original.encode(out, options);
    }

    @Override
    byte[] getOptionsAsProperties(final Object options) throws IOException {
        return original.getOptionsAsProperties(options);
    }

    @Override
    Object getOptionsFromCoder(final Coder coder, final InputStream in) throws IOException {
        return original.getOptionsFromCoder(coder, in);
    }
}
