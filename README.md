# opencv-android-efectos-camara

App Android que procesa la cámara en tiempo real con OpenCV en C++ (JNI).
Práctica 2-1, parte 1, de Visión por Computador (Universidad Politécnica
Salesiana, junio de 2026). La parte 2 está en
[morfologia-radiografias-torax](https://github.com/Whiteherobot/morfologia-radiografias-torax).

| Modo | Qué hace |
|---|---|
| Karl Struss | Separa los canales y mezcla azul y rojo: *salida = (1 − α)·B + α·R*. Con α moviéndose de 0 a 1 aparecen o desaparecen los objetos rojos, como en los efectos de maquillaje del cine en blanco y negro. |
| Chroma key | Reemplaza el fondo verde o azul (rango de tono en HSV) por un color sólido, con el borde de la máscara suavizado. |
| Ruido y filtro | Agrega ruido gaussiano o *speckle* solo al primer plano y lo atenúa con un filtro de mediana o gaussiano de tamaño ajustable. Muestra los FPS. |

La cámara usa CameraX; cada cuadro pasa de YUV a bitmap, se procesa en
`app/src/main/cpp/native-lib.cpp` y vuelve a la pantalla.

## Compilar

Hace falta el SDK de Android (con NDK y CMake) y el SDK de OpenCV para Android:

```bash
scripts/descargar-opencv.sh            # OpenCV 4.10.0 en ../opencv-android-sdk
./gradlew assembleDebug                # app/build/outputs/apk/debug/app-debug.apk
```

Si OpenCV está en otro lado: `OPENCV_ANDROID_SDK=/ruta/OpenCV-android-sdk ./gradlew assembleDebug`.
Solo se compila para ARM (`arm64-v8a`, `armeabi-v7a`): las librerías x86 del
SDK de OpenCV dieron problemas. La CI compila el APK y lo deja como artefacto.

## Cambios en 2026

El repositorio se llamaba `Practica2-1-VisionComputador`. Se corrigió el
chroma key: la máscara difuminada se usaba con `copyTo`, que la trata como
binaria, así que el difuminado agrandaba el área reemplazada en lugar de
suavizar el borde; ahora se mezcla con la máscara como peso. La ruta del SDK
de OpenCV pasó a ser configurable y se agregaron el script de descarga, la CI
y este README.

## Autores

- Michael Lata ([@Whiteherobot](https://github.com/Whiteherobot))
- Jordy Romero ([@JordyRomeroa](https://github.com/JordyRomeroa))

Licencia MIT.
