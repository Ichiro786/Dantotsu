package ani.dantotsu.media.manga.mangareader.webgpu;

import androidx.annotation.Nullable;

import ca.mpreg.webgpuviewer.viewer.ImagePage;
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState;
import ca.mpreg.webgpuviewer.viewer.PagePlacement;

/**
 * Where the continuous viewer draws a page on screen.
 * <p>
 * The library exposes this as a Kotlin {@code internal} member (used by its own Compose page
 * host), which Kotlin callers outside the module can't see but Java can call by its JVM name.
 * Written in Java so R8 sees a direct reference instead of a reflective lookup.
 */
final class WebGpuPlacement {
    private WebGpuPlacement() {
    }

    /** {left, top, scale} in surface pixels, or null when the page isn't laid out. */
    @Nullable
    static float[] place(ImageViewerContinuousState state, ImagePage page) {
        PagePlacement p = state.screenPlacement$library(page);
        if (p == null) return null;
        return new float[]{p.getLeft(), p.getTop(), p.getScale()};
    }
}
