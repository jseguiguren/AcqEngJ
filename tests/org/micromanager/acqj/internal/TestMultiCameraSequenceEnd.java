package org.micromanager.acqj.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mmcorej.TaggedImage;
import mmcorej.org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.micromanager.acqj.api.AcquisitionAPI;
import org.micromanager.acqj.example.BlackHoleDataSink;
import org.micromanager.acqj.main.Acquisition;
import org.micromanager.acqj.main.AcquisitionEvent;

/**
 * A hardware sequence on two cameras, where the camera that is not the Core camera
 * delivers its last frame after the Core camera has finished. The engine must keep
 * waiting for that frame instead of reporting "Expected images did not arrive in
 * circular buffer".
 *
 * <p>Seen with LightSheetManager on two PVCAM cameras triggered together: the Core
 * camera's sequence ended, the buffer was empty for a moment, the engine threw, and
 * the other camera's last frame arrived just after.
 */
public class TestMultiCameraSequenceEnd {

   private static final String CORE_CAMERA = "CamA";
   private static final String OTHER_CAMERA = "CamB";
   private static final int FRAMES_PER_CAMERA = 3;

   private TwoCameraCore core_;
   private Acquisition acq_;
   private Method acquireImages_;

   @Before
   public void setUp() throws Exception {
      core_ = new TwoCameraCore();
      acq_ = new Acquisition(new BlackHoleDataSink(), false);
      // the harness resets the engine singleton and installs the fake core
      new EngineTestHarness(core_, acq_);
      acquireImages_ = Engine.class.getDeclaredMethod(
            "acquireImages", AcquisitionEvent.class, HardwareSequences.class);
      acquireImages_.setAccessible(true);
   }

   @Test
   public void waitsForTheLastFrameOfACameraThatIsNotTheCoreCamera() throws Exception {
      core_.lateFrameAfterEmptyPolls = 3;

      acquireImages(twoCameraSequence());

      Assert.assertEquals(0, core_.getRemainingImageCount());
      Assert.assertEquals(2 * FRAMES_PER_CAMERA, acq_.getImageTransferQueueCount());
   }

   @Test
   public void stillReportsAFrameThatNeverArrives() throws Exception {
      core_.lateFrameAfterEmptyPolls = Integer.MAX_VALUE;
      core_.otherCameraStopsWithoutItsLastFrame = true;

      try {
         acquireImages(twoCameraSequence());
         Assert.fail("a frame that never arrives must still be reported");
      } catch (Exception e) {
         Assert.assertTrue(e.getMessage(),
               e.getMessage().contains("Expected images did not arrive in circular buffer"));
      }
   }

   /** One event per frame and camera, merged into one sequence, the way LSM builds them. */
   private AcquisitionEvent twoCameraSequence() {
      List<AcquisitionEvent> events = new ArrayList<>();
      for (String camera : new String[] {CORE_CAMERA, OTHER_CAMERA}) {
         for (int z = 0; z < FRAMES_PER_CAMERA; z++) {
            AcquisitionEvent e = new AcquisitionEvent((AcquisitionAPI) acq_);
            e.setCameraDeviceName(camera);
            e.setZ(z, (double) z);
            events.add(e);
         }
      }
      return new AcquisitionEvent(events);
   }

   private void acquireImages(AcquisitionEvent event) throws Exception {
      try {
         acquireImages_.invoke(Engine.getInstance(), event, new HardwareSequences());
      } catch (InvocationTargetException e) {
         throw (Exception) e.getCause();
      }
   }

   /**
    * Two cameras sharing the circular buffer. The Core camera inserts all its frames
    * and stops at once. The other camera inserts all but its last frame at once and
    * stays running until the buffer has been found empty {@code lateFrameAfterEmptyPolls}
    * times; then it inserts the last frame and stops. Like a real camera, it inserts
    * its last frame before it reports that its sequence has stopped.
    */
   private static final class TwoCameraCore extends RecordingCMMCore {

      int lateFrameAfterEmptyPolls;
      boolean otherCameraStopsWithoutItsLastFrame;

      private final ArrayDeque<TaggedImage> buffer_ = new ArrayDeque<>();
      private final Map<String, Boolean> running_ = new HashMap<>();
      private TaggedImage lateFrame_;
      private int emptyPolls_;

      TwoCameraCore() {
         super("Z", "XY", "", CORE_CAMERA);
      }

      @Override
      public void startSequenceAcquisition(String camera, int numImages,
                                           double intervalMs, boolean stopOnOverflow) {
         for (int i = 0; i < numImages; i++) {
            TaggedImage frame = frame(camera);
            if (camera.equals(OTHER_CAMERA) && i == numImages - 1) {
               lateFrame_ = frame;
            } else {
               buffer_.add(frame);
            }
         }
         running_.put(camera, camera.equals(OTHER_CAMERA));
      }

      @Override
      public TaggedImage popNextTaggedImage() throws Exception {
         if (!buffer_.isEmpty()) {
            return buffer_.poll();
         }
         emptyPolls_++;
         if (lateFrame_ != null && otherCameraStopsWithoutItsLastFrame) {
            lateFrame_ = null;
            running_.put(OTHER_CAMERA, false);
         } else if (lateFrame_ != null && emptyPolls_ >= lateFrameAfterEmptyPolls) {
            buffer_.add(lateFrame_);
            lateFrame_ = null;
            running_.put(OTHER_CAMERA, false);
         }
         throw new Exception("Circular buffer is empty.");
      }

      @Override
      public boolean isSequenceRunning() {
         return running_.getOrDefault(getCameraDevice(), false);
      }

      @Override
      public boolean isSequenceRunning(String camera) {
         return running_.getOrDefault(camera, false);
      }

      @Override
      public int getRemainingImageCount() {
         return buffer_.size();
      }

      @Override
      public boolean isBufferOverflowed() {
         return false;
      }

      @Override
      public long getNumberOfCameraChannels() {
         return 1;
      }

      @Override
      public double getExposure() {
         return 10.0;
      }

      @Override
      public double getPixelSizeUm() {
         return 1.0;
      }

      @Override
      public void clearCircularBuffer() {
         buffer_.clear();
      }

      private static TaggedImage frame(String camera) {
         JSONObject tags = new JSONObject();
         try {
            tags.put("Camera", camera);
         } catch (Exception e) {
            throw new RuntimeException(e);
         }
         return new TaggedImage(new byte[1], tags);
      }
   }
}
