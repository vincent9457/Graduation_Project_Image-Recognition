/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class CameraFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Pose Landmarker"
        private const val TOTAL_STEPS_PER_SET = 15
        private const val TOTAL_SETS = 3
        private const val REST_TIME_MS = 30000L
        private const val CONTACT_THRESHOLD = 0.12f 
        private const val VISIBILITY_THRESHOLD = 0.5f 
        private const val MIN_STEP_DISTANCE = 0.02f  
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Gait Test Variables
    private var currentStep = 0
    private var currentSet = 1
    private var isResting = false
    private var lastLeadingFoot = -1 
    private var totalStepsAccumulated = 0 // 總累計步數
    private var validContactCount = 0
    private var totalShoulderBalance = 0f
    private var balanceTicks = 0
    private var restTimer: CountDownTimer? = null
    private var isTestCompleted = false

    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
        }
        backgroundExecutor.execute {
            if(this::poseLandmarkerHelper.isInitialized && poseLandmarkerHelper.isClose()) {
                poseLandmarkerHelper.setupPoseLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        restTimer?.cancel()
        if(this::poseLandmarkerHelper.isInitialized) {
            viewModel.setMinPoseDetectionConfidence(poseLandmarkerHelper.minPoseDetectionConfidence)
            viewModel.setMinPoseTrackingConfidence(poseLandmarkerHelper.minPoseTrackingConfidence)
            viewModel.setMinPosePresenceConfidence(poseLandmarkerHelper.minPosePresenceConfidence)
            viewModel.setDelegate(poseLandmarkerHelper.currentDelegate)
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        fragmentCameraBinding.viewFinder.post { setUpCamera() }
        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = this
            )
        }
        
        fragmentCameraBinding.btnFinish.setOnClickListener {
            findNavController().navigate(R.id.home_fragment)
        }

        fragmentCameraBinding.fabSwitchCamera.setOnClickListener {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            bindCameraUseCases()
        }

        initBottomSheetControls()
    }

    private fun initBottomSheetControls() {
        // ... (保持原樣或依需求更新)
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation).build()
        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> detectPose(image) } }
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc) }
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if(this::poseLandmarkerHelper.isInitialized) {
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                val results = resultBundle.results.first()
                processGaitLogic(results)
                fragmentCameraBinding.overlay.setResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processGaitLogic(results: PoseLandmarkerResult) {
        if (isResting || isTestCompleted || results.landmarks().isEmpty()) return

        val landmarks = results.landmarks()[0]
        
        val leftFootVisible = landmarks[29].visibility().orElse(0f) > VISIBILITY_THRESHOLD && 
                            landmarks[31].visibility().orElse(0f) > VISIBILITY_THRESHOLD
        val rightFootVisible = landmarks[30].visibility().orElse(0f) > VISIBILITY_THRESHOLD && 
                             landmarks[32].visibility().orElse(0f) > VISIBILITY_THRESHOLD

        if (!leftFootVisible || !rightFootVisible) {
            fragmentCameraBinding.overlay.updateTestInfo(
                currentStep, currentSet, "請將雙腳放入畫面", calculateAccuracy(), isTestCompleted
            )
            return
        }

        // 準確率計算：雙肩平衡
        val shoulderBalance = 1.0f - abs(landmarks[11].y() - landmarks[12].y())
        totalShoulderBalance += (shoulderBalance * 100f).coerceIn(0f, 100f)
        balanceTicks++

        val leftHeel = landmarks[29]; val rightHeel = landmarks[30]
        val leftToe = landmarks[31]; val rightToe = landmarks[32]

        val yDiff = leftHeel.y() - rightHeel.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0 
            yDiff < -MIN_STEP_DISTANCE -> 1 
            else -> lastLeadingFoot
        }

        if (lastLeadingFoot != -1 && currentLeadingFoot != lastLeadingFoot) {
            currentStep++
            totalStepsAccumulated++ // 累計總步數
            
            val contactDist = if (currentLeadingFoot == 0) abs(rightToe.y() - leftHeel.y()) else abs(leftToe.y() - rightHeel.y())

            if (contactDist < CONTACT_THRESHOLD) validContactCount++

            if (currentStep >= TOTAL_STEPS_PER_SET) {
                if (currentSet < TOTAL_SETS) startRestPeriod() else completeTest()
            }
        }
        
        lastLeadingFoot = currentLeadingFoot
        val accuracy = calculateAccuracy()
        val status = if (isResting) "休息中" else "測試中..."
        fragmentCameraBinding.overlay.updateTestInfo(currentStep, currentSet, status, accuracy, isTestCompleted)
    }

    private fun calculateAccuracy(): Float {
        if (totalStepsAccumulated == 0) return 0f
        // 足部接觸得分 (50%) + 肩膀平衡得分 (50%)
        val contactScore = (validContactCount.toFloat() / totalStepsAccumulated).coerceIn(0f, 1f) * 100f
        val balanceScore = if (balanceTicks == 0) 0f else totalShoulderBalance / balanceTicks
        return (contactScore * 0.5f + balanceScore * 0.5f)
    }

    private fun startRestPeriod() {
        isResting = true
        restTimer = object : CountDownTimer(REST_TIME_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                fragmentCameraBinding.overlay.updateTestInfo(
                    currentStep, currentSet, "休息 (${millisUntilFinished/1000}s)", calculateAccuracy()
                )
            }
            override fun onFinish() {
                isResting = false; currentSet++; currentStep = 0; lastLeadingFoot = -1;
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAccuracy()
        fragmentCameraBinding.overlay.updateTestInfo(currentStep, currentSet, "測試完成！", finalAccuracy, true)
        
        // 顯示結果對話框
        fragmentCameraBinding.resultPanel.visibility = View.VISIBLE
        fragmentCameraBinding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }
}
