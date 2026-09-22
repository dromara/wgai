package org.jeecg.modules.demo.video.util;

import org.bytedeco.javacv.Frame;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import java.nio.ByteBuffer;

/** Zero-copy BGR view. Release the Mat before closing its owning Frame; never pool this view. */
public final class FrameMatView {
    private FrameMatView() { }

    public static Mat bgr(Frame frame) {
        if (frame == null || frame.image == null || frame.image.length == 0
                || !(frame.image[0] instanceof ByteBuffer) || frame.imageDepth != Frame.DEPTH_UBYTE
                || frame.imageChannels != 3 || frame.imageWidth <= 0 || frame.imageHeight <= 0
                || frame.imageStride < frame.imageWidth * 3) {
            throw new IllegalArgumentException("Expected an 8-bit, 3-channel BGR frame");
        }
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        pixels.clear();
        if (!pixels.isDirect() || pixels.capacity() < (long) frame.imageStride * frame.imageHeight) {
            throw new IllegalArgumentException("Invalid BGR frame buffer");
        }
        return new Mat(frame.imageHeight, frame.imageWidth, CvType.CV_8UC3, pixels, frame.imageStride);
    }
}
