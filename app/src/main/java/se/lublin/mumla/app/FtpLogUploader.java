package se.lublin.mumla.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.SocketFactory;

/**
 * IW2DPO: client FTP minimale (solo Java standard, nessuna libreria esterna)
 * per inviare i file di log a un server FTP nella LAN Wi-Fi.
 *
 * Modalita' passiva, trasferimento binario, FTP in chiaro (senza TLS).
 */
public final class FtpLogUploader {

    // ---------------------------------------------------------------
    // CONFIGURAZIONE SERVER FTP  (modificare qui se cambiano i dati)
    // ---------------------------------------------------------------
    public static final String FTP_HOST = "192.168.113.253";
    public static final int    FTP_PORT = 21;
    public static final String FTP_USER = "Max";
    public static final String FTP_PASS = "Massimo2026";
    /** Cartella remota (viene creata se possibile; se non si puo', si usa la radice). */
    public static final String FTP_DIR  = "MumlaLog";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS    = 10000;

    private FtpLogUploader() { }

    /**
     * Invia tutti i .txt presenti in localDir che non sono ancora stati inviati.
     * Quelli inviati con successo vengono spostati in localDir/sent.
     * Da chiamare SEMPRE fuori dal thread principale.
     */
    public static synchronized void uploadPending(Context ctx, File localDir) {
        if (localDir == null || !localDir.isDirectory()) return;
        File[] files = localDir.listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null || files.length == 0) return;
        File sentDir = new File(localDir, "sent");
        if (!sentDir.exists()) sentDir.mkdirs();
        for (File f : files) {
            try {
                if (upload(ctx, f)) {
                    File dest = new File(sentDir, f.getName());
                    if (!f.renameTo(dest)) f.delete();
                }
            } catch (Throwable ignored) {
                // il server non e' raggiungibile: si riprova al prossimo avvio
            }
        }
    }

    /** Invia un singolo file. Restituisce true se il server ha confermato (226). */
    public static boolean upload(Context ctx, File file) throws IOException {
        byte[] data = readAll(file);
        SocketFactory sf = wifiSocketFactory(ctx);

        Socket ctrl = sf.createSocket();
        try {
            ctrl.connect(new InetSocketAddress(FTP_HOST, FTP_PORT), CONNECT_TIMEOUT_MS);
            ctrl.setSoTimeout(READ_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = ctrl.getOutputStream();

            expect(readReply(in), 220);
            int code = cmd(in, out, "USER " + FTP_USER);
            if (code == 331) code = cmd(in, out, "PASS " + FTP_PASS);
            expect(code, 230);
            expect(cmd(in, out, "TYPE I"), 200);

            if (FTP_DIR != null && !FTP_DIR.isEmpty()) {
                cmd(in, out, "MKD " + FTP_DIR);          // errore ignorato se esiste gia'
                cmd(in, out, "CWD " + FTP_DIR);          // se fallisce si resta nella radice
            }

            // Modalita' passiva
            String pasv = readReplyLine(in, out, "PASV");
            if (!pasv.startsWith("227")) throw new IOException("PASV rifiutato: " + pasv);
            int dataPort = parsePasvPort(pasv);

            Socket dataSock = sf.createSocket();
            try {
                // Si usa sempre l'IP del server (evita problemi con IP errati nella risposta PASV)
                dataSock.connect(new InetSocketAddress(FTP_HOST, dataPort), CONNECT_TIMEOUT_MS);
                code = cmd(in, out, "STOR " + file.getName());
                if (code != 150 && code != 125) throw new IOException("STOR rifiutato: " + code);
                OutputStream dout = dataSock.getOutputStream();
                dout.write(data);
                dout.flush();
            } finally {
                try { dataSock.close(); } catch (IOException ignored) { }
            }
            expect(readReply(in), 226);
            try { cmd(in, out, "QUIT"); } catch (IOException ignored) { }
            return true;
        } finally {
            try { ctrl.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Se il P50 PRO ha anche i dati mobili attivi, Android potrebbe instradare
     * il traffico sulla rete cellulare (soprattutto se il Wi-Fi non ha Internet).
     * Qui si forza l'uso della rete Wi-Fi, dove si trova il server FTP.
     */
    private static SocketFactory wifiSocketFactory(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return n.getSocketFactory();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return SocketFactory.getDefault();
    }

    // ---------------------------------------------------------------
    // Protocollo FTP
    // ---------------------------------------------------------------

    private static int cmd(BufferedReader in, OutputStream out, String line) throws IOException {
        send(out, line);
        return readReply(in);
    }

    private static String readReplyLine(BufferedReader in, OutputStream out, String line) throws IOException {
        send(out, line);
        return readFullReply(in);
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static int readReply(BufferedReader in) throws IOException {
        String r = readFullReply(in);
        try {
            return Integer.parseInt(r.substring(0, 3));
        } catch (Exception e) {
            throw new IOException("Risposta FTP non valida: " + r);
        }
    }

    /** Legge una risposta anche multi-riga ("230-..." ... "230 ..."); restituisce l'ultima riga. */
    private static String readFullReply(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) throw new IOException("Connessione FTP chiusa dal server");
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String code = line.substring(0, 3);
            String l;
            while ((l = in.readLine()) != null) {
                if (l.startsWith(code + " ")) return l;
            }
            throw new IOException("Connessione FTP chiusa dal server");
        }
        return line;
    }

    private static void expect(int code, int wanted) throws IOException {
        if (code != wanted) throw new IOException("Risposta FTP " + code + ", attesa " + wanted);
    }

    /** "227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)" -> p1*256+p2 */
    private static int parsePasvPort(String reply) throws IOException {
        int a = reply.indexOf('(');
        int b = reply.indexOf(')', a + 1);
        if (a < 0 || b < 0) throw new IOException("Risposta PASV non valida: " + reply);
        String[] p = reply.substring(a + 1, b).split(",");
        if (p.length != 6) throw new IOException("Risposta PASV non valida: " + reply);
        return Integer.parseInt(p[4].trim()) * 256 + Integer.parseInt(p[5].trim());
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = is.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        }
    }
}
