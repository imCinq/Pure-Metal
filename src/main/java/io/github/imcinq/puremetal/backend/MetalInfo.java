package io.github.imcinq.puremetal.backend;

import org.jspecify.annotations.Nullable;

/** What the running Metal device is, for the F3 debug line. */
public final class MetalInfo {
    private MetalInfo() {
    }

    /** "Metal 3" or "Metal 4" for the live device, or null while Minecraft does not render through Pure Metal. */
    public static @Nullable String backend() {
        MetalDevice device = MetalDevice.current;
        return device == null ? null : device.metal4Encoding() ? "Metal 4" : "Metal 3";
    }

    /** The MTLDevice name of the live device (cached when the device was created), or an empty string. */
    public static String gpuName() {
        MetalDevice device = MetalDevice.current;
        return device == null ? "" : device.gpuName();
    }
}
