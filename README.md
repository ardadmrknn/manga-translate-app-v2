# Manga Çeviri V2

Manga Çeviri V2, Android üzerinde çalışan bir ekran-üstü manga çeviri uygulamasıdır.
Kullanıcının seçtiği ekran alanını yakalar, OCR ile metni çıkarır, cihazdaki LiteRT-LM Gemma modeli ile çevirir ve sonucu tekrar ekran üstüne overlay olarak yazar.

## Ne Yapar?

- Floating bubble ile seçim modu açılır.
- Seçilen alan MediaProjection ile görüntü olarak yakalanır.
- ML Kit Text Recognition ile metin blokları çıkarılır.
- Metinler önce sözlük ve önbellek katmanlarından geçirilir.
- Eksik kalan bloklar Gemma modeli ile çevrilir.
- Sonuç, orijinal kutulara denk gelecek şekilde overlay olarak gösterilir.

## Öne Çıkan Özellikler

- Cihaz içi çeviri (on-device) yaklaşımı
- Seçilebilir model dosyası (.litertlm/.task)
- Kaynak dil, hedef dil ve ton seçimi
- Literal (sansürsüz/birebir) çeviri modu
- Sözlük profili yönetimi
- JSON ile sözlük profili dışa aktarma / içe aktarma
- Son çeviri düzeltmelerini kaydetme
- Çeviri metrik paneli (istek sayısı, ortalama süre, cache oranı)
- Debug kayıt paneli (capture/OCR/sözlük/inference/parse/render süreleri)
- AMOLED uyumlu yönetim panelleri
- Model boşta kalınca otomatik kapatma (idle unload)

## Çalışma Akışı (Servis)

Servis, çeviri sırasında 6 adımlı bir pipeline izler:

1. Capture: Seçili alan yakalanır.
2. OCR: Metin blokları çıkarılır.
3. Dictionary: Sözlük eşleşmeleri uygulanır.
4. Model/Cache: Gerekirse model çağrılır veya önbellek kullanılır.
5. Parse: Model JSON cevabı ayrıştırılır.
6. Render: Çevrilen metin overlay olarak çizilir.

## İzinler

Manifest dosyasında aşağıdaki izinler tanımlıdır:

- SYSTEM_ALERT_WINDOW
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_MEDIA_PROJECTION
- INTERNET

## Gereksinimler

- Android 9+ (minSdk 28)
- compileSdk 36 / targetSdk 36
- Java 11 / Kotlin JVM target 11
- Android Studio (güncel) + Gradle Wrapper
- `app/src/main/assets` altında model dosyası:
  - `gemma-4-E2B-it.litertlm`

## Kurulum

1. Projeyi Android Studio ile açın.
2. Gradle sync tamamlanana kadar bekleyin.
3. Gerçek Android cihaza debug kurulum yapın.
4. Uygulama açılınca overlay iznini verin.
5. "Çeviriyi Başlat" ile ekran yakalama iznini onaylayın.

## Kullanım

1. Bubble ikonuna dokunun.
2. Çeviri alınacak alanı seçin.
3. Overlay üzerinde çevrilmiş metni görün.
4. Gerekirse "Son Çeviri Düzeltmeleri" panelinden düzeltme girin ve kaydedin.
5. İsterseniz sözlük profilini JSON olarak dışa/içe aktarın.

## Derleme Komutları

Debug APK:

```bash
./gradlew.bat :app:assembleDebug
```

Kotlin derleme kontrolü:

```bash
./gradlew.bat :app:compileDebugKotlin
```

Release APK:

```bash
./gradlew.bat :app:assembleRelease
```

## Proje Yapısı (Önemli Dosyalar)

- `app/src/main/java/com/vibecode/mangaceviriv2/MainActivity.kt`
- `app/src/main/java/com/vibecode/mangaceviriv2/MangaTranslationService.kt`
- `app/src/main/java/com/vibecode/mangaceviriv2/GemmaInferenceEngine.kt`
- `app/src/main/java/com/vibecode/mangaceviriv2/OverlayManager.kt`
- `app/src/main/java/com/vibecode/mangaceviriv2/OverlayRenderer.kt`
- `app/src/main/java/com/vibecode/mangaceviriv2/TranslationFeedbackStore.kt`
- `app/src/main/AndroidManifest.xml`

## Sorun Giderme

- Overlay görünmüyorsa: Sistem overlay iznini tekrar kontrol edin.
- Çeviri başlamıyorsa: Ekran yakalama izninin verildiğini doğrulayın.
- Model bulunamıyorsa: Model dosyasının `app/src/main/assets` altında olduğundan emin olun.
- Performans düşükse: Daha küçük seçim alanı kullanın ve sözlük profilini aktif tutun.
