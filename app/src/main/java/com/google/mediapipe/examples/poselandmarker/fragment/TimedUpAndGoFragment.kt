package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentTimedUpAndGoBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TimedUpAndGoFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentTimedUpAndGoBinding? = null
    private val binding get() = _binding!!
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var backgroundExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    private var startTime = 0L
    private var isTesting = false
    private var currentSeconds = 0f

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTimedUpAndGoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        binding.viewFinder.post { setUpCamera() }

        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                poseLandmarkerHelperListener = this
            )
        }

        binding.btnAction.setOnClickListener {
            if (!isTesting) {
                isTesting = true
                startTime = SystemClock.elapsedRealtime()
                binding.btnAction.text = "回到椅子並坐下(完成)"
            } else {
                finishTest()
            }
        }

        binding.btnDialogOk.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.fabSwitchCamera.setOnClickListener {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            bindCameraUseCases()
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!::poseLandmarkerHelper.isInitialized) {
            imageProxy.close()
            return
        }

        val isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        poseLandmarkerHelper.detectLiveStream(imageProxy, isFrontCamera)
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding != null) {
                val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                if (isTesting) {
                    currentSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
                }
                binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)

                // 更新 OverlayView 的左上方資訊
                binding.overlay.updateTestInfo(
                    count = 0,
                    sets = 1,
                    message = if (isTesting) "測試中" else "準備中",
                    accuracy = 0f,
                    label = "計時起身行走測試",
                    maxSets = 0, // 不顯示組數/次數
                    setLabel = "", // 隱藏第一行
                    time = String.format(Locale.US, "%.2f", currentSeconds)
                )
            }
        }
    }

    private fun finishTest() {
        isTesting = false
        val duration = (SystemClock.elapsedRealtime() - startTime) / 1000f
        val resultText = if (duration > 20f) "超過 20 秒" else "未超過 20 秒"
        binding.dialogLayout.visibility = View.VISIBLE
        binding.tvDialogResult.text = String.format(Locale.US, "執行時間: %.2fs\n結果: %s", duration, resultText)
    }

    override fun onError(error: String, errorCode: Int) { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    override fun onDestroyView() { _binding = null; super.onDestroyView(); backgroundExecutor.shutdown() }
}
