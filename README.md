# VideoStabilizer (Android AI Video Stabilizer)

An advanced Android video stabilization app built with Jetpack Compose and Kotlin. The application allows users to select any object or region in a video; it automatically detects the precise contour boundary of the object, tracks its position and rotation angle frame-by-frame (even during severe camera shake or when the object temporarily exits the frame), and smoothly centers/stabilizes the video around the target.

---

## 🌟 Key Features

1. **Interactive Object & Area Selection**:
   - Tap and drag a bounding box directly on any frame of the video preview.
   - Live visual guide and bounding box overlays.

2. **AI Boundary & Contour Detection (`ObjectBoundaryDetector`)**:
   - **Bimodal Color Modeling**: Separates object foreground from background using Gaussian color distance priors.
   - **Sobel Gradient Edge Energy**: Refines boundaries along sharp high-frequency edges.
   - **Connected Component Analysis & Contour Extraction**: Computes the exact convex polygon contour enclosing the object.
   - **Image Moments & Inertial Axis**: Computes second-order central moments ($\mu_{20}, \mu_{02}, \mu_{11}$) to determine the object's orientation angle $\theta = \frac{1}{2}\text{atan2}(2\mu_{11}, \mu_{20} - \mu_{02})$.

3. **Robust Object Tracking with Rotation (`VisualObjectTracker`)**:
   - **Rotated Template Matching (ZNCC)**: Evaluates Zero-mean Normalized Cross-Correlation across rotational candidate angles.
   - **HSV Color Histogram Back-projection**: Provides color invariance under dynamic motion.
   - **Contour Rotation & Transform**: Transforms boundary polygon coordinates dynamically with object rotation.
   - **Angular Velocity Estimation**: Tracks rotational velocity ($\omega = \dot{\theta}$) with phase-unrolling to prevent 360° flip discontinuities.

4. **Out-of-Frame Handling & Inertial Coasting**:
   - Detects when the target moves partially or completely out of frame.
   - Employs **Kalman-inspired kinematic coasting**: maintains linear and angular velocity with friction damping so camera movements remain cinematic and do not jitter or snap.
   - Automatically re-locks onto the target with expanded spatial search windows upon re-entry.

5. **Smooth Video Stabilization (`TrajectorySmoother` & `VideoStabilizationEngine`)**:
   - **Translation Modes**:
     - `STRICT_CENTER`: Locks the object's center of mass strictly at screen center.
     - `CINEMATIC_FOLLOW`: Exponentially smoothed following for cinematic camera motion.
     - `EXTREME_STABILIZE`: Aggressive high-frequency jitter suppression.
   - **Rotation Modes**:
     - `UPRIGHT_LEVEL`: Counter-rotates video to keep the tracked object upright ($0^\circ$).
     - `FOLLOW_OBJECT_ROTATION`: Follows the object's orientation smoothly.
     - `SMOOTH_ROTATION`: Dampens fast rotational vibrations while allowing macro camera pans.
   - **Safe-Margin Boundary Clamping**: Dynamically calculates zoom scale factor and translation margins to prevent black borders from appearing at the edges of the frame.

6. **Interactive Compose UI**:
   - Real-time preview with side-by-side or split comparison mode (Original vs. Stabilized).
   - Dynamic boundary polygon and orientation vector HUD overlays.
   - Built-in Synthetic Demo Video Generator with simulated camera shake, rotating drone, and out-of-frame motion.
   - Full timeline scrubber, playback controls, and stabilization configuration bottom sheet.

7. **Hardware-Accelerated Video Export (`VideoExporter`)**:
   - Real-time transformation matrix application (translation, rotation, scaling) using Android `MediaCodec` and `MediaMuxer`.

---

## 🛠 Tech Stack & Requirements

- **Language**: Kotlin 2.3.20
- **Platform**: Android SDK 36 (Android 16 / VanillaIceCream / Backwards compatible down to Android 7.0 / API 24)
- **UI Framework**: Jetpack Compose & Material 3
- **Architecture**: MVVM with Kotlin Coroutines & StateFlow
- **Build System**: Gradle 9.0.1 with Version Catalogs (`gradle/libs.versions.toml`)

---

## 🚀 Building & Testing

### Prerequisites
- JDK 17 (e.g. Android Studio JBR: `C:\Program Files\Android\Android Studio\jbr`)
- Android SDK Platform 36

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Build Debug APK
```bash
./gradlew assembleDebug
```
The output APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License
MIT License. Created for AI Video Stabilization on Android.
