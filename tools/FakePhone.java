// Protocol test client: behaves like the Android app against a running Mac app.
// Usage: java tools/FakePhone.java [host] [port] [--wrong-key | --full | --demo <scene>[,<scene>…]]
// Default is handshake-only (nothing shows up on the Mac). --full also sends test notifications,
// clipboard text and a file, which DO appear on the Mac.
//
// --demo plays the video series' demo data (video/series/STORY.md, section 2) as a "Pixel 8" at 82%, so every
// screen recording of the Mac app is repeatable. `--demo list` shows the scenes. It stays connected and answers
// what you do on the Mac (Reply, Mark as read, Answer, Messages, music buttons) the way a phone would.
//   -Ddelay=3     seconds before the scene starts (time to start recording)
//   -Dgap=5       seconds between scenes when several are given
//   -Dlisten=600  seconds to stay connected afterwards
//   -Ddemo.files=video/series/demo   where dinner-menu.jpg, Q4-deck.pdf and the cover are
// Reads the pairing key from ~/Library/Application Support/MacNDroid/identity.json (or -Didentity=<path>).
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
        String demo = a.length > 2 && a[2].equals("--demo") ? (a.length > 3 ? a[3] : "list") : null;
        if ("list".equals(demo)) { System.out.println(SCENES); return; }

        // -Didentity=<path> tests the Windows or Linux app (their identity.json has the same format).
        String idPath = System.getProperty("identity");
        String id = Files.readString(idPath != null ? Path.of(idPath) : Path.of(System.getProperty("user.home"),
            "Library/Application Support/MacNDroid/identity.json"));
        String macId = field(id, "macId");
        byte[] pairingKey = Base64.getDecoder().decode(field(id, "key").replace("\\/", "/"));
        if (wrongKey) pairingKey[0] ^= 1;

        Socket s = new Socket(host, port);
        s.setSoTimeout(5000);
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());

        byte[] pn = new byte[16]; new SecureRandom().nextBytes(pn);
        write(out, ("{\"t\":\"hello\",\"v\":1,\"phoneId\":\"" + (demo != null ? "demo-pixel-8" : "test") + "\",\"nonce\":\"" + Base64.getEncoder().encodeToString(pn) + "\"}").getBytes());
        String hello = new String(read(in));
        System.out.println("← " + hello);
        if (!macId.equals(field(hello, "macId"))) throw new AssertionError("macId mismatch");
        byte[] mn = Base64.getDecoder().decode(field(hello, "nonce").replace("\\/", "/"));

        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(pairingKey, "HmacSHA256"));
        h.update("mnd-v1".getBytes()); h.update(pn); h.update(mn);
        key = new SecretKeySpec(h.doFinal(), "AES");

        write(out, seal(demo != null ? "{\"t\":\"device\",\"name\":\"Pixel 8\",\"model\":\"Google Pixel 8\",\"os\":\"Android 16\"}"
            : "{\"t\":\"device\",\"name\":\"Test Pixel\",\"model\":\"Google Pixel 9\",\"os\":\"Android 16\"}"));
        String welcome;
        try { welcome = open(read(in)); }
        catch (EOFException e) {
            if (demo != null) {
                System.out.println("✗ The Mac turned the demo phone away. It pairs like a phone that scans the QR code for the first time, so the\n"
                    + "  Mac must not be paired with another phone: record in the separate macOS account, or use Reset Pairing there.");
                System.exit(1);
            }
            System.out.println("✓ Mac rejected wrong key (connection closed before welcome)"); return;
        }
        System.out.println("← " + welcome);
        if (wrongKey) throw new AssertionError("wrong key was accepted!");
        if (demo != null) { demo(demo, s, in, out); return; }
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

    // MARK: Demo scenes for the video series

    static final String SCENES = String.join("\n",
        "Scenes (several can be given, separated by commas):",
        "  idle        connected as Pixel 8 at 82%, nothing else (menu bar popover, Ring, Messages, drag files to the notch)",
        "  notify      Priya: \"Dinner at 8? 🍝\" with Reply and Mark as read",
        "  code        Northwind Bank: \"482913 is your sign-in code. Never share it with anyone.\"",
        "  call        incoming call from Sam with Answer and Decline",
        "  mum         a second text, from Mum (for paging through the notch)",
        "  clipboard   the phone copies \"Osteria Lucca, 42 Elm Street\" to the Mac",
        "  screenshot  a phone screenshot lands on the Mac's clipboard",
        "  file-menu   dinner-menu.jpg arrives in Downloads",
        "  file-deck   Q4-deck.pdf arrives in Downloads",
        "  music       \"Golden Hour Drive\" by Lumen Field plays on the phone",
        "  clear       takes the notifications and the music away again",
        "Example: java -Ddelay=5 tools/FakePhone.java 127.0.0.1 47800 --demo notify,code,file-menu");

    static DataOutputStream dout;
    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    static void send(String json) throws Exception { synchronized (FakePhone.class) { write(dout, seal(json)); } }
    static long num(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }
    static Path demoFile(String name) {
        Path dir = Path.of(System.getProperty("demo.files", "video/series/demo"));
        Path p = dir.resolve(name);
        if (!Files.exists(p)) throw new IllegalStateException(p + " is missing. Run tools/video/mac-shots.sh once (it draws the demo files), or pass -Ddemo.files=<folder>.");
        return p;
    }

    static final String REPLY_ACTIONS = "[{\"i\":0,\"title\":\"Reply\",\"reply\":true},{\"i\":1,\"title\":\"Mark as read\",\"reply\":false}]";
    static final String PRIYA = "+1 555 0142", SAM = "+1 555 0178", MUM = "+1 555 0109";
    /** Priya's conversation; the reply typed on the Mac is added to it. */
    static final java.util.List<String[]> priyaThread = new java.util.ArrayList<>();   // {body, out}
    static boolean priyaTexted = false, playing = true;
    static int track = 0;
    static final String[][] TRACKS = { { "Golden Hour Drive", "214000" }, { "Slow Tide", "187000" }, { "Paper Lanterns", "242000" } };

    static void notif(String key, String pkg, String app, String cat, String title, String text, boolean call, String actions) throws Exception {
        send("{\"t\":\"notif\",\"key\":" + q(key) + ",\"pkg\":" + q(pkg) + ",\"app\":" + q(app) + ",\"cat\":" + q(cat) + ",\"title\":" + q(title)
            + ",\"text\":" + q(text) + ",\"time\":" + System.currentTimeMillis() + ",\"call\":" + call + ",\"actions\":" + actions + "}");
        System.out.println("→ " + app + " · " + title + ": " + text);
    }

    static void sendFile(String id, String name, String mime, byte[] data, boolean clip) throws Exception {
        send("{\"t\":\"file_start\",\"id\":" + q(id) + ",\"name\":" + q(name) + ",\"size\":" + data.length + ",\"mime\":" + q(mime) + (clip ? ",\"clip\":true" : "") + "}");
        for (int off = 0; off < data.length; off += 256 * 1024) {
            byte[] chunk = java.util.Arrays.copyOfRange(data, off, Math.min(data.length, off + 256 * 1024));
            send("{\"t\":\"file_chunk\",\"id\":" + q(id) + ",\"data\":\"" + Base64.getEncoder().encodeToString(chunk) + "\"}");
        }
        send("{\"t\":\"file_end\",\"id\":" + q(id) + "}");
        System.out.println("→ " + (clip ? "screenshot " : "file ") + name + " (" + data.length / 1024 + " KB)");
    }

    static void media(boolean withArt) throws Exception {
        long now = System.currentTimeMillis();
        String art = withArt ? ",\"art\":\"" + Base64.getEncoder().encodeToString(Files.readAllBytes(demoFile("golden-hour-drive.jpg"))) + "\"" : "";
        send("{\"t\":\"media\",\"active\":true,\"id\":\"demo.music\",\"pkg\":\"demo.music\",\"app\":\"Music\",\"title\":" + q(TRACKS[track][0])
            + ",\"artist\":\"Lumen Field\",\"album\":\"Golden Hour Drive\",\"duration\":" + TRACKS[track][1] + ",\"playing\":" + playing
            + ",\"position\":" + (track == 0 ? 81000 : 0) + ",\"positionAt\":" + now + ",\"speed\":1,\"since\":" + (playing ? now : 0)
            + ",\"canPrev\":true,\"canNext\":true,\"canSeek\":true" + art + "}");
        System.out.println("→ music: " + TRACKS[track][0] + " by Lumen Field (" + (playing ? "playing" : "paused") + ")");
    }

    static void smsThreads() throws Exception {
        long now = System.currentTimeMillis();
        String[] last = priyaThread.get(priyaThread.size() - 1);
        send("{\"t\":\"sms_threads\",\"threads\":["
            + "{\"id\":\"s1\",\"address\":\"" + PRIYA + "\",\"name\":\"Priya\",\"snippet\":" + q(last[0]) + ",\"date\":" + now
            + ",\"unread\":" + (last[1].equals("true") ? 0 : 1) + ",\"out\":" + last[1] + "},"
            + "{\"id\":\"s3\",\"address\":\"" + MUM + "\",\"name\":\"Mum\",\"snippet\":\"Call me when you're free ❤️\",\"date\":" + (now - 35 * 60000L) + ",\"unread\":0,\"out\":false},"
            + "{\"id\":\"s2\",\"address\":\"" + SAM + "\",\"name\":\"Sam\",\"snippet\":\"Q4 deck is ready when you are\",\"date\":" + (now - 47 * 60000L) + ",\"unread\":0,\"out\":false}]}");
    }

    static void smsThread(String id) throws Exception {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder("{\"t\":\"sms_thread\",\"id\":" + q(id) + ",\"messages\":[");
        if (id.equals("s1")) {
            for (int i = 0; i < priyaThread.size(); i++) {
                String[] m = priyaThread.get(i);
                b.append(i > 0 ? "," : "").append("{\"id\":\"p").append(i).append("\",\"body\":").append(q(m[0])).append(",\"date\":")
                    .append(now - (priyaThread.size() - 1 - i) * 60000L).append(",\"out\":").append(m[1]).append("}");
            }
        } else if (id.equals("s2")) {
            b.append("{\"id\":\"a0\",\"body\":\"Q4 deck is ready when you are\",\"date\":" + (now - 47 * 60000L) + ",\"out\":false}");
        } else if (id.equals("s3")) {
            b.append("{\"id\":\"u0\",\"body\":\"Call me when you're free ❤️\",\"date\":" + (now - 35 * 60000L) + ",\"out\":false}");
        }
        send(b.append("]}").toString());
    }

    static void scene(String name) throws Exception {
        switch (name) {
            case "idle": break;
            case "notify":
                priyaTexted = true;
                priyaThread.add(new String[] { "Dinner at 8? 🍝", "false" });
                notif("0|demo.messages|priya", "demo.messages", "Messages", "messages", "Priya", "Dinner at 8? 🍝", false, REPLY_ACTIONS);
                send("{\"t\":\"sms_changed\"}");
                break;
            case "mum":
                notif("0|demo.messages|mum", "demo.messages", "Messages", "messages", "Mum", "Call me when you're free ❤️", false, REPLY_ACTIONS);
                break;
            case "code":
                notif("0|demo.messages|northwind", "demo.messages", "Messages", "messages", "Northwind Bank",
                    "482913 is your sign-in code. Never share it with anyone.", false, "[]");
                break;
            case "call":
                notif("0|demo.phone|call", "demo.phone", "Phone", "calls", "Sam", "Mobile " + SAM, true,
                    "[{\"i\":0,\"title\":\"Answer\",\"reply\":false},{\"i\":1,\"title\":\"Decline\",\"reply\":false}]");
                break;
            case "clipboard":
                send("{\"t\":\"clip\",\"text\":\"Osteria Lucca, 42 Elm Street\",\"explicit\":true}");
                System.out.println("→ copied on the phone: Osteria Lucca, 42 Elm Street");
                break;
            case "screenshot": {
                // A real screen of the Android app if the stills have been made, otherwise the menu photo.
                Path shot = Path.of(System.getProperty("demo.screenshot", "video/series/captures/android/01-home.png"));
                sendFile("shot" + System.nanoTime(), "Screenshot.png", "image/png", Files.readAllBytes(Files.exists(shot) ? shot : demoFile("dinner-menu.jpg")), true);
                break;
            }
            case "file-menu": sendFile("f" + System.nanoTime(), "dinner-menu.jpg", "image/jpeg", Files.readAllBytes(demoFile("dinner-menu.jpg")), false); break;
            case "file-deck": sendFile("f" + System.nanoTime(), "Q4-deck.pdf", "application/pdf", Files.readAllBytes(demoFile("Q4-deck.pdf")), false); break;
            case "music": playing = true; track = 0; send("{\"t\":\"volume\",\"vol\":70}"); media(true); break;
            case "clear":
                for (String k : new String[] { "0|demo.messages|priya", "0|demo.messages|mum", "0|demo.messages|northwind", "0|demo.phone|call" })
                    send("{\"t\":\"notif_removed\",\"key\":" + q(k) + "}");
                send("{\"t\":\"media_end\",\"id\":\"demo.music\"}");
                System.out.println("→ cleared notifications and music");
                break;
            default: throw new IllegalArgumentException("No scene called \"" + name + "\".\n" + SCENES);
        }
    }

    /** What the Mac asks for or does, answered as the phone would. */
    static void answer(String m, java.util.Map<String, long[]> incoming) throws Exception {
        String t = field(m, "t");
        if (t == null) return;
        switch (t) {
            case "ping": send("{\"t\":\"pong\"}"); break;
            case "pong": case "clip_history": case "mac_media": case "mac_media_end": case "mac_volume": break;
            case "notif_action": {
                String key = field(m, "key"), text = field(m, "text");
                System.out.println("← " + (text != null ? "replied: " + text : "pressed button " + num(m, "i") + " on " + key));
                if (text != null && key != null && key.endsWith("priya")) priyaThread.add(new String[] { text, "true" });
                // Replying, marking as read, answering or declining all take the notification off the phone.
                send("{\"t\":\"notif_removed\",\"key\":" + q(key) + "}");
                if (text != null) send("{\"t\":\"sms_changed\"}");
                break;
            }
            case "notif_dismiss": System.out.println("← dismissed on the Mac: " + field(m, "key") + " (gone from the phone too)"); break;
            case "ring": System.out.println("← RING (the phone would ring at full volume now)"); break;
            case "ring_stop": System.out.println("← ring stopped"); break;
            case "clip": System.out.println("← copied on the Mac: " + field(m, "text")); break;
            case "open_url": System.out.println("← open on the phone: " + field(m, "url")); break;
            case "sms_threads": smsThreads(); break;
            case "sms_thread": smsThread(field(m, "id")); break;
            case "sms_send": {
                String text = field(m, "text"), thread = field(m, "thread"), address = field(m, "address");
                System.out.println("← text to " + ("s1".equals(thread) || PRIYA.equals(address) ? "Priya" : address) + ": " + text);
                if ("s1".equals(thread) || PRIYA.equals(address)) priyaThread.add(new String[] { text, "true" });
                send("{\"t\":\"sms_sent\",\"id\":" + q(field(m, "id")) + ",\"ok\":true}");
                break;
            }
            case "contacts_search": {
                String query = field(m, "q") == null ? "" : field(m, "q").toLowerCase();
                StringBuilder b = new StringBuilder();
                for (String[] c : new String[][] { { "Priya", PRIYA }, { "Sam", SAM }, { "Mum", MUM } })
                    if (!query.isEmpty() && c[0].toLowerCase().contains(query))
                        b.append(b.length() > 0 ? "," : "").append("{\"name\":\"" + c[0] + "\",\"number\":\"" + c[1] + "\",\"label\":\"Mobile\"}");
                send("{\"t\":\"contacts\",\"q\":" + q(field(m, "q") == null ? "" : field(m, "q")) + ",\"permission\":true,\"results\":[" + b + "]}");
                break;
            }
            case "media_cmd": {
                String cmd = field(m, "cmd");
                if ("next".equals(cmd)) track = (track + 1) % TRACKS.length;
                else if ("prev".equals(cmd)) track = (track + TRACKS.length - 1) % TRACKS.length;
                else if ("toggle".equals(cmd)) playing = !playing;
                else if ("play".equals(cmd)) playing = true;
                else if ("pause".equals(cmd)) playing = false;
                System.out.println("← music: " + cmd);
                media(false);
                break;
            }
            case "volume_set": send("{\"t\":\"volume\",\"vol\":" + num(m, "vol") + "}"); break;
            case "mirror_start": send("{\"t\":\"mirror_stopped\",\"reason\":\"denied\"}"); System.out.println("← mirror asked (needs a real phone)"); break;
            case "gallery_list": send("{\"t\":\"gallery\",\"offset\":0,\"kind\":\"all\",\"access\":\"off\",\"total\":0,\"items\":[]}"); break;
            case "file_start": incoming.put(field(m, "id"), new long[] { num(m, "size"), 0 }); System.out.println("← receiving " + field(m, "name") + " (" + num(m, "size") / 1024 + " KB)"); break;
            case "file_chunk": break;
            case "file_end": System.out.println("← received (on a real phone it is now in Downloads › Mac n Droid)"); break;
            default: System.out.println("← " + (m.length() > 160 ? m.substring(0, 160) + "…" : m));
        }
    }

    static void demo(String scenes, Socket s, DataInputStream in, DataOutputStream out) throws Exception {
        dout = out;
        priyaThread.add(new String[] { "Found the place: Osteria Lucca, 42 Elm Street", "false" });
        priyaThread.add(new String[] { "Oh nice, I've wanted to try it", "true" });
        send("{\"t\":\"battery\",\"level\":82,\"charging\":false}");
        send("{\"t\":\"volume\",\"vol\":70}");
        String[] list = scenes.split(",");
        double delay = Double.parseDouble(System.getProperty("delay", "3")), gap = Double.parseDouble(System.getProperty("gap", "5"));
        System.out.println("✓ connected as Pixel 8 · 82%. First scene in " + delay + " s. Ctrl-C to stop.");
        long start = System.currentTimeMillis();
        long until = start + (long) (delay * 1000 + gap * 1000 * list.length) + (long) (Double.parseDouble(System.getProperty("listen", "600")) * 1000);
        s.setSoTimeout(100);
        java.util.Map<String, long[]> incoming = new java.util.HashMap<>();
        int next = 0;
        while (System.currentTimeMillis() < until) {
            if (next < list.length && System.currentTimeMillis() >= start + (long) (delay * 1000 + gap * 1000 * next)) scene(list[next++].trim());
            int len;
            // Only the frame's first bytes may time out: a frame is never left half read.
            try { len = in.readInt(); } catch (java.net.SocketTimeoutException e) { continue; } catch (EOFException | java.net.SocketException e) { break; }
            s.setSoTimeout(0);
            byte[] b = new byte[len];
            in.readFully(b);
            s.setSoTimeout(100);
            answer(open(b), incoming);
        }
        s.close();
        System.out.println("✓ done");
    }
}
