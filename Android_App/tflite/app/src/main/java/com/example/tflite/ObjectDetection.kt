package com.example.tflite

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.*
import android.media.Image.Plane
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.SurfaceView
import android.view.View
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
//import kotlinx.android.synthetic.main.activity_object_detection.*

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ObjectDetection : AppCompatActivity(), ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "ObjectDetection"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        init {
            System.loadLibrary("native-lib")
        }
    }

    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var detectorAddr = 0L
    private lateinit var nv21: ByteArray
    private val labelsMap = arrayListOf<String>()
    private val _paint = Paint()

    private lateinit var surfaceView: SurfaceView
    private lateinit var viewFinder:PreviewView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_object_detection)

        supportActionBar?.hide()

        surfaceView=findViewById<SurfaceView>(R.id.surfaceView)
        viewFinder=findViewById<PreviewView>(R.id.viewFinder)
                // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // init the paint for drawing the detections
        _paint.color = Color.GREEN
        _paint.style = Paint.Style.STROKE
        _paint.strokeWidth = 3f
        _paint.textSize = 50f
        _paint.textAlign = Paint.Align.LEFT


        // Set the detections drawings surface transparent
        surfaceView.setZOrderOnTop(true)
        surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)

        loadLabels()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {  /// 카메라x 예제코드 참고하여 만들어졌다.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val rotation = viewFinder.display.rotation

            // Preview
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            // ImageAnalysis   중요!
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1080, 1920))
                .setTargetRotation(rotation)
                .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(cameraExecutor, this)
                }

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                val camKind = intent.getIntExtra("value", 0)
                // Bind use cases to camera
                if(camKind==0) {
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageAnalyzer
                    )
                }
                else {
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalyzer
                    )
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun analyze(image: ImageProxy) {
        if (image.planes.size < 3) {return}
        if (detectorAddr == 0L) {
            detectorAddr = initDetector(this.assets)
        }

        val rotation = image.imageInfo.rotationDegrees   // cpp native 통한 rotation ????

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        if (!::nv21.isInitialized) {   // YUV로 변환
            nv21 = ByteArray(ySize + uSize + vSize)
        }

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        // 탐지 후 결과
        val res = detect(detectorAddr, nv21, image.width, image.height, rotation)

        val canvas = surfaceView.holder.lockCanvas()
        if (canvas != null) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.MULTIPLY)

            // Draw the detections, in our case there are only 3
            for (i in 0 until res[0].toInt()) {
                this.drawDetection(canvas, image.width, image.height, rotation, res, i)
            }

            surfaceView.holder.unlockCanvasAndPost(canvas)
        }

        image.close()
    }

    private fun drawDetection(
        canvas: Canvas,
        frameWidth: Int,
        frameHeight: Int,
        rotation: Int,
        detectionsArr: FloatArray,
        detectionIdx: Int
    ) {

        val pos = detectionIdx * 6 + 1
        val score = detectionsArr[pos + 0]
        val classId = detectionsArr[pos + 1]
        var xmin = detectionsArr[pos + 2]
        var ymin = detectionsArr[pos + 3]
        var xmax = detectionsArr[pos + 4]
        var ymax = detectionsArr[pos + 5]

        // Filter by score
        if (score < 0.4) return

        // Get the frame dimensions
        val w = if (rotation == 0 || rotation == 180) frameWidth else frameHeight
        val h = if (rotation == 0 || rotation == 180) frameHeight else frameWidth

        // detection coords are in frame coord system, convert to screen coords
        val scaleX = viewFinder.width.toFloat() / w
        val scaleY = viewFinder.height.toFloat() / h

        // The camera view offset on screen
        val xoff = 0 // viewFinder.left.toFloat()
        val yoff = 0 // viewFinder.top.toFloat()

        xmin = xoff + xmin * scaleX
        xmax = xoff + xmax * scaleX
        ymin = yoff + ymin * scaleY
        ymax = yoff + ymax * scaleY


        // Draw the rect
        val p = Path()
        p.moveTo(xmin, ymin)
        p.lineTo(xmax, ymin)
        p.lineTo(xmax, ymax)
        p.lineTo(xmin, ymax)
        p.lineTo(xmin, ymin)

        canvas.drawPath(p, _paint)

        // classId is zero-based (meaning class id 0 is class 1)
        val label = labelsMap[classId.toInt()]

        val txt = "%s (%.2f)".format(label, score)
        canvas.drawText(txt, xmin, ymin, _paint)
    }

    private fun loadLabels() {
        val labelsInput = this.assets.open("labels.txt")
        val br = BufferedReader(InputStreamReader(labelsInput))
        var line = br.readLine()
        while (line != null) {
            labelsMap.add(line)
            line = br.readLine()
        }

        br.close()
    }


    // native 선언함수들
    private external fun initDetector(assetManager: AssetManager?): Long
    private external fun destroyDetector(ptr: Long)
    private external fun detect(ptr: Long, srcAddr: ByteArray, width: Int, height: Int, rotation: Int): FloatArray
}