# Uygulama Gelistirme Yol Plani (Maddeler 3, 4, 5, 6, 8)

Tarih: 5 Nisan 2026
Kapsam: Akis kontrolu, gecikme optimizasyonu, geri besleme, onboarding, test/metrik.

## 1) Bekleme yerine iptal + yeni istek (Madde 3)
- Hedef: Ceviri surerken yeni secim geldiginde istek reddetmek yerine onceki islemi iptal edip yenisini calistirmak.
- Uygulama:
  - Serviste aktif coroutines job referansi tutulacak.
  - Yeni secimde aktif job cancel edilecek.
  - Request token/kimlik ile stale sonucun overlay yazmasi engellenecek.
- Basari kriteri:
  - Ardisik hizli secimlerde "mesgul" nedeniyle kayip istek olmamasi.

## 2) OCR + model gecikme optimizasyonu (Madde 4)
- Hedef: Tekrar eden bolgelerde daha hizli donus ve daha az gereksiz model cagrisi.
- Uygulama:
  - Kisa omurlu LRU inference cache eklenecek (hash key + TTL).
  - Hash key, secim bolgesi + config + OCR bloklarini icerecek.
  - Adaptif token limiti hesaplanip modele iletilecek.
  - Inference oncesi blok onceliklendirme skoru uygulanacak.
- Basari kriteri:
  - Tekrar eden sahnelerde model cagrisi oraninin dusmesi.

## 3) Duzeltme geri besleme mini ekrani (Madde 5)
- Hedef: Kullanici duzeltmelerini sozluge hizli yazip sonraki cevirilerde kullanmak.
- Uygulama:
  - Servis son ceviri orneklerini SharedPreferences'a yazacak.
  - MainActivity'de "Son Ceviri Duzeltmeleri" karti olacak.
  - Duzeltmeler sozluk depolamasina islenecek.
- Basari kriteri:
  - Duzeltilen metinlerin sonraki cevirilerde otomatik gelmesi.

## 4) Onboarding / izin sihirbazi (Madde 6)
- Hedef: Ilk kurulumda hangi adimin eksik oldugunu net gostermek.
- Uygulama:
  - Overlay izni, model varligi, ekran yakalama adimlari tek kartta gosteilecek.
  - Adimlara gore aksiyon butonu sunulacak.
- Basari kriteri:
  - Ilk acilista kullanicinin "ne yapmaliyim" belirsizliginin azalmasi.

## 5) Test + metrik altyapisi (Madde 8)
- Hedef: Kritik metin isleme adimlarini birim testlerle guvenceye almak ve temel performans metriklerini toplamak.
- Uygulama:
  - OCR merge, JSON parse, cache key stabilitesi icin unit testler eklenecek.
  - Servis tarafinda ceviri sure metrikleri kaydedilecek.
- Basari kriteri:
  - Core utility testleri gecmeli.
  - Ortalama ceviri suresi uygulama icinde gorulebilmeli.

## Uygulama Sirasi
1. Servis iptal/yeniden baslat mekanizmasi
2. Inference cache + adaptif token + blok onceliklendirme
3. Feedback depolama ve duzeltme UI
4. Onboarding karti
5. Testler ve metrik karti
6. Build/test dogrulamasi

## Riskler
- Serviste stale job sonucu UI yarisi olusabilir: request token ile engellenecek.
- SharedPreferences eszamanlilik riski: sozluk yazimlari tek noktadan ve atomik update ile yapilacak.
- Model cagrisi uzun surebilir: iptal gelirse sonuc stale kontrolu ile yok sayilacak.
