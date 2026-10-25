# Launcher Ringan untuk Google TV / Android TV

Launcher super sederhana: satu layar berisi grid aplikasi, jam, dan tanggal.
Tanpa iklan, tanpa rekomendasi konten, tanpa internet, tanpa fitur premium.

Fitur:
- Jadwal sholat Yogyakarta (Subuh, Zuhur, Asar, Magrib, Isya) dengan penanda waktu berikutnya dan hitung mundur.
  Dihitung di TV sendiri tanpa internet (lihat bagian "Soal jadwal sholat" di bawah).
- Menampilkan semua aplikasi, termasuk aplikasi sideload yang tidak muncul di launcher Google.
- Tahan tombol OK pada sebuah aplikasi: Buka / Info aplikasi (untuk nonaktifkan atau copot) / Sembunyikan dari layar.
- Tile "Pengaturan" di paling awal. Tile "Tersembunyi (n)" di akhir untuk melihat aplikasi yang disembunyikan.

PENTING: kode ini belum pernah dikompilasi (workspace pembuatnya tidak punya Android SDK).
Kalau saat build muncul error, salin teks errornya dan kirim ke Claude untuk diperbaiki.

---

## Soal jadwal sholat

Tidak ada API publik resmi dari Muhammadiyah, jadi jadwal dihitung langsung di TV (file `PrayerTimes.java`)
memakai kriteria Majelis Tarjih dan Tajdid PP Muhammadiyah untuk Yogyakarta
(lintang -07 47' 48", bujur 110 21' 50" BT, WIB):

- Subuh dan Isya: tinggi matahari -18 derajat
- Magrib: tinggi matahari sekitar -1,19 derajat
- Asar: bayangan = benda + bayangan tengah hari
- Ihtiyat 1 menit, dibulatkan ke menit berikutnya

Parameter ini dikalibrasi terhadap tabel imsakiyah 1447 H Yogyakarta terbitan Majelis Tarjih
(18 Feb - 19 Mar 2026): 149 dari 150 waktu persis sama, sisanya selisih 1 menit.
Di luar rentang itu rumusnya sama, tapi belum ada tabel resmi lain untuk dibandingkan.
Bulan Ramadan berikutnya, bandingkan dengan tabel imsakiyah terbaru dari Muhammadiyah untuk memastikan.

Kalau mau ganti kota, ubah `LATITUDE` dan `LONGITUDE` di `PrayerTimes.java`.
Kelas ini memakai zona waktu WIB tetap, jadi jadwal tetap benar walau zona waktu TV diubah.

---

## Cara 1: Build di GitHub (tanpa memasang apa pun di laptop)

1. Buat akun di github.com, lalu buat repositori baru (New repository), misalnya `launcher-ringan`.
2. Ekstrak ZIP ini. Di halaman repositori pilih "uploading an existing file", lalu seret SEMUA isi folder
   `tv-launcher` (termasuk folder `.github`, `app`, `gradle`) ke halaman itu, lalu klik Commit changes.
   Kalau folder `.github` tidak ikut terseret, buat manual lewat Add file > Create new file dengan nama
   `.github/workflows/build.yml` lalu tempel isi file itu.
3. Buka tab Actions. Kalau diminta, aktifkan workflow. Pilih "Build APK" lalu Run workflow.
4. Tunggu sekitar 3-5 menit sampai ada centang hijau. Klik run itu, lalu di bagian Artifacts unduh
   `launcher-ringan-apk`, ekstrak ZIP-nya, dan kamu mendapat `app-debug.apk`.

## Cara 2: Build dengan Android Studio

1. Pasang Android Studio dari developer.android.com/studio.
2. File > Open, pilih folder `tv-launcher`, tunggu sync Gradle selesai (butuh internet, bisa lama di awal).
   Kalau ditawari upgrade Gradle/AGP, boleh diterima.
3. Menu Build > Build Bundle(s) / APK(s) > Build APK(s).
4. APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

---

## Memasang di TV (lewat ADB yang sudah tersambung)

Di terminal, di folder `platform-tools`, pastikan `adb devices` menampilkan TV-mu dengan status `device`. Lalu:

```
adb install -r "C:\lokasi\app-debug.apk"
```

Ganti lokasinya sesuai tempat kamu menyimpan APK. Kalau berhasil muncul `Success`.

Uji dulu tanpa menjadikannya default: di TV buka daftar aplikasi, cari "Launcher Ringan", lalu buka.
Pastikan grid tampil, remote bisa menggeser, dan aplikasi bisa dibuka.

## Menjadikannya layar utama (tombol Home)

Setelah yakin launcher berfungsi:

1. Lihat dulu siapa yang sekarang menangani Home:
   ```
   adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
2. Nonaktifkan Google TV Home (bisa dibatalkan):
   ```
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   ```
3. Tekan Home di remote. Seharusnya langsung masuk ke Launcher Ringan. Kalau muncul pilihan, pilih
   Launcher Ringan dan "Selalu".

Kalau ada masalah, kembalikan ke semula:
```
adb shell pm enable com.google.android.apps.tv.launcherx
```

Mencopot launcher ini kapan saja:
```
adb uninstall id.oprek.tvlauncher
```
