/**
 * native-lib.cpp — Procesamiento nativo JNI para Práctica 2-1
 * ============================================================
 * Parte 1-A: In-camera transformation (Efecto Karl Struss)
 *   - cv::split()  separar canales R, G, B
 *   - Alpha blending: Salida = (1-α)·B + α·R
 *
 * Parte 1-B: Chroma Key + Ruido + Filtros
 *   - cv::cvtColor → HSV + cv::inRange → máscara de fondo
 *   - Ruido Gaussiano y Speckle sobre primer plano
 *   - Filtro Mediana o Gaussiano ajustable
 *
 * Autor  : Michael Lata
 * Materia: Visión por Computador — Práctica 2-1
 */

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <vector>
#include <cmath>

#define LOG_TAG "Practica21"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Utilidad: convertir Bitmap ARGB_8888 → cv::Mat BGR
// ---------------------------------------------------------------------------
static cv::Mat bitmapToMat(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return {};

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return {};

    cv::Mat src(info.height, info.width, CV_8UC4, pixels);
    cv::Mat bgr;
    cv::cvtColor(src, bgr, cv::COLOR_RGBA2BGR);

    AndroidBitmap_unlockPixels(env, bitmap);
    return bgr;
}

// ---------------------------------------------------------------------------
// Utilidad: cv::Mat BGR → escribir de vuelta al Bitmap ARGB_8888
// ---------------------------------------------------------------------------
static void matToBitmap(JNIEnv* env, const cv::Mat& mat, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return;

    cv::Mat dst(info.height, info.width, CV_8UC4, pixels);
    cv::cvtColor(mat, dst, cv::COLOR_BGR2RGBA);

    AndroidBitmap_unlockPixels(env, bitmap);
}

// ---------------------------------------------------------------------------
// PARTE 1-A — Efecto Karl Struss (separación de canales + alpha blending)
// ---------------------------------------------------------------------------
/**
 * Aplica: Salida(x,y) = (1 - alpha) * B(x,y) + alpha * R(x,y)
 * La imagen pasa de dominantemente azul (alpha→0) a dominantemente roja (alpha→1),
 * revelando objetos pintados en rojo que son invisibles en el canal azul.
 *
 * @param alpha  valor entre 0.0 y 1.0 controlado desde el SeekBar de Android
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_ups_practica21_MainActivity_nativeKarlStruss(
        JNIEnv*  env,
        jobject  /* thiz */,
        jobject  bitmap,
        jfloat   alpha)
{
    cv::Mat frame = bitmapToMat(env, bitmap);
    if (frame.empty()) { LOGE("bitmapToMat devolvio frame vacio"); return; }

    // Separar los tres canales BGR
    std::vector<cv::Mat> canales(3);
    cv::split(frame, canales);          // canales[0]=B, [1]=G, [2]=R

    // Fórmula: Salida = (1 - alpha)*B + alpha*R  (en escala de grises)
    cv::Mat salida;
    cv::addWeighted(canales[0], 1.0 - alpha,   // peso canal Azul
                    canales[2], alpha,           // peso canal Rojo
                    0.0, salida);

    // Convertir salida de grises a BGR para mostrar en pantalla
    cv::Mat salidaBGR;
    cv::cvtColor(salida, salidaBGR, cv::COLOR_GRAY2BGR);

    matToBitmap(env, salidaBGR, bitmap);
}

// ---------------------------------------------------------------------------
// PARTE 1-B — Chroma Key (segmentación de fondo verde/azul en HSV)
// ---------------------------------------------------------------------------
/**
 * Elimina el fondo de color sólido (verde o azul) y lo reemplaza con
 * un color de relleno sólido (simula inserción de fondo alternativo).
 *
 * @param hMin, hMax  rango de tono (Hue) para el fondo a eliminar
 *                    Verde: hMin=35, hMax=85
 *                    Azul:  hMin=100, hMax=130
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_ups_practica21_MainActivity_nativeChromaKey(
        JNIEnv*  env,
        jobject  /* thiz */,
        jobject  bitmap,
        jint     hMin,
        jint     hMax)
{
    cv::Mat frame = bitmapToMat(env, bitmap);
    if (frame.empty()) return;

    // Convertir a HSV para segmentación robusta por color
    cv::Mat hsv;
    cv::cvtColor(frame, hsv, cv::COLOR_BGR2HSV);

    // Generar máscara binaria del fondo (píxeles dentro del rango de color)
    cv::Scalar lower(hMin, 50, 50);
    cv::Scalar upper(hMax, 255, 255);
    cv::Mat maskFondo;
    cv::inRange(hsv, lower, upper, maskFondo);

    // Cerrar huecos y suavizar el borde de la máscara para reducir aliasing
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, {5, 5});
    cv::morphologyEx(maskFondo, maskFondo, cv::MORPH_CLOSE, kernel);
    cv::GaussianBlur(maskFondo, maskFondo, {5, 5}, 1.0);

    // Mezclar con la máscara suavizada como peso (0 = primer plano, 1 = fondo).
    // Usarla en copyTo la volvía binaria (cualquier valor > 0 contaba como
    // fondo), así que el difuminado agrandaba el área reemplazada.
    cv::Mat peso;
    maskFondo.convertTo(peso, CV_32F, 1.0 / 255.0);
    cv::Mat peso3;
    cv::merge(std::vector<cv::Mat>{peso, peso, peso}, peso3);
    cv::Mat frameF, fondoF;
    frame.convertTo(frameF, CV_32FC3);
    cv::Mat(frame.size(), CV_32FC3, cv::Scalar(34, 139, 34)).copyTo(fondoF);
    cv::Mat mezcla = frameF.mul(cv::Scalar::all(1.0) - peso3) + fondoF.mul(peso3);
    mezcla.convertTo(frame, CV_8UC3);

    matToBitmap(env, frame, bitmap);
}

