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
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentToeHeelWalkingBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class ToeHeelWalkingFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    enum class Mode { TOE, HEEL }
    enum class State { WALKING, RESTING_SHORT, RESTING_SET }

    companion object {
        private const val TAG = "ToeHeelWalkingFragment"
        private const val STEPS_PER_PHASE = 7
        private const val TOTAL_SETS = 3
        private const val SHORT_REST_MS = 5000L
        private const val LONG_REST_MS = 60000L
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val MIN_STEP_DISTANCE = 0.02f
        private const val TOE_HEEL_Y_DIFF_THRESHOLD = 0.015f // 判定踮腳或腳跟走的位移閾值
    }

    private var _binding: FragmentToeHeelWalkingBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Test Variables
    private var currentSet = 1
    private var currentPhaseMode = Mode.TOE
    private var currentState = State.WALKING
    private var stepsInCurrentPhase = 0
    private var lastLeadingFoot = -1 // -1: None, 0: Left, 1: Right
    
    private var totalBalanceScore = 0f
    private var balanceTicks = 0
    private var isTestCompleted = false
    private var timer: CountDownTimer? = null
    private var currentTimerStatusText = ""

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToeHeelWalkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        binding.viewFinder.post { setUpCamera() }
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
        binding.btnFinish.setOnClickListener {
            findNavController().navigate(R.id.home_fragment)
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

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation).build()
        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> detectPose(image) } }
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
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
            if (_binding != null) {
                val results = resultBundle.results.first()
                processLogic(results)
                binding.overlay.setPoseResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processLogic(results: PoseLandmarkerResult) {
        if (isTestCompleted || results.landmarks().isEmpty() || currentState != State.WALKING) return

        val landmarks = results.landmarks()[0]
        
        // 1. 可見度檢查
        val requiredIndices = intArrayOf(11, 12, 23, 24, 27, 28, 29, 30, 31, 32)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }
        
        if (!isVisible) {
            binding.overlay.updateTestInfo(stepsInCurrentPhase, currentSet, "請將全身放入畫面", calculateAvgAccuracy(), isTestCompleted, "步數", 3)
            return
        }

        // 2. 平衡感與正確姿勢判定
        val leftHeel = landmarks[29]; val rightHeel = landmarks[30]
        val leftToe = landmarks[31]; val rightToe = landmarks[32]
        
        val isPoseCorrect = when (currentPhaseMode) {
            Mode.TOE -> (leftHeel.y() < leftToe.y() - TOE_HEEL_Y_DIFF_THRESHOLD) && (rightHeel.y() < rightToe.y() - TOE_HEEL_Y_DIFF_THRESHOLD)
            Mode.HEEL -> (leftToe.y() < leftHeel.y() - TOE_HEEL_Y_DIFF_THRESHOLD) && (rightToe.y() < rightHeel.y() - TOE_HEEL_Y_DIFF_THRESHOLD)
        }

        // 3. 步數偵測
        val yDiff = leftHeel.y() - rightHeel.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0 
            yDiff < -MIN_STEP_DISTANCE -> 1 
            else -> lastLeadingFoot
        }

        if (lastLeadingFoot != -1 && currentLeadingFoot != lastLeadingFoot) {
            stepsInCurrentPhase++
            if (stepsInCurrentPhase >= STEPS_PER_PHASE) {
                moveToNextPhase()
            }
        }
        lastLeadingFoot = currentLeadingFoot

        // 4. 準確率計算
        val shoulderBalance = 1.0f - abs(landmarks[11].y() - landmarks[12].y())
        val frameScore = (shoulderBalance * 0.7f + (if (isPoseCorrect) 0.3f else 0f)) * 100f
        totalBalanceScore += frameScore
        balanceTicks++

        val phaseText = if (currentPhaseMode == Mode.TOE) "腳尖走路" else "腳跟走路"
        val status = if (isPoseCorrect) "正在$phaseText..." else "請使用${phaseText}姿勢！"
        
        binding.overlay.updateTestInfo(stepsInCurrentPhase, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "步數", 3)
    }

    private fun calculateAvgAccuracy() = if (balanceTicks == 0) 0f else (totalBalanceScore / balanceTicks).coerceIn(0f, 100f)

    private fun moveToNextPhase() {
        lastLeadingFoot = -1
        
        if (currentPhaseMode == Mode.TOE) {
            startTimer(SHORT_REST_MS, State.RESTING_SHORT, "休息中，準備換腳跟走路")
        } else {
            if (currentSet < TOTAL_SETS) {
                startTimer(LONG_REST_MS, State.RESTING_SET, "組間休息中")
            } else {
                completeTest()
            }
        }
    }

    private fun startTimer(durationMs: Long, targetState: State, message: String) {
        currentState = targetState
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                currentTimerStatusText = "$message (${ms/1000}s)"
                binding.overlay.updateTestInfo(stepsInCurrentPhase, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "步數", 3)
            }
            override fun onFinish() {
                stepsInCurrentPhase = 0 // 休息結束後才歸零步數
                if (targetState == State.RESTING_SHORT) {
                    currentPhaseMode = Mode.HEEL
                } else if (targetState == State.RESTING_SET) {
                    currentSet++ // 組間休息結束才增加組數
                    currentPhaseMode = Mode.TOE
                }
                currentState = State.WALKING
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        binding.overlay.updateTestInfo(stepsInCurrentPhase, currentSet, "測試完成！", finalAccuracy, true, "步數", 3)
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    override fun onPause() {
        super.onPause()
        timer?.cancel()
        backgroundExecutor.execute { if(this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.clearPoseLandmarker() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }
}
