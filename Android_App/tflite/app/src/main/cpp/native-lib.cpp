#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include "ObjectDetector.h"

using namespace cv;

void rotateMat(Mat &matImage, int rotation)
{
    if (rotation == 90) {
        transpose(matImage, matImage);
        flip(matImage, matImage, 1); //transpose+flip(1)=CW
    } else if (rotation == 270) {
        transpose(matImage, matImage);
        flip(matImage, matImage, 0); //transpose+flip(0)=CCW
    } else if (rotation == 180) {
        flip(matImage, matImage, -1);    //flip(-1)=180
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_tflite_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_tflite_ObjectDetection_initDetector(JNIEnv* env, jobject p_this, jobject assetManager) {

    char *buffer = nullptr;
    long size = 0;

    if (!(env->IsSameObject(assetManager, NULL))) {
        AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
        AAsset *asset = AAssetManager_open(mgr, "od_model.tflite", AASSET_MODE_UNKNOWN);
        assert(asset != nullptr);

        size = AAsset_getLength(asset);
        buffer = (char *) malloc(sizeof(char) * size);
        AAsset_read(asset, buffer, size);
        AAsset_close(asset);
    }

    jlong res = (jlong) new ObjectDetector(buffer, size);
    free(buffer); // ObjectDetector duplicate it
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_tflite_ObjectDetection_destroyDetector(JNIEnv* env, jobject p_this, jlong p_native_ptr) {
    if (p_native_ptr)
        delete (ObjectDetector *) p_native_ptr;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_tflite_ObjectDetection_detect(JNIEnv* env, jobject p_this,
                                           jlong detectorAddr, jbyteArray src, int width,
                                           int height, int rotation) {

    jbyte *_yuv = env->GetByteArrayElements(src, 0);

    Mat myyuv(height + height / 2, width, CV_8UC1, _yuv);
    Mat frame(height, width, CV_8UC4);

    cvtColor(myyuv, frame, COLOR_YUV2BGRA_NV21); // YUV -> BGRA 변환
    rotateMat(frame, rotation);

    env->ReleaseByteArrayElements(src, _yuv, 0);

    ObjectDetector *detector = (ObjectDetector *) detectorAddr;
    DetectResult *res = detector->detect(frame);

    int arrlen = 6 * detector->DETECT_NUM + 1;
    jfloat* jres = new jfloat[arrlen];
    jres[0] = detector->DETECT_NUM;

    for (int i = 0; i < detector->DETECT_NUM; ++i) {
        int pos = i * 6 + 1;
        jres[pos + 0] = res[i].score;
        jres[pos + 1] = res[i].label;
        jres[pos + 2] = res[i].xmin;
        jres[pos + 3] = res[i].ymin;
        jres[pos + 4] = res[i].xmax;
        jres[pos + 5] = res[i].ymax;
    }

    jfloatArray output = env->NewFloatArray(arrlen);
    env->SetFloatArrayRegion(output, 0, arrlen, jres);

    return output;
}