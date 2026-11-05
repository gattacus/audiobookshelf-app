package com.audiobookshelf.app.listening;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Runs in the disposable test APK, using framework classes only (no target-app classloader). */
public class ListeningDocumentsProvider extends DocumentsProvider {
  private File root() { File root = new File(getContext().getCacheDir(), "listening-documents"); root.mkdirs(); return root; }
  @Override public boolean onCreate() { return true; }
  private File file(String id) throws FileNotFoundException {
    File root = root();
    File file = "root".equals(id) ? root : new File(root, id.startsWith("root/") ? id.substring(5) : id);
    try { if (!file.getCanonicalPath().equals(root.getCanonicalPath()) && !file.getCanonicalPath().startsWith(root.getCanonicalPath()+"/")) throw new FileNotFoundException(); }
    catch (IOException e) { throw new FileNotFoundException(); }
    return file;
  }
  @Override public Cursor queryRoots(String[] projection) {
    MatrixCursor cursor = new MatrixCursor(new String[]{"root_id","document_id","title","flags"});
    cursor.addRow(new Object[]{"fixture","root","Test audio",0}); return cursor;
  }
  @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
    File f = file(id); if (!f.exists()) throw new FileNotFoundException();
    String[] columns = projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE};
    MatrixCursor cursor = new MatrixCursor(columns);
    int flags = id.endsWith("locked.mp3") ? 0 : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_DIR_SUPPORTS_CREATE;
    Object[] values = new Object[columns.length];
    for (int i=0; i<columns.length; i++) {
      if (Document.COLUMN_DOCUMENT_ID.equals(columns[i])) values[i]=id;
      else if (Document.COLUMN_DISPLAY_NAME.equals(columns[i])) values[i]=f.getName();
      else if (Document.COLUMN_MIME_TYPE.equals(columns[i])) values[i]=f.isDirectory()?Document.MIME_TYPE_DIR:"audio/mpeg";
      else if (Document.COLUMN_FLAGS.equals(columns[i])) values[i]=flags;
      else if (Document.COLUMN_SIZE.equals(columns[i])) values[i]=f.length();
    }
    cursor.addRow(values); return cursor;
  }
  @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) { return new MatrixCursor(new String[]{Document.COLUMN_DOCUMENT_ID}); }
  @Override public boolean isChildDocument(String parent, String document) { return "root".equals(parent) && document.startsWith("root/"); }
  @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException { return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode)); }
  @Override public void deleteDocument(String id) throws FileNotFoundException { if (!file(id).delete()) throw new FileNotFoundException(); }
}
