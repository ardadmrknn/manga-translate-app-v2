# Manga Ceviri V2

Manga Ceviri V2, Android uzerinde calisan bir ekran-ustu ceviri uygulamasidir.
Kullanicinin sectigi ekran alanini yakalar, OCR ile metni cikarir, cihazdaki LiteRT-LM Gemma modeli ile cevirir ve sonucu tekrar ekran ustune overlay olarak yazar.

## Uygulama Ne Yapiyor?

- Floating bubble ile secim modu acilir.
- Secilen alan MediaProjection ile goruntu olarak yakalanir.
- ML Kit Text Recognition ile metin bloklari bulunur.
- Metinler sozluk ve onbellek katmanlarindan gecirilir.
- Eksik kalan bloklar Gemma modeli ile cevrilir.
- Sonuc, orijinal kutulara denk gelecek sekilde overlay olarak gosterilir.

## Temel Ozellikler

- Cihaz ici ceviri (on-device) yaklasimi.
- Secilebilir model dosyasi (.litertlm/.task)
- Kaynak dil, hedef dil ve ton secimi
- Literal (sansursuz/birebir) ceviri modu
- Sozluk tabanli tekrarli metin hizlandirma
- Inference sonucu onbellekleme
- Overlay metin kutularini semantik benzerlige gore birlestirme
- Modeli bosta kaldiginda otomatik kapatma (idle unload)

## Calisma Akisi (Servis)

Servis durumu uygulama icinde 6 adim olarak ilerler:

1. Capture: Secili alan yakalanir.
2. OCR: Metin bloklari cikarilir.
3. Dictionary: Sozluk eslesmeleri uygulanir.
4. Model/Cache: Gerekirse model cagrilir veya onbellek kullanilir.
5. Parse: JSON cevap ayristirilir.
6. Render: Cevrilen metin overlay olarak cizilir.

## Izinler

Manifestte asagidaki izinler tanimlidir:

- SYSTEM_ALERT_WINDOW
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_MEDIA_PROJECTION
- INTERNET

## Gereksinimler

- Android 9+ (minSdk 28)
- compileSdk 36 / targetSdk 36
- Android Studio (guncel), Gradle Wrapper
- app/src/main/assets altinda model dosyasi:
	- gemma-4-E2B-it.litertlm

## Kurulum ve Calistirma

1. Projeyi Android Studio ile acin ve Gradle sync yapin.
2. Gercek bir Android cihaza kurulum yapin.
3. Uygulama acilinca once overlay iznini verin.
4. Ceviriyi Baslat ile ekran yakalama iznini onaylayin.
5. Bubble iconuna basip ceviri alinacak alani secin.

## Derleme Komutlari

Debug APK:

./gradlew.bat :app:assembleDebug

Release APK:

./gradlew.bat :app:assembleRelease

## Signed Release Bilgisi

Release imzalama ayari app/build.gradle.kts icinde keystore.properties dosyasindan okunur.
Yerel dosya ornegi:

storeFile=keystore/release-keystore.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=manga_release
keyPassword=YOUR_KEY_PASSWORD

Guvenlik notu:

- keystore.properties ve .jks dosyalari repoya eklenmemelidir.

## APK Boyutu ve GitHub Release Notu

Model dosyasi APK icine gomulu oldugu icin release APK boyutu cok buyuk olabilir.
GitHub Release tek dosya limiti 2 GiB oldugundan buyuk APK dosyalari dogrudan yuklenemeyebilir.
Bu durumda:

- APK parcalanip yuklenebilir (part01/part02)
- Kullanimdan once parcalar tek APK haline birlestirilmelidir

## Ana Dosyalar

- app/src/main/java/com/vibecode/mangaceviriv2/MainActivity.kt
- app/src/main/java/com/vibecode/mangaceviriv2/MangaTranslationService.kt
- app/src/main/java/com/vibecode/mangaceviriv2/GemmaInferenceEngine.kt
- app/src/main/java/com/vibecode/mangaceviriv2/OverlayManager.kt
- app/src/main/java/com/vibecode/mangaceviriv2/OverlayRenderer.kt
- app/src/main/AndroidManifest.xml