// ---------------------------------------------------------------------------
// PARTE 1-B — Ruido Gaussiano y Speckle sobre primer plano
// ---------------------------------------------------------------------------
/**
 * Añade ruido Gaussiano al primer plano (fuera de la máscara de fondo).
 *
 * @param varianza  controla la intensidad del ruido (0-255), desde SeekBar
 * @param tipo      0=Gaussiano, 1=Speckle multiplicativo
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_ups_practica21_MainActivity_nativeAddNoise(
        JNIEnv*  env,
        jobject  /* thiz */,
        jobject  bitmap,
        jint     hMin,
        jint     hMax,
        jfloat   varianza,
        jint     tipo)
{
    cv::Mat frame = bitmapToMat(env, bitmap);
    if (frame.empty()) return;

    // Máscara de primer plano (inverso del fondo)
    cv::Mat hsv;
    cv::cvtColor(frame, hsv, cv::COLOR_BGR2HSV);
    cv::Mat maskFondo;
    cv::inRange(hsv, cv::Scalar(hMin, 50, 50), cv::Scalar(hMax, 255, 255), maskFondo);
    cv::Mat maskPrimerPlano;
    cv::bitwise_not(maskFondo, maskPrimerPlano);

    // Generar ruido en canal único y aplicar a cada canal BGR por separado
    cv::Mat ruido(frame.size(), CV_32F);
    if (tipo == 0) {
        cv::randn(ruido, 0.0f, std::sqrt(varianza));
    } else {
        cv::randn(ruido, 1.0f, std::sqrt(varianza / 255.0f));
    }

    cv::Mat frameF;
    frame.convertTo(frameF, CV_32F);

    std::vector<cv::Mat> ch(3);
    cv::split(frameF, ch);
    for (auto& c : ch) {
        if (tipo == 0) {
            c += ruido;           // Gaussiano aditivo
        } else {
            cv::multiply(c, ruido, c);   // Speckle multiplicativo
        }
    }
    cv::merge(ch, frameF);

    cv::Mat frameOut;
    frameF.convertTo(frameOut, CV_8U);

    // Aplicar ruido solo en primer plano
    cv::Mat primerPlano;
    frameOut.copyTo(primerPlano, maskPrimerPlano);
    primerPlano.copyTo(frame, maskPrimerPlano);

    matToBitmap(env, frame, bitmap);
}

// ---------------------------------------------------------------------------
// PARTE 1-B — Filtros espaciales (Mediana o Gaussiano) para atenuar ruido
// ---------------------------------------------------------------------------
/**
 * @param tipoFiltro  0=Mediana (mejor para ruido Speckle), 1=Gaussiano
 * @param kernelSize  tamaño del kernel (impar: 3, 5, 7, 9 ...)
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_ups_practica21_MainActivity_nativeSpatialFilter(
        JNIEnv*  env,
        jobject  /* thiz */,
        jobject  bitmap,
        jint     tipoFiltro,
        jint     kernelSize)
{
    cv::Mat frame = bitmapToMat(env, bitmap);
    if (frame.empty()) return;

    // Asegurar que kernelSize sea impar
    int ks = (kernelSize % 2 == 0) ? kernelSize + 1 : kernelSize;
    ks = std::max(3, std::min(ks, 21));   // clamp 3..21

    cv::Mat resultado;
    if (tipoFiltro == 0) {
        cv::medianBlur(frame, resultado, ks);
    } else {
        cv::GaussianBlur(frame, resultado, cv::Size(ks, ks), 0.0);
    }

    matToBitmap(env, resultado, bitmap);
}
