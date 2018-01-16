/*
 * MIT License
 *
 * Copyright (c) 2017 Shahen Hovhannisyan.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.shahenlibrary.Trimmer;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.shahenlibrary.Events.Events;
import com.shahenlibrary.interfaces.OnCompressVideoListener;
import com.shahenlibrary.utils.VideoEdit;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.UUID;
import android.os.Environment;
import wseemann.media.FFmpegMediaMetadataRetriever;


public class Trimmer {

  private static final String LOG_TAG = "RNTrimmerManager";
  private static final String FFMPEG_FILE_NAME = "ffmpeg";
  private static final String FFMPEG_SHA1 = "77ae380db4bf56d011eca9ef9f20d397c0467aec";

  private static boolean ffmpegLoaded = false;
  private static final int DEFAULT_BUFFER_SIZE = 4096;
  private static final int END_OF_FILE = -1;

  private static class FfmpegCmdAsyncTaskParams {
    ArrayList<String> cmd;
    final String pathToProcessingFile;
    ReactApplicationContext ctx;
    final Promise promise;
    final String errorMessageTitle;
    final OnCompressVideoListener cb;

    FfmpegCmdAsyncTaskParams(ArrayList<String> cmd, final String pathToProcessingFile, ReactApplicationContext ctx, final Promise promise, final String errorMessageTitle, final OnCompressVideoListener cb) {
      this.cmd = cmd;
      this.pathToProcessingFile = pathToProcessingFile;
      this.ctx = ctx;
      this.promise = promise;
      this.errorMessageTitle = errorMessageTitle;
      this.cb = cb;
    }
  }

  private static class FfmpegCmdAsyncTask extends AsyncTask<FfmpegCmdAsyncTaskParams, Void, Void> {

    @Override
    protected Void doInBackground(FfmpegCmdAsyncTaskParams... params) {
      ArrayList<String> cmd = params[0].cmd;
      final String pathToProcessingFile = params[0].pathToProcessingFile;
      ReactApplicationContext ctx = params[0].ctx;
      final Promise promise = params[0].promise;
      final String errorMessageTitle = params[0].errorMessageTitle;
      final OnCompressVideoListener cb = params[0].cb;


      String errorMessageFromCmd = null;

      try {
        // NOTE: 3. EXECUTE "ffmpeg" COMMAND
        String ffmpegInDir = getFfmpegAbsolutePath(ctx);
        cmd.add(0, ffmpegInDir);
        Process p = new ProcessBuilder(cmd).start();

        BufferedReader input = getOutputFromProcess(p);
        String line = null;

        StringBuilder sInput = new StringBuilder();

        while((line=input.readLine()) != null) {
            Log.d(LOG_TAG, "processing ffmpeg");
            System.out.println(sInput);
            sInput.append(line);
        }
        input.close();

        int errorCode = p.waitFor();
        Log.d(LOG_TAG, "ffmpeg processing completed");

        if ( errorCode != 0 ) {
          BufferedReader error = getErrorFromProcess(p);
          StringBuilder sError = new StringBuilder();

          Log.d(LOG_TAG, "ffmpeg error code: " + errorCode);
          while((line=error.readLine()) != null) {
              System.out.println(sError);
              sError.append(line);
          }
          error.close();

          errorMessageFromCmd = sError.toString();
        }
      } catch (Exception e) {
        errorMessageFromCmd = e.toString();
      }

      if ( errorMessageFromCmd != null ) {
        String errorMessage = errorMessageTitle + ": failed. " + errorMessageFromCmd;

        if (cb != null) {
          cb.onError(errorMessage);
        } else if (promise != null) {
          promise.reject(errorMessage);
        }
      } else {
        String filePath = "file://" + pathToProcessingFile;
        if (cb != null) {
          cb.onSuccess(filePath);
        } else if (promise != null) {
          WritableMap event = Arguments.createMap();
          event.putString("source", filePath);
          promise.resolve(event);
        }
      }

      return null;
    }

  }


  private static class LoadFfmpegAsyncTaskParams {
    ReactApplicationContext ctx;

    LoadFfmpegAsyncTaskParams(ReactApplicationContext ctx) {
      this.ctx = ctx;
    }
  }

  private static class LoadFfmpegAsyncTask extends AsyncTask<LoadFfmpegAsyncTaskParams, Void, Void> {

    @Override
    protected Void doInBackground(LoadFfmpegAsyncTaskParams... params) {
      ReactApplicationContext ctx = params[0].ctx;

      // NOTE: 1. COPY "ffmpeg" FROM ASSETS TO /data/data/com.myapp...
      String filesDir = getFilesDirAbsolutePath(ctx);

      // TODO: MAKE SURE THAT WHEN WE UPDATE FFMPEG AND USER UPDATES APP IT WILL LOAD NEW FFMPEG (IT MUST OVERWRITE OLD FFMPEG)
      try {
        File ffmpegFile = new File(filesDir, FFMPEG_FILE_NAME);
        if ( !(ffmpegFile.exists() && getSha1FromFile(ffmpegFile).equalsIgnoreCase(FFMPEG_SHA1)) ) {
          final FileOutputStream ffmpegStreamToDataDir = new FileOutputStream(ffmpegFile);
          byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];

          int n;
          InputStream ffmpegInAssets = ctx.getAssets().open("armeabi-v7a" + File.separator + FFMPEG_FILE_NAME);
          while(END_OF_FILE != (n = ffmpegInAssets.read(buffer))) {
            ffmpegStreamToDataDir.write(buffer, 0, n);
          }

          ffmpegStreamToDataDir.flush();
          ffmpegStreamToDataDir.close();

          ffmpegInAssets.close();
        }
      } catch (IOException e) {
        Log.d(LOG_TAG, "Failed to copy ffmpeg" + e.toString());
        ffmpegLoaded = false;
        return null;
      }

      String ffmpegInDir = getFfmpegAbsolutePath(ctx);

      // NOTE: 2. MAKE "ffmpeg" EXECUTABLE
      String[] cmdlineChmod = { "chmod", "700", ffmpegInDir };
      // TODO: 1. CHECK PERMISSIONS
      Process pChmod = null;
      try {
        pChmod = Runtime.getRuntime().exec(cmdlineChmod);
      } catch (IOException e) {
        Log.d(LOG_TAG, "Failed to make ffmpeg executable. Error in execution cmd. " + e.toString());
        ffmpegLoaded = false;
        return null;
      }

      try {
        pChmod.waitFor();
      } catch (InterruptedException e) {
        Log.d(LOG_TAG, "Failed to make ffmpeg executable. Error in wait cmd. " + e.toString());
        ffmpegLoaded = false;
        return null;
      }

      ffmpegLoaded = true;
      return null;
    }
  }


  public static void getPreviewImages(String path, Promise promise, ReactApplicationContext ctx) {
    FFmpegMediaMetadataRetriever retriever = new FFmpegMediaMetadataRetriever();
    if (VideoEdit.shouldUseURI(path)) {
      retriever.setDataSource(ctx, Uri.parse(path));
    } else {
      retriever.setDataSource(path);
    }

    WritableArray images = Arguments.createArray();
    int duration = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_DURATION));
    int width = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
    int height = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
    int orientation = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));

    float aspectRatio = width / height;

    int resizeWidth = 200;
    int resizeHeight = Math.round(resizeWidth / aspectRatio);

    float scaleWidth = ((float) resizeWidth) / width;
    float scaleHeight = ((float) resizeHeight) / height;

    Log.d(TrimmerManager.REACT_PACKAGE, "getPreviewImages: \n\tduration: " + duration +
            "\n\twidth: " + width +
            "\n\theight: " + height +
            "\n\torientation: " + orientation +
            "\n\taspectRatio: " + aspectRatio +
            "\n\tresizeWidth: " + resizeWidth +
            "\n\tresizeHeight: " + resizeHeight
    );

    Matrix mx = new Matrix();

    mx.postScale(scaleWidth, scaleHeight);
    mx.postRotate(orientation - 360);

    for (int i = 0; i < duration; i += duration / 10) {
      Bitmap frame = retriever.getFrameAtTime(i * 1000);

      if(frame == null) {
        continue;
      }
      Bitmap currBmp = Bitmap.createScaledBitmap(frame, resizeWidth, resizeHeight, false);

      Bitmap normalizedBmp = Bitmap.createBitmap(currBmp, 0, 0, resizeWidth, resizeHeight, mx, true);
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      normalizedBmp.compress(Bitmap.CompressFormat.PNG, 90, byteArrayOutputStream);
      byte[] byteArray = byteArrayOutputStream .toByteArray();
      String encoded = "data:image/png;base64," + Base64.encodeToString(byteArray, Base64.DEFAULT);
      images.pushString(encoded);
    }

    WritableMap event = Arguments.createMap();

    event.putArray("images", images);

    promise.resolve(event);
    retriever.release();
  }

  @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
  public static void getVideoInfo(String path, Promise promise, ReactApplicationContext ctx) {
    FFmpegMediaMetadataRetriever mmr = new FFmpegMediaMetadataRetriever();

    if (VideoEdit.shouldUseURI(path)) {
      mmr.setDataSource(ctx, Uri.parse(path));
    } else {
      mmr.setDataSource(path);
    }

    int duration = Integer.parseInt(mmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_DURATION));
    int width = Integer.parseInt(mmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
    int height = Integer.parseInt(mmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
    int orientation = Integer.parseInt(mmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
    if (orientation == 90 || orientation == 270) {
      width = width + height;
      height = width - height;
      width = width - height;
    }

    WritableMap event = Arguments.createMap();
    WritableMap size = Arguments.createMap();

    size.putInt(Events.WIDTH, width);
    size.putInt(Events.HEIGHT, height);

    event.putMap(Events.SIZE, size);
    event.putInt(Events.DURATION, duration / 1000);
    event.putInt(Events.ORIENTATION, orientation);

    promise.resolve(event);

    mmr.release();
  }

  static void trim(ReadableMap options, final Promise promise, ReactApplicationContext ctx) {
    String source = options.getString("source");
    String startTime = options.getString("startTime");
    String endTime = options.getString("endTime");

    final File tempFile = createTempFile("mp4", promise, ctx);

    ArrayList<String> cmd = new ArrayList<String>();
    cmd.add("-y"); // NOTE: OVERWRITE OUTPUT FILE

    // NOTE: INPUT FILE
    cmd.add("-i");
    cmd.add(source);

    // NOTE: PLACE ARGUMENTS FOR FFMPEG IN THIS ORDER:
    // 1. "-i" (INPUT FILE)
    // 2. "-ss" (START TIME)
    // 3. "-to" (END TIME) or "-t" (TRIM TIME)
    // OTHERWISE WE WILL LOSE ACCURACY AND WILL GET WRONG CLIPPED VIDEO

    cmd.add("-ss");
    cmd.add(startTime);

    cmd.add("-to");
    cmd.add(endTime);

    cmd.add("-preset");
    cmd.add("ultrafast");
    // NOTE: DO NOT CONVERT AUDIO TO SAVE TIME
    cmd.add("-c:a");
    cmd.add("copy");
    // NOTE: FLAG TO CONVER "AAC" AUDIO CODEC
    cmd.add("-strict");
    cmd.add("-2");
    // NOTE: OUTPUT FILE
    cmd.add(tempFile.getPath());

    executeFfmpegCommand(cmd, tempFile.getPath(), ctx, promise, "Trim error", null);
  }

  private static ReadableMap formatWidthAndHeightForFfmpeg(int width, int height, int availableVideoWidth, int availableVideoHeight) {
    // NOTE: WIDTH/HEIGHT FOR FFMpeg NEED TO BE DEVIDED BY 2.
    // OR YOU WILL SEE BLANK WHITE LINES FROM LEFT/RIGHT (FOR CROP), OR CRASH FOR OTHER COMMANDS
    while( width % 2 > 0 && width < availableVideoWidth ) {
      width += 1;
    }
    while( width % 2 > 0 && width > 0 ) {
      width -= 1;
    }
    while( height % 2 > 0 && height < availableVideoHeight ) {
      height += 1;
    }
    while( height % 2 > 0 && height > 0 ) {
      height -= 1;
    }

    WritableMap sizes = Arguments.createMap();
    sizes.putInt("width", width);
    sizes.putInt("height", height);
    return sizes;
  }

  private static ReadableMap getVideoWidthAndHeight(String source, Context ctx) {
    Log.d(LOG_TAG, "getVideoWidthAndHeight: " + source);

    FFmpegMediaMetadataRetriever retriever = new FFmpegMediaMetadataRetriever();
    if (VideoEdit.shouldUseURI(source)) {
      retriever.setDataSource(ctx, Uri.parse(source));
    } else {
      retriever.setDataSource(source);
    }

    int width = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
    int height = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
    retriever.release();

    Log.d(LOG_TAG, "getVideoWidthAndHeight: " + Integer.toString(width));
    Log.d(LOG_TAG, "getVideoWidthAndHeight: " + Integer.toString(height));

    WritableMap sizes = Arguments.createMap();
    sizes.putInt("width", width);
    sizes.putInt("height", height);
    return sizes;
  }



  public static void compress(String source, ReadableMap options, final Promise promise, final OnCompressVideoListener cb, ThemedReactContext tctx, ReactApplicationContext rctx) {
    Log.d(LOG_TAG, "OPTIONS: " + options.toString());

    Context ctx = tctx != null ? tctx : rctx;

    ReadableMap videoSizes = getVideoWidthAndHeight(source, ctx);
    int videoWidth = videoSizes.getInt("width");
    int videoHeight = videoSizes.getInt("height");

    int width = options.hasKey("width") ? (int)( options.getDouble("width") ) : 0;
    int height = options.hasKey("height") ? (int)( options.getDouble("height") ) : 0;

    if ( width != 0 && height != 0 && videoWidth != 0 && videoHeight != 0 ) {
      ReadableMap sizes = formatWidthAndHeightForFfmpeg(
        width,
        height,
        videoWidth,
        videoHeight
      );
      width = sizes.getInt("width");
      height = sizes.getInt("height");
    }

    Double minimumBitrate = options.hasKey("minimumBitrate") ? options.getDouble("minimumBitrate") : null;
    Double bitrateMultiplier = options.hasKey("bitrateMultiplier") ? options.getDouble("bitrateMultiplier") : null;
    Boolean removeAudio = options.hasKey("removeAudio") ? options.getBoolean("removeAudio") : false;

    final File mediaFile = createMediaFile( promise, ctx);


    //-y -i "+mInputPath+" -c:v libx264 -preset ultrafast -tune fastdecode -vf -s 640x480 -threads 5 -strict -2 "+mOutputPath.getAbsolutePath();


    ArrayList<String> cmd = new ArrayList<String>();

    cmd.add("-hwaccel"); //add param
    cmd.add("auto");//add param

    cmd.add("-threads");
    cmd.add("6");

    cmd.add("-y");

    cmd.add("-i");
    cmd.add(source);

    cmd.add("-c:v");
    cmd.add("libx264");

    cmd.add("-preset");
    cmd.add("ultrafast");

    cmd.add("-tune");
    cmd.add("fastdecode");

    if ( width != 0 && height != 0 ) {
      cmd.add("-vf");
      cmd.add("scale=" + Integer.toString(width) + ":" + Integer.toString(height));
      //cmd.add("-s");//add param
      //cmd.add(Integer.toString(width)+"x"+Integer.toString(height));//add param

    }

    //debug start

    cmd.add("-crf");
    cmd.add("28");

    //cmd.add("-strict");
    //cmd.add("experimental");

    //debug end

    //cmd.add("-pix_fmt");//for ios quicktime needs
    //cmd.add("yuv420p");//for ios quicktime needs

    if (removeAudio) {
      cmd.add("-an");
    }
    cmd.add(mediaFile.getPath());

    executeFfmpegCommand(cmd, mediaFile.getPath(), rctx, promise, "compress error", cb);
  }

  private static File createMediaFile(final Promise promise, Context ctx) {
    UUID uuid = UUID.randomUUID();
    String mixName = uuid.toString() + "-merged.mp4";
    //String path = Environment.getExternalStorageDirectory().getAbsolutePath();
    File path = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
    File moviefile = new File(path, mixName);

    try {
      moviefile.createNewFile();
    } catch( IOException e ) {
      promise.reject("Failed to create media file", e.toString());
      return null;
    }

    if (moviefile.exists()) {
      moviefile.delete();
    }

    return moviefile;
  }

  static File createTempFile(String extension, final Promise promise, Context ctx) {
    UUID uuid = UUID.randomUUID();
    String imageName = uuid.toString() + "-screenshot";

    File cacheDir = ctx.getCacheDir();
    File tempFile = null;
    try {
      tempFile = File.createTempFile(imageName, "." + extension, cacheDir);
    } catch( IOException e ) {
      promise.reject("Failed to create temp file", e.toString());
      return null;
    }

    if (tempFile.exists()) {
      tempFile.delete();
    }

    return tempFile;
  }

  static void getPreviewImageAtPosition(String source, double sec, String format, final Promise promise, ReactApplicationContext ctx) {
    FFmpegMediaMetadataRetriever metadataRetriever = new FFmpegMediaMetadataRetriever();
    FFmpegMediaMetadataRetriever.IN_PREFERRED_CONFIG = Bitmap.Config.ARGB_8888;
    metadataRetriever.setDataSource(source);

    Bitmap bmp = metadataRetriever.getFrameAtTime((long) (sec * 1000000));

    // NOTE: FIX ROTATED BITMAP
    int orientation = Integer.parseInt( metadataRetriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) );
    metadataRetriever.release();

    if ( orientation != 0 ) {
      Matrix matrix = new Matrix();
      matrix.postRotate(orientation);
      bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
    }

    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

    WritableMap event = Arguments.createMap();

    if ( format == null || (format != null && format.equals("base64")) ) {
      bmp.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
      byte[] byteArray = byteArrayOutputStream .toByteArray();
      String encoded = Base64.encodeToString(byteArray, Base64.DEFAULT);

      event.putString("image", encoded);
    } else if ( format.equals("JPEG") ) {
      bmp.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
      byte[] byteArray = byteArrayOutputStream.toByteArray();

      File tempFile = createTempFile("jpeg", promise, ctx);

      try {
        FileOutputStream fos = new FileOutputStream( tempFile.getPath() );

        fos.write( byteArray );
        fos.close();
      } catch (java.io.IOException e) {
        promise.reject("Failed to save image", e.toString());
        return;
      }

      WritableMap imageMap = Arguments.createMap();
      imageMap.putString("uri", "file://" + tempFile.getPath());

      event.putMap("image", imageMap);
    } else {
      promise.reject("Wrong format error", "Wrong 'format'. Expected one of 'base64' or 'JPEG'.");
      return;
    }

    promise.resolve(event);
  }

  private static BufferedReader getOutputFromProcess(Process p) {
    return new BufferedReader(new InputStreamReader(p.getInputStream()));
  }

  private static BufferedReader getErrorFromProcess(Process p) {
    return new BufferedReader(new InputStreamReader(p.getErrorStream()));
  }

  static void crop(String source, ReadableMap options, final Promise promise, ReactApplicationContext ctx) {
    int cropWidth = (int)( options.getDouble("cropWidth") );
    int cropHeight = (int)( options.getDouble("cropHeight") );
    int cropOffsetX = (int)( options.getDouble("cropOffsetX") );
    int cropOffsetY = (int)( options.getDouble("cropOffsetY") );

    ReadableMap videoSizes = getVideoWidthAndHeight(source, ctx);
    int videoWidth = videoSizes.getInt("width");
    int videoHeight = videoSizes.getInt("height");

    ReadableMap sizes = formatWidthAndHeightForFfmpeg(
      cropWidth,
      cropHeight,
      // NOTE: MUST CHECK AGAINST "CROPPABLE" WIDTH/HEIGHT. NOT FULL WIDTH/HEIGHT
      videoWidth - cropOffsetX,
      videoHeight - cropOffsetY
    );
    cropWidth = sizes.getInt("width");
    cropHeight = sizes.getInt("height");

    // TODO: 1) ADD METHOD TO CHECK "IS FFMPEG LOADED".
    // 2) CHECK IT HERE
    // 3) EXPORT THAT METHOD TO "JS"

    final File tempFile = createTempFile("mp4", promise, ctx);

    ArrayList<String> cmd = new ArrayList<String>();
    cmd.add("-y"); // NOTE: OVERWRITE OUTPUT FILE

    // NOTE: INPUT FILE
    cmd.add("-i");
    cmd.add(source);

    // NOTE: PLACE ARGUMENTS FOR FFMPEG IN THIS ORDER:
    // 1. "-i" (INPUT FILE)
    // 2. "-ss" (START TIME)
    // 3. "-to" (END TIME) or "-t" (TRIM TIME)
    // OTHERWISE WE WILL LOSE ACCURACY AND WILL GET WRONG CLIPPED VIDEO

    String startTime = options.getString("startTime");
    if ( !startTime.equals(null) && !startTime.equals("") ) {
      cmd.add("-ss");
      cmd.add(startTime);
    }

    String endTime = options.getString("endTime");
    if ( !endTime.equals(null) && !endTime.equals("") ) {
      cmd.add("-to");
      cmd.add(endTime);
    }

    cmd.add("-vf");
    cmd.add("crop=" + Integer.toString(cropWidth) + ":" + Integer.toString(cropHeight) + ":" + Integer.toString(cropOffsetX) + ":" + Integer.toString(cropOffsetY));

    cmd.add("-preset");
    cmd.add("ultrafast");
    // NOTE: DO NOT CONVERT AUDIO TO SAVE TIME
    cmd.add("-c:a");
    cmd.add("copy");
    // NOTE: FLAG TO CONVER "AAC" AUDIO CODEC
    cmd.add("-strict");
    cmd.add("-2");
    // NOTE: OUTPUT FILE
    cmd.add(tempFile.getPath());

    executeFfmpegCommand(cmd, tempFile.getPath(), ctx, promise, "Crop error", null);
  }

  static private Void executeFfmpegCommand(@NonNull ArrayList<String> cmd, @NonNull final String pathToProcessingFile, @NonNull ReactApplicationContext ctx, @NonNull final Promise promise, @NonNull final String errorMessageTitle, @Nullable final OnCompressVideoListener cb) {
    FfmpegCmdAsyncTaskParams ffmpegCmdAsyncTaskParams = new FfmpegCmdAsyncTaskParams(cmd, pathToProcessingFile, ctx, promise, errorMessageTitle, cb);

    FfmpegCmdAsyncTask ffmpegCmdAsyncTask = new FfmpegCmdAsyncTask();
    ffmpegCmdAsyncTask.execute(ffmpegCmdAsyncTaskParams);

    return null;
  }

  private static String getFilesDirAbsolutePath(ReactApplicationContext ctx) {
    return ctx.getFilesDir().getAbsolutePath();
  }

  private static String getFfmpegAbsolutePath(ReactApplicationContext ctx) {
    return getFilesDirAbsolutePath(ctx) + File.separator + FFMPEG_FILE_NAME;
  }

  public static String getSha1FromFile(final File file) {
    MessageDigest messageDigest = null;
    try {
      messageDigest = MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException e) {
      Log.d(LOG_TAG, "Failed to load SHA1 Algorithm. " + e.toString());
      return "";
    }

    try {
      try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
        final byte[] buffer = new byte[1024];
        for (int read = 0; (read = is.read(buffer)) != -1;) {
          messageDigest.update(buffer, 0, read);
        }
        is.close();
      }
    } catch (IOException e) {
      Log.d(LOG_TAG, "Failed to load SHA1 Algorithm. IOException. " + e.toString());
      return "";
    }

    try (Formatter f = new Formatter()) {
      for (final byte b : messageDigest.digest()) {
        f.format("%02x", b);
      }
      return f.toString();
    }
  }

  public static void loadFfmpeg(ReactApplicationContext ctx) {
    LoadFfmpegAsyncTaskParams loadFfmpegAsyncTaskParams = new LoadFfmpegAsyncTaskParams(ctx);

    LoadFfmpegAsyncTask loadFfmpegAsyncTask = new LoadFfmpegAsyncTask();
    loadFfmpegAsyncTask.execute(loadFfmpegAsyncTaskParams);

    // TODO: EXPOSE TO JS "isFfmpegLoaded" AND "isFfmpegLoading"

    return;
  }
}
