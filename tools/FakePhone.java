// Protocol test client: behaves like the Android app against a running Mac app.
// Usage: java tools/FakePhone.java [host] [port] [--wrong-key | --full]
// Default is handshake-only (nothing shows up on the Mac). --full also sends test notifications,
// clipboard text and a file, which DO appear on the Mac.
// Reads the pairing key from ~/Library/Application Support/MacNDroid/identity.json.
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.*;

public class FakePhone {
    static SecretKeySpec key;
    static long sendCtr = 0, recvCtr = 0;

    static byte[] nonce(int dir, long c) { return ByteBuffer.allocate(12).putInt(dir).putLong(c).array(); }

    static byte[] seal(String s) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(1, sendCtr++)));
        return c.doFinal(s.getBytes(StandardCharsets.UTF_8));
    }

    static String open(byte[] b) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(2, recvCtr++)));
        return new String(c.doFinal(b), StandardCharsets.UTF_8);
    }

    static void write(DataOutputStream o, byte[] p) throws IOException { o.writeInt(p.length); o.write(p); o.flush(); }
    static byte[] read(DataInputStream i) throws IOException { byte[] b = new byte[i.readInt()]; i.readFully(b); return b; }
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static void main(String[] a) throws Exception {
        String host = a.length > 0 ? a[0] : "127.0.0.1";
        int port = a.length > 1 ? Integer.parseInt(a[1]) : 47800;
        boolean wrongKey = a.length > 2 && a[2].equals("--wrong-key");
        boolean full = a.length > 2 && a[2].equals("--full");

        String id = Files.readString(Path.of(System.getProperty("user.home"),
            "Library/Application Support/MacNDroid/identity.json"));
        String macId = field(id, "macId");
        byte[] pairingKey = Base64.getDecoder().decode(field(id, "key").replace("\\/", "/"));
        if (wrongKey) pairingKey[0] ^= 1;

        Socket s = new Socket(host, port);
        s.setSoTimeout(5000);
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());

        byte[] pn = new byte[16]; new SecureRandom().nextBytes(pn);
        write(out, ("{\"t\":\"hello\",\"v\":1,\"phoneId\":\"test\",\"nonce\":\"" + Base64.getEncoder().encodeToString(pn) + "\"}").getBytes());
        String hello = new String(read(in));
        System.out.println("← " + hello);
        if (!macId.equals(field(hello, "macId"))) throw new AssertionError("macId mismatch");
        byte[] mn = Base64.getDecoder().decode(field(hello, "nonce").replace("\\/", "/"));

        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(pairingKey, "HmacSHA256"));
        h.update("mnd-v1".getBytes()); h.update(pn); h.update(mn);
        key = new SecretKeySpec(h.doFinal(), "AES");

        write(out, seal("{\"t\":\"device\",\"name\":\"Test Pixel\",\"model\":\"Google Pixel 9\",\"os\":\"Android 16\"}"));
        String welcome;
        try { welcome = open(read(in)); }
        catch (EOFException e) { System.out.println("✓ Mac rejected wrong key (connection closed before welcome)"); return; }
        System.out.println("← " + welcome);
        if (wrongKey) throw new AssertionError("wrong key was accepted!");
        if (!full) {
            write(out, seal("{\"t\":\"ping\"}"));
            System.out.println("← " + open(read(in)));
            s.close();
            System.out.println("✓ handshake OK (use --full for the end-to-end test)");
            return;
        }

        write(out, seal("{\"t\":\"battery\",\"level\":76,\"charging\":true}"));
        write(out, seal("{\"t\":\"notif\",\"key\":\"0|com.whatsapp|1\",\"pkg\":\"com.whatsapp\",\"app\":\"WhatsApp\",\"title\":\"Priya\",\"text\":\"Are we still on for dinner tonight?\",\"time\":" + System.currentTimeMillis() + ",\"call\":false,\"actions\":[{\"i\":0,\"title\":\"Reply\",\"reply\":true},{\"i\":1,\"title\":\"Mark as read\",\"reply\":false}]}"));
        write(out, seal("{\"t\":\"notif\",\"key\":\"0|com.google.android.apps.messaging|2\",\"pkg\":\"com.google.android.apps.messaging\",\"app\":\"Messages\",\"title\":\"HDFC Bank\",\"text\":\"Your verification code is 482913. Do not share it.\",\"time\":" + System.currentTimeMillis() + ",\"call\":false,\"actions\":[]}"));
        write(out, seal("{\"t\":\"clip\",\"text\":\"Hello from the fake phone\",\"explicit\":true}"));

        byte[] file = "This file came from the phone.\n".repeat(20000).getBytes();
        write(out, seal("{\"t\":\"file_start\",\"id\":\"f1\",\"name\":\"from-phone-test.txt\",\"size\":" + file.length + ",\"mime\":\"text/plain\"}"));
        for (int off = 0; off < file.length; off += 256 * 1024) {
            int n = Math.min(256 * 1024, file.length - off);
            byte[] chunk = java.util.Arrays.copyOfRange(file, off, off + n);
            write(out, seal("{\"t\":\"file_chunk\",\"id\":\"f1\",\"data\":\"" + Base64.getEncoder().encodeToString(chunk) + "\"}"));
        }
        write(out, seal("{\"t\":\"file_end\",\"id\":\"f1\"}"));
        write(out, seal("{\"t\":\"ping\"}"));
        System.out.println("→ sent battery, 2 notifications, clipboard, " + file.length + "-byte file, ping");

        long until = System.currentTimeMillis() + Long.parseLong(System.getProperty("listen", "4000"));
        while (System.currentTimeMillis() < until) {
            try { System.out.println("← " + open(read(in))); }
            catch (java.net.SocketTimeoutException e) { break; }
        }
        s.close();
        System.out.println("✓ done");
    }
}
