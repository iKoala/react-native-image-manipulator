package com.reactnativeimagemanipulator;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.facebook.common.executors.CallerThreadExecutor;
import com.facebook.common.executors.UiThreadImmediateExecutorService;
import com.facebook.common.references.CloseableReference;
import com.facebook.datasource.DataSource;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.common.RotationOptions;
import com.facebook.imagepipeline.datasource.BaseBitmapDataSubscriber;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

public class ImageManipulatorModule extends ReactContextBaseJavaModule {
  private static final String TAG = "ImageManipulatorModule";

  private static final String DECODE_ERROR_TAG = "E_DECODE_ERR";
  private static final String ARGS_ERROR_TAG = "E_ARGS_ERR";

  private static final int COLOR_TOLERANCE = 40;

  public ImageManipulatorModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return "ImageManipulator";
  }

  @ReactMethod
  public void manipulateAsync(final String uriString, final ReadableArray actions, final ReadableMap saveOptions, final Promise promise) {
    if (uriString == null || uriString.length() == 0) {
      promise.reject(ARGS_ERROR_TAG, "Uri passed to ImageManipulator cannot be empty!");
      return;
    }
    ImageRequest imageRequest =
        ImageRequestBuilder
            .newBuilderWithSource(Uri.parse(uriString))
            .setRotationOptions(RotationOptions.autoRotate())
            .build();
    final DataSource<CloseableReference<CloseableImage>> dataSource
        = Fresco.getImagePipeline().fetchDecodedImage(imageRequest, getReactApplicationContext());
    dataSource.subscribe(new BaseBitmapDataSubscriber() {
                           @Override
                           public void onNewResultImpl(Bitmap bitmap) {
                             if (bitmap != null) {
                               processBitmapWithActions(bitmap, actions, saveOptions, promise);
                             } else {
                               onFailureImpl(dataSource);
                             }
                           }

                           @Override
                           public void onFailureImpl(DataSource dataSource) {
                             // No cleanup required here.
                             String basicMessage = "Could not get decoded bitmap of " + uriString;
                             if (dataSource.getFailureCause() != null) {
                               promise.reject(DECODE_ERROR_TAG,
                                   basicMessage + ": " + dataSource.getFailureCause().toString(), dataSource.getFailureCause());
                             } else {
                               promise.reject(DECODE_ERROR_TAG, basicMessage + ".");
                             }
                           }
                         },
        CallerThreadExecutor.getInstance()
    );
  }

  private void processBitmapWithActions(Bitmap bmp, ReadableArray actions, ReadableMap saveOptions, Promise promise) {
    int imageWidth, imageHeight;

    for (int idx = 0; idx < actions.size(); idx ++) {
      ReadableMap options = actions.getMap(idx);

      imageWidth = bmp.getWidth();
      imageHeight = bmp.getHeight();

      if (options.hasKey("resize")) {
        ReadableMap resize = options.getMap("resize");
        int requestedWidth = 0;
        int requestedHeight = 0;
        float imageRatio = 1.0f * imageWidth / imageHeight;

        if (resize.hasKey("width")) {
          requestedWidth = (int) resize.getDouble("width");
          requestedHeight = (int) (requestedWidth / imageRatio);
        }
        if (resize.hasKey("height")) {
          requestedHeight = (int) resize.getDouble("height");
          requestedWidth = requestedWidth == 0 ? (int) (imageRatio * requestedHeight) : requestedWidth;
        }

        bmp = Bitmap.createScaledBitmap(bmp, requestedWidth, requestedHeight, true);
      } else if (options.hasKey("rotate")) {
        int requestedRotation = options.getInt("rotate");
        Matrix rotationMatrix = new Matrix();
        rotationMatrix.postRotate(requestedRotation);
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), rotationMatrix, true);
      } else if (options.hasKey("flip")) {
        Matrix rotationMatrix = new Matrix();
        ReadableMap flip = options.getMap("flip");
        if (flip.hasKey("horizontal") && flip.getBoolean("horizontal")) {
          rotationMatrix.postScale(-1, 1);
        }
        if (flip.hasKey("vertical") && flip.getBoolean("vertical")) {
          rotationMatrix.postScale(1, -1);
        }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), rotationMatrix, true);
      } else if (options.hasKey("crop")) {
        ReadableMap crop = options.getMap("crop");
        if (!crop.hasKey("originX") || !crop.hasKey("originY") || !crop.hasKey("width") || !crop.hasKey("height")) {
          promise.reject("E_INVALID_CROP_DATA", "Invalid crop options has been passed. Please make sure the object contains originX, originY, width and height.");
          return;
        }
        int originX, originY, requestedWidth, requestedHeight;
        originX = (int) crop.getDouble("originX");
        originY = (int) crop.getDouble("originY");
        requestedWidth = (int) crop.getDouble("width");
        requestedHeight = (int) crop.getDouble("height");
        if (originX > imageWidth || originY > imageHeight || requestedWidth > bmp.getWidth() || requestedHeight > bmp.getHeight()) {
          promise.reject("E_INVALID_CROP_DATA", "Invalid crop options has been passed. Please make sure the requested crop rectangle is inside source image.");
          return;
        }
        bmp = Bitmap.createBitmap(bmp, originX, originY, requestedWidth, requestedHeight);
      } else if (options.hasKey("cutout")) {
        ReadableMap cutout = options.getMap("cutout");
        // int tolerance = COLOR_TOLERANCE;
        int toleranceAlpha = COLOR_TOLERANCE;
        int toleranceRed = COLOR_TOLERANCE;
        int toleranceGreen = COLOR_TOLERANCE;
        int toleranceBlue = COLOR_TOLERANCE;
        int alpha = 0;
        int red = 0;
        int green = 0;
        int blue = 0;
        if (cutout.hasKey("tolarenceRed")) {
          toleranceRed = cutout.getInt("tolarenceRed");
        }
        if (cutout.hasKey("tolarenceGreen")) {
          toleranceGreen = cutout.getInt("tolarenceGreen");
        }
        if (cutout.hasKey("tolarenceBlue")) {
          toleranceBlue = cutout.getInt("tolarenceBlue");
        }
        if (cutout.hasKey("alpha")) {
          alpha = cutout.getInt("alpha");
        }
        if (cutout.hasKey("red")) {
          red = cutout.getInt("red");
        }
        if (cutout.hasKey("green")) {
          green = cutout.getInt("green");
        }
        if (cutout.hasKey("blue")) {
          blue = cutout.getInt("blue");
        }
        bmp = this.removeBackgroundColor(bmp, alpha, red, green, blue, toleranceAlpha, toleranceRed, toleranceGreen, toleranceBlue);
      }
    }

    int compressionQuality = 100;
    if (saveOptions.hasKey("compress")) {
      compressionQuality = (int) (100 * saveOptions.getDouble("compress"));
    }
    String format, extension;
    Bitmap.CompressFormat compressFormat;

    if (saveOptions.hasKey("format")) {
      format = saveOptions.getString("format");
    } else {
      format = "jpeg";
    }

    if (format.equals("png")) {
      compressFormat = Bitmap.CompressFormat.PNG;
      extension = ".png";
    } else if (format.equals("jpeg")) {
      compressFormat = Bitmap.CompressFormat.JPEG;
      extension = ".jpg";
    } else {
      compressFormat = Bitmap.CompressFormat.JPEG;
      extension = ".jpg";
    }

    boolean base64 = saveOptions.hasKey("base64") && saveOptions.getBoolean("base64");

    FileOutputStream out = null;
    ByteArrayOutputStream byteOut = null;
    String path = null;
    String base64String = null;
    try {
      path = this.getReactApplicationContext().getFilesDir() + "/" + UUID.randomUUID() + extension;
      out = new FileOutputStream(path);
      bmp.compress(compressFormat, compressionQuality, out);

      if (base64) {
        byteOut = new ByteArrayOutputStream();
        bmp.compress(compressFormat, compressionQuality, byteOut);
        base64String = Base64.encodeToString(byteOut.toByteArray(), Base64.DEFAULT);
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        if (out != null) {
          out.close();
        }
        if (byteOut != null) {
          byteOut.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    WritableMap response = Arguments.createMap();
    response.putString("uri", Uri.fromFile(new File(path)).toString());
    response.putInt("width", bmp.getWidth());
    response.putInt("height", bmp.getHeight());
    if (base64) {
      response.putString("base64", base64String);
    }
    response.putString("bg", "removed");
    promise.resolve(response);
  }

  protected Bitmap removeBackgroundColor(Bitmap oldBitmap, int alpha, int red, int green, int blue, int toleranceAlpha, int toleranceRed, int toleranceGreen, int toleranceBlue) {
    Log.d(TAG, "removeBackgroundColor()");
    Log.d(TAG, "toleranceAlpha >> " + toleranceAlpha);
    Log.d(TAG, "toleranceRed >> " + toleranceRed);
    Log.d(TAG, "toleranceGreen >> " + toleranceGreen);
    Log.d(TAG, "toleranceBlue >> " + toleranceBlue);
    Log.d(TAG, "alpha >> " + alpha);
    Log.d(TAG, "red >> " + red);
    Log.d(TAG, "green >> " + green);
    Log.d(TAG, "blue >> " + blue);
    // Bitmap oldBitmap = drawViewWeakReference.get().imageBitmap;

    // String hexColor = "00ffffff";
    // int A = 255;
    // int R = 255;
    // int G = 255;
    // int B = 255;

    // int colorToReplace = oldBitmap.getPixel(points[0], points[1]);
    // int colorToReplace = Integer.parseInt(hexColor, 16);
    // int colorToReplace = (A & 0xff) << 24 | (R & 0xff) << 16 | (G & 0xff) << 8 | (B & 0xff);

    int width = oldBitmap.getWidth();
    int height = oldBitmap.getHeight();
    int[] pixels = new int[width * height];
    oldBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

    // int rA = Color.alpha(colorToReplace);
    // int rR = Color.red(colorToReplace);
    // int rG = Color.green(colorToReplace);
    // int rB = Color.blue(colorToReplace);
    int rA = alpha;
    int rR = red;
    int rG = green;
    int rB = blue;

    // Log.d(TAG, "rA >> " + rA);
    // Log.d(TAG, "rR >> " + rR);
    // Log.d(TAG, "rG >> " + rG);
    // Log.d(TAG, "rB >> " + rB);

    int pixel;

    // iteration through pixels
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            // get current index in 2D-matrix
            int index = y * width + x;
            pixel = pixels[index];
            int rrA = Color.alpha(pixel);
            int rrR = Color.red(pixel);
            int rrG = Color.green(pixel);
            int rrB = Color.blue(pixel);
            
            // Include Alpha color value
            // if (rA - COLOR_TOLERANCE < rrA && rrA < rA + COLOR_TOLERANCE && rR - COLOR_TOLERANCE < rrR && rrR < rR + COLOR_TOLERANCE &&
            //         rG - COLOR_TOLERANCE < rrG && rrG < rG + COLOR_TOLERANCE && rB - COLOR_TOLERANCE < rrB && rrB < rB + COLOR_TOLERANCE) {
            //     pixels[index] = Color.TRANSPARENT;
            // }

            // Ignore Alpha color value
            // if (rR - toleranceRed < rrR && rrR < rR + toleranceRed
            //     && rG - toleranceGreen < rrG && rrG < rG + toleranceGreen
            //     && rB - toleranceBlue < rrB && rrB < rB + toleranceBlue) {
            //     pixels[index] = Color.TRANSPARENT;
            // }

            // if the color at the current pixel is mostly green
            // * (green value is greater than blue and red combined), 
            //  * then use the new background color
            int combindedColor = 0;
            // Green Background
            if (rG >= rR && rG >= rB) {
              combindedColor = (rrR + rrB) + 50 - toleranceGreen;
              if (rrG >= 80 && combindedColor / 2 < rrG) {
                pixels[index] = Color.TRANSPARENT;
              }
            }
            // Blue Background
            if (rB >= rR && rB >= rG) {
              combindedColor = rrR + rrG + 50 - toleranceBlue;
              if (rrB >= 80 && combindedColor / 2 < rrB) {
                pixels[index] = Color.TRANSPARENT;
              }
            }
            // Red Background
            if (rR >= rG && rR >= rB) {
              combindedColor = rrG + rrB + 50 - toleranceRed;
              if (rrR >= 80 && combindedColor / 2 < rrR) {
                pixels[index] = Color.TRANSPARENT;
              }
            }

            // Debug Test
            // if (y <= height / 10) {
            //   pixels[index] = Color.TRANSPARENT;
            // }
        }
    }

    Bitmap newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    newBitmap.setPixels(pixels, 0, width, 0, 0, width, height);

    return newBitmap;
  }
}
