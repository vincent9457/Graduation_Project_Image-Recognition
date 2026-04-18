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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentGaitSpeed6mBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GaitSpeed6mFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentGaitSpeed6mBinding? = null
    private val binding get() = _binding!!
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var backgroundExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    private var testCount = 0
    private var startTime = 0L
    private var isWalking = false
    private var bestTime = Float.MAX_VALUE
    private var currentSeconds = 0f

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGaitSpeed6mBinding.inflate(inflater, container, false)
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
            if (!isWalking) {
                testCount++
                isWalking = true
                startTime = SystemClock.elapsedRealtime()
                binding.btnAction.text = "到達 6m 終點"
            } else {
                finishTrial()
            }
        }

        binding.btnDialogOk.setOnClickListener {
            binding.dialogLayout.visibility = View.GONE
            if (testCount >= 2) {
                findNavController().navigateUp()
            } else {
                binding.btnAction.text = "開始第二次測試"
            }
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
                if (isWalking) {
                    currentSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
                }
                binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)

                // 更新 OverlayView 的左上方資訊
                binding.overlay.updateTestInfo(
                    count = testCount,
                    sets = if (testCount > 0) testCount else 1,
                    message = if (isWalking) "測試中" else "準備中",
                    accuracy = 0f,
                    label = "測試次數",
                    maxSets = 2,
                    setLabel = "測試次數",
                    time = String.format(Locale.US, "%.2f", currentSeconds)
                )
            }
        }
    }

    private fun finishTrial() {
        isWalking = false
        val duration = (SystemClock.elapsedRealtime() - startTime) / 1000f
        if (duration < bestTime) bestTime = duration
        
        val resultText = if (bestTime > 7.5f) "超過 7.5 秒" else "未超過 7.5 秒"
        binding.dialogLayout.visibility = View.VISIBLE
        binding.tvDialogResult.text = String.format(Locale.US, "本次時間: %.2fs\n最短時間: %.2fs\n結果: %s", duration, bestTime, resultText)
        binding.btnDialogOk.text = if (testCount < 2) "進行下一次測試" else "結束"
    }

    override fun onError(error: String, errorCode: Int) { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    override fun onDestroyView() { _binding = null; super.onDestroyView(); backgroundExecutor.shutdown() }
}
