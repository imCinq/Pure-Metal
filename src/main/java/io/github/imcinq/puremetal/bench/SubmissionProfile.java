package io.github.imcinq.puremetal.bench;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** Opt-in JFR diagnostics (-Dpuremetal.profile=true), without auto-exit or changing gameplay settings. */
@Name("puremetal.Submission")
@Label("Metal submission completion")
@Category({"Pure Metal", "Performance"})
@StackTrace(false)
public final class SubmissionProfile extends Event {
    public static final boolean ENABLED = Boolean.getBoolean("puremetal.profile");
    @Timespan(Timespan.NANOSECONDS) public long waitNs;
    @Timespan(Timespan.NANOSECONDS) public long frameGpuNs;
    @Timespan(Timespan.NANOSECONDS) public long earlyUploadGpuNs;
    public long completedFrames;
    public long completedUploads;
    @Timespan(Timespan.NANOSECONDS) public long renderVertexNs;
    @Timespan(Timespan.NANOSECONDS) public long renderFragmentNs;
    @Timespan(Timespan.NANOSECONDS) public long inFrameBlitNs;
    public long vertexSamples, fragmentSamples, blitSamples;

    public static void record(long wait, long frameGpu, long uploadGpu, long frames, long uploads) {
        record(wait, frameGpu, uploadGpu, frames, uploads, 0, 0, 0, 0, 0, 0);
    }

    public static void record(long wait, long frameGpu, long uploadGpu, long frames, long uploads,
                              long vertex, long fragment, long blit, long vertexCount, long fragmentCount, long blitCount) {
        SubmissionProfile event = new SubmissionProfile();
        if (!event.isEnabled()) {
            return;
        }
        // Completed buffers can belong to earlier submissions. These are interval aggregates,
        // not the GPU time of this exact frame; queue order and lifetime remain untouched.
        event.waitNs = wait;
        event.frameGpuNs = frameGpu;
        event.earlyUploadGpuNs = uploadGpu;
        event.completedFrames = frames;
        event.completedUploads = uploads;
        event.renderVertexNs = vertex;
        event.renderFragmentNs = fragment;
        event.inFrameBlitNs = blit;
        event.vertexSamples = vertexCount;
        event.fragmentSamples = fragmentCount;
        event.blitSamples = blitCount;
        event.commit();
    }
}
