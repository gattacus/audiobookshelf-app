package androidx.media;

import android.support.v4.media.MediaBrowserCompat;
import java.util.List;

/** Test bridge to the media library's package-private Result constructor and callback. */
public final class CapturingBrowserResult
    extends MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>> {
  public int calls;
  public List<MediaBrowserCompat.MediaItem> items;

  public CapturingBrowserResult() { super("Android Auto regression"); }

  @Override void onResultSent(List<MediaBrowserCompat.MediaItem> result) {
    calls++;
    items = result;
  }
}
