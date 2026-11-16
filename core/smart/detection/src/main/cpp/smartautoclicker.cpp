/*
 * Copyright (C) 2025 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <jni.h>
#include <opencv2/imgproc/imgproc.hpp>
#include <string>
#include <map>
#include <vector>

#include "jni/jni.hpp"
#include "detector/detector.hpp"

using namespace smartautoclicker;

std::vector<std::string> toNativeStrings(JNIEnv *env, jobjectArray javaStrings) {
    std::vector<std::string> nativeStrings;
    const jsize length = env->GetArrayLength(javaStrings);
    nativeStrings.reserve(length);

    for (jsize index = 0; index < length; index++) {
        auto javaString = static_cast<jstring>(env->GetObjectArrayElement(javaStrings, index));
        const char* nativeString = env->GetStringUTFChars(javaString, nullptr);
        if (nativeString != nullptr) nativeStrings.emplace_back(nativeString);
        if (nativeString != nullptr) env->ReleaseStringUTFChars(javaString, nativeString);
        env->DeleteLocalRef(javaString);
    }
    return nativeStrings;
}

extern "C" {

    JNIEXPORT jlong JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_newDetector(
            JNIEnv *env,
            jobject self
    ) {
        return reinterpret_cast<jlong>(new Detector());
    }

    JNIEXPORT jboolean JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_loadDetectionModels(
            JNIEnv* env,
            jobject self,
            jstring detectionModelPath,
            jobjectArray recognitionModelIds,
            jobjectArray recognitionModelPaths
    ) {
        const char* nativeDetectionPath = env->GetStringUTFChars(detectionModelPath, nullptr);

        // Convert parallel arrays back to std::map<std::string, std::string>
        std::map<std::string, std::string> recognitionModels;
        jsize length = env->GetArrayLength(recognitionModelIds);

        for (jsize i = 0; i < length; i++) {
            auto key = (jstring) env->GetObjectArrayElement(recognitionModelIds, i);
            auto value = (jstring) env->GetObjectArrayElement(recognitionModelPaths, i);

            const char* nativeKey = env->GetStringUTFChars(key, nullptr);
            const char* nativeValue = env->GetStringUTFChars(value, nullptr);

            recognitionModels[nativeKey] = std::string(nativeValue);

            env->ReleaseStringUTFChars(key, nativeKey);
            env->ReleaseStringUTFChars(value, nativeValue);
            env->DeleteLocalRef(key);
            env->DeleteLocalRef(value);
        }

        auto detector = getDetectorFromJavaRef(env, self);
        bool result = false;
        if (detector) {
            result = detector->loadModels(nativeDetectionPath, recognitionModels);
        }

        env->ReleaseStringUTFChars(detectionModelPath, nativeDetectionPath);

        return result ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_setScreenImage(
            JNIEnv *env,
            jobject self,
            jobject screenBitmap,
            jstring metricsTag
    ) {
        const char* nativeMetricsTag = env->GetStringUTFChars(metricsTag, nullptr);
        if (nativeMetricsTag == nullptr) return;

        auto detector = getDetectorFromJavaRef(env, self);
        if (!detector) return;

        std::unique_ptr<cv::Mat> screenMat = loadMatFromRGBA8888Bitmap(env, screenBitmap);
        if (!screenMat) return;

        detector->setScreenImage(std::move(screenMat), nativeMetricsTag);
        env->ReleaseStringUTFChars(metricsTag, nativeMetricsTag);
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_detectImageNative(
            JNIEnv *env,
            jobject self,
            jobject conditionBitmap,
            jint conditionWidth,
            jint conditionHeight,
            jint x,
            jint y,
            jint width,
            jint height,
            jint threshold
    ) {
        auto detector = getDetectorFromJavaRef(env, self);
        if (!detector) return nullptr;

        std::unique_ptr<cv::Mat> conditionMat = loadMatFromRGBA8888Bitmap(env, conditionBitmap);
        if (!conditionMat) return nullptr;

        jdoubleArray result = nullptr;
        try {
            result = toJniResult(env, detector->detectImage(
                    std::move(conditionMat),
                    conditionWidth,
                    conditionHeight,
                    cv::Rect(x, y, width, height),
                    threshold));
        } catch (...) {
            releaseBitmapLock(env, conditionBitmap);
            throwRuntimeException(env, "Invalid detection arguments for image detection");
        }

        releaseBitmapLock(env, conditionBitmap);
        return result;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_detectColorNative(
            JNIEnv *env,
            jobject self,
            jint conditionColor,
            jint x,
            jint y,
            jint width,
            jint height,
            jint threshold
    ) {
        auto detector = getDetectorFromJavaRef(env, self);
        if (!detector) return nullptr;

        try {
            return toJniResult(env, detector->detectColor(
                    conditionColor,
                    cv::Rect(x, y, width, height),
                    threshold));
        } catch (...) {
            throwRuntimeException(env, "Invalid detection arguments for color detection");
            return nullptr;
        }
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_detectTextNative(
            JNIEnv *env,
            jobject self,
            jobjectArray conditionTexts,
            jstring recognitionModelId,
            jint x,
            jint y,
            jint width,
            jint height,
            jint threshold
    ) {
        auto detector = getDetectorFromJavaRef(env, self);
        if (!detector) return nullptr;

        std::vector<std::string> nativeConditionTexts = toNativeStrings(env, conditionTexts);
        if (nativeConditionTexts.empty()) return nullptr;

        const char* nativeRecognitionModelId = env->GetStringUTFChars(recognitionModelId, nullptr);
        if (nativeRecognitionModelId == nullptr) return nullptr;

        jdoubleArray result = nullptr;
        try {
            result = toJniResult(env, detector->detectText(
                    nativeConditionTexts,
                    nativeRecognitionModelId,
                    cv::Rect(x, y, width, height),
                    threshold));
        } catch (...) {
            throwRuntimeException(env, "Invalid detection arguments for text detection");
        }

        env->ReleaseStringUTFChars(recognitionModelId, nativeRecognitionModelId);
        return result;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_detectNumberNative(
            JNIEnv *env,
            jobject self,
            jint x,
            jint y,
            jint width,
            jint height,
            jint threshold,
            jint numberFormat
    ) {
        auto detector = getDetectorFromJavaRef(env, self);
        if (!detector) return nullptr;

        try {
            return toJniResult(env, detector->detectNumber(
                    cv::Rect(x, y, width, height),
                    threshold,
                    static_cast<NumberFormat>(numberFormat)));
        } catch (...) {
            throwRuntimeException(env, "Invalid detection arguments for number detection");
        }

        return nullptr;
    }

    JNIEXPORT void JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_releaseScreenImage(
            JNIEnv *env,
            jobject self,
            jobject screenBitmap
    ) {
        releaseBitmapLock(env, screenBitmap);
    }

    JNIEXPORT void JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_deleteDetector(
            JNIEnv *env,
            jobject self
    ) {
        auto detector = getDetectorFromJavaRef(env, self);
        if (!detector) return;

        delete detector;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_buzbuz_smartautoclicker_core_detection_NativeDetector_readTextNative(
        JNIEnv* env, jobject self, jstring model, jint x, jint y, jint width, jint height, jint threshold, jint maxResults) {
    auto detector = getDetectorFromJavaRef(env, self);
    if (!detector) return nullptr;
    const char* modelChars = env->GetStringUTFChars(model, nullptr);
    if (!modelChars) return nullptr;
    std::vector<TextRecognizerResult> results;
    try { results = detector->readText(cv::Rect(x, y, width, height), modelChars, threshold, maxResults); }
    catch (...) { throwRuntimeException(env, "Invalid free-text recognition arguments"); }
    env->ReleaseStringUTFChars(model, modelChars);
    if (env->ExceptionCheck()) return nullptr;
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    jobjectArray output = env->NewObjectArray(results.size() * 6, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (!output) return nullptr;
    int index = 0;
    for (const auto& result : results) {
        std::vector<std::string> fields = {result.text, std::to_string(result.confidence * 100),
            std::to_string(result.boundingBox.x), std::to_string(result.boundingBox.y),
            std::to_string(result.boundingBox.width), std::to_string(result.boundingBox.height)};
        for (const auto& field : fields) {
            jstring value = env->NewStringUTF(field.c_str());
            if (!value) return nullptr;
            env->SetObjectArrayElement(output, index++, value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) return nullptr;
        }
    }
    return output;
}
