package app.strata.fixture.zz;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * T-APPS-02: writes the launch intent's source bounds ({@code Rect.flattenToString}) to {@code files/source_bounds.txt}
 * (read back with {@code run-as}), then finishes, so the launcher comes back to the front.
 */
public final class ZzActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Rect bounds = getIntent().getSourceBounds();
        String text = bounds == null ? "" : bounds.flattenToString();
        File out = new File(getFilesDir(), "source_bounds.txt");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The test reports a missing file as a skipped check.
        }
        finish();
    }
}
