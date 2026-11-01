package com.partha.wirelessmic;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where recordings are saved: the app's own folder by default, or any folder the user picks with
 * Android's folder picker (Storage Access Framework, e.g. Music/WirelessMic or Download).
 */
final class RecStore {
    static final String PREF_TREE = "rec_tree";

    /** An open, seekable output for a new recording. */
    static final class Output implements Closeable {
        final FileChannel channel;
        final String name;
        private final Closeable owner;

        Output(FileChannel channel, String name, Closeable owner) { this.channel = channel; this.name = name; this.owner = owner; }

        @Override public void close() throws IOException { channel.close(); owner.close(); }
    }

    /** A saved recording, for the browser receiver's list/download. */
    static final class Item {
        final String name; final long size; final Uri uri; final File file;
        Item(String name, long size, Uri uri, File file) { this.name = name; this.size = size; this.uri = uri; this.file = file; }
    }

    static Uri tree(Context c) {
        String s = c.getSharedPreferences("wmic", Context.MODE_PRIVATE).getString(PREF_TREE, null);
        return s == null ? null : Uri.parse(s);
    }

    static void setTree(Context c, Uri tree) {
        c.getSharedPreferences("wmic", Context.MODE_PRIVATE).edit().putString(PREF_TREE, tree == null ? null : tree.toString()).apply();
    }

    static File defaultDir(Context c) { return TxService.recDir(c); }

    /** Human-readable folder, e.g. /storage/emulated/0/Music/WirelessMic. */
    static String describe(Context c) {
        Uri t = tree(c);
        if (t == null) return defaultDir(c).getAbsolutePath();
        String id = DocumentsContract.getTreeDocumentId(t);           // e.g. "primary:Music/WirelessMic"
        int colon = id.indexOf(':');
        String vol = colon > 0 ? id.substring(0, colon) : id, rel = colon > 0 ? id.substring(colon + 1) : "";
        return ("primary".equals(vol) ? "/storage/emulated/0" : "/storage/" + vol) + (rel.isEmpty() ? "" : "/" + rel);
    }

    static Output create(Context c, String name) throws IOException {
        Uri t = tree(c);
        if (t != null) {
            try {
                ContentResolver cr = c.getContentResolver();
                Uri dir = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
                Uri doc = DocumentsContract.createDocument(cr, dir, "audio/wav", name);
                if (doc != null) {
                    ParcelFileDescriptor pfd = cr.openFileDescriptor(doc, "rw");
                    if (pfd != null) return new Output(new FileOutputStream(pfd.getFileDescriptor()).getChannel(), name, pfd);
                }
            } catch (Exception e) {
                // folder permission lost (folder deleted / app reinstalled): fall back to the app folder
            }
        }
        File dir = defaultDir(c);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        RandomAccessFile raf = new RandomAccessFile(new File(dir, name), "rw");
        raf.setLength(0);
        return new Output(raf.getChannel(), name, raf);
    }

    static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        Uri t = tree(c);
        if (t != null) {
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
            String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE};
            try (Cursor cur = c.getContentResolver().query(kids, cols, null, null, null)) {
                while (cur != null && cur.moveToNext()) {
                    String name = cur.getString(1);
                    if (name != null && name.endsWith(".wav"))
                        out.add(new Item(name, cur.getLong(2), DocumentsContract.buildDocumentUriUsingTree(t, cur.getString(0)), null));
                }
            } catch (Exception ignored) { }
        }
        File[] files = defaultDir(c).listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".wav")) out.add(new Item(f.getName(), f.length(), null, f));
        Collections.sort(out, (a, b) -> b.name.compareTo(a.name));
        return out;
    }

    static InputStream open(Context c, Item it) throws IOException {
        return it.file != null ? new FileInputStream(it.file) : c.getContentResolver().openInputStream(it.uri);
    }

    private RecStore() {}
}
